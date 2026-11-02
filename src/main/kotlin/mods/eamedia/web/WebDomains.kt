package mods.eamedia.web

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

/** Server-owned policy. A literal '*' opts into public websites; destination checks still apply. */
class WebDomains(entries: Iterable<String>,val plex: Set<String> = emptySet()) {
    val entries=entries.map{it.trim().lowercase(Locale.ROOT).removeSuffix(".")}.filter{it.isNotEmpty()}.toSet().also { values->
        require(values.size<=32 && values.all { it=="*" || it.length in 3..253 && it.contains('.') && it.split('.').all{label->label.isNotEmpty() && label.length<=63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() && label.all{c->c in 'a'..'z' || c in '0'..'9' || c=='-'}} }){"Invalid approved web domains"}
    }
    init { require(plex.size<=16 && plex.all { endpoint->endpoint.length<=259 && endpoint.substringAfterLast(':',"").toIntOrNull() in 1..65535 && endpoint.substringBeforeLast(':').let { h->h.isNotBlank() && h.length<=253 && h.all { it in 'a'..'z' || it in '0'..'9' || it in ".-" } } }) { "Invalid Plex endpoint" } }
    fun allowsPlex(host: String,port: Int)="${host.lowercase(Locale.ROOT).removeSuffix(".")}:$port" in plex
    fun allowsPage(uri: java.net.URI)=allows(uri.host.orEmpty()) || allowsPlex(uri.host.orEmpty(),if(uri.port>0)uri.port else if(uri.scheme=="https")443 else 80)
    fun wire(): String=com.google.gson.Gson().toJson(mapOf("domains" to entries,"plex" to plex))
    val allPublicWebsites get()="*" in entries
    fun allows(host: String): Boolean {
        val name=host.lowercase(Locale.ROOT).removeSuffix(".")
        return name.isNotEmpty() && (allPublicWebsites || entries.any{name==it || name.endsWith(".$it")})
    }
    companion object {
        val defaults=listOf("youtube.com","youtube-nocookie.com","googlevideo.com","ytimg.com","gstatic.com","youtubei.googleapis.com","consent.google.com","jnn-pa.googleapis.com","youtubeembeddedplayer.googleapis.com","yt3.ggpht.com","yt4.ggpht.com","plex.tv","plexapp.com")
        fun fromWire(value: String): WebDomains {
            val j=com.google.gson.JsonParser.parseString(value).asJsonObject
            return WebDomains(j["domains"].asJsonArray.map { it.asString },j["plex"].asJsonArray.map { it.asString }.toSet())
        }
        fun load(path: Path): WebDomains {
            if(!Files.exists(path)) {
                Files.createDirectories(path.parent)
                Files.writeString(path,"# TV website domains approved by the server owner.\n# Each entry also permits its subdomains. Restart after edits.\n# Optional: add * on its own line to permit all public HTTP/HTTPS websites.\n# Private/local addresses and non-web ports remain blocked in either mode.\n"+defaults.joinToString("\n")+"\n")
            }
            require(Files.size(path)<=16384){"Approved-domain file is too large"}
            val local=path.resolveSibling("eamedia-plex-servers.txt")
            if(!Files.exists(local))Files.writeString(local,"# Exact Plex Media Server host:port endpoints reachable from the Minecraft server.\n# Restart after edits. Example: 192.168.1.20:32400\n# Add the exact plex.direct hostname and port too if your Plex server uses one.\n")
            require(Files.size(local)<=8192) { "Plex endpoint file is too large" }
            val endpoints=Files.readAllLines(local).map { it.substringBefore('#').trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
            return WebDomains(Files.readAllLines(path).map{it.substringBefore('#').trim()},endpoints)
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
