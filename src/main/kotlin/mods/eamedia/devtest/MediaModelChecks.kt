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
    private val mediaNames=listOf(MediaKind.JUKEBOX.title,MediaKind.TV.title,"Wall-mounted Browser Television")
    private val outdoorNames=listOf("Shoebox Parking Lot Light","Twin Wide Area Parking Lot Light","Three Arm Parking Lot Light","Gumdrop Sidewalk Light","Dual Gumdrop Sidewalk Light")
    private val cabinetNames=listOf("Four Cell Plug-in UPS","Programmable UPS","Automatic Transfer Switch","Wall-mounted Programmable UPS","Wall-mounted Automatic Transfer Switch")
    private var phase=0;private var ticks=0;private var total=0;private var done=false
    private var task: CompletableFuture<Unit>?=null
    private var reload: CompletableFuture<Void>?=null
    private val passed=ArrayList<String>();private val values=linkedMapOf<String,Any>()
    private val output get()=mc.gameDirectory.toPath().resolve("media-model-checks")
    private fun photo(name: String) { Files.createDirectories(output);Screenshot.takeScreenshot(mc.mainRenderTarget).use { it.writeToFile(output.resolve("$name.png")) } }
    private fun setup() {
        val w=mc.singleplayerServer!!.overworld();val p=w.players().first();val fixtures=NativeCampaignFixtures(w,p)
        for(x in 15..17)for(z in 15..17)w.setChunkForced(x,z,true)
        for(x in -12..20)for(z in -12..12)w.setBlockAndUpdate(at.offset(x,-1,z),Blocks.STONE.defaultBlockState())
        for((i,name) in mediaNames.withIndex()) {
            val pos=at.east(i*4)
            if(i==2) { for(x in -1..1)for(y in 0..1)w.setBlockAndUpdate(pos.offset(x,y,-1),Blocks.STONE_BRICKS.defaultBlockState())
                check(Eln.transparentNodeItem.placeBlockAt(Eln.findItemStack(name,1),p,w,pos,net.minecraft.core.Direction.SOUTH))
            } else { val node=fixtures.placeMachine(Eln.findItemStack(name,1),pos);node.front=Direction.ZP;node.reconnect() }
            p.inventory.setItem(i,Eln.findItemStack(name,1))
        }
        for((i,name) in outdoorNames.withIndex()) {
            val pos=at.offset(-8+i*5,0,-7);val stack=Eln.findItemStack(name,1);p.setItemInHand(InteractionHand.MAIN_HAND,stack)
            Eln.sixNodeItem.onItemUse(stack,p,w,pos,InteractionHand.MAIN_HAND,net.minecraft.core.Direction.UP,.5f,1f,.5f)
        }
        for((i,name) in cabinetNames.withIndex()) {
            val pos=at.offset(-4+i*2,0,4)
            if(i>=3) { w.setBlockAndUpdate(pos.north(),Blocks.STONE_BRICKS.defaultBlockState());check(Eln.transparentNodeItem.placeBlockAt(Eln.findItemStack(name,1),p,w,pos,net.minecraft.core.Direction.SOUTH)) }
            else { val e=fixtures.placeMachine(Eln.findItemStack(name,1),pos);e.front=Direction.ZP;e.reconnect()
                if(e is mods.eln.transparentnode.ups.UpsElement)repeat(e.inventory.containerSize) { slot ->
                    val cell=Eln.findItemStack("UPS Industrial Battery Module",1);mods.eln.transparentnode.ups.upsBattery(cell)!!.let { it.setEnergy(cell,it.getEnergyMax(cell)*.5) };e.inventory.setItem(slot,cell)
                }
            }
        }
        val source=(mods.eln.node.NodeManager.instance!!.getNodeFromCoordonate(mods.eln.misc.Coordinate(at.x-4,at.y,at.z+4,w)) as mods.eln.node.transparent.TransparentNode).element as mods.eln.transparentnode.ups.CompactUpsElement
        for(i in 0..3) {
            val pos=at.offset(-7+i*2,0,8);val stack=Eln.findItemStack("Basic Lamp Holder",1);p.setItemInHand(InteractionHand.MAIN_HAND,stack)
            Eln.sixNodeItem.onItemUse(stack,p,w,pos,InteractionHand.MAIN_HAND,net.minecraft.core.Direction.UP,.5f,1f,.5f)
            val lamp=(mods.eln.node.NodeManager.instance!!.getNodeFromCoordonate(mods.eln.misc.Coordinate(pos.x,pos.y,pos.z,w)) as mods.eln.node.six.SixNode).sideElementList.filterIsInstance<mods.eln.sixnode.lampsocket.LampSocketElement>().single()
            lamp.inventory.setItem(0,Eln.findItemStack("120V LED Light Bulb",1));lamp.inventory.setChanged()
            check(lamp.cord.attach(source.anchor(i),false)) { "Cannot attach UPS socket $i" }
        }
        p.teleportTo(w,258.5,80.0,263.5,180f,6f);w.setDayTime(6000)
    }
    private fun bodies(label: String) {
        // The addon intentionally does not depend on core's internal TerrainMesh Kotlin types.
        // Inspect the actual compiled mesh in this opt-in test, rather than accepting an empty registration.
        val getMesh=TerrainModels.javaClass.methods.single { it.name.startsWith("meshFor$") }
        val counts=linkedMapOf<String,Int>()
        for((i,name) in mediaNames.withIndex()) {
            val render=(mc.level!!.getBlockEntity(at.east(i*4)) as TransparentNodeEntity).elementRender as MediaRender
            check(TerrainModels.hasBakedBody(render))
            val mesh=checkNotNull(getMesh.invoke(TerrainModels,render))
            val batches=mesh.javaClass.getMethod("getBatches").invoke(mesh) as List<*>
            val vertices=batches.sumOf { (it!!.javaClass.getMethod("getVertices").invoke(it) as IntArray).size/9 }
            check(vertices>100) { "Empty/incomplete ${name} cabinet: $vertices baked vertices" }
            counts[name]=vertices
        }
        for((i,name) in cabinetNames.withIndex()) {
            val tile=mc.level!!.getBlockEntity(at.offset(-4+i*2,0,4)) as TransparentNodeEntity
            check(TerrainModels.hasBakedBody(tile.elementRender!!)) { "Unbaked $name" }
        }
        for((i,name) in outdoorNames.withIndex()) {
            val tile=mc.level!!.getBlockEntity(at.offset(-8+i*5,0,-7)) as mods.eln.node.six.SixNodeEntity
            check(tile.elementRenderList.filterNotNull().any { TerrainModels.hasBakedBody(it) }) { "Unbaked $name" }
        }
        values[label]=counts;passed+="$label: all thirteen placed models have terrain bodies; three media cabinets have substantial geometry"
    }
    private fun items(label: String) {
        val counts=linkedMapOf<String,Int>()
        for(name in mediaNames+outdoorNames+cabinetNames)for(context in listOf(ItemDisplayContext.GUI,ItemDisplayContext.GROUND,ItemDisplayContext.FIXED,ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)) {
            val stack=Eln.findItemStack(name,1)
            val source=mc.itemRenderer.itemModelShaper.getItemModel(stack)
            val model=source.applyTransform(context,PoseStack(),false)
            val quads=model.getRenderPasses(stack,false).sumOf { it.getQuads(null,null,RandomSource.create(0)).size }
            check(!model.isCustomRenderer && quads>20) { "Invisible/unbaked ${name} $context preview: $quads quads" }
            counts["${name}/$context"]=quads
        }
        values[label]=counts;passed+="$label: all thirteen baked item previews contain geometry in five display contexts"
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
                    passed+="Packaged addon owns all media cabinet models independently of the core model registry"
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
                4->if(ticks>10) { photo("media-inventory");mc.setScreen(null);mc.options.hideGui=true
                    task=mc.singleplayerServer!!.submit<Unit> { val w=mc.singleplayerServer!!.overworld();w.players().first().teleportTo(w,257.5,80.0,270.5,180f,-4f) };phase=5;ticks=0 }
                5->if(task!!.isDone && ticks>60) { task!!.join();photo("outdoor-lights-and-panels")
                    task=mc.singleplayerServer!!.submit<Unit> { val w=mc.singleplayerServer!!.overworld();w.players().first().teleportTo(w,252.5,80.0,263.0,180f,20f) };phase=7;ticks=0 }
                7->if(task!!.isDone && ticks>60) { task!!.join();photo("compact-ups-close-up")
                    task=mc.singleplayerServer!!.submit<Unit> {
                        val w=mc.singleplayerServer!!.overworld()
                        val source=(mods.eln.node.NodeManager.instance!!.getNodeFromCoordonate(mods.eln.misc.Coordinate(at.x-4,at.y,at.z+4,w)) as mods.eln.node.transparent.TransparentNode).element as mods.eln.transparentnode.ups.CompactUpsElement
                        check(source.freeSocket()==null);check(source.unit.secondary.voltage>119);check(source.unit.outputWatts>1)
                        val powers=(0..3).map { i ->
                            val pos=at.offset(-7+i*2,0,8)
                            val lamp=(mods.eln.node.NodeManager.instance!!.getNodeFromCoordonate(mods.eln.misc.Coordinate(pos.x,pos.y,pos.z,w)) as mods.eln.node.six.SixNode).sideElementList.filterIsInstance<mods.eln.sixnode.lampsocket.LampSocketElement>().single()
                            check(lamp.cord.connected);check(lamp.electricalLoad.voltage>118);lamp.electricalLoad.voltage
                        }
                        values["fourPluggedLampsVolts"]=powers
                        source.disconnectSocket(1,w.players().first());check(source.freeSocket()==1)
                        passed+="Four actual lamp cords draw battery power from four UPS sockets; disconnecting one releases only its socket"
                        val player=w.players().first();val outletPos=at.offset(-4,0,2)
                        w.setBlockAndUpdate(outletPos.north(),Blocks.STONE_BRICKS.defaultBlockState())
                        check(Eln.transparentNodeItem.placeBlockAt(Eln.findItemStack("Experimental Wall Outlet",1),player,w,outletPos,net.minecraft.core.Direction.SOUTH))
                        val outlet=(mods.eln.node.NodeManager.instance!!.getNodeFromCoordonate(mods.eln.misc.Coordinate(outletPos.x,outletPos.y,outletPos.z,w)) as mods.eln.node.transparent.TransparentNode).element as mods.eln.transparentnode.cord.OutletElement
                        check(source.cord.attach(outlet.anchor(0),false));player.setItemInHand(InteractionHand.MAIN_HAND,net.minecraft.world.item.ItemStack.EMPTY)
                        check(source.cord.carry(player))
                        val carried=player.mainHandItem
                        check(Eln.transparentNodeItem.placeBlockAt(carried,player,w,at.offset(-4,0,4),net.minecraft.core.Direction.UP))
                        val restored=(mods.eln.node.NodeManager.instance!!.getNodeFromCoordonate(mods.eln.misc.Coordinate(at.x-4,at.y,at.z+4,w)) as mods.eln.node.transparent.TransparentNode).element as mods.eln.transparentnode.ups.CompactUpsElement
                        check(restored.freeSocket()==0) { "Carried UPS has stale socket reservations" };check(restored.bank.activeCount==4) { "Carried UPS lost battery cells" };check(restored.cord.plugged) { "Carried UPS lost its input cord" }
                        passed+="Picking up and replacing the plugged UPS retains its four cells and input cord without leaving orphaned output reservations"

                    };phase=6;ticks=0 }
                6->if(task!!.isDone) { task!!.join();finish(null) }
            }
        } catch(e: Throwable) { finish(e) }
    }
}
