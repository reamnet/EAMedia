package mods.eamedia.web

import mods.eamedia.MediaElement
import net.minecraft.network.Connection
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Only active TV hosts can reach server-approved public HTTP(S) destinations.
 * Packets use the authenticated game connection; there is no public proxy port. */
data class WebPacket(val message: WebMessage): CustomPacketPayload {
    override fun type()=TYPE
    companion object {
        val TYPE=CustomPacketPayload.Type<WebPacket>(ResourceLocation.fromNamespaceAndPath("eamedia","web_route"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf,WebPacket> = StreamCodec.of({b,p->
            val m=p.message;b.writeByte(m.kind);b.writeVarInt(m.id)
            when(m.kind) {
                WebMessage.OPEN->{b.writeUtf(m.host,253);b.writeShort(m.port)}
                WebMessage.DATA->{require(m.bytes.size in 1..WebMessage.CHUNK);b.writeByteArray(m.bytes)}
                WebMessage.CREDIT->{require(m.credit in 1..WebMessage.WINDOW);b.writeVarInt(m.credit)}
                WebMessage.STATUS->{b.writeBoolean(m.credit==1);b.writeUtf(m.host,8192)}
                WebMessage.READY,WebMessage.END,WebMessage.CLOSE->Unit
                else->throw IllegalArgumentException("Unknown web route packet")
            }
        },{b->
            val kind=b.readUnsignedByte().toInt();val id=b.readVarInt()
            WebPacket(when(kind) {
                WebMessage.OPEN->WebMessage(kind,id,b.readUtf(253),b.readUnsignedShort())
                WebMessage.DATA->WebMessage(kind,id,bytes=b.readByteArray(WebMessage.CHUNK).also{require(it.isNotEmpty())})
                WebMessage.CREDIT->WebMessage(kind,id,credit=b.readVarInt().also{require(it in 1..WebMessage.WINDOW)})
                WebMessage.STATUS->{val enabled=b.readBoolean();WebMessage(kind,id,host=b.readUtf(8192),credit=if(enabled)1 else 0)}
                WebMessage.READY,WebMessage.END,WebMessage.CLOSE->WebMessage(kind,id)
                else->throw IllegalArgumentException("Unknown web route packet")
            })
        })
    }
}

@EventBusSubscriber(modid="eamedia")
object WebRoutingNetwork {
    private data class Peer(val connection: Connection,val service: WebPeer)
    private val authenticated=ConcurrentHashMap<UUID,Connection>()
    private val peers=ConcurrentHashMap<UUID,Peer>()
    private val grants=WebHostGrants()
    @Volatile private var running=false
    @Volatile private var domains=WebDomains(emptyList())
    @Volatile private var enabled=false
    private val allowLoopback get()=java.lang.Boolean.getBoolean("eamedia.mirrorServer") && java.lang.Boolean.getBoolean("eamedia.webTestLoopback")
    @SubscribeEvent fun start(e: ServerStartedEvent) {
        try {domains=WebDomains.load(Path.of("config/eamedia-web-domains.txt"));enabled=System.getProperty("eamedia.webRouting","true").toBoolean()}
        catch(error: Exception){enabled=false;mods.eln.Eln.LOGGER.error("Cannot load config/eamedia-web-domains.txt; TV web routing remains disabled",error)}
        running=true
    }
    @SubscribeEvent fun login(e: PlayerEvent.PlayerLoggedInEvent) {
        val player=e.entity as? ServerPlayer ?: return
        authenticated[player.uuid]=player.connection.connection
        PacketDistributor.sendToPlayer(player,WebPacket(WebMessage(WebMessage.STATUS,host=domains.entries.joinToString(","),credit=if(enabled)1 else 0)))
    }
    fun observe(player: ServerPlayer,tv: MediaElement) {
        if(!enabled || !tv.working || tv.sharedTv.serverHosted || tv.sharedTv.host!=player.uuid)return
        grants.touch(tv.sharedTv.session,player.connection.connection)?.let(::closeIfNotHosting)
    }
    fun revoke(session: UUID){grants.revoke(session)?.let(::closeIfNotHosting)}
    private fun closeIfNotHosting(connection: Any) {
        if(grants.allows(connection))return
        peers.entries.removeIf { (_,peer)->if(peer.connection===connection){peer.service.close();true}else false }
    }
    @SubscribeEvent fun tick(e: ServerTickEvent.Post){grants.purge();peers.values.forEach{closeIfNotHosting(it.connection)}}
    @SubscribeEvent fun logout(e: PlayerEvent.PlayerLoggedOutEvent) {
        val player=e.entity as? ServerPlayer ?: return
        authenticated.remove(player.uuid,player.connection.connection);grants.logout(player.connection.connection);closeIfNotHosting(player.connection.connection)
    }
    @SubscribeEvent fun stop(e: ServerStoppingEvent){running=false;authenticated.clear();grants.clear();peers.values.forEach{it.service.close()};peers.clear()}
    fun handle(packet: WebPacket,context: IPayloadContext) {
        val message=packet.message
        // Status can arrive before the render thread installs the play listener/player.
        // Direction is available during that transition; player() is not.
        if(context.flow()==net.minecraft.network.protocol.PacketFlow.CLIENTBOUND) {
            val connection=context.connection()
            if(message.kind==WebMessage.STATUS)BrowserWebProxy.client.attach(connection,{connection.send(ServerboundCustomPayloadPacket(WebPacket(it)))},message.credit==1,WebDomains(message.host.split(',')))
            else BrowserWebProxy.client.receive(connection,message)
            return
        }
        val player=context.player() as? ServerPlayer ?: return
        val connection=context.connection()
        if(!running || authenticated[player.uuid]!==connection || !enabled || !grants.allows(connection)) {
            if(message.kind==WebMessage.OPEN)context.reply(WebPacket(WebMessage(WebMessage.CLOSE,message.id)))
            return
        }
        if(message.kind==WebMessage.STATUS || message.kind==WebMessage.READY)return
        var peer=peers[player.uuid]
        if(peer?.connection!==connection) {
            if(message.kind!=WebMessage.OPEN)return
            peer=peers.compute(player.uuid){_,old->
                if(old?.connection===connection)old else {
                    old?.service?.close();val test=allowLoopback
                    val policy=if(test)WebDestination(domains,true){host->if(host=="fixture.eamedia.test")arrayOf(java.net.InetAddress.getByName("127.0.0.1"))else java.net.InetAddress.getAllByName(host)}else WebDestination(domains)
                    Peer(connection,WebPeer(policy,{PacketDistributor.sendToPlayer(player,WebPacket(it))},{WebDialer.connect(it,testLoopback=test)},{mods.eln.Eln.LOGGER.info("TV web domain needs server approval: {} (config/eamedia-web-domains.txt)",it)}))
                }
            }
        }
        peer?.service?.receive(message)
    }
    fun diagnostic(player: ServerPlayer): Map<String,Any> {
        if(!java.lang.Boolean.getBoolean("eamedia.mirrorServer"))return emptyMap()
        val service=peers[player.uuid]?.service
        return mapOf("enabled" to enabled,"hostingAllowed" to grants.allows(player.connection.connection),"opened" to (service?.opened?.get() ?: 0L),"downloaded" to (service?.downloaded?.get() ?: 0L),"uploaded" to (service?.uploaded?.get() ?: 0L))
    }
}

@EventBusSubscriber(modid="eamedia",value=[Dist.CLIENT])
object BrowserWebClientEvents {
    @SubscribeEvent fun logout(e: ClientPlayerNetworkEvent.LoggingOut){BrowserWebProxy.client.detach()}
}
