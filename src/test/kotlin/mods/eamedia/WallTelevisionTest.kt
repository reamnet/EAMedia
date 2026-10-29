package mods.eamedia

import mods.eln.Eln
import mods.eln.api.media.ReservedMediaDevice
import mods.eln.misc.Direction
import mods.eln.misc.Obj3D
import org.junit.Test
import kotlin.test.*

class WallTelevisionTest {
    @Test fun separateSaveIdKeepsExistingTelevisionsAndJukeboxesUnchanged() {
        val factory=MediaAddon()
        assertTrue(factory.supportsWallTelevision())
        val wall=factory.descriptor(ReservedMediaDevice.WALL_TV)
        assertTrue(wall.wallMounted);assertEquals(MediaKind.TV,wall.kind)
        assertFalse(factory.descriptor(ReservedMediaDevice.TV).wallMounted)
        assertEquals((126 shl 6)+9,ReservedMediaDevice.JUKEBOX.legacyId)
        assertEquals((126 shl 6)+10,ReservedMediaDevice.TV.legacyId)
        assertEquals((126 shl 6)+11,ReservedMediaDevice.WALL_TV.legacyId)
        assertEquals(960,TelevisionStream.WIDTH);assertEquals(540,TelevisionStream.HEIGHT)
    }
    @Test fun screenFitsWallModelAndSharesTheSamePowerAndBrowserSettings() {
        val d=MediaAddon().descriptor(ReservedMediaDevice.WALL_TV)
        assertFalse(d.mustHaveFloor());assertTrue(d.mustHaveWallFrontInverse())
        assertNotNull(d.checkCanPlace(null,Direction.YP))
        val model=Obj3D().apply { assertTrue(loadFile("${d.modelName}/${d.modelName}.obj")) }
        assertNotNull(model.getPart("Base"));assertNotNull(model.getPart("Screen"))
        assertEquals(1.44,d.screenTop-d.screenBottom,1e-9)
        val screen=javaClass.getResource("/assets/eln/model/wall_browser_tv/wall_browser_tv.obj")!!.readText().substringAfter("o Screen\n").substringBefore("\no ")
        val actualFront=screen.lineSequence().filter { it.startsWith("v ") }.maxOf { it.substringAfterLast(' ').toDouble() }
        assertTrue(d.screenZ>actualFront)
        assertTrue(d.screenZ<0.0);assertTrue(d.screenTop<model.yMax)
        assertTrue(d.kind.valid(doubleArrayOf(120.0,.65)));assertTrue(d.kind.valid(doubleArrayOf(240.0,.65)))
        assertEquals(150.0,d.kind.watts)
    }
    @Test fun wallPowerTerminalsFollowTheWallAtEveryHorizontalRotation() {
        val descriptor=MediaAddon().descriptor(ReservedMediaDevice.WALL_TV)
        for(front in listOf(Direction.XN,Direction.XP,Direction.ZN,Direction.ZP)) {
            val e=MediaElement(mods.eln.node.transparent.TransparentNode(),descriptor);e.front=front
            for(side in listOf(front.left(),Direction.YN)) {
                val edge=side.getLRDUGoingTo(front.inverse)!!
                assertSame(e.input,e.getElectricalLoad(side,edge))
                for(other in mods.eln.misc.LRDU.entries.filter { it!=edge })assertNotSame(e.input,e.getElectricalLoad(side,other))
            }
        }
    }

    @Test fun recipeAndPreviewUseTheActualRegisteredItemName() {
        val registry=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Eln.findItemStack("Wall-mounted Browser Television",1).item)
        assertEquals("eln:wall-mounted_browser_television",registry.toString())
        assertNotNull(javaClass.getResource("/assets/eln/models/item/${registry.path}.json"))
        val recipe=com.google.gson.JsonParser.parseString(javaClass.getResource("/data/eln/recipe/wall_browser_tv.json")!!.readText()).asJsonObject
        assertEquals(registry.toString(),recipe.getAsJsonObject("result").get("id").asString)
    }

}
