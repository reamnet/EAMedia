package mods.eamedia.devtest

import com.google.gson.GsonBuilder
import mods.eamedia.*
import mods.eln.Eln
import mods.eln.api.media.ReservedMediaDevice
import mods.eln.misc.Direction
import mods.eln.node.transparent.TransparentNodeEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.*
import net.minecraft.world.level.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/** Audio-only browser hosting with real positional speaker streams and electrical power. */
@EventBusSubscriber(modid="eamedia",value=[Dist.CLIENT])
object JukeboxSpeakerChecks {
    private val mc get()=Minecraft.getInstance()
    private val at=BlockPos(256,80,256)
    private var phase=0;private var ticks=0;private var total=0;private var done=false
    private var task: CompletableFuture<*>?=null
    private lateinit var fixtures: NativeCampaignFixtures
    private lateinit var device: MediaElement
    private lateinit var render: MediaRender
    private val passed=ArrayList<String>()
    private val output get()=mc.gameDirectory.toPath().resolve("jukebox-speaker-checks")
    private fun server(f: ()->Unit) { task=mc.singleplayerServer!!.submit<Unit>(f) }
    private fun settled(): Boolean { task?.let { if(!it.isDone)return false;it.get();task=null };return true }
    private fun next() { phase++;ticks=0 }
    private fun photo(name: String) { Files.createDirectories(output);Screenshot.takeScreenshot(mc.mainRenderTarget).use { it.writeToFile(output.resolve("$name.png")) } }
    private fun setup() {
        val w=mc.singleplayerServer!!.overworld();val p=w.players().first();fixtures=NativeCampaignFixtures(w,p)
        for(x in 15..17)for(z in 15..17)w.setChunkForced(x,z,true)
        for(x in -5..8)for(z in -4..7)w.setBlockAndUpdate(at.offset(x,-1,z),Blocks.STONE.defaultBlockState())
        device=fixtures.placeMachine(Eln.findItemStack(MediaKind.JUKEBOX.title,1),at) as MediaElement;device.front=Direction.ZP;device.reconnect();fixtures.wire(at.west());fixtures.source(at.west(2),120.0)
        fixtures.placeMachine(Eln.findItemStack(ReservedMediaDevice.FLOOR_SPEAKER.title,1),at.east(4))
        val ceiling=at.east(6).above(2);w.setBlockAndUpdate(ceiling.above(),Blocks.STONE.defaultBlockState());fixtures.placeMachine(Eln.findItemStack(ReservedMediaDevice.CEILING_SPEAKER.title,1),ceiling)
        check(device.playBrowser(System.getProperty("eamedia.jukeboxFixture","http://fixture.eamedia.test:8139/jukebox.html")))
        p.teleportTo(w,258.5,80.0,260.5,180f,0f);w.setDayTime(6000)
    }
    private fun finish(error: Throwable?) {
        done=true;Files.createDirectories(output);Files.writeString(output.resolve("report.json"),GsonBuilder().setPrettyPrinting().create().toJson(mapOf("passed" to passed,"failure" to error?.stackTraceToString())))
        if(error!=null)Eln.LOGGER.error("JUKEBOX_SPEAKERS FAIL",error) else Eln.LOGGER.info("JUKEBOX_SPEAKERS COMPLETE")
        mc.execute { mc.stop();kotlin.concurrent.thread { Thread.sleep(1500);kotlin.system.exitProcess(if(error==null)0 else 1) } }
    }
    @SubscribeEvent fun tick(event: ClientTickEvent.Post) {
        if(!java.lang.Boolean.getBoolean("eamedia.jukeboxSpeakerChecks") || done)return
        try {
            if(phase==3 && ticks%100==0) {
                Eln.LOGGER.info("JUKEBOX_DEBUG {} bytes={} status={}",JukeboxAudioClient.debugSnapshot(render.tvSession!!),JukeboxAudioClient.diagnostics(render.tvSession!!),render.television.status()+" "+render.television.mirror.audioDebug())
                render.television.browser?.executeJavaScript("(()=>{document.querySelector('#native_diag')?.remove();let pre=document.createElement('pre');pre.id='native_diag';pre.textContent='CAPTURE_DIAG:'+JSON.stringify(window.__eaTVAudioDiagnostics?.());document.body.append(pre);})();", "",0)
                render.television.browser?.getText { Eln.LOGGER.info("JUKEBOX_PAGE {}",it.takeLast(1800)) }
            }
            ticks++;check(++total<5000) { "Jukebox campaign timeout phase $phase" }
            when(phase) {
                0->if(mc.screen is TitleScreen && mc.overlay==null && ticks>20) {
                    val name="jukebox-${System.currentTimeMillis()}"
                    mc.createWorldOpenFlows().createFreshLevel(name,LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,GameRules(),WorldDataConfiguration.DEFAULT),WorldOptions(74074L,false,false),{ it.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },mc.screen!!);next()
                }
                1->if(mc.player!=null && mc.screen==null && ticks>40) { server(::setup);next() }
                2->if(settled() && ticks>80) {
                    val r=(mc.level!!.getBlockEntity(at) as? TransparentNodeEntity)?.elementRender as? MediaRender ?: return
                    if(!r.working || !r.browserMode)return
                    render=r;mc.setScreen(TelevisionScreen(r));next()
                }
                3->if(ticks>150 && render.television.browser!=null) {
                    val bytes=JukeboxAudioClient.diagnostics(render.tvSession!!)
                    if(bytes.size<3 || bytes.values.any { it<10000 }) {
                        if(ticks%60==0) {
                            val b=render.television.browser!!
                            b.sendMousePress(380,315,0);b.sendMouseRelease(380,315,0)
                        }
                        return
                    }
                    check(render.television.mirror.encodedFrames==0L);photo("private-browser-controls")
                    passed+="Real Chromium media audio plays through the jukebox plus floor and ceiling speaker streams; all three consume PCM"
                    passed+="Private browser paints locally but encodes no shared video frames"
                    render.television.browser!!.executeJavaScript("history.pushState({},'', '?X-Plex-Token=native-private-check');", "",0)
                    mc.setScreen(null);next()
                }
                4->if(ticks>80) {
                    photo("jukebox-and-speakers-without-display")
                    server { check(!device.mediaUrl.contains("native-private-check"));check(device.mirrorRelay.cached.isEmpty());passed+="Authentication-style page redirects are not published or cached as shared images" }
                    val speaker=(mc.level!!.getBlockEntity(at.east(4)) as TransparentNodeEntity).elementRender as SpeakerRender;speaker.clientSendInt(80,9999);next()
                }
                5->if(settled() && ticks>80) {
                    val bytes=JukeboxAudioClient.diagnostics(render.tvSession!!);check(bytes.size==2) { "Expected two remaining endpoints, got $bytes" }
                    passed+="Changing a speaker's channel over the real control packet disconnects only that endpoint"
                    server { fixtures.voltage(at.west(2),0.0) };next()
                }
                6->if(settled() && ticks>100) {
                    check(!render.working);check(render.television.browser==null);check(JukeboxAudioClient.diagnostics(render.tvSession!!).isEmpty())
                    passed+="Removing electrical power stops the private browser and every linked speaker";finish(null)
                }
            }
        }catch(t: Throwable) { finish(t.cause ?: t) }
    }
}
