package mods.eamedia.devtest

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mods.eln.Eln
import mods.eln.misc.Direction
import mods.eln.node.transparent.TransparentNodeEntity
import mods.eamedia.*
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.*
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.nio.file.Files
import java.nio.file.Path

/** File-driven localhost checks are disabled unless explicitly selected with a JVM property. */
private abstract class MirrorProbe(private val role: String) {
    private val directory=Path.of(System.getProperty("eamedia.mirrorDirectory"))
    private var previous="";private var command: JsonObject?=null;private var started=0L
    protected var ticks=0
    protected open fun begin(c: JsonObject) {}
    protected abstract fun observe(c: JsonObject): JsonObject?
    protected open fun photo(id: String) {}
    protected open fun finish(c: JsonObject) {}
    protected fun action(c: JsonObject)=c["action"].asString
    fun tick() {
        if(command==null) {
            val file=directory.resolve("$role-command.json");if(!Files.exists(file))return
            val next=JsonParser.parseString(Files.readString(file)).asJsonObject
            if(next["id"].asString==previous)return
            command=next;previous=next["id"].asString;require(previous.matches(Regex("[a-zA-Z0-9_-]+")));started=System.nanoTime();ticks=0
            try { begin(next) } catch(e: Throwable) { complete(next,null,e);return }
        }
        val c=command ?: return
        try { ticks++;val result=observe(c);if(result!=null)complete(c,result,null) else check(System.nanoTime()-started<150_000_000_000) { "Timeout: $role / ${action(c)}" } }
        catch(e: Throwable) { complete(c,null,e) }
    }
    private fun complete(c: JsonObject,result: JsonObject?,error: Throwable?) {
        val output=JsonObject().apply { addProperty("role",role);addProperty("id",previous);addProperty("action",action(c));addProperty("status",if(error==null)"passed" else "failed");addProperty("failure",error?.stackTraceToString());add("observation",result ?: JsonObject()) }
        if(error==null)photo(previous)
        Files.createDirectories(directory);Files.writeString(directory.resolve("$role-$previous.json"),GsonBuilder().setPrettyPrinting().create().toJson(output))
        Eln.LOGGER.info("TV_MIRROR {} {} {}",role,previous,output["status"])
        command=null;if(error==null)finish(c)
    }
}

