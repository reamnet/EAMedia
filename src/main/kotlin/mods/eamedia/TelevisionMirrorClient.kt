package mods.eamedia

import com.mojang.blaze3d.platform.NativeImage
import mods.eln.node.transparent.TransparentNodeEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.Sound
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.*
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.valueproviders.ConstantFloat
import org.lwjgl.BufferUtils
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.sound.sampled.AudioFormat

object TelevisionMirrorClient {
    private fun render(pos: net.minecraft.core.BlockPos)=((Minecraft.getInstance().level?.getBlockEntity(pos) as? TransparentNodeEntity)?.elementRender as? MediaRender)?.takeIf { it.kind==MediaKind.TV }
    fun receive(packet: TelevisionStream) { render(packet.pos)?.television?.mirror?.receive(packet) }

}

/** Every client decodes the same server picture and plays the same server PCM stream. */
class TelevisionMirror(private val render: MediaRender): AutoCloseable {
    private val mc get()=Minecraft.getInstance()
    private data class Stamp(val session: java.util.UUID,val epoch: java.util.UUID,val revision: Int)
    @Volatile private var stamp: Stamp?=null
    private val decoding=AtomicBoolean()
    private var assembler=TelevisionFrameAssembler()
    private var texture: DynamicTexture?=null
    private val location=ResourceLocation.fromNamespaceAndPath("eamedia","mirror/${ids.incrementAndGet()}")
    private var pcm=TelevisionPcmQueue()
    private var sound: MirrorSound?=null
    private var audioReceived=-1L
    private var soundStarted=0L
    var playbackStarts=0L;private set
    val playedAudioBytes get()=pcm.consumedBytes
    val channelResumes get()=pcm.channelResumes
    val soundActive get()=sound?.let { mc.soundManager.isActive(it) } ?: false
    val encodedFrames get()=0L
    var receivedFrames=0L;private set
    var receivedAudioBytes=0L;private set
    @Volatile var frameHash=0;private set
    fun update() {
        val next=render.tvSession?.let { Stamp(it,render.tvHostEpoch,render.mediaRevision) }
        if(next!=stamp) { clear();stamp=next }
        if(receivedAudioBytes>0)ensureSound()
    }
    private fun current(s: Stamp)=stamp==s && render.working && !render.tileEntity.isRemoved
    fun receive(packet: TelevisionStream) {
        val s=stamp ?: return
        if(packet.session!=s.session || packet.epoch!=s.epoch || packet.revision!=s.revision || !current(s))return
        if(packet.kind==TelevisionStream.AUDIO) {
            if(packet.sequence<=audioReceived || packet.bytes.size !in 4..8192 || packet.bytes.size%4!=0)return
            audioReceived=packet.sequence;receivedAudioBytes+=packet.bytes.size
            ensureSound();pcm.offer(packet.bytes)
            return
        }
        val data=assembler.accept(packet) ?: return
        if(!decoding.compareAndSet(false,true))return
        workers.execute {
            try {
                val image=decode(data)
                mc.execute {
                    try {
                        if(current(s)) {
                            var target=texture
                            if(target==null || target.pixels?.width!=image.width || target.pixels?.height!=image.height) {
                                if(target!=null)mc.textureManager.release(location)
                                target=DynamicTexture(NativeImage(image.width,image.height,false));texture=target;mc.textureManager.register(location,target)
                            }
                            val pixels=target.pixels!!
                            for(y in 0 until image.height)for(x in 0 until image.width) {
                                val argb=image.getRGB(x,y);pixels.setPixelRGBA(x,y,(argb and 0xff00ff00.toInt()) or ((argb and 255) shl 16) or ((argb ushr 16) and 255))
                            }
                            target.upload();receivedFrames++;frameHash=data.contentHashCode()
                        }
                    } finally { decoding.set(false) }
                }
            } catch(_: Exception) { decoding.set(false) }
        }
    }
    fun texture()=if(texture!=null && receivedFrames>0)location else null
    private fun ensureSound() {
        if(mc.options.getSoundSourceVolume(SoundSource.MASTER)<=0f || mc.options.getSoundSourceVolume(SoundSource.RECORDS)<=0f)return
        val now=System.nanoTime()
        val current=sound
        if(current!=null && now-soundStarted<1_000_000_000L)return
        if(current!=null && !pcm.isClosed && mc.soundManager.isActive(current))return
        if(now-soundStarted<1_000_000_000L)return
        current?.let(mc.soundManager::stop)
        pcm.close();pcm=TelevisionPcmQueue()
        val next=MirrorSound(render,pcm);sound=next;soundStarted=now;playbackStarts++
        mc.soundManager.play(next)
    }
    private fun clear() {
        texture?.let { mc.textureManager.release(location) };texture=null
        sound?.let(mc.soundManager::stop);sound=null;pcm.close();pcm=TelevisionPcmQueue()
        assembler=TelevisionFrameAssembler();audioReceived=-1;receivedFrames=0;receivedAudioBytes=0;soundStarted=0
    }
    override fun close() { stamp=null;clear() }
    companion object {
        private val ids=AtomicLong()
        private val workers=Executors.newFixedThreadPool(2) { r->Thread(r,"EA Media TV mirror").apply { isDaemon=true } }
        internal fun decode(bytes: ByteArray): BufferedImage {
            require(bytes.size<=TelevisionStream.MAX_FRAME)
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input->
                val readers=ImageIO.getImageReaders(input);require(readers.hasNext());val reader=readers.next()
                try { reader.input=input;require(reader.formatName.equals("jpeg",true));require(reader.getWidth(0) in 1..960 && reader.getHeight(0) in 1..540);return reader.read(0) }
                finally { reader.dispose() }
            }
        }
    }
}

