package mods.eamedia

import mods.eln.Eln
import mods.eln.api.media.*
import mods.eln.misc.Coordinate
import mods.eln.node.transparent.TransparentNode
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import org.junit.Test
import kotlin.test.*

class AddonMigrationTest {
    @Test fun addonActivatesExistingNamesAndSaveIdsWithoutChangingTheCoreDevices() {
        assertTrue(MediaAddons.available)
        for(kind in MediaKind.entries) {
            val descriptor=Eln.transparentNodeItem.subItemList[kind.legacyId] as MediaDescriptor
            assertEquals(kind,descriptor.kind);assertFalse(descriptor.isHidden())
            val expected=if(kind==MediaKind.TV)"eln:powered_browser_television" else "eln:powered_web_jukebox"
            assertEquals(expected,BuiltInRegistries.ITEM.getKey(descriptor.newItemStack(1).item).toString())
        }
        assertEquals(9,mods.eln.transparentnode.industrial.IndustrialKind.entries.size)
    }
    @Test fun oldSettingsSurviveDisablingAndReinstallingTheAddon() {
        val original=CompoundTag().apply {
            putDouble("industrialSetting0",240.0);putDouble("industrialSetting1",.4);putBoolean("industrialEnabled",true)
            putString("mediaUrl","https://example.org/watch#saved");putBoolean("mediaRequested",true);putInt("mediaRevision",23)
            putDouble("industrialLifetime",987654.0);putString("unknownData","retained")
        }
        val hidden=MissingMediaElement(TransparentNode(),MissingMediaDescriptor(ReservedMediaDevice.TV));hidden.readFromNBT(original)
        val saved=CompoundTag().also(hidden::writeToNBT)
        val descriptor=Eln.transparentNodeItem.subItemList[MediaKind.TV.legacyId] as MediaDescriptor
        val restored=MediaElement(TransparentNode().apply { coordinate=Coordinate(0,80,0,0) },descriptor);restored.readFromNBT(saved)
        assertEquals(240.0,restored.nominal);assertEquals(.4,restored.settings[1]);assertTrue(restored.mediaRequested)
        assertEquals("https://example.org/watch#saved",restored.mediaUrl);assertEquals(23,restored.mediaRevision);assertEquals(987654.0,restored.lifetimeJoules)
        val again=CompoundTag().also(restored::writeToNBT);assertEquals("retained",again.getString("unknownData"))
    }
}
