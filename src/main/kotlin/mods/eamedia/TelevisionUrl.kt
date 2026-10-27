package mods.eamedia

import java.net.URI

/** Browser addresses are data shared by the server; DNS and browsing happen only on clients. */
object TelevisionUrl {
    fun parse(value: String): URI?=try {
        val text=value.trim()
        val uri=URI(text)
        uri.takeIf { text.length in 1..2048 && value.none(Char::isISOControl) &&
            it.scheme.lowercase(java.util.Locale.ROOT) in setOf("http","https") &&
            !it.host.isNullOrBlank() && it.rawUserInfo==null }
    } catch(_: Exception) { null }
}
