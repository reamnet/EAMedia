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
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.BufferUtils
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import javax.sound.sampled.AudioFormat

object TelevisionMirrorClient {
    private fun render(pos: net.minecraft.core.BlockPos)=((Minecraft.getInstance().level?.getBlockEntity(pos) as? TransparentNodeEntity)?.elementRender as? MediaRender)?.takeIf { it.kind==MediaKind.TV }
    fun receive(packet: TelevisionStream) { render(packet.pos)?.television?.mirror?.receive(packet) }
    fun input(packet: TelevisionHostInput) {
        val r=render(packet.pos) ?: return
        if(r.tvSession!=packet.session || r.tvHostEpoch!=packet.epoch || r.mediaRevision!=packet.revision || r.tvHost!=Minecraft.getInstance().player?.uuid)return
        r.television.remoteInput(packet.value,packet.controller)
    }
}

/** One encoder at the host, bounded fragments through the server, one texture/audio queue per viewer. */
class TelevisionMirror(private val render: MediaRender): AutoCloseable {
    private val mc get()=Minecraft.getInstance()
    private data class Stamp(val session: java.util.UUID,val epoch: java.util.UUID,val revision: Int)
    @Volatile private var stamp: Stamp?=null
    @Volatile private var hosting=false
    private val encoding=AtomicBoolean();private val decoding=AtomicBoolean()
    private val videoSequence=AtomicLong();private val audioSequence=AtomicLong()
    private var assembler=TelevisionFrameAssembler()
    private var texture: DynamicTexture?=null
    private val location=ResourceLocation.fromNamespaceAndPath("eamedia","mirror/${ids.incrementAndGet()}")
    private var pcm=TelevisionPcmQueue()
    private var sound: MirrorSound?=null
    private var audioReceived=-1L
    @Volatile private var nextCapture=0L
    @Volatile var encodedFrames=0L;private set
    var receivedFrames=0L;private set
    var receivedAudioBytes=0L;private set
    @Volatile var frameHash=0;private set
    fun update() {
        val next=render.tvSession?.let { Stamp(it,render.tvHostEpoch,render.mediaRevision) }
        if(next!=stamp) { clear();stamp=next }
        hosting=render.tvHost==mc.player?.uuid
    }
    private fun current(s: Stamp)=stamp==s && render.working && !render.tileEntity.isRemoved
    fun capture(buffer: ByteBuffer,width: Int,height: Int,popup: ByteBuffer?=null,bounds: java.awt.Rectangle?=null) {
        val s=stamp ?: return
        if(!hosting || width!=960 || height!=540 || System.nanoTime()<nextCapture || !encoding.compareAndSet(false,true))return
        nextCapture=System.nanoTime()+1_000_000_000L/12
        val raw=ByteArray(width*height*4)
        try {
            buffer.duplicate().apply { position(0);get(raw) }
            if(popup!=null && bounds!=null && bounds.width>0 && bounds.height>0 && popup.capacity()>=bounds.width.toLong()*bounds.height*4) {
                val clipped=bounds.intersection(java.awt.Rectangle(0,0,width,height));val overlay=popup.duplicate()
                for(y in clipped.y until clipped.y+clipped.height) {
                    overlay.position(((y-bounds.y)*bounds.width+clipped.x-bounds.x)*4)
                    overlay.get(raw,(y*width+clipped.x)*4,clipped.width*4)
                }
            }
        } catch(_: Exception) { encoding.set(false);return }
        workers.execute {
            try {
                val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);val rgb=IntArray(width*height)
                for(i in rgb.indices) { val p=i*4;rgb[i]=((raw[p+2].toInt() and 255) shl 16) or ((raw[p+1].toInt() and 255) shl 8) or (raw[p].toInt() and 255) }
                image.setRGB(0,0,width,height,rgb,0,width)
                val bytes=sequenceOf(.65f,.45f,.25f,.12f).map { jpeg(image,it) }.firstOrNull { it.size<=TelevisionStream.MAX_FRAME }
                if(bytes==null) { encoding.set(false);return@execute }
                mc.execute {
                    try {
                        if(current(s) && hosting && bytes.size<=TelevisionStream.MAX_FRAME) {
                            val sequence=videoSequence.incrementAndGet()
                            TelevisionStream.split(render.tileEntity.blockPos,s.session,s.epoch,s.revision,sequence,bytes).forEach(PacketDistributor::sendToServer)
                            encodedFrames++;frameHash=bytes.contentHashCode()
                        }
                    } finally { encoding.set(false) }
                }
            } catch(_: Exception) { encoding.set(false) }
        }
    }
    fun captureAudio(value: String) {
        val s=stamp ?: return
        if(!hosting || value.length>11000)return
        val data=try { Base64.getDecoder().decode(value) } catch(_: IllegalArgumentException) { return }
        if(data.size !in 4..8192 || data.size%4!=0)return
        mc.execute {
            if(current(s) && hosting)PacketDistributor.sendToServer(TelevisionStream(render.tileEntity.blockPos,s.session,s.epoch,s.revision,audioSequence.incrementAndGet(),TelevisionStream.AUDIO,0,1,data))
        }
    }
    fun receive(packet: TelevisionStream) {
        val s=stamp ?: return
        if(packet.session!=s.session || packet.epoch!=s.epoch || packet.revision!=s.revision || hosting || !current(s))return
        if(packet.kind==TelevisionStream.AUDIO) {
            if(packet.sequence<=audioReceived || packet.bytes.size !in 4..8192 || packet.bytes.size%4!=0)return
            audioReceived=packet.sequence;receivedAudioBytes+=packet.bytes.size;pcm.offer(packet.bytes)
            if(sound==null) { sound=MirrorSound(render,pcm);mc.soundManager.play(sound!!) }
            return
        }
        val data=assembler.accept(packet) ?: return
        if(!decoding.compareAndSet(false,true))return
        workers.execute {
            try {
                val image=decode(data)
                mc.execute {
                    try {
                        if(current(s) && !hosting) {
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
    private fun clear() {
        texture?.let { mc.textureManager.release(location) };texture=null
        sound?.let(mc.soundManager::stop);sound=null;pcm.close();pcm=TelevisionPcmQueue()
        assembler=TelevisionFrameAssembler();audioReceived=-1;receivedFrames=0;receivedAudioBytes=0
    }
    override fun close() { stamp=null;hosting=false;clear() }
    companion object {
        private val ids=AtomicLong()
        private val workers=Executors.newFixedThreadPool(2) { r->Thread(r,"EA Media TV mirror").apply { isDaemon=true } }
        internal fun jpeg(image: BufferedImage,quality: Float=.65f): ByteArray {
            val writer=ImageIO.getImageWritersByFormatName("jpeg").next();val bytes=ByteArrayOutputStream()
            try { MemoryCacheImageOutputStream(bytes).use { out->writer.output=out;val p=writer.defaultWriteParam;p.compressionMode=ImageWriteParam.MODE_EXPLICIT;p.compressionQuality=quality;writer.write(null,IIOImage(image,null,null),p) } }
            finally { writer.dispose() }
            return bytes.toByteArray()
        }
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
    @Synchronized fun offer(bytes: ByteArray) {
        if(closed)return
        while(buffered+bytes.size>23040 && queue.isNotEmpty()) { buffered-=queue.removeFirst().size-offset;offset=0 }
        queue.addLast(bytes);buffered+=bytes.size;(this as java.lang.Object).notifyAll()
    }
    override fun getFormat()=format
    @Synchronized override fun read(size: Int): ByteBuffer {
        if(closed)return BufferUtils.createByteBuffer(0)
        val count=minOf(size,1920).let { it-it%4 };val result=BufferUtils.createByteBuffer(count)
        if(buffered<count)try { (this as java.lang.Object).wait(25) } catch(_: InterruptedException) { Thread.currentThread().interrupt() }
        while(result.hasRemaining() && queue.isNotEmpty()) {
            val first=queue.first;val n=minOf(result.remaining(),first.size-offset);result.put(first,offset,n);offset+=n;buffered-=n
            if(offset==first.size) { queue.removeFirst();offset=0 }
        }
        while(result.hasRemaining())result.put(0.toByte())
        result.flip();return result
    }
    @Synchronized override fun close() { closed=true;queue.clear();buffered=0;(this as java.lang.Object).notifyAll() }
}

private class MirrorSound(private val render: MediaRender,private val pcm: TelevisionPcmQueue): AbstractTickableSoundInstance(SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("eamedia","television_audio")),SoundSource.RECORDS,SoundInstance.createUnseededRandom()) {
    init { val p=render.tileEntity.blockPos;x=p.x+.5;y=p.y+.5;z=p.z+.5;attenuation=SoundInstance.Attenuation.NONE;volume=render.settings[1].toFloat() }
    override fun resolve(manager: SoundManager): WeighedSoundEvents {
        sound=Sound(location,ConstantFloat.of(1f),ConstantFloat.of(1f),1,Sound.Type.FILE,true,false,32)
        return WeighedSoundEvents(location,null).also { it.addSound(sound) }
    }
    override fun getStream(library: SoundBufferLibrary,sound: Sound,looping: Boolean): CompletableFuture<AudioStream> = CompletableFuture.completedFuture(pcm)
    override fun tick() {
        val mc=Minecraft.getInstance();val p=render.tileEntity.blockPos
        val distance=mc.player?.distanceToSqr(p.x+.5,p.y+.5,p.z+.5) ?: Double.POSITIVE_INFINITY
        volume=(render.settings[1]*(1-kotlin.math.sqrt(distance)/32).coerceIn(0.0,1.0)).toFloat()
        if(!render.working || render.tileEntity.isRemoved || distance>1024)stop()
    }
}
