package mods.eamedia

import com.google.gson.Gson
import com.google.gson.JsonParser
import net.minecraft.client.sounds.JOrbisAudioStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BundledMediaRuntimeTest {
    @get:Rule val temp=TemporaryFolder()
    private val base="/assets/eln/media-runtime/"
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fake(cache: Path,bytes: ByteArray,corrupt: Boolean=false): BundledMediaRuntime {
        val manifest=Gson().toJson(mapOf("schema" to 1,"platforms" to mapOf("linux-x64" to mapOf("ffmpeg" to mapOf(
            "resource" to "test.bin","sha256" to sha(bytes),"binarySha256" to sha(bytes),"binaryBytes" to bytes.size,"version" to "test","format" to "raw")))))
        return BundledMediaRuntime(cache) { name->ByteArrayInputStream(if(name=="manifest.json")manifest.toByteArray() else if(corrupt)"corrupt".toByteArray() else bytes) }
    }
    private fun run(executable: Path,vararg args: String): String {
        val process=ProcessBuilder(listOf(executable.toString())+args).redirectErrorStream(true).apply { environment()["PATH"]="" }.start()
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Bundled process timed out")
            val output=process.inputStream.bufferedReader().readText();assertEquals(0,process.exitValue(),output);return output
        } finally { process.destroyForcibly() }
    }
    @Test fun everyBundledPlatformHasAllToolsAndEveryArchiveMatchesItsPinnedChecksum() {
        val manifest=javaClass.getResourceAsStream(base+"manifest.json")!!.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val platforms=manifest.getAsJsonObject("platforms");assertEquals(setOf("windows-x64","linux-x64","linux-arm64","macos-x64","macos-arm64"),platforms.keySet())
        val checked=HashSet<String>()
        for((_,v)in platforms.entrySet()) {
            assertEquals(setOf("ffmpeg","ytdlp","quickjs"),v.asJsonObject.keySet())
            for((_,value)in v.asJsonObject.entrySet()) {
                val a=value.asJsonObject;val file=a["resource"].asString
                if(!checked.add(file))continue
                val digest=MessageDigest.getInstance("SHA-256")
                javaClass.getResourceAsStream(base+file)!!.use { input->val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) } }
                assertEquals(a["sha256"].asString,digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) },file)
            }
        }
        assertEquals(14,checked.size)
    }
    @Test fun extractionRepairsChangedCacheAndRejectsCorruptBundledBytes() {
        val root=temp.newFolder("repair").toPath();val runtime=fake(root,"test".toByteArray());val exe=runtime.resolve("ffmpeg","Linux","amd64")
        assertContentEquals("test".toByteArray(),Files.readAllBytes(exe))
        Files.write(exe,"evil".toByteArray());Files.setLastModifiedTime(exe,FileTime.fromMillis(System.currentTimeMillis()+1000))
        assertEquals(exe,runtime.resolve("ffmpeg","Linux","amd64"));assertContentEquals("test".toByteArray(),Files.readAllBytes(exe))
        val failed=temp.newFolder("corrupt").toPath()
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { fake(failed,"test".toByteArray(),true).resolve("ffmpeg","Linux","amd64") }
        Files.walk(failed).use { paths->assertTrue(paths.noneMatch { Files.isRegularFile(it) }) }
    }
    @Test fun bundledToolsRunWithoutAnythingOnPathAndTranscodeAudioForMinecraft() {
        val runtime=BundledMediaRuntime(temp.newFolder("runtime").toPath())
        val ffmpeg=runtime.resolve("ffmpeg");val yt=runtime.resolve("ytdlp");val quickjs=runtime.resolve("quickjs")
        assertTrue(run(ffmpeg,"-version").startsWith("ffmpeg version"))
        assertTrue(run(yt,"--version").contains("2026.10.26"))
        assertEquals("2",run(quickjs,"-e","console.log(1+1)").trim())
        val source=temp.newFile("input.ogg").toPath();mods.eln.Eln::class.java.getResourceAsStream("/assets/eln/sounds/click.ogg")!!.use { Files.copy(it,source,java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        val mp3=temp.root.toPath().resolve("track.mp3");val converted=temp.root.toPath().resolve("track.ogg")
        run(ffmpeg,"-nostdin","-v","error","-i",source.toString(),"-c:a","libmp3lame",mp3.toString())
        run(ffmpeg,"-nostdin","-v","error","-protocol_whitelist","file,pipe","-i",mp3.toString(),"-vn","-ac","1","-ar","44100","-c:a","libvorbis","-q:a","4","-f","ogg",converted.toString())
        JOrbisAudioStream(Files.newInputStream(converted)).use { decoded->assertTrue(decoded.read(4096).hasRemaining());assertEquals(1,decoded.format.channels) }
    }
    @Test fun platformDetectionKeepsDarwinSeparateFromWindows() {
        assertEquals("macos-arm64",BundledMediaRuntime.platform("Darwin","aarch64"))
        assertEquals("windows-x64",BundledMediaRuntime.platform("Windows 11","amd64"))
        assertEquals("linux-arm64",BundledMediaRuntime.platform("Linux","arm64"))
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { BundledMediaRuntime.platform("Linux","unknown") }
    }
}
