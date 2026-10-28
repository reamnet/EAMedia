package mods.eamedia

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.Assert.assertThrows
import org.junit.Test
import java.awt.image.BufferedImage
import java.util.UUID
import kotlin.test.*

class SharedTelevisionTest {
    private val a=UUID(1,1);private val b=UUID(2,2);private val c=UUID(3,3)
    @Test fun onlyOneBrowserHostExistsWhileAnotherPlayerCanControlItsPicture() {
        var now=0L;val tv=SharedTelevision { now }
        assertTrue(tv.claim(a,false));val epoch=tv.hostEpoch
        assertTrue(tv.claim(b,false));assertEquals(a,tv.host)
        assertTrue(tv.claim(b,true));assertEquals(b,tv.controller);assertEquals(a,tv.host)
        assertFalse(tv.claim(c,true));tv.release(b);assertTrue(tv.claim(c,true))
        assertEquals(setOf(a,b),tv.activeViewers)
        now=6001;assertNull(tv.host);assertNull(tv.controller);assertTrue(tv.activeViewers.isEmpty())
        assertTrue(tv.claim(b,false));assertEquals(b,tv.host);assertNotEquals(epoch,tv.hostEpoch)
    }
    @Test fun playbackClockIsSharedPowerAwareAndNotAdvancedWhileUnloaded() {
        var now=0L;val tv=SharedTelevision { now };tv.claim(a,false);tv.running(true)
        assertTrue(tv.report(a,TelevisionPlayback(12.0,false,1.0,100.0,false,true),1))
        now=2000;assertEquals(14.0,tv.snapshot().position)
        tv.running(false);now=10_000;assertEquals(14.0,tv.snapshot().position)
        tv.running(true);now=11_000;assertEquals(15.0,tv.snapshot().position)
        tv.claim(a,false);assertTrue(tv.report(a,TelevisionPlayback(15.0,true,1.0,100.0,false,true),2))
        now=12_000;assertEquals(15.0,tv.snapshot().position)
    }
    @Test fun staleOrUntrustedPlaybackReportsCannotReplaceHostState() {
        var now=0L;val tv=SharedTelevision { now };tv.claim(a,false)
        val s=TelevisionPlayback(8.0,false,1.0,20.0,false,true)
        assertFalse(tv.report(b,s,1));assertFalse(tv.report(a,s.copy(position=Double.NaN),1))
        assertTrue(tv.report(a,s,1));assertFalse(tv.report(a,s.copy(position=9.0),2))
        now=500;assertFalse(tv.report(a,s,1));assertTrue(tv.report(a,s.copy(position=9.0),2))
        now=7000;tv.claim(b,false);assertFalse(tv.report(a,s,3));assertTrue(tv.report(b,s,1))
    }
    @Test fun restartRetainsResumePositionButNeverAnOldHostOrControlLease() {
        var now=0L;val tv=SharedTelevision { now };tv.claim(a,false);tv.claim(b,true);tv.running(true)
        tv.report(a,TelevisionPlayback(30.0,false,1.5,100.0,false,true),1);now=2000
        val tag=CompoundTag().also(tv::write);val copy=SharedTelevision { now+86_400_000 }.apply { read(tag) }
        assertEquals(33.0,copy.snapshot().position);assertNull(copy.host);assertNull(copy.controller);assertNotEquals(tv.session,copy.session)
        tag.getCompound("televisionPlayback").putDouble("position",Double.POSITIVE_INFINITY);copy.read(tag);assertFalse(copy.snapshot().known)
    }
    @Test fun frameFragmentsRoundTripWithoutExceedingServerboundPayloadLimits() {
        val data=ByteArray(TelevisionStream.MAX_FRAME) { it.toByte() }
        val packets=TelevisionStream.split(BlockPos(3,80,4),a,b,10,20,data)
        assertEquals(6,packets.size)
        for(p in packets) {
            val buf=RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY)
            try {
                TelevisionStream.CODEC.encode(buf,p);assertTrue(buf.readableBytes()<32767)
                val copy=TelevisionStream.CODEC.decode(buf)
                assertEquals(p.pos,copy.pos);assertEquals(p.session,copy.session);assertEquals(p.epoch,copy.epoch);assertEquals(p.revision,copy.revision)
                assertEquals(p.part,copy.part);assertEquals(p.parts,copy.parts);assertContentEquals(p.bytes,copy.bytes)
            } finally { buf.release() }
        }
        val input=TelevisionHostInput(BlockPos(3,80,4),a,b,10,c,"""{"type":"char","text":"A","mods":0}""")
        val buf=RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY)
        try { TelevisionHostInput.CODEC.encode(buf,input);assertEquals(input,TelevisionHostInput.CODEC.decode(buf)) } finally { buf.release() }
        val assembler=TelevisionFrameAssembler();var result: ByteArray?=null
        for(p in packets.reversed())assembler.accept(p)?.let { result=it }
        assertContentEquals(data,result);assertNull(assembler.accept(packets[0]))
    }
    @Test fun incompleteOldOversizedOrTimedOutFramesAreDiscarded() {
        var now=0L;val assembler=TelevisionFrameAssembler { now }
        val chunks=TelevisionStream.split(BlockPos.ZERO,a,b,0,1,ByteArray(50_000))
        assertNull(assembler.accept(chunks[0]));assertNull(assembler.accept(chunks[0]))
        assertNull(assembler.accept(chunks[1].copy(parts=7)));assertNull(assembler.accept(chunks[1].copy(bytes=ByteArray(24_001))))
        now=1501;assertNull(assembler.accept(chunks[1]));assertNull(assembler.accept(chunks[2]))
        val newer=chunks.map { it.copy(sequence=2) };newer.forEach { assembler.accept(it) }
        assertNull(assembler.accept(chunks[2]))
        val oversized=(0 until 6).map { chunks[0].copy(sequence=3,parts=6,part=it,bytes=ByteArray(24_000)) }
        assertTrue(oversized.map { assembler.accept(it) }.all { it==null })
    }
    @Test fun byteBudgetsDoNotAccumulateUnlimitedBurstCapacity() {
        var now=0L;val budget=TelevisionByteBudget(1000.0,2000.0) { now }
        assertTrue(budget.accept(2000));assertFalse(budget.accept(1));assertFalse(budget.accept(-1))
        now=500;assertTrue(budget.accept(500));assertFalse(budget.accept(1))
        now=1_000_000;assertFalse(budget.accept(2001));assertTrue(budget.accept(2000));assertFalse(budget.accept(1))
    }
    @Test fun jpegDecoderChecksDimensionsAndFormatBeforeReadingPixels() {
        val image=BufferedImage(960,540,BufferedImage.TYPE_INT_RGB);image.setRGB(400,200,0xffcc2200.toInt())
        val encoded=TelevisionMirror.jpeg(image);assertTrue(encoded.size<TelevisionStream.MAX_FRAME)
        val copy=TelevisionMirror.decode(encoded);assertEquals(960,copy.width);assertEquals(540,copy.height)
        assertThrows(IllegalArgumentException::class.java) { TelevisionMirror.decode(TelevisionMirror.jpeg(BufferedImage(961,540,BufferedImage.TYPE_INT_RGB))) }
        assertThrows(IllegalArgumentException::class.java) { TelevisionMirror.decode(ByteArray(TelevisionStream.MAX_FRAME+1)) }
    }
    @Test fun remoteInputAcceptsOnlyBrowserEventsAndBoundedCoordinates() {
        assertTrue(TelevisionNetwork.validInput("""{"type":"down","x":100,"y":200,"button":0}"""))
        assertTrue(TelevisionNetwork.validInput("""{"type":"char","text":"A","mods":0}"""))
        assertFalse(TelevisionNetwork.validInput("""{"type":"down","x":960,"y":200,"button":0}"""))
        assertFalse(TelevisionNetwork.validInput("""{"type":"wheel","x":0,"y":0,"delta":"NaN"}"""))
        assertFalse(TelevisionNetwork.validInput("""{"type":"javascript","text":"alert(1)"}"""))
        assertNull(TelevisionNetwork.parsePlayback("""{"position":-10,"paused":false,"rate":1,"duration":20,"live":false}"""))
    }
    @Test fun pcmQueueDropsOldBacklogAndReturnsSmallAlignedAudioBuffers() {
        val queue=TelevisionPcmQueue();assertEquals(2,queue.format.channels);assertEquals(24000f,queue.format.sampleRate)
        queue.offer(ByteArray(8192) { 1 });queue.offer(ByteArray(8192) { 2 });queue.offer(ByteArray(8192) { 3 })
        val data=queue.read(65_536);assertEquals(1920,data.remaining());assertEquals(2.toByte(),data.get())
        queue.close();assertEquals(0,queue.read(1920).remaining())
    }
}
