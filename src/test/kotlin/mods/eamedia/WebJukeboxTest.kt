package mods.eamedia

import mods.eln.Eln
import mods.eln.misc.Coordinate
import mods.eln.node.transparent.TransparentNode
import mods.eln.sim.mna.RootSystem
import mods.eln.sim.mna.component.VoltageSource
import net.minecraft.client.sounds.AudioStream
import net.minecraft.nbt.CompoundTag
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import kotlin.test.*

class WebJukeboxTest {
    @Test fun onlyHttpsMediaAddressesAreAcceptedAndYouTubeHostsMustMatchExactly() {
        for(s in listOf("https://example.org/song.ogg","https://www.youtube.com/watch?v=abc","https://youtu.be/abc"))assertNotNull(WebAudioUrl.parse(s))
        for(s in listOf("http://example.org/song.ogg","file:///music/song.ogg","https://name:password@example.org/song.ogg","https://","https://example.org/\ntrack"))assertNull(WebAudioUrl.parse(s))
        assertTrue(WebAudioUrl.youtube(WebAudioUrl.parse("https://www.youtube.com/watch?v=abc")!!))
        assertFalse(WebAudioUrl.youtube(WebAudioUrl.parse("https://youtube.com.example.org/audio")!!))
    }
    @Test fun playbackUsesRealPowerAndStopsOnLossOfSupplyWithoutServerNetworking() {
        val d=MediaElement(TransparentNode().apply { coordinate=Coordinate(0,80,0,0) },Eln.transparentNodeItem.subItemList[9+(126 shl 6)] as MediaDescriptor)
        val root=RootSystem(.05,1);d.electricalLoadList.forEach(root::addState);d.electricalComponentList.forEach(root::addComponent)
        val source=VoltageSource("jukebox supply",d.input,null).apply { voltage=120.0 };root.addComponent(source)
        fun advance() { repeat(3) { d.prepare(true);root.step();d.account(.05) };d.prepare(true) }
        advance();assertEquals(2.0,d.resistor.power,1e-6);assertFalse(d.working)
        assertTrue(d.playMedia("https://example.org/song.ogg"));advance();assertTrue(d.working);assertEquals(25.0,d.resistor.power,1e-6)
        val revision=d.mediaRevision;assertFalse(d.playMedia("file:///etc/hosts"));assertEquals(revision,d.mediaRevision)
        source.voltage=0.0;advance();assertFalse(d.working);assertEquals(0.0,d.resistor.power,1e-8)
        val copy=MediaElement(TransparentNode(),Eln.transparentNodeItem.subItemList[9+(126 shl 6)] as MediaDescriptor);copy.readFromNBT(CompoundTag().also(d::writeToNBT));assertEquals(d.mediaUrl,copy.mediaUrl);assertTrue(copy.mediaRequested)
        d.stopMedia();source.voltage=120.0;advance();assertFalse(d.working);assertEquals(2.0,d.resistor.power,1e-6)
    }
    @Test fun stereoBecomesMonoForPositionalPlaybackAndEofAndCleanupAreReported() {
        var supplied=false;var closed=0;var ended=0;var cleaned=0
        val source=object: AudioStream {
            override fun getFormat()=AudioFormat(44100f,16,2,true,false)
            override fun read(size: Int): ByteBuffer {
                if(supplied)return ByteBuffer.allocateDirect(0)
                supplied=true;return ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(32767);putShort(32767);putShort(-32768);putShort(32767);putShort(1000);putShort(-1000);flip() }
            }
            override fun close() { closed++ }
        }
        val stream=WebJukeboxAudio.LocalMonoStream(source,{ ended++ },{ cleaned++ })
        assertEquals(1,stream.format.channels);val pcm=stream.read(6).order(ByteOrder.LITTLE_ENDIAN);assertEquals(6,pcm.remaining());assertEquals(32767,pcm.short.toInt());assertEquals(0,pcm.short.toInt());assertEquals(0,pcm.short.toInt())
        assertFalse(stream.read(64).hasRemaining());assertEquals(1,ended);stream.close();stream.close();assertEquals(1,closed);assertEquals(1,cleaned)
    }
}
