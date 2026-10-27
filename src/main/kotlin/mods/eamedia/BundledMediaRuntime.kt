package mods.eamedia

import com.google.gson.JsonParser
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** Native tools are resources inside the mod jar. Extraction is local and runs only on audio workers. */
class BundledMediaRuntime(private val cache: Path,private val resource: (String)->InputStream? = { BundledMediaRuntime::class.java.getResourceAsStream("/assets/eln/media-runtime/$it") }) {
    private val manifest by lazy {
        val input=resource("manifest.json") ?: throw IOException("Bundled media manifest missing")
        input.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }.also { require(it["schema"].asInt==1) }
    }
    private data class Verified(val bytes: Long,val modified: FileTime)
    private val verified=ConcurrentHashMap<Path,Verified>()
    private val locks=ConcurrentHashMap<Path,Any>()
    fun resolve(tool: String): Path=resolve(tool,System.getProperty("os.name"),System.getProperty("os.arch"))
    internal fun resolve(tool: String,os: String,arch: String): Path {
        require(tool in listOf("ffmpeg","ytdlp","quickjs"))
        val platform=platform(os,arch)
        val asset=manifest.getAsJsonObject("platforms").getAsJsonObject(platform)?.getAsJsonObject(tool) ?: throw IOException("Unsupported bundled media platform")
        val filename=asset["resource"].asString;val hash=asset["binarySha256"].asString;val archiveHash=asset["sha256"].asString;val version=asset["version"].asString
        val expectedBytes=asset["binaryBytes"].asLong;val format=asset["format"].asString
        require(filename.matches(Regex("[A-Za-z0-9._-]+")) && version.matches(Regex("[A-Za-z0-9._-]+")))
        require(hash.matches(Regex("[0-9a-f]{64}")) && archiveHash.matches(Regex("[0-9a-f]{64}")) && expectedBytes in 1..MAX_BINARY)
        val dir=cache.resolve(platform);val target=dir.resolve("$tool-$version-${hash.take(16)}"+if(platform.startsWith("windows"))".exe" else "")
        synchronized(locks.computeIfAbsent(target) { Any() }) {
            if(Files.isRegularFile(target) && Files.size(target)==expectedBytes) {
                val stamp=Verified(Files.size(target),Files.getLastModifiedTime(target))
                if(verified[target]==stamp || sha256(target)==hash) {
                    if(!platform.startsWith("windows") && !Files.isExecutable(target) && !target.toFile().setExecutable(true,true))throw IOException("Cannot make bundled tool executable")
                    verified[target]=stamp;return target
                }
            }
            Files.createDirectories(dir)
            val archive=Files.createTempFile(dir,"archive-",".part");val binary=Files.createTempFile(dir,"extract-",".part")
            try {
                val source=resource(filename) ?: throw IOException("Bundled media archive missing")
                source.use { input->copy(input,archive,MAX_ARCHIVE,archiveHash) }
                when(format) {
                    "raw"->Files.newInputStream(archive).use { copy(it,binary,expectedBytes,hash) }
                    "gzip"->GZIPInputStream(Files.newInputStream(archive)).use { copy(it,binary,expectedBytes,hash) }
                    "zip"->ZipFile(archive.toFile()).use { zip->
                        val member=asset["member"].asString
                        require(member.split('/').none { it==".." } && !member.startsWith('/'))
                        val entry=zip.getEntry(member) ?: throw IOException("Bundled executable missing from archive")
                        zip.getInputStream(entry).use { copy(it,binary,expectedBytes,hash) }
                    }
                    else->throw IOException("Unsupported bundled archive format")
                }
                if(Files.size(binary)!=expectedBytes)throw IOException("Bundled executable size mismatch")
                if(!platform.startsWith("windows") && !binary.toFile().setExecutable(true,true))throw IOException("Cannot make bundled tool executable")
                try { Files.move(binary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
                catch(_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(binary,target,StandardCopyOption.REPLACE_EXISTING) }
                verified[target]=Verified(Files.size(target),Files.getLastModifiedTime(target));return target
            } finally { Files.deleteIfExists(archive);Files.deleteIfExists(binary) }
        }
    }
    private fun copy(input: InputStream,path: Path,limit: Long,expectedHash: String) {
        val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
        Files.newOutputStream(path).use { out->
            val buffer=ByteArray(65536)
            while(true) {
                if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Media extraction cancelled")
                val n=input.read(buffer);if(n<0)break
                bytes+=n;if(bytes>limit)throw IOException("Bundled archive exceeds expected size")
                digest.update(buffer,0,n);out.write(buffer,0,n)
            }
        }
        if(digest.digest().toHex()!=expectedHash)throw IOException("Bundled media checksum mismatch")
    }
    private fun sha256(path: Path): String=Files.newInputStream(path).use { input->
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) };digest.digest().toHex()
    }
    private fun ByteArray.toHex()=joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object {
        private const val MAX_ARCHIVE=64L*1024*1024
        private const val MAX_BINARY=256L*1024*1024
        internal fun platform(os: String,arch: String): String {
            val system=when { os.startsWith("Windows",true)->"windows";os.contains("linux",true)->"linux";os.contains("mac",true) || os.contains("darwin",true)->"macos";else->throw IOException("Unsupported media operating system") }
            val cpu=when(arch.lowercase(java.util.Locale.ROOT)) { "amd64","x86_64"->"x64";"aarch64","arm64"->"arm64";else->throw IOException("Unsupported media architecture") }
            return "$system-$cpu"
        }
    }
}
