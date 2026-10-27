package mods.eamedia

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class BundledChromiumTest {
    @get:Rule val temp=TemporaryFolder()
    private val base="/assets/eamedia/chromium/"
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fake(root: Path,name: String="linux_amd64/runtime.bin"): BundledChromium {
        val payload="native runtime fixture".toByteArray();val buffer=ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(buffer)).use { tar->
            val entry=TarArchiveEntry(name).apply { size=payload.size.toLong();mode=420 };tar.putArchiveEntry(entry);tar.write(payload);tar.closeArchiveEntry()
        }
        val archive=buffer.toByteArray()
        val manifest=Gson().toJson(mapOf("schema" to 1,"jcefCommit" to "test","platforms" to mapOf("linux_amd64" to mapOf(
            "archiveBytes" to archive.size,"archiveSha256" to hash(archive),"unpackedBytes" to payload.size,
            "parts" to listOf(mapOf("resource" to "part.bin","bytes" to archive.size,"sha256" to hash(archive))),
            "files" to listOf(mapOf("path" to "runtime.bin","bytes" to payload.size,"sha256" to hash(payload),"executable" to false)),"links" to emptyList<Any>()))))
        return BundledChromium(root) { ByteArrayInputStream(if(it=="manifest.json")manifest.toByteArray() else archive) }
    }
    @Test fun everyBundledPlatformArchiveAndChunkMatchesItsPinnedHash() {
        val manifest=javaClass.getResourceAsStream(base+"manifest.json")!!.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val platforms=manifest.getAsJsonObject("platforms")
        assertEquals(setOf("linux_amd64","windows_amd64","macos_amd64","macos_arm64"),platforms.keySet())
        for((platform,value)in platforms.entrySet()) {
            val entry=value.asJsonObject;val archiveHash=MessageDigest.getInstance("SHA-256");var total=0L
            for(p in entry.getAsJsonArray("parts")) {
                val part=p.asJsonObject;val partHash=MessageDigest.getInstance("SHA-256");var count=0L
                javaClass.getResourceAsStream(base+part["resource"].asString)!!.use { input->
                    val b=ByteArray(65536);while(true) { val n=input.read(b);if(n<0)break;partHash.update(b,0,n);archiveHash.update(b,0,n);count+=n }
                }
                assertEquals(part["bytes"].asLong,count);assertTrue(count<=45L*1024*1024)
                assertEquals(part["sha256"].asString,partHash.digest().joinToString("") { "%02x".format(it.toInt() and 255) });total+=count
            }
            assertEquals(entry["archiveBytes"].asLong,total)
            assertEquals(entry["archiveSha256"].asString,archiveHash.digest().joinToString("") { "%02x".format(it.toInt() and 255) },platform)
            assertTrue(entry.getAsJsonObject("nativeArchitectures").entrySet().all { it.value.asString in setOf(platform.substringAfterLast('_'),"universal") })
        }
    }
    @Test fun everySupportedRuntimeExtractsFromLocalResourcesAndVerifiesAllFiles() {
        val installer=BundledChromium(temp.newFolder("all-platforms").toPath())
        for(platform in listOf("linux_amd64","windows_amd64","macos_amd64","macos_arm64")) {
            val path=installer.install(platform);assertTrue(Files.isDirectory(path))
            assertTrue(Files.walk(path).use { files->files.anyMatch { Files.isRegularFile(it) } })
        }
    }
    @Test fun changedCacheIsRepairedAndAValidCacheIsReused() {
        val root=temp.newFolder("cache").toPath();val installer=fake(root);val path=installer.install("linux_amd64").resolve("runtime.bin")
        val stamp=Files.getLastModifiedTime(path);installer.install("linux_amd64");assertEquals(stamp,Files.getLastModifiedTime(path))
        Files.writeString(path,"corrupt cached executable");installer.install("linux_amd64")
        assertEquals("native runtime fixture",Files.readString(path))
    }
    @Test fun archiveCannotWriteOutsideItsRuntimeAndFailedInstallIsCleanedUp() {
        val root=temp.newFolder("unsafe").toPath()
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { fake(root,"linux_amd64/../../escaped.bin").install("linux_amd64") }
        assertFalse(Files.exists(root.parent.resolve("escaped.bin")))
        assertTrue(Files.list(root).use { it.findAny().isEmpty })
    }
}
