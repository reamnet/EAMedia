package mods.eamedia

import mods.eln.Eln
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

@Mod("eamedia")
class EAMedia(bus: IEventBus) {
    init {
        bus.addListener<RegisterPayloadHandlersEvent> { event->
            // Required channel negotiation prevents mixing active media devices with a client
            // that only has ELN's inert placeholders or the old client-local TV protocol.
            // Older relays impose a lower video budget and would silently drop 540p frames.
            val registrar=event.registrar("7")
            registrar.playToClient(MediaPresence.TYPE,MediaPresence.CODEC) { _,_-> }
            registrar.playToServer(TelevisionCommand.TYPE,TelevisionCommand.CODEC) { packet,context->
                context.enqueueWork { (context.player() as? net.minecraft.server.level.ServerPlayer)?.let { TelevisionNetwork.handle(it,packet) } }
            }
            registrar.playBidirectional(TelevisionStream.TYPE,TelevisionStream.CODEC) { packet,context->
                context.enqueueWork {
                    val player=context.player()
                    if(player is net.minecraft.server.level.ServerPlayer)TelevisionNetwork.stream(player,packet)
                    else TelevisionMirrorClient.receive(packet)
                }
            }
            registrar.playToClient(TelevisionHostInput.TYPE,TelevisionHostInput.CODEC) { packet,context->
                context.enqueueWork { TelevisionMirrorClient.input(packet) }
            }
            registrar.executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK)
                .playBidirectional(mods.eamedia.web.WebPacket.TYPE,mods.eamedia.web.WebPacket.CODEC,mods.eamedia.web.WebRoutingNetwork::handle)

        }
        Eln.LOGGER.info("Electrical Age Media loaded; TV hosting mode {}; jukebox playback remains client-side",if(TelevisionHosting.serverHosted)"server" else "client")
    }
}
object MediaPresence: CustomPacketPayload {
    val TYPE=CustomPacketPayload.Type<MediaPresence>(ResourceLocation.fromNamespaceAndPath("eamedia","compatibility"))
    val CODEC: StreamCodec<RegistryFriendlyByteBuf,MediaPresence> = StreamCodec.of({ buf,_->buf.writeByte(1) },{ buf->check(buf.readUnsignedByte().toInt()==1);MediaPresence })
    override fun type()=TYPE
}
