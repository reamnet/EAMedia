package mods.eamedia

import mods.eln.api.media.MediaAddonProvider
import mods.eln.api.media.ReservedMediaDevice

/** Called by ELN while reserving the historical save IDs; no browser initialization here. */
class MediaAddon: MediaAddonProvider {
    override fun descriptor(device: ReservedMediaDevice)=MediaDescriptor(MediaKind.entries.single { it.reserved==device })
}
