package mods.eamedia

import net.minecraft.nbt.CompoundTag
import org.junit.Test
import java.awt.image.BufferedImage
import java.util.UUID
import kotlin.test.*

class ClientTelevisionTest {
    private val a=UUID(1,1);private val b=UUID(2,2)
    @Test fun controllerChangesDoNotMoveTheClientHostButLeavingOrLeaseExpiryDoes() {
        var now=0L;val tv=SharedTelevision(serverHosted=false){now}
        tv.claim(a,false);val epoch=tv.hostEpoch;assertEquals(a,tv.host)
        tv.claim(b,false);tv.claim(b,true);assertEquals(a,tv.host);assertEquals(b,tv.controller);assertEquals(epoch,tv.hostEpoch)
        now=5000;tv.claim(a,false);now=7000;tv.claim(b,false);assertEquals(a,tv.host)
        tv.leave(a);tv.claim(b,false);assertEquals(b,tv.host);assertNotEquals(epoch,tv.hostEpoch)
        val second=tv.hostEpoch;now=23_000;assertNull(tv.host)
        tv.claim(a,false);assertEquals(a,tv.host);assertNotEquals(second,tv.hostEpoch)
    }
    @Test fun onlyCurrentClientHostReportsAndSavedStateDoesNotRetainOwnership() {
        var now=0L;val tv=SharedTelevision(serverHosted=false){now};tv.claim(a,false);tv.running(true)
        tv.startServerHost();assertEquals(a,tv.host)
        val sample=TelevisionPlayback(20.0,false,1.0,100.0,false,true)
        assertFalse(tv.report(SharedTelevision.SERVER_HOST,sample,1));assertFalse(tv.report(b,sample,1));assertTrue(tv.report(a,sample,1))
        now=1500;val tag=CompoundTag().also(tv::write)
        val copy=SharedTelevision(serverHosted=false){now}.apply{read(tag)}
        assertEquals(21.5,copy.snapshot().position);assertNull(copy.host);assertTrue(copy.activeViewers.isEmpty())
        tv.leave(a);tv.claim(b,false);assertFalse(tv.report(a,sample,2));assertTrue(tv.report(b,sample,1))
    }
    @Test fun defaultHostingIsClientAndServerRenderingRequiresExplicitSelection() {
        val before=System.getProperty("eamedia.tvHosting")
        try {
            System.clearProperty("eamedia.tvHosting");assertFalse(TelevisionHosting.serverHosted)
            System.setProperty("eamedia.tvHosting","server");assertTrue(TelevisionHosting.serverHosted)
            System.setProperty("eamedia.tvHosting","client");assertFalse(TelevisionHosting.serverHosted)
        }finally{if(before==null)System.clearProperty("eamedia.tvHosting") else System.setProperty("eamedia.tvHosting",before)}
    }
    @Test fun detailedPictureFitsMirrorBudgetAndRetainsAspectRatio() {
        val image=BufferedImage(960,540,BufferedImage.TYPE_INT_RGB);val random=java.util.Random(42)
        val rgb=IntArray(960*540){random.nextInt()};image.setRGB(0,0,960,540,rgb,0,960)
        val bytes=assertNotNull(TelevisionMirror.encodeMirror(image));assertTrue(bytes.size<=TelevisionStream.MAX_FRAME)
        val copy=TelevisionMirror.decode(bytes);assertEquals(960,copy.width);assertEquals(540,copy.height)
    }
    @Test fun relayAdmitsThirtyFullSizedFramesPerSecondBeforeAndAfterSessionReset() {
        var now=0L;val relay=TelevisionRelay{now}
        val data=ByteArray(TelevisionStream.MAX_FRAME)
        for(revision in 0..1) {
            relay.reset(a,b,revision)
            val start=now
            // Two seconds at 30 fps, checking every fragment against the real relay budget.
            for(frame in 0 until 60) {
                now=start+frame*1000L/30
                val packets=TelevisionStream.split(net.minecraft.core.BlockPos.ZERO,a,b,revision,frame.toLong(),data)
                for(packet in packets)assertTrue(relay.videoBudget.accept(packet.bytes.size),"Frame $frame was throttled")
            }
            assertFalse(relay.videoBudget.accept(TelevisionStream.MAX_FRAME*2+1))
        }
    }
    @Test fun slowFramesCatchUpAudioWithoutAcceleratingTheClockOrBuildingBacklog() {
        val clock=TelevisionAudioClock()
        assertEquals(1,clock.claim(0));assertEquals(0,clock.claim(25_000_000))
        assertEquals(1,clock.claim(50_000_000));assertEquals(4,clock.claim(250_000_000))
        assertEquals(0,clock.claim(250_000_001));assertEquals(1,clock.claim(300_000_000))
        assertEquals(4,clock.claim(10_000_000_000));assertEquals(0,clock.claim(10_000_000_001))
        clock.reset();assertEquals(1,clock.claim(10_000_000_002))
    }

}
