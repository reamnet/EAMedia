package mods.eamedia.devtest

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import mods.eln.Eln
import mods.eln.client.terrain.TerrainModels
import mods.eln.misc.Direction
import mods.eln.node.transparent.TransparentNodeEntity
import mods.eamedia.*
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.*
import net.minecraft.world.level.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/** Opt-in real Chromium video/input/power campaign. Supply an HTTP test page with the documented fixture. */
@EventBusSubscriber(modid="eamedia",value=[Dist.CLIENT])
object TelevisionChecks {
    private val mc get()=Minecraft.getInstance()
    private val at=BlockPos(256,80,256)
    private var phase=0;private var ticks=0;private var total=0;private var done=false
    private var task: CompletableFuture<Unit>?=null
    private lateinit var fixtures: NativeCampaignFixtures
    private lateinit var device: MediaElement
    private var render: MediaRender?=null
    @Volatile private var text=""
    private var identity: Any?=null
    private val audioEvents=java.util.concurrent.atomic.AtomicInteger()
    private var jukebox: MediaElement?=null
    private val passed=ArrayList<String>();private val values=linkedMapOf<String,Any>()
    private val output get()=mc.gameDirectory.toPath().resolve("television-checks")
    private fun server(action: ()->Unit) { ticks=0;task=mc.singleplayerServer!!.submit<Unit>(action) }
    private fun photo(name: String) { Files.createDirectories(output);Screenshot.takeScreenshot(mc.mainRenderTarget).use { it.writeToFile(output.resolve("$name.png")) } }
    private fun setup() {
        val w=mc.singleplayerServer!!.overworld();val p=w.players().first();fixtures=NativeCampaignFixtures(w,p)
        for(x in 15..17)for(z in 15..17)w.setChunkForced(x,z,true)
        for(x in -5..5)for(z in -4..6)w.setBlockAndUpdate(at.offset(x,-1,z),Blocks.STONE.defaultBlockState())
        device=fixtures.placeMachine(Eln.findItemStack(MediaKind.TV.title,1),at) as MediaElement
        device.front=Direction.ZP;device.reconnect();fixtures.wire(at.west());fixtures.source(at.west(2),120.0)
        p.teleportTo(w,256.5,80.0,260.5,180f,0f);p.lookAt(EntityAnchorArgument.Anchor.EYES,Vec3(256.5,81.1,256.5))
        w.setDayTime(6000)
    }
    private fun state()=try { JsonParser.parseString(text.substringAfter("ELN_TV_STATE:").substringBefore(":END")).asJsonObject } catch(_: Exception) { null }
    private fun finish(error: Throwable?) {
        done=true;Files.createDirectories(output)
        Files.writeString(output.resolve("report.json"),GsonBuilder().setPrettyPrinting().create().toJson(mapOf("passed" to passed,"measurements" to values,"failure" to error?.stackTraceToString())))
        if(error==null)Eln.LOGGER.info("TELEVISION COMPLETE") else Eln.LOGGER.error("TELEVISION FAIL",error)
        mc.execute { mc.stop();kotlin.concurrent.thread { Thread.sleep(1500);kotlin.system.exitProcess(if(error==null)0 else 1) } }
    }
    @SubscribeEvent fun audio(event: net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent) {
        if(java.lang.Boolean.getBoolean("eln.televisionChecks") && event.sound.location.path=="web_jukebox_audio")audioEvents.incrementAndGet()
    }
    @SubscribeEvent fun tick(event: ClientTickEvent.Post) {
        if(!java.lang.Boolean.getBoolean("eln.televisionChecks") || done)return
        try {
            ticks++;if(ticks%200==0)Eln.LOGGER.info("TV phase={} position={} yaw={} pitch={} screen={}",phase,mc.player?.position(),mc.player?.yRot,mc.player?.xRot,mc.screen?.javaClass?.simpleName);check(++total<5000) { "TV campaign timeout phase $phase: $text" }
            render?.television?.browser?.let { if(ticks%20==0)it.getText { value->text=value } }
            when(phase) {
                0->if(mc.screen is TitleScreen && mc.overlay==null && ticks>20) {
                    mc.options.renderDistance().set(6)
                    mc.createWorldOpenFlows().createFreshLevel("television-${System.currentTimeMillis()}",LevelSettings("Television",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,GameRules(),WorldDataConfiguration.DEFAULT),WorldOptions(59059L,false,false),{ it.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },mc.screen!!)
                    phase=1;ticks=0
                }
                1->if(mc.player!=null && mc.screen==null && ticks>40) { mc.options.hideGui=true;server(::setup);phase=2 }
                2->if(task!!.isDone && ticks>60) {
                    task!!.join();render=(mc.level!!.getBlockEntity(at) as? TransparentNodeEntity)?.elementRender as? MediaRender
                    if(render==null)return
                    server { mods.eln.GuiHandler.open(fixtures.player,mods.eln.GuiHandler.nodeBaseOpen+Direction.ZP.int,fixtures.world,at.x,at.y,at.z) };phase=20
                }
                20->if(task!!.isDone && mc.screen is TelevisionScreen && ticks>20) {
                    task!!.join();val screen=mc.screen as TelevisionScreen
                    screen.children().filterIsInstance<net.minecraft.client.gui.components.EditBox>().single().value=System.getProperty("eln.tvTestUrl")
                    screen.mouseClicked(screen.width-28.0,18.0,0);screen.mouseReleased(screen.width-28.0,18.0,0)
                    ticks=0;phase=21
                }
                21->if(ticks>30) {
                    server {
                        check(device.mediaRequested && device.mediaUrl==System.getProperty("eln.tvTestUrl")) { "TV Go packet was rejected" }
                        passed+="Actual TV Go button reaches the server from a browser screen with no inventory menu"
                    };phase=22
                }
                22->if(task!!.isDone && ticks>30) {
                    task!!.join()
                    val r=render ?: return
                    mc.player!!.yRot=180f;mc.player!!.yRotO=180f;mc.player!!.xRot=7.4f;mc.player!!.xRotO=7.4f;mc.options.cameraType=net.minecraft.client.CameraType.FIRST_PERSON
                    val s=state() ?: return
                    if((s["time"]?.asDouble ?: 0.0)<1 || r.television.paintedFrames<10)return
                    check(!s["paused"].asBoolean && s["width"].asInt==320 && s["audioTracks"].asInt>0 && !s["muted"].asBoolean)
                    if(s["rms"].asDouble<=.001) { check(ticks<800) { "No decoded audio: $s" };return }
                    check(TerrainModels.hasBakedBody(r))
                    values["browserPlayback"]=s;values["paintedFrames"]=r.television.paintedFrames
                    passed+="Real Chromium decodes moving WebM video and nonzero audio samples from an HTTP stream; static cabinet is baked"
                    photo("tv-playing-world");identity=r.television.browser
                    server {
                        check(device.working && device.resistor.power in 148.0..151.0);values["powerWatts"]=device.resistor.power
                        mods.eln.GuiHandler.open(fixtures.player,mods.eln.GuiHandler.nodeBaseOpen+Direction.ZP.int,fixtures.world,at.x,at.y,at.z)
                    };phase=3
                }
                3->if(task!!.isDone && mc.screen is TelevisionScreen && ticks>30) {
                    task!!.join();photo("tv-browser-controls")
                    (mc.screen as TelevisionScreen).mouseClicked(190.0,mc.screen!!.height-18.0,0)
                    (mc.screen as TelevisionScreen).mouseReleased(190.0,mc.screen!!.height-18.0,0)
                    val screen=mc.screen as TelevisionScreen
                    val scale=kotlin.math.min((screen.width-16)/16.0,(screen.height-92)/9.0).coerceAtLeast(1.0)
                    val w=(scale*16).toInt();val h=(scale*9).toInt();val x=(screen.width-w)/2
                    fun click(px: Int,py: Int) { val sx=x+px*w/960.0;val sy=36+py*h/540.0;screen.mouseClicked(sx,sy,0);screen.mouseReleased(sx,sy,0) }
                    click(380,316);click(70,316);"TV!".forEach { screen.charTyped(it,0) }
                    ticks=0;phase=4
                }
                4->if(ticks>30) {
                    val s=state() ?: return
                    if(s["clicks"].asInt<1 || s["input"].asString!="TV!")return
                    values["interactivePage"]=s;passed+="Real browser mouse clicks and typed characters pass through the TV screen to webpage controls"
                    (mc.screen as TelevisionScreen).onClose();ticks=0;phase=5
                }
                5->if(ticks>35) {
                    check(render!!.television.browser===identity && render!!.television.paintedFrames>10)
                    photo("tv-playing-world");passed+="Closing controls leaves the same browser and video playing on the in-world TV"
                    server { check(device.settings[1]>.7) { "TV settings packet was rejected" };passed+="Actual TV volume controls update server settings without an inventory container";fixtures.voltage(at.west(2),0.0) };phase=6
                }
                6->if(task!!.isDone && ticks>55) {
                    task!!.join();check(!render!!.working && render!!.television.browser==null)
                    passed+="Loss of actual placed-cable power closes Chromium and stops playback"
                    photo("tv-power-off");server { fixtures.voltage(at.west(2),120.0) };phase=7
                }
                7->if(task!!.isDone && ticks>55 && render!!.television.paintedFrames>10) {
                    task!!.join();check(render!!.working && render!!.television.browser!=null)
                    passed+="Restoring electrical power recreates the browser and resumes the selected page"
                    server { fixtures.player.teleportTo(fixtures.world,256.5,80.0,300.5,180f,0f) };phase=8
                }
                8->if(task!!.isDone && ticks>35) {
                    task!!.join();check(render!!.television.browser==null)
                    passed+="Leaving the 32-block viewing range releases the browser and its texture/audio resources"
                    render!!.clientSendString(86,"https://example.org/rejected-distant-control")
                    ticks=0;phase=80
                }
                80->if(ticks>20) {
                    server {
                        check(!device.mediaUrl.contains("rejected-distant-control"));passed+="Server rejects media control packets from outside the eight-block interaction range"
                        val p=at.east(5);jukebox=fixtures.placeMachine(Eln.findItemStack(MediaKind.JUKEBOX.title,1),p) as MediaElement
                        jukebox!!.front=Direction.ZP;jukebox!!.reconnect();fixtures.wire(p.west());fixtures.source(p.west(2),120.0)
                        fixtures.player.teleportTo(fixtures.world,p.x+.5,80.0,p.z+4.5,180f,0f)
                    };phase=29
                }
                29->if(task!!.isDone && ticks>40 && (mc.level!!.getBlockEntity(at.east(5)) as? TransparentNodeEntity)?.elementRender is MediaRender) {
                    task!!.join()
                    server { val p=at.east(5);mods.eln.GuiHandler.open(fixtures.player,mods.eln.GuiHandler.nodeBaseOpen+Direction.ZP.int,fixtures.world,p.x,p.y,p.z) };phase=30
                }
                30->if(task!!.isDone && mc.screen is JukeboxScreen && ticks>35) {
                    task!!.join();val screen=mc.screen as JukeboxScreen
                    screen.children().filterIsInstance<net.minecraft.client.gui.components.EditBox>().single().value="https://raw.githubusercontent.com/reamnet/ELN1.21.1ForkPatch/main/src/main/resources/assets/eln/sounds/click.ogg"
                    screen.mouseClicked((screen.width-304)/2+75.0,(screen.height-174)/2+68.0,0)
                    screen.mouseReleased((screen.width-304)/2+75.0,(screen.height-174)/2+68.0,0)
                    ticks=0;phase=31
                }
                31->if(ticks>35) {
                    check(ticks<600) { "Jukebox stream did not start" }
                    if(audioEvents.get()==0)return
                    server { check(jukebox!!.mediaRequested && jukebox!!.working);passed+="Actual jukebox Play button opens a positional Minecraft stream from HTTPS audio in the addon" };phase=32
                }
                32->if(task!!.isDone && ticks>10) { task!!.join();photo("jukebox-controls");values["jukeboxStreams"]=audioEvents.get();finish(null) }

            }
        } catch(e: Throwable) { finish(e) }
    }
}
