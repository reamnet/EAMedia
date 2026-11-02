package mods.eamedia

import mods.eln.api.media.MediaAddonProvider
import mods.eln.api.media.ReservedMediaDevice

/** Called by ELN while reserving the historical save IDs; no browser initialization here. */
class MediaAddon: MediaAddonProvider {
    fun supportsWallTelevision()=true
    fun supportsSpeakers()=true
    override fun descriptor(device: ReservedMediaDevice)=if(device==ReservedMediaDevice.FLOOR_SPEAKER || device==ReservedMediaDevice.CEILING_SPEAKER)SpeakerDescriptor(device==ReservedMediaDevice.CEILING_SPEAKER) else if(device==ReservedMediaDevice.WALL_TV)MediaDescriptor(MediaKind.TV,wallMounted=true) else MediaDescriptor(MediaKind.entries.single { it.reserved==device })
}