@EventBusSubscriber(modid="eamedia")
object MirrorServerChecks {
    private val at=BlockPos(256,80,256)
    private var probe: MirrorProbe?=null
    @SubscribeEvent fun start(e: ServerStartedEvent) {
        if(!java.lang.Boolean.getBoolean("eamedia.mirrorServer"))return
        val w=e.server.overworld();val p=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(w);val f=NativeCampaignFixtures(w,p)
        for(x in 15..18)for(z in 15..17)w.setChunkForced(x,z,true)
        for(x in 250..292)for(z in 251..265)w.setBlockAndUpdate(BlockPos(x,79,z),Blocks.STONE.defaultBlockState())
        val tv=f.placeMachine(Eln.findItemStack(MediaKind.TV.title,1),at) as MediaElement
        tv.front=Direction.ZP;tv.reconnect();f.wire(at.west());f.source(at.west(2),120.0);w.setDayTime(6000);w.setWeatherParameters(12000,0,false,false)
        if(java.lang.Boolean.getBoolean("eamedia.poleFixtures"))Class.forName("mods.eln.devtest.PoleAttachmentChecks").getMethod("install",net.minecraft.server.level.ServerLevel::class.java,ServerPlayer::class.java).invoke(null,w,p)
        probe=object: MirrorProbe("server") {
            override fun begin(c: JsonObject) { if(action(c)=="power")f.voltage(at.west(2),c["voltage"].asDouble) }
            override fun observe(c: JsonObject): JsonObject? {
                if(ticks<5)return null
                return JsonObject().apply {
                    addProperty("url",tv.mediaUrl);addProperty("working",tv.working);addProperty("watts",tv.resistor.power);addProperty("revision",tv.mediaRevision)
                    addProperty("host",tv.sharedTv.host?.toString());addProperty("controller",tv.sharedTv.controller?.toString());addProperty("epoch",tv.sharedTv.hostEpoch.toString())
                    add("serverBrowser",com.google.gson.Gson().toJsonTree(ServerTelevisions.diagnostic(tv)))
                    add("webRouting",com.google.gson.Gson().toJsonTree(w.server.playerList.players.associate{it.gameProfile.name to mods.eamedia.web.WebRoutingNetwork.diagnostic(it)}))
                    val sample=tv.sharedTv.snapshot();addProperty("position",sample.position);addProperty("paused",sample.paused)
                    val bytes=tv.mirrorRelay.cached.flatMap { it.bytes.asIterable() }.toByteArray();addProperty("imageHash",bytes.contentHashCode());addProperty("imageBytes",bytes.size)
                    if(java.lang.Boolean.getBoolean("eamedia.poleFixtures"))add("poles",JsonParser.parseString(Class.forName("mods.eln.devtest.PoleAttachmentChecks").getMethod("report").invoke(null).toString()))
                }
            }
        }
        Eln.LOGGER.info("TV_MIRROR SERVER READY")
    }
    @SubscribeEvent fun tick(e: ServerTickEvent.Post) { probe?.tick() }
    @SubscribeEvent fun login(e: PlayerEvent.PlayerLoggedInEvent) {
        if(!java.lang.Boolean.getBoolean("eamedia.mirrorServer"))return
        val p=e.entity as? ServerPlayer ?: return;p.server.playerList.op(p.gameProfile);p.setGameMode(GameType.CREATIVE)
        p.abilities.flying=true;p.onUpdateAbilities();p.setNoGravity(true)
        val x=if(p.gameProfile.name.endsWith("B"))257.7 else 256.5
        p.teleportTo(p.serverLevel(),x,80.0,260.5,180f,0f);p.lookAt(EntityAnchorArgument.Anchor.EYES,Vec3(256.5,81.1,256.5))
    }
}

