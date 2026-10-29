package mods.eamedia

import com.google.gson.Gson
import mods.eamedia.server.BrowserWorker
import mods.eln.node.transparent.TransparentNodeEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

/** The main server only validates controls and relays bounded encoded packets.
 * Chromium, image encoding, extraction and every blocking operation live elsewhere. */
@EventBusSubscriber(modid="eamedia")
object ServerTelevisions {
    private val workers=Executors.newFixedThreadPool(8) { r->Thread(r,"EA Media server browser startup").apply { isDaemon=true } }
    private val sessions=LinkedHashMap<UUID,Session>()
    private val limit get()=Integer.getInteger("eamedia.serverBrowserLimit",2).coerceIn(1,8)
    private class Session(val tv: MediaElement,val world: ServerLevel,val pos: BlockPos) {
        val revision=tv.mediaRevision
        val epoch=tv.sharedTv.hostEpoch
        var sequence=0L
        var report=0L
        var controller: UUID?=null
        var watching=true
        var retryAt=Long.MAX_VALUE
        val profile=world.server.getWorldPath(LevelResource.ROOT).resolve("eamedia-browser").resolve(world.dimension().location().toString().replace(':','_')).resolve("${pos.x}_${pos.y}_${pos.z}")
        val process=ServerBrowserProcess(profile,tv.mediaUrl,Gson().toJson(tv.sharedTv.snapshot()))
    }
    fun observe(tv: MediaElement,world: ServerLevel,pos: BlockPos) {
        if(!tv.sharedTv.serverHosted || !tv.working || sessions.containsKey(tv.sharedTv.session))return
        if(sessions.size>=limit){status(tv,"CAPACITY");return}
        tv.sharedTv.startServerHost();tv.mirrorRelay.reset(tv.sharedTv.session,tv.sharedTv.hostEpoch,tv.mediaRevision)
        val session=Session(tv,world,pos.immutable());sessions[tv.sharedTv.session]=session
        status(tv,"STARTING");workers.execute { session.process.start() }
    }
    private fun status(tv: MediaElement,value: String) { if(tv.tvServerStatus!=value){tv.tvServerStatus=value;tv.needPublish()} }
    internal fun diagnostic(tv: MediaElement): Map<String,Any?> {
        if(!java.lang.Boolean.getBoolean("eamedia.mirrorServer"))return emptyMap()
        val s=sessions[tv.sharedTv.session]
        return mapOf("status" to tv.tvServerStatus,"workerPid" to s?.process?.processId,"viewers" to tv.sharedTv.activeViewers.size)
    }
    fun input(tv: MediaElement,value: String) {
        val s=sessions[tv.sharedTv.session] ?: return
        if(s.revision!=tv.mediaRevision)return
        reconcileController(s);s.process.input(value)
    }
    fun navigate(tv: MediaElement,action: Int) {
        val s=sessions[tv.sharedTv.session] ?: return
        if(s.revision!=tv.mediaRevision)return
        s.process.command(when(action){TelevisionCommand.BACK->BrowserWorker.BACK;TelevisionCommand.FORWARD->BrowserWorker.FORWARD;else->BrowserWorker.RELOAD})
    }
    private fun reconcileController(s: Session) {
        val next=s.tv.sharedTv.controller
        if(next!=s.controller){s.controller=next;s.process.command(BrowserWorker.RELEASE)}
    }
    @SubscribeEvent fun tick(event: ServerTickEvent.Post) {
        val restart=ArrayList<Session>()
        val iterator=sessions.values.iterator()
        while(iterator.hasNext()) {
            val s=iterator.next();val tv=s.tv
            val present=s.world.hasChunkAt(s.pos) && (s.world.getBlockEntity(s.pos) as? TransparentNodeEntity)?.node?.let { (it as? mods.eln.node.transparent.TransparentNode)?.element }===tv
            if(!present || !tv.working || tv.mediaRevision!=s.revision) {
                iterator.remove();s.process.close();tv.sharedTv.stopServerHost();tv.mirrorRelay.drop();status(tv,"OFF")
                if(present && tv.working)restart+=s
                continue
            }
            reconcileController(s)
            val visible=tv.sharedTv.activeViewers.isNotEmpty()
            if(visible!=s.watching){s.watching=visible;s.process.command(BrowserWorker.WATCHING){it.writeBoolean(visible)}}
            s.process.problem?.let { problem->
                status(tv,problem)
                if(s.retryAt==Long.MAX_VALUE)s.retryAt=System.nanoTime()+10_000_000_000L
                if(System.nanoTime()>=s.retryAt){iterator.remove();s.process.close();tv.sharedTv.stopServerHost();restart+=s}
            }
            // Bounded work per TV per tick. No decode, encode, process waits or socket I/O.
            repeat(16) {
                val message=s.process.messages.poll() ?: return@repeat
                when(message.type) {
                    BrowserWorker.READY->if(s.process.problem==null)status(tv,"READY")
                    BrowserWorker.ERROR->status(tv,"FAILED")
                    BrowserWorker.ADDRESS->{
                        val url=String(message.bytes,StandardCharsets.UTF_8)
                        if(TelevisionUrl.parse(url)!=null)tv.browserPageChanged(url)
                    }
                    BrowserWorker.STATE->{
                        val sample=TelevisionNetwork.parsePlayback(String(message.bytes,StandardCharsets.UTF_8))
                        if(sample!=null && tv.sharedTv.report(SharedTelevision.SERVER_HOST,sample,++s.report))tv.needPublish()
                    }
                    BrowserWorker.VIDEO->{
                        val data=message.bytes
                        if(data.size>=4 && data[0]==0xff.toByte() && data[1]==0xd8.toByte() && tv.mirrorRelay.videoBudget.accept(data.size)) {
                            val packets=TelevisionStream.split(s.pos,tv.sharedTv.session,s.epoch,s.revision,++s.sequence,data)
                            tv.mirrorRelay.cached=packets;send(s,packets)
                        }
                    }
                    BrowserWorker.AUDIO->{
                        val bytes=message.bytes
                        if(bytes.size in 4..8192 && bytes.size%4==0 && tv.mirrorRelay.audioBudget.accept(bytes.size))
                            send(s,listOf(TelevisionStream(s.pos,tv.sharedTv.session,s.epoch,s.revision,++s.sequence,TelevisionStream.AUDIO,0,1,bytes)))
                    }
                }
            }
        }
        restart.forEach { observe(it.tv,it.world,it.pos) }
    }
    private fun send(s: Session,packets: List<TelevisionStream>) {
        for(id in s.tv.sharedTv.activeViewers) {
            val player=s.world.server.playerList.getPlayer(id) ?: continue
            if(player.serverLevel()!==s.world || player.distanceToSqr(s.pos.x+.5,s.pos.y+.5,s.pos.z+.5)>1024){s.tv.sharedTv.leave(id);continue}
            packets.forEach { PacketDistributor.sendToPlayer(player,it) }
        }
    }
    @SubscribeEvent fun stop(event: ServerStoppingEvent) {
        sessions.values.forEach { it.process.close();it.tv.sharedTv.stopServerHost() };sessions.clear()
        if(event.server.isDedicatedServer)workers.execute { ServerBrowserRuntime.close() }
    }
}
