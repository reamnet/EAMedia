package mods.eamedia

import com.cinemamod.mcef.MCEF
import com.cinemamod.mcef.MCEFPlatform
import com.cinemamod.mcef.internal.MCEFDownloadListener
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mods.eln.Eln
import mods.eln.i18n.I18N.tr
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream

/** The native browser comes exclusively from the addon jar. No installer network requests. */
class BundledChromium(private val cache: Path,private val serverTar: Boolean=false,private val resource: (String)->InputStream? = { BundledChromium::class.java.getResourceAsStream("/assets/eamedia/chromium/$it") }) {
    private val manifest by lazy { checkNotNull(resource("manifest.json")) { "Bundled Chromium manifest missing" }.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }.also { require(it["schema"].asInt==1) } }
    internal val revision get()=manifest["jcefCommit"].asString
    fun install(platform: String): Path=synchronized(lock) {
        require(platform.matches(Regex("[a-z0-9_]+")))
        val entry=manifest.getAsJsonObject("platforms").getAsJsonObject(platform) ?: throw IOException("No compatible bundled Chromium runtime for $platform")
        val target=cache.resolve(platform)
        if(valid(target,entry))return@synchronized target
        Files.createDirectories(cache)
        val stage=Files.createTempDirectory(cache,"eamedia-native-")
        val archive=stage.resolve("runtime.tar.gz")
        val unpack=stage.resolve("unpack");Files.createDirectories(unpack)
        var backup: Path?=null
        try {
            assemble(archive,entry)
            extract(archive,unpack,platform,entry["unpackedBytes"].asLong)
            val selected=unpack.resolve(platform)
            if(!valid(selected,entry))throw IOException("Bundled Chromium extracted-file verification failed")
            if(Files.exists(target)) {
                backup=cache.resolve(".eamedia-old-$platform-${UUID.randomUUID()}");move(target,backup!!)
            }
            try { move(selected,target) } catch(e: Exception) { backup?.let { move(it,target);backup=null };throw e }
            backup?.let(::deleteTree);backup=null
            target
        } finally { deleteTree(stage) }
    }
    private fun assemble(output: Path,entry: JsonObject) {
        val hash=MessageDigest.getInstance("SHA-256");var total=0L
        val expected=entry["archiveBytes"].asLong;require(expected in 1..MAX_ARCHIVE)
        Files.newOutputStream(output).use { out->
            for(value in entry.getAsJsonArray("parts")) {
                val part=value.asJsonObject;val name=part["resource"].asString
                require(name.matches(Regex("[a-z0-9_.-]+")))
                val digest=MessageDigest.getInstance("SHA-256");var count=0L
                val input=resource(name) ?: throw IOException("Bundled Chromium archive part missing")
                input.use {
                    val buffer=ByteArray(65536)
                    while(true) {
                        if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Chromium extraction cancelled")
                        val n=it.read(buffer);if(n<0)break
                        count+=n;total+=n
                        if(count>part["bytes"].asLong || total>expected)throw IOException("Chromium archive exceeds expected size")
                        digest.update(buffer,0,n);hash.update(buffer,0,n);out.write(buffer,0,n)
                    }
                }
                if(count!=part["bytes"].asLong || digest.digest().hex()!=part["sha256"].asString)throw IOException("Bundled Chromium archive-part checksum mismatch")
            }
        }
        if(total!=expected || hash.digest().hex()!=entry["archiveSha256"].asString)throw IOException("Bundled Chromium archive checksum mismatch")
    }
    private fun child(root: Path,name: String): Path {
        if(name.contains(':') || name.contains('\\') || name.startsWith('/'))throw IOException("Invalid Chromium archive path")
        val path=root.resolve(name).normalize()
        if(!path.startsWith(root) || name.split('/').any { it==".." })throw IOException("Chromium archive path escapes extraction directory")
        return path
    }
    private data class Link(val path: Path,val target: String,val hard: Boolean)
    private fun extract(archive: Path,root: Path,platform: String,maxBytes: Long) {
        require(maxBytes in 1..MAX_UNPACKED)
        if(serverTar) {
            // Dedicated Minecraft lacks the client's Commons Compress library. This archive
            // has already passed the pinned manifest's complete SHA-256/size verification.
            // Use the OS tar in the staging directory; install() still verifies every native
            // file and resolved link before committing anything to the runtime directory.
            val log=root.resolve("extract.log")
            val process=ProcessBuilder("tar","-xzf",archive.toString(),"-C",root.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                if(!process.waitFor(120,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw IOException("Server Chromium extraction timed out")}
                if(process.exitValue()!=0)throw IOException("Server Chromium extraction failed: "+Files.readString(log).take(2048))
            }finally{if(process.isAlive)process.destroyForcibly()}
            return
        }
        val selected=root.resolve(platform);val links=ArrayList<Link>();var total=0L
        TarArchiveInputStream(GZIPInputStream(Files.newInputStream(archive))).use { tar->
            while(true) {
                val entry=tar.nextTarEntry ?: break
                val path=child(root,entry.name.removePrefix("./"))
                if(!path.startsWith(selected))throw IOException("Chromium archive has an unexpected platform directory")
                when {
                    entry.isDirectory->Files.createDirectories(path)
                    entry.isSymbolicLink || entry.isLink->links.add(Link(path,entry.linkName,entry.isLink))
                    entry.isFile->{
                        // GNU sparse entries report packed bytes as size, but read() expands their holes.
                        val fileBytes=entry.realSize
                        if(fileBytes<0 || fileBytes>maxBytes-total)throw IOException("Chromium archive exceeds expected unpacked size")
                        Files.createDirectories(path.parent)
                        Files.newOutputStream(path,StandardOpenOption.CREATE_NEW).use { out->
                            val buffer=ByteArray(65536);var count=0L
                            while(true) { val n=tar.read(buffer);if(n<0)break;count+=n;if(count>fileBytes)throw IOException("Chromium file ${entry.name} exceeds $fileBytes bytes (read $count)");out.write(buffer,0,n) }
                            if(count!=fileBytes)throw IOException("Truncated Chromium archive file");total+=count
                        }
                        if(entry.mode and 73!=0 && !System.getProperty("os.name").startsWith("Windows",true))path.toFile().setExecutable(true,true)
                    }
                    else->throw IOException("Unsupported Chromium archive entry")
                }
            }
        }
        if(total!=maxBytes)throw IOException("Chromium unpacked size mismatch")
        // File writes finish before creating links, so archive links cannot redirect extraction.
        for(link in links.filter { !it.hard }) {
            val target=link.path.parent.resolve(link.target).normalize()
            if(!target.startsWith(selected) || Path.of(link.target).isAbsolute)throw IOException("Chromium symlink escapes its runtime")
            Files.createDirectories(link.path.parent);Files.createSymbolicLink(link.path,Path.of(link.target))
        }
        val real=selected.toRealPath()
        for(link in links.filter { !it.hard })if(!link.path.toRealPath().startsWith(real))throw IOException("Chromium link resolves outside its runtime")
        for(link in links.filter { it.hard }) {
            val target=child(root,link.target)
            if(!target.toRealPath().startsWith(real))throw IOException("Chromium hard link escapes its runtime")
            Files.createDirectories(link.path.parent);Files.createLink(link.path,target)
        }
    }
    private fun valid(root: Path,entry: JsonObject): Boolean {
        if(!Files.isDirectory(root))return false
        return try {
            val real=root.toRealPath()
            for(value in entry.getAsJsonArray("files")) {
                val file=value.asJsonObject;val path=child(root,file["path"].asString)
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(real) || Files.size(path)!=file["bytes"].asLong)return false
                if(Files.newInputStream(path).use(::sha256)!=file["sha256"].asString)return false
                if(file["executable"].asBoolean && !System.getProperty("os.name").startsWith("Windows",true) && !Files.isExecutable(path))return false
            }
            for(value in entry.getAsJsonArray("links")) {
                val link=value.asJsonObject;val path=child(root,link["path"].asString)
                if(!Files.exists(path) || !path.toRealPath().startsWith(real))return false
                if(!link["hard"].asBoolean && (!Files.isSymbolicLink(path) || Files.readSymbolicLink(path).toString()!=link["target"].asString))return false
            }
            true
        } catch(_: IOException) { false }
    }
    private fun sha256(input: InputStream): String {
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true) { val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n) };return hash.digest().hex()
    }
    private fun ByteArray.hex()=joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun move(from: Path,to: Path) {
        try { Files.move(from,to,StandardCopyOption.ATOMIC_MOVE) }
        catch(_: AtomicMoveNotSupportedException) { Files.move(from,to) }
    }
    private fun deleteTree(path: Path) { if(Files.exists(path))Files.walk(path).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    companion object {
        private val lock=Any()
        private const val MAX_ARCHIVE=1024L*1024*1024
        private const val MAX_UNPACKED=4L*1024*1024*1024
        @JvmStatic @Throws(IOException::class) fun ensureInstalled() {
            try {
            MCEFDownloadListener.INSTANCE.setTask(tr("Verifying bundled Chromium..."))
            val path=System.getProperty("mcef.libraries.path") ?: throw IOException("MCEF did not configure a native library directory")
            val runtime=BundledChromium(Path.of(path))
            if(runtime.revision!=MCEF.getJavaCefCommit())throw IOException("Bundled Chromium does not match the installed MCEF bridge")
            val platform=MCEFPlatform.getPlatform().normalizedName
            runtime.install(platform)
            Eln.LOGGER.info("EAMedia verified local bundled Chromium for {}; no runtime download",platform)
            } catch(e: IOException) { throw e } catch(e: Exception) { throw IOException("Could not prepare the bundled Chromium runtime",e) }
        }
    }
}
