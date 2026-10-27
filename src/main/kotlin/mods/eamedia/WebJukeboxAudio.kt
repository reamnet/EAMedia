package mods.eamedia

import mods.eln.Eln
import mods.eln.i18n.I18N.tr
import mods.eln.node.transparent.TransparentNodeEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.Sound
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.*
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.valueproviders.ConstantFloat
import org.lwjgl.BufferUtils
import java.io.*
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.sound.sampled.AudioFormat

/** Client-only I/O. Completed local files keep networking and transcoding off the render/audio threads. */
class WebJukeboxAudio(private val render: MediaRender,private val tile: TransparentNodeEntity): AutoCloseable {
    private var revision=Int.MIN_VALUE
    private var job: Job?=null
    private var muted=false
    fun update() {
        val mc=Minecraft.getInstance();val p=mc.player
        muted=render.settings[1]<=0 || mc.options.getSoundSourceVolume(SoundSource.RECORDS)<=0 || mc.options.getSoundSourceVolume(SoundSource.MASTER)<=0
        val near=p!=null && p.distanceToSqr(tile.blockPos.x+.5,tile.blockPos.y+.5,tile.blockPos.z+.5)<=32.0*32.0
        if(!render.working || !render.mediaRequested || !near || muted || tile.isRemoved) { close();return }
        if(job==null || revision!=render.mediaRevision) {
            close();revision=render.mediaRevision
            val uri=WebAudioUrl.parse(render.mediaUrl) ?: return
            job=Job(uri,render,tile).also { it.start() }
        }
    }
    fun status(): String=when {
        muted->tr("Web audio muted; check volume / Records slider")
        !render.mediaRequested->tr("Paste a HTTPS audio / YouTube link and press Play")
        !render.working->tr("Waiting for electrical power")
        else->job?.message() ?: tr("Waiting for listener within 32 blocks")
    }
    override fun close() { job?.close();job=null }

