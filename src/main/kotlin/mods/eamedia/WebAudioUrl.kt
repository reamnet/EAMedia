package mods.eamedia

import java.net.URI

/** Shared validation does not resolve DNS or contact a media server on the game/server thread. */
object WebAudioUrl {
    fun parse(value: String): URI?=try {
        val url=URI(value.trim())
        url.takeIf { value.length<=2048 && it.scheme.equals("https",true) && !it.host.isNullOrBlank() && it.rawUserInfo==null && it.fragment==null && value.none(Char::isISOControl) }
    } catch(_: Exception) { null }
    fun youtube(url: URI)=url.host.lowercase(java.util.Locale.ROOT) in setOf("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtu.be","www.youtu.be")
}
