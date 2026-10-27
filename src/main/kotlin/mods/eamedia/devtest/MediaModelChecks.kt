package mods.eamedia.devtest

import com.google.gson.GsonBuilder
import com.mojang.blaze3d.vertex.PoseStack
import mods.eamedia.MediaKind
import mods.eamedia.MediaRender
import mods.eln.Eln
import mods.eln.client.terrain.TerrainModels
import mods.eln.misc.Direction
import mods.eln.node.transparent.TransparentNodeEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.util.RandomSource
import net.minecraft.world.*
import net.minecraft.world.item.ItemDisplayContext
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

/** Run from a packaged addon jar: a registered but empty terrain body is not a passing model. */
@EventBusSubscriber(modid="eamedia",value=[Dist.CLIENT])
object MediaModelChecks {
    private val mc get()=Minecraft.getInstance()
    private val at=BlockPos(256,80,256)
    private var phase=0;private var ticks=0;private var total=0;private var done=false
    private var task: CompletableFuture<Unit>?=null
    private var reload: CompletableFuture<Void>?=null
    private val passed=ArrayList<String>();private val values=linkedMapOf<String,Any>()
    private val output get()=mc.gameDirectory.toPath().resolve("media-model-checks")
    private fun photo(name: String) { Files.createDirectories(output);Screenshot.takeScreenshot(mc.mainRenderTarget).use { it.writeToFile(output.resolve("$name.png")) } }
    private fun setup() {
        val w=mc.singleplayerServer!!.overworld();val p=w.players().first();val fixtures=NativeCampaignFixtures(w,p)
        for(x in 15..17)for(z in 15..17)w.setChunkForced(x,z,true)
        for(x in -4..8)for(z in -3..7)w.setBlockAndUpdate(at.offset(x,-1,z),Blocks.STONE.defaultBlockState())
        for((i,kind) in MediaKind.entries.withIndex()) {
            val node=fixtures.placeMachine(Eln.findItemStack(kind.title,1),at.east(i*4));node.front=Direction.ZP;node.reconnect()
            p.inventory.setItem(i,Eln.findItemStack(kind.title,1))
        }
        p.teleportTo(w,258.5,80.0,263.5,180f,6f);w.setDayTime(6000)
    }
    private fun bodies(label: String) {
        // The addon intentionally does not depend on core's internal TerrainMesh Kotlin types.
        // Inspect the actual compiled mesh in this opt-in test, rather than accepting an empty registration.
        val getMesh=TerrainModels.javaClass.methods.single { it.name.startsWith("meshFor$") }
        val counts=linkedMapOf<String,Int>()
        for((i,kind) in MediaKind.entries.withIndex()) {
            val render=(mc.level!!.getBlockEntity(at.east(i*4)) as TransparentNodeEntity).elementRender as MediaRender
            check(TerrainModels.hasBakedBody(render))
            val mesh=checkNotNull(getMesh.invoke(TerrainModels,render))
            val batches=mesh.javaClass.getMethod("getBatches").invoke(mesh) as List<*>
            val vertices=batches.sumOf { (it!!.javaClass.getMethod("getVertices").invoke(it) as IntArray).size/9 }
            check(vertices>100) { "Empty/incomplete ${kind.name} cabinet: $vertices baked vertices" }
            counts[kind.name]=vertices
        }
        values[label]=counts;passed+="$label: both placed cabinets contain substantial baked geometry"
    }
    private fun items(label: String) {
        val counts=linkedMapOf<String,Int>()
        for(kind in MediaKind.entries)for(context in listOf(ItemDisplayContext.GUI,ItemDisplayContext.GROUND,ItemDisplayContext.FIXED,ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)) {
            val stack=Eln.findItemStack(kind.title,1)
            val source=mc.itemRenderer.itemModelShaper.getItemModel(stack)
            val model=source.applyTransform(context,PoseStack(),false)
            val quads=model.getRenderPasses(stack,false).sumOf { it.getQuads(null,null,RandomSource.create(0)).size }
            check(!model.isCustomRenderer && quads>20) { "Invisible/unbaked ${kind.name} $context preview: $quads quads" }
            counts["${kind.name}/$context"]=quads
        }
        values[label]=counts;passed+="$label: both baked item previews contain geometry in five display contexts"
    }
    private fun finish(error: Throwable?) {
        done=true;Files.createDirectories(output)
        Files.writeString(output.resolve("report.json"),GsonBuilder().setPrettyPrinting().create().toJson(mapOf("passed" to passed,"measurements" to values,"failure" to error?.stackTraceToString())))
        if(error==null)Eln.LOGGER.info("MEDIA_MODELS COMPLETE") else Eln.LOGGER.error("MEDIA_MODELS FAIL",error)
        mc.execute { mc.stop();kotlin.concurrent.thread { Thread.sleep(1500);kotlin.system.exitProcess(if(error==null)0 else 1) } }
    }
    @SubscribeEvent fun tick(event: ClientTickEvent.Post) {
        if(!java.lang.Boolean.getBoolean("eln.mediaModelChecks") || done)return
        try {
            ticks++;check(++total<4000) { "Media model campaign timeout at $phase" }
            when(phase) {
                0->if(mc.screen is TitleScreen && mc.overlay==null && ticks>20) {
                    mc.options.renderDistance().set(5)
                    mc.createWorldOpenFlows().createFreshLevel("media-models-${System.currentTimeMillis()}",LevelSettings("Media models",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,GameRules(),WorldDataConfiguration.DEFAULT),WorldOptions(60101L,false,false),{ it.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },mc.screen!!)
                    phase=1;ticks=0
                }
                1->if(mc.player!=null && mc.screen==null && ticks>40) { task=mc.singleplayerServer!!.submit<Unit>(::setup);phase=2;ticks=0 }
                2->if(task!!.isDone && ticks>80) {
                    task!!.join()
                    check(MediaModelChecks::class.java.protectionDomain.codeSource.location.toString().contains(".jar")) { "Campaign must run from the packaged addon jar" }
                    check(MediaKind.entries.none { it.model in Eln.obj.objectList }) { "Core model discovery unexpectedly included addon assets" }
                    passed+="Packaged addon owns both cabinet models independently of the core model registry"
                    bodies("placed");items("previews")
                    mc.options.hideGui=true
                    mc.options.cameraType=net.minecraft.client.CameraType.FIRST_PERSON
                    mc.player!!.yRot=180f;mc.player!!.yRotO=180f;mc.player!!.xRot=6f;mc.player!!.xRotO=6f
                    mc.gui.chat.clearMessages(false)
                    phase=21;ticks=0
                }
                21->if(ticks>220) {
                    val camera=mc.gameRenderer.mainCamera
                    values["view"]=mapOf("player" to mc.player!!.position().toString(),"camera" to camera.position.toString(),"yaw" to camera.yRot,"pitch" to camera.xRot)
                    photo("media-cabinets-world");reload=mc.reloadResourcePacks();phase=3;ticks=0
                }
                3->if(reload!!.isDone && mc.overlay==null && ticks>60) {
                    reload!!.join();bodies("afterReload");items("previewsAfterReload")
                    mc.options.hideGui=false;mc.setScreen(InventoryScreen(mc.player!!));phase=4;ticks=0
                }
                4->if(ticks>10) { photo("media-inventory");finish(null) }
            }
        } catch(e: Throwable) { finish(e) }
    }
}
