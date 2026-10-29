package mods.eamedia

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer
import java.nio.ByteOrder
import mods.eamedia.server.BrowserWorker
import kotlin.test.*

class ServerTelevisionTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun pageAudioCapturePreservesPageMuteAndVolumeWithoutAnyViewersGain()=captureCheck(false)
    @Test fun clientHostSpeakerIsSilentWithoutChangingTheCapturedPageVolume()=captureCheck(true)
    private fun captureCheck(relay: Boolean) {
        val capture=javaClass.getResourceAsStream("/assets/eln/browser/mirror-capture.js")!!.bufferedReader().use { it.readText() }
        val script="""
            globalThis.window=globalThis;const handlers={},gains=[],elementSources=[];let periodic,ended,captures=0;
            class AudioNode {connect(node){this.destination=node;return node;}disconnect(){this.disconnected=true;}}
            class AudioDestinationNode extends AudioNode {}
            class MediaStream {constructor(tracks){this.tracks=tracks;}getAudioTracks(){return this.tracks;}}
            class AudioContext {
                constructor(){this.destination=new AudioDestinationNode();this.destination.context=this;}
                createScriptProcessor(){return new AudioNode();}
                createGain(){const n=new AudioNode();n.gain={value:1};gains.push(n);return n;}
                createMediaStreamSource(){return new AudioNode();}
                createMediaElementSource(){const source=new AudioNode();elementSources.push(source);return source;}
                createMediaStreamDestination(){return Object.assign(new AudioNode(),{stream:new MediaStream([1])});}
                resume(){return Promise.resolve();}
            }
            Object.assign(globalThis,{AudioNode,AudioDestinationNode,MediaStream,AudioContext});
            const media={readyState:2,volume:.7,muted:false,paused:false,currentTime:1,duration:10,playbackRate:1,
                captureStream:()=>{captures++;return new MediaStream([{addEventListener:(name,fn)=>ended=fn}]);},getBoundingClientRect:()=>({width:960,height:540}),tagName:'VIDEO'};
            globalThis.document={querySelectorAll:()=>[media],addEventListener:(name,fn)=>handlers[name]=fn};
            globalThis.setInterval=fn=>periodic=fn;globalThis.eaTvCapture=()=>{};
            // A viewer may be muted locally. It must never scale what the server captures.
            window.__elnTVGain=0;window.__eaTVRelayAudio=$relay;
            $capture
            function equal(a,b){if(Math.abs(a-b)>1e-9)throw Error(a+' != '+b);}
            equal(media.volume,.7);
            if(window.__eaTVRelayAudio){
                equal(elementSources.length,1);equal(captures,0);
                // HTML media enters our processor, whose only native output has zero gain.
                equal(elementSources[0].destination.destination.gain.value,0);
                media.muted=true;handlers.volumechange();media.muted=false;media.volume=.3;periodic();equal(media.volume,.3);
                equal(elementSources.length,1);
            }else{
                const capturedGain=gains.find(g=>g.gain.value===.7);equal(capturedGain.gain.value,.7);
                media.muted=true;handlers.volumechange();equal(capturedGain.gain.value,0);
                media.muted=false;media.volume=.3;periodic();equal(capturedGain.gain.value,.3);equal(media.volume,.3);
                const previousGain=capturedGain;ended();periodic();equal(captures,2);
                if(!previousGain.disconnected)throw Error('Ended source was retained');
                equal(gains[gains.length-1].gain.value,.3);
            }
            console.log('Server capture checks passed');
        """.trimIndent()
        val file=temp.root.toPath().resolve("capture.js");Files.writeString(file,script)
        val executable=BundledMediaRuntime(temp.root.toPath().resolve("tools")).resolve("quickjs")
        val process=ProcessBuilder(executable.toString(),file.toString()).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(15,TimeUnit.SECONDS));val output=process.inputStream.bufferedReader().readText()
            assertEquals(0,process.exitValue(),output);assertTrue(output.contains("Server capture checks passed"))
        }finally{process.destroyForcibly()}
    }
    @Test fun noPlayerCanBecomeTheHostOrPublishAPlaybackReport() {
        val tv=SharedTelevision(serverHosted=true);val first=UUID.randomUUID();val second=UUID.randomUUID()
        tv.claim(first,false);tv.claim(first,true);assertNull(tv.host)
        tv.startServerHost();val epoch=tv.hostEpoch
        val state=TelevisionPlayback(10.0,false,1.0,30.0,false,true)
        assertFalse(tv.report(first,state,1));assertFalse(tv.report(second,state,1))
        assertTrue(tv.report(SharedTelevision.SERVER_HOST,state,1))
        tv.leave(first);tv.claim(second,false);tv.claim(second,true)
        assertEquals(SharedTelevision.SERVER_HOST,tv.host);assertEquals(epoch,tv.hostEpoch)
        assertEquals(second,tv.controller)
    }
    @Test fun closingAndReopeningAudioDoesNotReuseAClosedStreamOrGrowUnboundedBacklogs() {
        val old=TelevisionPcmQueue();old.offer(ByteArray(8192){1});old.close()
        assertTrue(old.isClosed);old.offer(ByteArray(8192){2});assertEquals(0,old.read(4800).remaining())
        val fresh=TelevisionPcmQueue()
        repeat(100){packet->fresh.offer(ByteArray(8192){packet.toByte()})}
        val first=fresh.read(96000);assertEquals(4800,first.remaining());assertEquals(98.toByte(),first.get())
        assertEquals(4800L,fresh.consumedBytes);fresh.close()
    }
    @Test fun workerInputTableLoadsWithoutLwjglOrAnyNativeClientWindow() {
        val loader=object: ClassLoader(ClassLoader.getPlatformClassLoader()) {
            fun install(bytes: ByteArray)=defineClass("org.lwjgl.glfw.GLFW",bytes,0,bytes.size)
        }
        val type=loader.install(WorkerInputConstants.bytecode())
        assertEquals(2,type.getField("GLFW_MOD_CONTROL").getInt(null))
        assertEquals(259,type.getField("GLFW_KEY_BACKSPACE").getInt(null))
        assertEquals(0,type.getField("GLFW_MOUSE_BUTTON_1").getInt(null))
        assertEquals(14,type.getMethod("glfwGetKeyScancode",Int::class.javaPrimitiveType).invoke(null,259))
    }
    @Test fun iframeAudioIsMixedClippedPacedAndBoundedInsteadOfInterleavedAtDoubleSpeed() {
        val now=AtomicLong();val mixer=BrowserWorker.AudioMixer(now::get)
        fun packet(left: Short,right: Short)=ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(480){putShort(left);putShort(right)} }.array()
        mixer.offer(1,packet(10000,20000));mixer.offer(2,packet(15000,20000))
        assertNull(mixer.read(480));now.set(100_000_000)
        val mixed=ByteBuffer.wrap(assertNotNull(mixer.read(480))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(25000.toShort(),mixed.short);assertEquals(32767.toShort(),mixed.short)
        now.set(3_000_000_000);assertNull(mixer.read(480));assertEquals(0,mixer.sourceCount())
        repeat(100){mixer.offer(it.toLong(),packet(100,100))};assertEquals(32,mixer.sourceCount())
        mixer.clear();assertEquals(0,mixer.sourceCount())
    }
}
