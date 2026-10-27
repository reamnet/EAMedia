package mods.eamedia

import mods.eln.api.media.ReservedMediaDevice
import mods.eln.i18n.I18N

enum class MediaKind(val title: String,val reserved: ReservedMediaDevice,val watts: Double) {
    JUKEBOX(I18N.TR_NAME(I18N.Type.NONE,"Powered Web Jukebox"),ReservedMediaDevice.JUKEBOX,25.0),
    TV(I18N.TR_NAME(I18N.Type.NONE,"Powered Browser Television"),ReservedMediaDevice.TV,150.0);
    val defaults get()=doubleArrayOf(120.0,.65)
    val legacyId get()=reserved.legacyId
    val model get()=if(this==TV)"industrial_tv" else "industrial_jukebox"
    fun valid(p: DoubleArray)=p.size==2 && p.all { it.isFinite() } && p[0] in listOf(120.0,240.0) && p[1] in 0.0..1.0
    fun address(value: String)=if(this==TV)TelevisionUrl.parse(value) else WebAudioUrl.parse(value)
}
