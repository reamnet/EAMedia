package mods.eamedia

import mods.eln.Eln
import mods.eln.misc.Coordinate
import mods.eln.node.transparent.TransparentNode
import mods.eamedia.web.WebDestination
import mods.eamedia.web.WebDomains
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.client.sounds.AudioStream
import org.junit.Test
import org.junit.Assert.assertThrows
import org.lwjgl.BufferUtils
import java.io.*
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import javax.sound.sampled.AudioFormat
import kotlin.test.*

class JukeboxExpansionTest {
    @Test fun listenersCannotBecomeOrTakeOverThePrivateBrowserHost() {
        var time=0L;val shared=SharedTelevision(false) { time }.apply { privateHost=true }
        val host=UUID.randomUUID();val listener=UUID.randomUUID()
        shared.claim(listener,false);assertNull(shared.host)
        assertTrue(shared.claim(host,true));assertEquals(host,shared.host)
        assertFalse(shared.claim(listener,true));assertEquals(host,shared.host)
        shared.release(host);shared.claim(listener,false);assertEquals(host,shared.host)
        time=16000;shared.claim(listener,false);assertNull(shared.host)
        assertTrue(shared.claim(listener,true));assertEquals(listener,shared.host)
    }
    @Test fun privateBrowserPageChangesDoNotPublishLoginRedirectsOrTokens() {
        val d=MediaElement(TransparentNode().apply { coordinate=Coordinate(0,80,0,0) },Eln.transparentNodeItem.subItemList[9+(126 shl 6)] as MediaDescriptor)
        assertTrue(d.playBrowser("https://app.plex.tv/desktop/"));assertFalse(d.playMedia("file:///bad"));assertTrue(d.browserMode);val revision=d.mediaRevision
        d.browserPageChanged("https://app.plex.tv/desktop/#!/?X-Plex-Token=private")
        assertEquals("https://app.plex.tv/desktop/",d.mediaUrl);assertEquals(revision,d.mediaRevision)
        assertFalse(d.playBrowser("https://app.plex.tv/?X-Plex-Token=private"))
        val s=JukeboxSnapshot.of(d);val out=ByteArrayOutputStream();s.write(DataOutputStream(out))
        assertFalse(out.toString(Charsets.UTF_8).contains("plex.tv"));assertEquals("",JukeboxSnapshot.read(DataInputStream(ByteArrayInputStream(out.toByteArray()))).url)
        val tag=CompoundTag().also(d::writeToNBT);assertFalse(tag.toString().contains("X-Plex-Token"))
        assertTrue(d.playMedia("https://example.org/audio.ogg"));assertFalse(d.browserMode)
    }
    @Test fun exactConfiguredPlexEndpointsAllowLanWithoutOpeningOtherHostsOrPorts() {
        val domains=WebDomains(listOf("*"),setOf("192.168.1.20:32400","localhost:32400"))
        val policy=WebDestination(domains,resolver={ InetAddress.getAllByName(it) })
        assertEquals(32400,policy.resolve("192.168.1.20",32400).port)
        assertThrows(IllegalArgumentException::class.java) { policy.resolve("192.168.1.21",32400) }
        assertThrows(IllegalArgumentException::class.java) { policy.resolve("192.168.1.20",22) }
        assertTrue(domains.allowsPage(URI("http://192.168.1.20:32400/web/")))
        assertEquals(domains.plex,WebDomains.fromWire(domains.wire()).plex)
        val metadata=WebDestination(WebDomains(emptyList(),setOf("169.254.169.254:32400")))
        assertThrows(IllegalArgumentException::class.java) { metadata.resolve("169.254.169.254",32400) }
    }
    @Test fun speakersRetainChannelVolumeAndCorrectMountingRequirements() {
        for(ceiling in listOf(false,true)) {
            val desc=SpeakerDescriptor(ceiling);assertEquals(ceiling,desc.mustHaveCeiling());assertEquals(!ceiling,desc.mustHaveFloor())
            val e=SpeakerElement(TransparentNode().apply { coordinate=Coordinate(0,80,0,0) },desc)
            e.readItemStackNBT(CompoundTag().apply { putInt("speakerChannel",4321);putDouble("speakerVolume",.4) })
            val copy=SpeakerElement(TransparentNode().apply { coordinate=Coordinate(0,80,0,0) },desc);copy.readItemStackNBT(e.getItemStackNBT())
            assertEquals(4321,copy.channel);assertEquals(.4,copy.volume);assertNull(SpeakerLinks.source(copy))
        }
    }
    private class Bytes: AudioStream {
        val data=ByteArray(32) { it.toByte() };var at=0;var closes=0
        override fun getFormat()=AudioFormat(44100f,16,1,true,false)
        override fun close() { closes++ }
        override fun read(size: Int): ByteBuffer { val n=minOf(size,data.size-at);return BufferUtils.createByteBuffer(n).apply { put(data,at,n);at+=n;flip() } }
    }
    private fun take(stream: AudioStream,count: Int)=stream.read(count).let { b->ByteArray(b.remaining()).also { b.get(it) } }
    @Test fun twoSpeakersReadTheSameDecodedSamplesWithoutStealingOrClosingEachOther() {
        val bytes=Bytes();val fan=PcmFanout(bytes,64);val a=fan.reader();val b=fan.reader()
        assertContentEquals(byteArrayOf(0,1,2,3),take(a,4));assertContentEquals(byteArrayOf(0,1,2,3),take(b,4))
        a.close();assertContentEquals(byteArrayOf(4,5,6,7),take(b,4));assertEquals(0,bytes.closes)
        fan.close();assertEquals(1,bytes.closes)
    }
    @Test fun slowSpeakerDropsOldSamplesWithinBoundedBufferAndLateSpeakerStartsAtCurrentPosition() {
        val bytes=Bytes();val fan=PcmFanout(bytes,16);val a=fan.reader();val slow=fan.reader()
        repeat(3) { take(a,8) }
        assertContentEquals((8..15).map(Int::toByte).toByteArray(),take(slow,8))
        val late=fan.reader();assertContentEquals((24..31).map(Int::toByte).toByteArray(),take(late,8));fan.close()
    }
}
