package mods.eamedia

import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/** Fragmented to stay below Minecraft's serverbound custom-payload limit. No server image/audio decoder. */
data class TelevisionStream(val pos: BlockPos,val session: UUID,val epoch: UUID,val revision: Int,val sequence: Long,
                            val kind: Int,val part: Int,val parts: Int,val bytes: ByteArray): CustomPacketPayload {
    override fun type()=TYPE
    companion object {
        const val VIDEO=0;const val AUDIO=1;const val CHUNK=24_000;const val MAX_FRAME=131_072;const val MAX_PARTS=6
        const val WIDTH=960;const val HEIGHT=540;const val FPS=30
        const val VIDEO_BYTES_PER_SECOND=MAX_FRAME*FPS
        val TYPE=CustomPacketPayload.Type<TelevisionStream>(ResourceLocation.fromNamespaceAndPath("eamedia","television_stream"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf,TelevisionStream> = StreamCodec.of({ b,p->
            b.writeBlockPos(p.pos);b.writeUUID(p.session);b.writeUUID(p.epoch);b.writeVarInt(p.revision);b.writeVarLong(p.sequence)
            b.writeByte(p.kind);b.writeByte(p.part);b.writeByte(p.parts);b.writeByteArray(p.bytes)
        },{ b->TelevisionStream(b.readBlockPos(),b.readUUID(),b.readUUID(),b.readVarInt(),b.readVarLong(),b.readUnsignedByte().toInt(),b.readUnsignedByte().toInt(),b.readUnsignedByte().toInt(),b.readByteArray(CHUNK)) })
        fun split(pos: BlockPos,session: UUID,epoch: UUID,revision: Int,sequence: Long,data: ByteArray): List<TelevisionStream> {
            require(data.size in 1..MAX_FRAME)
            val parts=(data.size+CHUNK-1)/CHUNK
            return (0 until parts).map { i->TelevisionStream(pos,session,epoch,revision,sequence,VIDEO,i,parts,data.copyOfRange(i*CHUNK,minOf(data.size,(i+1)*CHUNK))) }
        }
    }
}

data class TelevisionHostInput(val pos: BlockPos,val session: UUID,val epoch: UUID,val revision: Int,val controller: UUID,val value: String): CustomPacketPayload {
    override fun type()=TYPE
    companion object {
        val TYPE=CustomPacketPayload.Type<TelevisionHostInput>(ResourceLocation.fromNamespaceAndPath("eamedia","television_input"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf,TelevisionHostInput> = StreamCodec.of({ b,p->
            b.writeBlockPos(p.pos);b.writeUUID(p.session);b.writeUUID(p.epoch);b.writeVarInt(p.revision);b.writeUUID(p.controller);b.writeUtf(p.value,2048)
        },{ b->TelevisionHostInput(b.readBlockPos(),b.readUUID(),b.readUUID(),b.readVarInt(),b.readUUID(),b.readUtf(2048)) })
    }
}

/** At most one incomplete frame; old, duplicate, oversized and timed-out fragments are discarded. */
class TelevisionFrameAssembler(private val clock: ()->Long={ System.nanoTime()/1_000_000 }) {
    private var sequence=-1L;private var completed=-1L;private var deadline=0L
    private var parts=emptyArray<ByteArray?>();private var size=0
    fun accept(packet: TelevisionStream): ByteArray? {
        if(packet.kind!=TelevisionStream.VIDEO || packet.parts !in 1..TelevisionStream.MAX_PARTS || packet.part !in 0 until packet.parts || packet.bytes.isEmpty() || packet.bytes.size>TelevisionStream.CHUNK || packet.sequence<=completed)return null
        if(packet.sequence<sequence)return null
        if(packet.sequence>sequence) { sequence=packet.sequence;deadline=clock()+1500;parts=arrayOfNulls(packet.parts);size=0 }
        if(clock()>deadline || packet.parts!=parts.size || parts[packet.part]!=null)return null
        size+=packet.bytes.size
        if(size>TelevisionStream.MAX_FRAME) { completed=sequence;parts=emptyArray();return null }
        parts[packet.part]=packet.bytes
        if(parts.any { it==null })return null
        val result=ByteArray(size);var at=0
        for(part in parts) { part!!.copyInto(result,at);at+=part.size }
        completed=sequence;parts=emptyArray();return result
    }
}

class TelevisionByteBudget(private val perSecond: Double,private val capacity: Double=perSecond,private val clock: ()->Long={ System.nanoTime()/1_000_000 }) {
    private var balance=capacity;private var sampled=clock()
    fun accept(bytes: Int): Boolean {
        val now=clock();balance=(balance+(now-sampled).coerceAtLeast(0)*perSecond/1000).coerceAtMost(capacity);sampled=now
        if(bytes<0 || bytes>balance)return false
        balance-=bytes;return true
    }
}

class TelevisionRelay(private val clock: ()->Long={ System.nanoTime()/1_000_000 }) {
    private var key=""
    private fun newVideoBudget()=TelevisionByteBudget(TelevisionStream.VIDEO_BYTES_PER_SECOND.toDouble(),TelevisionStream.MAX_FRAME*2.0,clock)
    var video=TelevisionFrameAssembler(clock);private set
    var videoBudget=newVideoBudget();private set
    var audioBudget=TelevisionByteBudget(120_000.0,24_576.0);private set
    val inputBudget=TelevisionByteBudget(120.0,120.0)
    var audioSequence=-1L
    var cached=emptyList<TelevisionStream>()
    fun drop() {
        if(key.isEmpty())return
        key="";cached=emptyList();video=TelevisionFrameAssembler(clock)
    }
    fun reset(session: UUID,epoch: UUID,revision: Int) {
        val next="$session/$epoch/$revision";if(next==key)return
        key=next;video=TelevisionFrameAssembler(clock);videoBudget=newVideoBudget();audioBudget=TelevisionByteBudget(120_000.0,24_576.0)
        audioSequence=-1;cached=emptyList()
    }
}
