package mods.eamedia.web

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

/** Server-owned policy. A literal '*' opts into public websites; destination checks still apply. */
class WebDomains(entries: Iterable<String>) {
    val entries=entries.map{it.trim().lowercase(Locale.ROOT).removeSuffix(".")}.filter{it.isNotEmpty()}.toSet().also { values->
        require(values.size<=32 && values.all { it=="*" || it.length in 3..253 && it.contains('.') && it.split('.').all{label->label.isNotEmpty() && label.length<=63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() && label.all{c->c in 'a'..'z' || c in '0'..'9' || c=='-'}} }){"Invalid approved web domains"}
    }
    val allPublicWebsites get()="*" in entries
    fun allows(host: String): Boolean {
        val name=host.lowercase(Locale.ROOT).removeSuffix(".")
        return name.isNotEmpty() && (allPublicWebsites || entries.any{name==it || name.endsWith(".$it")})
    }
    companion object {
        val defaults=listOf("youtube.com","youtube-nocookie.com","googlevideo.com","ytimg.com","gstatic.com","youtubei.googleapis.com","consent.google.com","jnn-pa.googleapis.com","youtubeembeddedplayer.googleapis.com","yt3.ggpht.com","yt4.ggpht.com")
        fun load(path: Path): WebDomains {
            if(!Files.exists(path)) {
                Files.createDirectories(path.parent)
                Files.writeString(path,"# TV website domains approved by the server owner.\n# Each entry also permits its subdomains. Restart after edits.\n# Optional: add * on its own line to permit all public HTTP/HTTPS websites.\n# Private/local addresses and non-web ports remain blocked in either mode.\n"+defaults.joinToString("\n")+"\n")
            }
            require(Files.size(path)<=16384){"Approved-domain file is too large"}
            return WebDomains(Files.readAllLines(path).map{it.substringBefore('#').trim()})
        }
    }
}

/** Permission exists only while this connection hosts a loaded, powered TV.
 * A viewer/control lease alone cannot create a generic proxy permission. */
internal class WebHostGrants(private val clock: ()->Long=System::nanoTime) {
    private data class Grant(val owner: Any,val until: Long)
    private val grants=HashMap<UUID,Grant>()
    @Synchronized fun touch(session: UUID,owner: Any): Any? {
        val previous=grants.put(session,Grant(owner,clock()+15_000_000_000L))
        return previous?.owner?.takeIf{it!==owner}
    }
    @Synchronized fun allows(owner: Any)=grants.values.any{it.owner===owner && clock()<it.until}
    @Synchronized fun revoke(session: UUID): Any?=grants.remove(session)?.owner
    @Synchronized fun logout(owner: Any){grants.entries.removeIf{it.value.owner===owner}}
    @Synchronized fun purge(){val now=clock();grants.entries.removeIf{it.value.until<=now}}
    @Synchronized fun clear(){grants.clear()}
}
