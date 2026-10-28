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
            val registrar=event.registrar("2")
            registrar.playToClient(MediaPresence.TYPE,MediaPresence.CODEC) { _,_-> }
            registrar.playToServer(TelevisionCommand.TYPE,TelevisionCommand.CODEC) { packet,context->
                context.enqueueWork { (context.player() as? net.minecraft.server.level.ServerPlayer)?.let { TelevisionNetwork.handle(it,packet) } }
            }
            registrar.playBidirectional(TelevisionStream.TYPE,TelevisionStream.CODEC) { packet,context->
                if(context.flow().isClientbound)context.enqueueWork { TelevisionMirrorClient.receive(packet) }
                else context.enqueueWork { (context.player() as? net.minecraft.server.level.ServerPlayer)?.let { TelevisionNetwork.stream(it,packet) } }
            }
            registrar.playToClient(TelevisionHostInput.TYPE,TelevisionHostInput.CODEC) { packet,context->context.enqueueWork { TelevisionMirrorClient.input(packet) } }
        }
        Eln.LOGGER.info("Electrical Age Media loaded; TV/jukebox backends remain client-only")
    }
}
object MediaPresence: CustomPacketPayload {
    val TYPE=CustomPacketPayload.Type<MediaPresence>(ResourceLocation.fromNamespaceAndPath("eamedia","compatibility"))
    val CODEC: StreamCodec<RegistryFriendlyByteBuf,MediaPresence> = StreamCodec.of({ buf,_->buf.writeByte(1) },{ buf->check(buf.readUnsignedByte().toInt()==1);MediaPresence })
    override fun type()=TYPE
}