@EventBusSubscriber(modid="eamedia",value=[Dist.CLIENT])
object MirrorClientChecks {
    private val role get()=System.getProperty("eamedia.mirrorClient")
    private val probe by lazy { ClientProbe(role) }
    @SubscribeEvent fun tick(e: ClientTickEvent.Post) { if(role!=null)probe.tick() }
    private class ClientProbe(private val role: String): MirrorProbe(role) {
        private val mc get()=Minecraft.getInstance()
        private val at=BlockPos(256,80,256)
        private fun render()=(mc.level?.getBlockEntity(at) as? TransparentNodeEntity)?.elementRender as? MediaRender
        @Volatile private var page=""
        @Volatile private var webAttempt: Boolean?=null
        override fun begin(c: JsonObject) {
            when(action(c)) {
                "join"->{ mc.options.renderDistance().set(4);ConnectScreen.startConnecting(mc.screen ?: TitleScreen(),mc,ServerAddress.parseString("127.0.0.1:25575"),ServerData("TV mirror check","127.0.0.1:25575",ServerData.Type.OTHER),false,null) }
                "open"->{ mc.player!!.inventory.selected=(0..8).first { mc.player!!.inventory.getItem(it).isEmpty };mc.gameMode!!.useItemOn(mc.player!!,InteractionHand.MAIN_HAND,BlockHitResult(Vec3(256.5,80.5,256.99),net.minecraft.core.Direction.SOUTH,at,false)) }
                "go"->{ val s=mc.screen as TelevisionScreen;s.children().filterIsInstance<EditBox>().single().value=c["url"].asString;s.mouseClicked(s.width-28.0,18.0,0);s.mouseReleased(s.width-28.0,18.0,0) }
                "click"->{
                    val s=mc.screen as TelevisionScreen;val scale=kotlin.math.min((s.width-16)/16.0,(s.height-92)/9.0).coerceAtLeast(1.0)
                    val w=(scale*16).toInt();val h=(scale*9).toInt();val x=(s.width-w)/2+c["x"].asDouble*w/960;val y=36+c["y"].asDouble*h/540
                    s.mouseMoved(x,y);s.mouseClicked(x,y,0);s.mouseReleased(x,y,0)
                }
                "type"->c["text"].asString.forEach { (mc.screen as TelevisionScreen).charTyped(it,0) }
                "close"->mc.screen?.onClose()
                "sound-volume"->mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.RECORDS).set(c["value"].asDouble)
                "sound-reset"->mc.soundManager.stop()
                "sound-stall"->Thread.sleep(500)
                "teleport"->mc.connection!!.sendCommand(c["command"].asString)
                "page"->{ page="" }
                "web-attempt"->{
                    webAttempt=null
                    Thread.ofVirtual().start {
                        webAttempt=try {
                            val proxy=java.net.Proxy(java.net.Proxy.Type.SOCKS,java.net.InetSocketAddress("127.0.0.1",mods.eamedia.web.BrowserWebProxy.client.start()))
                            java.net.Socket(proxy).use{socket->socket.connect(java.net.InetSocketAddress.createUnresolved("fixture.eamedia.test",8765),5000)};true
                        }catch(_: Exception){false}
                    }
                }
                "pole-open"->{
                    val pos=BlockPos(c["x"].asInt,80,256);val player=mc.player!!
                    player.inventory.selected=(0..8).first { player.inventory.getItem(it).isEmpty }
                    mc.connection!!.send(net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(player,net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY))
                    mc.gameMode!!.useItemOn(player,InteractionHand.MAIN_HAND,BlockHitResult(Vec3(pos.x+.5,80.5,256.99),net.minecraft.core.Direction.SOUTH,pos,false))
                    mc.connection!!.send(net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(player,net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY))
                }
                "pole-button"->{
                    val screen=mc.screen!!;val b=screen.children().filterIsInstance<net.minecraft.client.gui.components.Button>().single { it.message.string==c["label"].asString }
                    screen.mouseClicked(b.x+b.width/2.0,b.y+b.height/2.0,0);screen.mouseReleased(b.x+b.width/2.0,b.y+b.height/2.0,0)
                }
                "quit"->{ mc.level?.disconnect();mc.disconnect(TitleScreen()) }
            }
        }
        override fun observe(c: JsonObject): JsonObject? {
            if(action(c)=="boot")return if(mc.screen is TitleScreen && mc.overlay==null)JsonObject() else null
            if(action(c)=="join")return if(mc.player!=null && mc.screen==null)JsonObject().apply { addProperty("uuid",mc.player!!.uuid.toString());addProperty("integrated",mc.singleplayerServer!=null) } else null
            if(action(c)=="quit")return if(mc.player==null)JsonObject() else null
            if(action(c)=="web-attempt")return webAttempt?.let{result->check(result==c["allowed"].asBoolean);JsonObject().apply{addProperty("allowed",result)}}
            if(action(c).startsWith("pole-")) {
                if(ticks<20)return null
                val pos=BlockPos(c["x"].asInt,80,256)
                val grid=(mc.level?.getBlockEntity(pos) as? TransparentNodeEntity)?.elementRender ?: return null
                if(action(c)=="pole-open" && mc.screen?.javaClass?.name!="mods.eln.utility.PoleStreetlightScreen")return null
                // Optional core-3.0.63 fixtures must not add a normal runtime dependency on it.
                val d=grid.javaClass.getMethod("getPoleStreetlight").invoke(grid)
                fun value(name: String)=d.javaClass.getMethod("get"+name.replaceFirstChar { it.uppercase() }).invoke(d)
                if(c.has("yaw") && value("yaw")!=c["yaw"].asInt)return null
                if(c.has("installed") && value("installed")!=c["installed"].asBoolean)return null
                return JsonObject().apply {
                    addProperty("screen",mc.screen?.javaClass?.simpleName);addProperty("installed",value("installed") as Boolean)
                    for((key,property) in mapOf("light" to "light","watts" to "power","volts" to "volts","yaw" to "yaw","height" to "height","pitch" to "pitch"))addProperty(key,value(property) as Number)
                }
            }
            val r=render() ?: return null;val tv=r.television
            if(action(c)=="open" && (mc.screen !is TelevisionScreen || !tv.isController))return null
            if(action(c)=="page") {
                if(ticks%10==0)tv.browser?.getText { page=it }
                val d=try { JsonParser.parseString(page.substringAfter("ELN_TV_STATE:").substringBefore(":END")).asJsonObject } catch(_: Exception) { return null }
                if(c.has("input") && d["input"].asString!=c["input"].asString)return null
                if(c.has("clicks") && d["clicks"].asInt<c["clicks"].asInt)return null
                if(c.has("paused") && d["paused"].asBoolean!=c["paused"].asBoolean)return null
                if(c.has("minimumTime") && d["time"].asDouble<c["minimumTime"].asDouble)return null
                return d
            }
            if(ticks<15)return null
            if(c.has("host") && tv.isHost!=c["host"].asBoolean)return null
            if(c.has("browser") && (tv.browser!=null)!=c["browser"].asBoolean)return null
            if(c.has("working") && r.working!=c["working"].asBoolean)return null
            if(c.has("frames") && (if(tv.isHost)tv.mirror.encodedFrames else tv.mirror.receivedFrames)<c["frames"].asLong)return null
            if(c.has("audio") && tv.mirror.receivedAudioBytes<c["audio"].asLong)return null
            if(c.has("playedAudio") && tv.mirror.playedAudioBytes<c["playedAudio"].asLong)return null
            if(c.has("soundActive") && tv.mirror.soundActive!=c["soundActive"].asBoolean)return null
            if(c.has("hash") && tv.mirror.frameHash!=c["hash"].asInt)return null
            return JsonObject().apply {
                addProperty("uuid",mc.player!!.uuid.toString());addProperty("host",tv.isHost);addProperty("browser",tv.browser!=null);addProperty("working",r.working);addProperty("url",r.mediaUrl)
                addProperty("paintedFrames",tv.paintedFrames);addProperty("controller",tv.isController);addProperty("encodedFrames",tv.mirror.encodedFrames);addProperty("receivedFrames",tv.mirror.receivedFrames);addProperty("receivedAudioBytes",tv.mirror.receivedAudioBytes);addProperty("imageHash",tv.mirror.frameHash)
                addProperty("playedAudioBytes",tv.mirror.playedAudioBytes);addProperty("soundActive",tv.mirror.soundActive);addProperty("playbackStarts",tv.mirror.playbackStarts);addProperty("serverStatus",r.tvServerStatus)
                addProperty("channelResumes",tv.mirror.channelResumes)
                val web=mods.eamedia.web.BrowserWebProxy.client
                addProperty("webEnabled",web.enabled);addProperty("webOpened",web.opened.get());addProperty("webDownloaded",web.downloaded.get());addProperty("webUploaded",web.uploaded.get())
            }
        }
        override fun photo(id: String) {
            if(mc.player==null)return
            val directory=Path.of(System.getProperty("eamedia.mirrorDirectory"));Files.createDirectories(directory)
            Screenshot.takeScreenshot(mc.mainRenderTarget).use { it.writeToFile(directory.resolve("$role-$id.png")) }
        }
        override fun finish(c: JsonObject) { if(action(c)=="quit")mc.execute { mc.stop();kotlin.concurrent.thread { Thread.sleep(1500);kotlin.system.exitProcess(0) } } }
    }
}