/** Fixed 24 kHz stereo PCM, bounded to 240 ms. Drop old samples instead of accumulating delay. */
class TelevisionPcmQueue: AudioStream {
    private val queue=ArrayDeque<ByteArray>();private var offset=0;private var buffered=0;private var closed=false
    private val format=AudioFormat(24000f,16,2,true,false)
    @Volatile var consumedBytes=0L;private set
    @Volatile var channelResumes=0L;private set
    fun onChannelResumed(){channelResumes++}
    val isClosed get()=synchronized(this){closed}
    @Synchronized fun offer(bytes: ByteArray) {
        if(closed)return
        while(buffered+bytes.size>23040 && queue.isNotEmpty()) { buffered-=queue.removeFirst().size-offset;offset=0 }
        queue.addLast(bytes);buffered+=bytes.size;(this as java.lang.Object).notifyAll()
    }
    override fun getFormat()=format
    @Synchronized override fun read(size: Int): ByteBuffer {
        if(closed)return BufferUtils.createByteBuffer(0)
        val count=minOf(size,4800).let { it-it%4 };val result=BufferUtils.createByteBuffer(count)
        if(buffered<count)try { (this as java.lang.Object).wait(25) } catch(_: InterruptedException) { Thread.currentThread().interrupt() }
        while(result.hasRemaining() && queue.isNotEmpty()) {
            val first=queue.first;val n=minOf(result.remaining(),first.size-offset);result.put(first,offset,n);offset+=n;buffered-=n;consumedBytes+=n
            if(offset==first.size) { queue.removeFirst();offset=0 }
        }
        while(result.hasRemaining())result.put(0.toByte())
        result.flip();return result
    }
    @Synchronized override fun close() { closed=true;queue.clear();buffered=0;(this as java.lang.Object).notifyAll() }
}

private class MirrorSound(private val render: MediaRender,private val pcm: TelevisionPcmQueue): AbstractTickableSoundInstance(SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("eamedia","television_audio")),SoundSource.RECORDS,SoundInstance.createUnseededRandom()) {
    init { val p=render.tileEntity.blockPos;x=p.x+.5;y=p.y+.5;z=p.z+.5;attenuation=SoundInstance.Attenuation.NONE;volume=render.settings[1].toFloat() }
    override fun canStartSilent()=true
    override fun resolve(manager: SoundManager): WeighedSoundEvents {
        sound=Sound(location,ConstantFloat.of(1f),ConstantFloat.of(1f),1,Sound.Type.FILE,true,false,32)
        return WeighedSoundEvents(location,null).also { it.addSound(sound) }
    }
    override fun getStream(library: SoundBufferLibrary,sound: Sound,looping: Boolean): CompletableFuture<AudioStream> = CompletableFuture.completedFuture(pcm)
    override fun tick() {
        val mc=Minecraft.getInstance();val p=render.tileEntity.blockPos
        val distance=mc.player?.distanceToSqr(p.x+.5,p.y+.5,p.z+.5) ?: Double.POSITIVE_INFINITY
        volume=televisionGain(render.settings[1],distance).toFloat()
        if(!render.working || render.tileEntity.isRemoved || distance>1024)stop()
    }
}

internal fun televisionGain(volume: Double,distanceSquared: Double): Double {
    if(!volume.isFinite() || !distanceSquared.isFinite() || distanceSquared<0.0)return 0.0
    return volume.coerceIn(0.0,1.0)*(1-kotlin.math.sqrt(distanceSquared)/32).coerceIn(0.0,1.0)
}
