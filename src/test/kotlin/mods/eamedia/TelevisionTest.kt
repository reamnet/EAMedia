package mods.eamedia

import mods.eln.Eln
import mods.eln.misc.Coordinate
import mods.eln.node.transparent.TransparentNode
import mods.eln.sim.mna.RootSystem
import mods.eln.sim.mna.component.VoltageSource
import mods.eln.transparentnode.cord.CordRating
import net.minecraft.nbt.CompoundTag
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TelevisionTest {
    @get:Rule val temp=TemporaryFolder()
    private fun device()=MediaElement(TransparentNode().apply { coordinate=Coordinate(0,80,0,0) },Eln.transparentNodeItem.subItemList[10+(126 shl 6)] as MediaDescriptor)
    @Test fun browserAddressesIncludeWebpagesFragmentsAndHttpButNeverExecutableSchemes() {
        for(s in listOf("https://www.youtube.com/watch?v=abc#t=20","https://example.org/page?q=test","http://127.0.0.1:8080/player"))assertNotNull(TelevisionUrl.parse(s))
        for(s in listOf("javascript:alert(1)","data:text/html,test","file:///etc/passwd","mod://eln/something","https://name:secret@example.org/","example.org","https://example.org/\n","https://"))assertNull(TelevisionUrl.parse(s))
    }
    @Test fun tvUsesPassivePowerAndRetainsAddressAndControlsAcrossSaveLoad() {
        val tv=device();assertEquals(MediaKind.TV,tv.kind);assertTrue(tv.acceptsCord(CordRating.V120));assertFalse(tv.acceptsCord(CordRating.V240))
        val root=RootSystem(.05,1);tv.electricalLoadList.forEach(root::addState);tv.electricalComponentList.forEach(root::addComponent)
        val source=VoltageSource("TV supply",tv.input,null).apply { voltage=120.0 };root.addComponent(source)
        fun step() { repeat(3) { tv.prepare(true);root.step();tv.account(.05) };tv.prepare(true) }
        step();assertFalse(tv.working);assertEquals(2.0,tv.resistor.power,1e-6)
        assertTrue(tv.playMedia("https://example.org/watch#video"));step();assertTrue(tv.working);assertEquals(150.0,tv.resistor.power,1e-6)
        val revision=tv.mediaRevision;assertFalse(tv.playMedia("javascript:alert(1)"));assertEquals(revision,tv.mediaRevision)
        source.voltage=114.0;step();assertTrue(tv.working);assertEquals(150*.95*.95,tv.resistor.power,1e-6)
        source.voltage=0.0;step();assertFalse(tv.working);assertEquals(0.0,tv.resistor.power,1e-8)
        val copy=device();copy.readFromNBT(CompoundTag().also(tv::writeToNBT));assertEquals(tv.mediaUrl,copy.mediaUrl);assertTrue(copy.mediaRequested)
        source.voltage=120.0;step();assertTrue(tv.working)
        tv.stopMedia();step();assertFalse(tv.working);assertEquals(2.0,tv.resistor.power,1e-6)
        tv.setEnabled(false);step();assertEquals(0.0,tv.resistor.power,1e-8)
        assertTrue(tv.applySettings(doubleArrayOf(240.0,.3)));assertTrue(tv.acceptsCord(CordRating.V240));assertFalse(tv.acceptsCord(CordRating.V120))
    }
    @Test fun browserTvAppendKeepsExistingIndustrialWorldIdsStable() {
        assertEquals(9,MediaKind.JUKEBOX.reserved.subId);assertEquals(8,mods.eln.transparentnode.industrial.IndustrialKind.WELDER.ordinal);assertEquals(10,MediaKind.TV.reserved.subId)
        assertFalse(MediaKind.TV.valid(doubleArrayOf(480.0,.5)));assertFalse(MediaKind.TV.valid(doubleArrayOf(120.0,1.1)))
    }
    @Test fun browserVolumePreservesPageMuteAndLocalVolumeAndAlsoControlsWebAudio() {
        val script="""
            globalThis.window=globalThis;
            class AudioNode { connect(destination){this.destination=destination;return destination} }
            class AudioDestinationNode extends AudioNode {}
            globalThis.AudioNode=AudioNode;globalThis.AudioDestinationNode=AudioDestinationNode;
            globalThis.WeakRef=class {constructor(value){this.value=value}deref(){return this.value}};
            const media={volume:1,muted:true};
            globalThis.document={querySelectorAll:()=>[media],addEventListener:()=>{}};
            globalThis.setInterval=()=>{};
            function equal(a,b){if(Math.abs(a-b)>.00001)throw Error(a+' != '+b)}
            ${TelevisionBrowser.volumeScript(.5)}
            equal(media.volume,.5);if(!media.muted)throw Error('Page mute was overwritten');
            const destination=new AudioDestinationNode();destination.context={createGain:()=>Object.assign(new AudioNode(),{gain:{value:1}})};
            const source=new AudioNode();source.connect(destination);equal(source.destination.gain.value,.5);
            ${TelevisionBrowser.volumeScript(.8)}
            equal(media.volume,.8);equal(source.destination.gain.value,.8);
            media.volume=.4;
            ${TelevisionBrowser.volumeScript(.5)}
            equal(media.volume,.2);
            ${TelevisionBrowser.volumeScript(Double.NaN)}
            equal(media.volume,0);equal(source.destination.gain.value,0);
            console.log('TV volume checks passed');
        """.trimIndent()
        val file=temp.root.toPath().resolve("volume-test.js");Files.writeString(file,script)
        val exe=BundledMediaRuntime(temp.root.toPath().resolve("runtime")).resolve("quickjs")
        val process=ProcessBuilder(exe.toString(),file.toString()).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(15,TimeUnit.SECONDS));val output=process.inputStream.bufferedReader().readText()
            assertEquals(0,process.exitValue(),output);assertTrue(output.contains("TV volume checks passed"))
        } finally { process.destroyForcibly() }
    }
}
