package mods.eamedia

import com.google.gson.JsonParser
import mods.eln.node.transparent.TransparentNode
import mods.eln.node.transparent.TransparentNodeEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

data class TelevisionCommand(val pos: BlockPos,val session: UUID,val revision: Int,val action: Int,val value: String="",val sequence: Long=0): CustomPacketPayload {
    override fun type()=TYPE
    companion object {
        const val CLAIM=0;const val RELEASE=1;const val NAVIGATE=2;const val REPORT=3
        const val OBSERVE=4;const val PAGE_CHANGED=5;const val BACK=6;const val FORWARD=7;const val RELOAD=8
        const val INPUT=9;const val LEAVE=10
        val TYPE=CustomPacketPayload.Type<TelevisionCommand>(ResourceLocation.fromNamespaceAndPath("eamedia","television_control"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf,TelevisionCommand> = StreamCodec.of({ b,p->
            b.writeBlockPos(p.pos);b.writeUUID(p.session);b.writeVarInt(p.revision);b.writeByte(p.action);b.writeUtf(p.value,4096);b.writeVarLong(p.sequence)
        },{ b->TelevisionCommand(b.readBlockPos(),b.readUUID(),b.readVarInt(),b.readUnsignedByte().toInt(),b.readUtf(4096),b.readVarLong()) })
    }
}

/** Validates the actual sender and loaded block before any browser-originated state is used. */
object TelevisionNetwork {
    fun handle(player: ServerPlayer,packet: TelevisionCommand) {
        val world=player.serverLevel();val p=packet.pos
        val background=packet.action in listOf(TelevisionCommand.OBSERVE,TelevisionCommand.REPORT,TelevisionCommand.PAGE_CHANGED,TelevisionCommand.LEAVE)
        if(!world.hasChunkAt(p) || packet.action!=TelevisionCommand.LEAVE && player.distanceToSqr(p.x+.5,p.y+.5,p.z+.5)>if(background)1024.0 else 64.0)return
        val node=(world.getBlockEntity(p) as? TransparentNodeEntity)?.node as? TransparentNode ?: return
        val tv=node.element as? MediaElement ?: return
        if(tv.kind!=MediaKind.TV || packet.session!=tv.sharedTv.session || packet.revision!=tv.mediaRevision)return
        val shared=tv.sharedTv
        when(packet.action) {
            TelevisionCommand.CLAIM->if(shared.claim(player.uuid,true))tv.needPublish()
            TelevisionCommand.OBSERVE->if(tv.working) {
                // A healthy host keeps its lease through short client stalls. A vanished
                // or out-of-range host is replaced on the next observation, without waiting.
                if(!shared.serverHosted)shared.host?.let { id->
                    val host=world.server.playerList.getPlayer(id)
                    if(host==null || host.serverLevel()!==world || host.distanceToSqr(p.x+.5,p.y+.5,p.z+.5)>1024)shared.leave(id)
                }
                val fresh=player.uuid !in shared.activeViewers
                shared.claim(player.uuid,false);mods.eamedia.web.WebRoutingNetwork.observe(player,tv);ServerTelevisions.observe(tv,world,p);tv.mirrorRelay.reset(shared.session,shared.hostEpoch,tv.mediaRevision);tv.needPublish()
                if(fresh && shared.host!=player.uuid)tv.mirrorRelay.cached.forEach { PacketDistributor.sendToPlayer(player,it) }
            }
            TelevisionCommand.RELEASE->{ shared.release(player.uuid);tv.needPublish() }
            TelevisionCommand.LEAVE->{ val host=shared.host==player.uuid;shared.leave(player.uuid);if(host)mods.eamedia.web.WebRoutingNetwork.revoke(shared.session);tv.needPublish() }
            TelevisionCommand.NAVIGATE->if(packet.value.length<=2048 && TelevisionUrl.parse(packet.value)!=null && shared.claim(player.uuid,true))tv.playMedia(packet.value)
            TelevisionCommand.BACK,TelevisionCommand.FORWARD,TelevisionCommand.RELOAD->if(shared.claim(player.uuid,true)) {
                if(shared.serverHosted)tv.navigateHistory(packet.action)
                else forwardInput(tv,player,p,when(packet.action){TelevisionCommand.BACK->"{\"type\":\"back\"}";TelevisionCommand.FORWARD->"{\"type\":\"forward\"}";else->"{\"type\":\"reload\"}"})
            }
            TelevisionCommand.PAGE_CHANGED->if(!shared.serverHosted && tv.working && shared.host==player.uuid && packet.value.length<=2048 && TelevisionUrl.parse(packet.value)!=null)tv.browserPageChanged(packet.value)
            TelevisionCommand.INPUT->{
                if(!tv.working || shared.controller!=player.uuid || !tv.mirrorRelay.inputBudget.accept(1) || !validInput(packet.value))return
                if(shared.serverHosted)ServerTelevisions.input(tv,packet.value) else forwardInput(tv,player,p,packet.value)
            }
            TelevisionCommand.REPORT->{
                if(shared.serverHosted || !tv.working || shared.host!=player.uuid || packet.value.length>1024)return
                val sample=parsePlayback(packet.value) ?: return
                if(shared.report(player.uuid,sample,packet.sequence))tv.needPublish()
            }
        }
    }
    private fun forwardInput(tv: MediaElement,controller: ServerPlayer,p: BlockPos,value: String) {
        val world=controller.serverLevel()
        val shared=tv.sharedTv
        val host=shared.host?.let(world.server.playerList::getPlayer) ?: return
        if(host.serverLevel()!==world || host.distanceToSqr(p.x+.5,p.y+.5,p.z+.5)>1024)return
        PacketDistributor.sendToPlayer(host,TelevisionHostInput(p,shared.session,shared.hostEpoch,tv.mediaRevision,controller.uuid,value))
    }
    fun stream(player: ServerPlayer,packet: TelevisionStream) {
        val world=player.serverLevel();val p=packet.pos
        if(!world.hasChunkAt(p) || player.distanceToSqr(p.x+.5,p.y+.5,p.z+.5)>1024)return
        val tv=((world.getBlockEntity(p) as? TransparentNodeEntity)?.node as? TransparentNode)?.element as? MediaElement ?: return
        val shared=tv.sharedTv
        if(tv.kind!=MediaKind.TV || shared.serverHosted || !tv.working || shared.host!=player.uuid || packet.session!=shared.session || packet.epoch!=shared.hostEpoch || packet.revision!=tv.mediaRevision || packet.sequence<0)return
        val relay=tv.mirrorRelay;relay.reset(shared.session,shared.hostEpoch,tv.mediaRevision)
        val packets=when(packet.kind) {
            TelevisionStream.VIDEO->{
                if(!relay.videoBudget.accept(packet.bytes.size))return
                val jpeg=relay.video.accept(packet) ?: return
                if(jpeg.size<4 || jpeg[0]!=0xff.toByte() || jpeg[1]!=0xd8.toByte())return
                TelevisionStream.split(p,packet.session,packet.epoch,packet.revision,packet.sequence,jpeg).also { relay.cached=it }
            }
            TelevisionStream.AUDIO->{
                if(packet.parts!=1 || packet.part!=0 || packet.bytes.size !in 4..8192 || packet.bytes.size%4!=0 || packet.sequence<=relay.audioSequence || !relay.audioBudget.accept(packet.bytes.size))return
                relay.audioSequence=packet.sequence;listOf(packet)
            }
            else->return
        }
        for(id in shared.activeViewers) {
            if(id==player.uuid)continue
            val watcher=world.server.playerList.getPlayer(id) ?: continue
            if(watcher.serverLevel()!==world || watcher.distanceToSqr(p.x+.5,p.y+.5,p.z+.5)>1024) { shared.leave(id);continue }
            packets.forEach { PacketDistributor.sendToPlayer(watcher,it) }
        }
    }
    internal fun validInput(value: String): Boolean=try {
        if(value.length>2048)false else {
            val d=JsonParser.parseString(value).asJsonObject
            fun mouse()=d["x"].asInt in 0 until 960 && d["y"].asInt in 0 until 540
            when(d["type"].asString) {
                "move"->mouse()
                "down","up"->mouse() && d["button"].asInt in 0..2
                "wheel"->mouse() && d["delta"].asDouble.let { it.isFinite() && it in -20.0..20.0 }
                "keyDown","keyUp"->d["key"].asInt in 0..512 && d["scan"].asInt in 0..65535 && d["mods"].asInt in 0..63
                "char"->d["text"].asString.length in 1..2 && d["mods"].asInt in 0..63
                "paste"->d["text"].asString.length in 1..256
                "back","forward","reload"->true
                else->false
            }
        }
    } catch(_: Exception) { false }
    internal fun parsePlayback(value: String): TelevisionPlayback?=try {
        val d=JsonParser.parseString(value).asJsonObject
        TelevisionPlayback(d["position"].asDouble,d["paused"].asBoolean,d["rate"].asDouble,
            d["duration"].asDouble,d["live"].asBoolean,true).takeIf { it.valid() }
    } catch(_: Exception) { null }
}
