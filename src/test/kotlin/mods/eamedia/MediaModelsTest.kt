package mods.eamedia

import mods.eln.misc.Obj3DFolder
import org.junit.Test
import kotlin.test.*

class MediaModelsTest {
    @Test fun addonLoadsBothCabinetsWithoutRegisteringThemInTheCoreModelFolder() {
        for(kind in MediaKind.entries) {
            // The core scanner does not discover addon assets. Its missing-model fallback
            // must never become the model used by a media descriptor.
            assertNull(Obj3DFolder().getObj(kind.model).getPart("Case"))
            val descriptor=MediaDescriptor(kind)
            for(part in listOf("Base","Case","Trim","Screen","Input","Output","Indicator")) {
                assertNotNull(descriptor.model.getPart(part),"${kind.name} missing $part")
            }
            assertSame(descriptor.model,descriptor.model)
        }
    }
}