    private class Job(val uri: URI,val render: MediaRender,val tile: TransparentNodeEntity): AutoCloseable {
        private val cancelled=AtomicBoolean()
        @Volatile var state=0
        @Volatile private var process: Process?=null
        @Volatile private var network: InputStream?=null
        @Volatile private var audio: AudioStream?=null
        @Volatile private var sound: WebSound?=null
        @Volatile private var directory: Path?=null
        private var future: Future<*>?=null
        fun message()=when(state) {
            0->tr("Preparing web audio...")
            1->tr("Downloading selected audio...")
            2->tr("Converting selected audio...")
            3->tr("Playing web audio")
            4->tr("Song finished; press Play to restart")
            5->tr("Could not prepare bundled YouTube tools")
            6->tr("Could not prepare bundled audio decoder")
            7->tr("Audio unavailable; check link, HTTPS access and media tools")
            8->tr("Four web audio players are already active")
            9->tr("Extracting bundled media tools...")
            else->tr("Web audio stopped")
        }
        fun start() {
            synchronized(active) {
                if(active.size>=4) { state=8;return }
                active.add(this)
            }
            future=workers.submit {
                try {
                    val dir=Files.createTempDirectory("eln-web-audio-");directory=dir;val source=dir.resolve("source.media")
                    state=1
                    if(WebAudioUrl.youtube(uri)) {
                        state=9
                        val ytdlp=tool("media.ytdlpPath","ytdlp");val javascript=tool("media.quickjsPath","quickjs");val ffmpeg=tool("media.ffmpegPath","ffmpeg")
                        state=1
                        runTool(ytdlp,listOf("--no-js-runtimes","--js-runtimes","quickjs:$javascript","--ffmpeg-location",ffmpeg,"--ignore-config","--no-plugin-dirs","--no-playlist","--no-progress","--quiet","--no-cache-dir","--no-part","--max-filesize","64M","--socket-timeout","15","--match-filter","duration <= 3600","-f","bestaudio","-o",source.toString(),"--",uri.toASCIIString()),5)
                    } else download(uri,source)
                    require(Files.size(source)<=MAX_BYTES)
                    if(cancelled.get())return@submit
                    val prefix=Files.newInputStream(source).use { it.readNBytes(96) }.toString(Charsets.ISO_8859_1)
                    val ready=if(prefix.startsWith("OggS") && prefix.contains("vorbis"))source else {
                        state=9;val ffmpeg=tool("media.ffmpegPath","ffmpeg");state=2;val converted=dir.resolve("decoded.ogg")
                        runTool(ffmpeg,listOf("-nostdin","-v","error","-protocol_whitelist","file,pipe","-i",source.toString(),"-vn","-t","3600","-ac","1","-ar","44100","-c:a","libvorbis","-q:a","4","-f","ogg",converted.toString()),6)
                        require(Files.size(converted)<=MAX_BYTES);converted
                    }
                    if(cancelled.get())return@submit
                    val decoded=LocalMonoStream(JOrbisAudioStream(Files.newInputStream(ready)),{ state=4 },{ cleanup() })
                    audio=decoded
                    if(cancelled.get()) { decoded.close();return@submit }
                    Minecraft.getInstance().execute {
                        if(cancelled.get())decoded.close() else {
                            state=3;val instance=WebSound(this);sound=instance;Minecraft.getInstance().soundManager.play(instance)
                        }
                    }
                } catch(_: CancellationException) {
                    if(!cancelled.get())state=7
                    cleanup()
                } catch(_: Throwable) {
                    if(!cancelled.get() && state!=5 && state!=6)state=7
                    cleanup()
                } finally {
                    if(cancelled.get()) { audio?.close();cleanup() }
                }
            }
        }
        private fun runTool(executable: String,args: List<String>,missing: Int) {
            if(cancelled.get())throw CancellationException()
            val child=try { ProcessBuilder(listOf(executable)+args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
            catch(e: IOException) { state=missing;throw e }
            process=child
            try {
                val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5)
                while(!child.waitFor(100,TimeUnit.MILLISECONDS)) {
                    if(cancelled.get() || System.nanoTime()>deadline)throw CancellationException()
                    directory?.let { dir->Files.list(dir).use { paths->paths.forEach { if(Files.isRegularFile(it) && Files.size(it)>MAX_BYTES)throw IOException("Audio too large") } } }
                }
                if(child.exitValue()!=0)throw IOException("Media helper failed")
            } finally { child.destroyForcibly();process=null }
        }
        private fun download(initial: URI,destination: Path) {
            var at=initial
            repeat(6) { redirect->
                if(cancelled.get())throw CancellationException()
                val addresses=InetAddress.getAllByName(at.host)
                require(addresses.isNotEmpty() && addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress })
                val conn=at.toURL().openConnection() as HttpsURLConnection
                conn.instanceFollowRedirects=false;conn.connectTimeout=10000;conn.readTimeout=10000;conn.setRequestProperty("User-Agent","ElectricalAgeMedia-WebJukebox/1.0.0")
                try {
                    if(conn.responseCode in 300..399) {
                        val location=conn.getHeaderField("Location") ?: throw IOException("Missing redirect")
                        at=WebAudioUrl.parse(at.resolve(location).toASCIIString()) ?: throw IOException("Non-HTTPS redirect")
                        if(redirect==5)throw IOException("Too many redirects")
                    } else {
                        require(conn.responseCode in 200..299 && conn.contentLengthLong<=MAX_BYTES)
                        val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3)
                        conn.inputStream.use { input->network=input;Files.newOutputStream(destination).use { output->
                            val bytes=ByteArray(32768);var count=0L
                            while(true) {
                                if(cancelled.get() || System.nanoTime()>deadline)throw CancellationException()
                                val n=input.read(bytes);if(n<0)break
                                count+=n;require(count<=MAX_BYTES);output.write(bytes,0,n)
                            }
                        } }
                        network=null;return
                    }
                } finally { conn.disconnect() }
            }
        }
        fun audioStream(): AudioStream=audio ?: throw IllegalStateException("Audio not prepared")
        private fun cleanup() {
            synchronized(active) { active.remove(this) }
            val dir=directory ?: return
            try { Files.walk(dir).use { paths->paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }catch(_: IOException) { }
            directory=null
        }
        override fun close() {
            if(!cancelled.compareAndSet(false,true))return
            sound?.let { Minecraft.getInstance().soundManager.stop(it) };process?.destroyForcibly()
            try { network?.close() }catch(_: IOException) { }
            future?.cancel(true);try { audio?.close() }catch(_: IOException) { }
            cleanup()
        }
        private fun tool(key: String,name: String): String {
            val override=Eln.config.getStringOrElse(key,"").trim()
            return override.ifBlank { runtime.resolve(name).toString() }
        }
    }
    private class WebSound(private val job: Job): AbstractTickableSoundInstance(SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("eln","web_jukebox_audio")),SoundSource.RECORDS,SoundInstance.createUnseededRandom()) {
        init { x=job.tile.blockPos.x+.5;y=job.tile.blockPos.y+.5;z=job.tile.blockPos.z+.5;volume=job.render.settings[1].toFloat() }
        override fun resolve(manager: SoundManager): WeighedSoundEvents {
            sound=Sound(location,ConstantFloat.of(1f),ConstantFloat.of(1f),1,Sound.Type.FILE,true,false,32)
            return WeighedSoundEvents(location,null).also { it.addSound(sound) }
        }
        override fun getStream(library: SoundBufferLibrary,sound: Sound,looping: Boolean): CompletableFuture<AudioStream> = CompletableFuture.completedFuture(job.audioStream())
        override fun tick() { volume=job.render.settings[1].toFloat();if(job.state!=3 || job.tile.isRemoved || !job.render.working)stop() }
    }
    /** Vorbis stereo is mixed locally to mono so OpenAL correctly attenuates the block sound. */
    internal class LocalMonoStream(private val delegate: AudioStream,private val ended: ()->Unit,private val cleanup: ()->Unit): AudioStream {
        private val channels=delegate.format.channels
        private val format=AudioFormat(delegate.format.sampleRate,16,1,true,false)
        private var closed=false
        init { require(channels in 1..8 && delegate.format.sampleSizeInBits==16) }
        override fun getFormat()=format
        @Synchronized override fun read(size: Int): ByteBuffer {
            if(closed)return BufferUtils.createByteBuffer(0)
            val input=delegate.read(size*channels).order(ByteOrder.LITTLE_ENDIAN)
            if(!input.hasRemaining())ended()
            if(channels==1)return input
            val output=BufferUtils.createByteBuffer(input.remaining()/channels).order(ByteOrder.LITTLE_ENDIAN)
            while(input.remaining()>=channels*2) { var sum=0;repeat(channels) { sum+=input.short.toInt() };output.putShort((sum/channels).toShort()) }
            output.flip();return output
        }
        @Synchronized override fun close() { if(closed)return;closed=true;try { delegate.close() }finally { cleanup() } }
    }
    companion object {
        private val runtime by lazy { BundledMediaRuntime(Minecraft.getInstance().gameDirectory.toPath().resolve("eln-media-runtime")) }
        private const val MAX_BYTES=64L*1024*1024
        private val active=HashSet<Job>()
        private val workers=Executors.newFixedThreadPool(2) { r->Thread(r,"eln-web-audio").apply { isDaemon=true } }
    }
}
