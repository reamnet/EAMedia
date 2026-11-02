package mods.eamedia

import mods.eln.node.transparent.TransparentNodeEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.Sound
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.*
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.valueproviders.ConstantFloat
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.BufferUtils
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture

class JukeboxAudioSource(var snapshot: JukeboxSnapshot) {
    val endpoints=LinkedHashMap<BlockPos,Float>()
    @Volatile var active=false
    val audio=WebJukeboxAudio(this)
    val browserSounds=HashMap<BlockPos,SpeakerPcmSound>()
    var audioSequence=-1L
    var browserStamp=""
    var nextObserve=0L
    fun close() { audio.close();browserSounds.values.forEach { Minecraft.getInstance().soundManager.stop(it);it.pcm.close() };browserSounds.clear();active=false }
}

@EventBusSubscriber(modid="eamedia",value=[Dist.CLIENT])
object JukeboxAudioClient {
    private data class Endpoint(val tile: TransparentNodeEntity,val snapshot: JukeboxSnapshot,val gain: Double)
    private val endpoints=HashMap<Any,Endpoint>()
    private val sources=HashMap<UUID,JukeboxAudioSource>()
    fun register(render: MediaRender) { JukeboxSnapshot.of(render)?.let { register(render,render.tileEntity,it,1.0) } }
    fun register(owner: Any,tile: TransparentNodeEntity,snapshot: JukeboxSnapshot,gain: Double) { endpoints[owner]=Endpoint(tile,snapshot,gain) }
    fun remove(owner: Any) { endpoints.remove(owner) }
    internal fun debugSnapshot(session: UUID): String=sources[session]?.let { "active=${it.active} endpoints=${it.endpoints} sequence=${it.audioSequence} stamp=${it.snapshot.epoch}/${it.snapshot.revision}" } ?: "No source"
    internal fun diagnostics(session: UUID)=sources[session]?.browserSounds?.mapValues { it.value.pcm.consumedBytes } ?: emptyMap()
    fun audio(render: MediaRender): WebJukeboxAudio {
        val s=JukeboxSnapshot.of(render) ?: JukeboxSnapshot(render.tileEntity.blockPos,UUID(0,0),UUID(0,0),0,false,false,"",0.0)
        return sources.getOrPut(s.session) { JukeboxAudioSource(s) }.audio
    }
    @SubscribeEvent fun tick(event: ClientTickEvent.Post) {
        val mc=Minecraft.getInstance();val p=mc.player
        endpoints.entries.removeIf { it.value.tile.isRemoved || it.value.tile.level!==mc.level }
        if(p==null) { sources.values.forEach { it.close() };sources.clear();endpoints.clear();return }
        val grouped=endpoints.values.groupBy { it.snapshot.session }
        sources.keys.filter { it !in grouped }.toList().forEach { sources.remove(it)?.close() }
        for((id,all)in grouped) {
            val best=all.maxWith(compareBy<Endpoint> { it.snapshot.revision }.thenBy { it.tile.blockPos==it.snapshot.pos })
            val source=sources.getOrPut(id) { JukeboxAudioSource(best.snapshot) };source.snapshot=best.snapshot
            source.endpoints.clear()
            all.filter { it.snapshot.revision==best.snapshot.revision && it.snapshot.active && it.gain>0 && p.distanceToSqr(it.tile.blockPos.center)<=1024 }.forEach { e->source.endpoints[e.tile.blockPos]=(best.snapshot.volume*e.gain).coerceIn(0.0,1.0).toFloat() }
            source.active=best.snapshot.active && best.snapshot.volume>0 && source.endpoints.isNotEmpty() && mc.options.getSoundSourceVolume(SoundSource.MASTER)>0 && mc.options.getSoundSourceVolume(SoundSource.RECORDS)>0
            if(best.snapshot.browser) {
                source.audio.close()
                val stamp="${best.snapshot.epoch}/${best.snapshot.revision}"
                if(source.browserStamp!=stamp || !source.active) { val resume=source.active;source.close();source.browserStamp=stamp;source.audioSequence=-1;source.active=resume }
                source.browserSounds.entries.removeIf { (pos,s)->if(pos !in source.endpoints) { mc.soundManager.stop(s);s.pcm.close();true } else false }
                if(source.active && System.nanoTime()>=source.nextObserve) {
                    source.nextObserve=System.nanoTime()+1_000_000_000L
                    PacketDistributor.sendToServer(TelevisionCommand(best.snapshot.pos,id,best.snapshot.revision,TelevisionCommand.OBSERVE,endpoint=source.endpoints.keys.first()))
                }
            } else {
                source.browserSounds.values.forEach { mc.soundManager.stop(it);it.pcm.close() };source.browserSounds.clear();source.audio.update()
            }
        }
    }
    fun receive(packet: TelevisionStream) {
        if(packet.kind!=TelevisionStream.AUDIO || packet.parts!=1 || packet.part!=0 || packet.bytes.size !in 4..8192 || packet.bytes.size%4!=0)return
        val source=sources[packet.session] ?: return;val s=source.snapshot
        if(!source.active || !s.browser || packet.pos!=s.pos || packet.revision!=s.revision || packet.epoch!=s.epoch || packet.sequence<=source.audioSequence)return
        source.audioSequence=packet.sequence
        val mc=Minecraft.getInstance()
        for((pos,gain)in source.endpoints) {
            var sound=source.browserSounds[pos]
            if(sound==null || sound.pcm.isClosed || !mc.soundManager.isActive(sound) && sound.age>40) {
                sound?.let { mc.soundManager.stop(it);it.pcm.close() }
                sound=SpeakerPcmSound(source,pos);source.browserSounds[pos]=sound;mc.soundManager.play(sound)
            }
            sound.pcm.offer(packet.bytes)
        }
    }
}
class SpeakerPcmSound(private val bus: JukeboxAudioSource,private val at: BlockPos): AbstractTickableSoundInstance(SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("eamedia","jukebox_speaker")),SoundSource.RECORDS,SoundInstance.createUnseededRandom()) {
    val pcm=TelevisionPcmQueue()
    private val mono=WebJukeboxAudio.LocalMonoStream(pcm,{},{})
    var age=0;private set
    init { x=at.x+.5;y=at.y+.5;z=at.z+.5;volume=bus.endpoints[at] ?: 0f }
    override fun resolve(manager: SoundManager): WeighedSoundEvents { sound=Sound(location,ConstantFloat.of(1f),ConstantFloat.of(1f),1,Sound.Type.FILE,true,false,32);return WeighedSoundEvents(location,null).also { it.addSound(sound) } }
    override fun getStream(library: SoundBufferLibrary,sound: Sound,looping: Boolean): CompletableFuture<AudioStream> = CompletableFuture.completedFuture(mono)
    override fun tick() { age++;volume=bus.endpoints[at] ?: 0f;if(!bus.active || at !in bus.endpoints) { pcm.close();stop() } }
}

/** One decoder with independent playback cursors. A slow/late speaker cannot steal another's PCM. */
internal class PcmFanout(private val delegate: AudioStream,private val capacity: Int=352800): AutoCloseable {
    private val ring=ByteArray(capacity)
    private var written=0L;private var eof=false;private var closed=false
    override fun close()=synchronized(this) { if(!closed) { closed=true;delegate.close() };Unit }
    fun reader(): AudioStream=synchronized(this) {
        object: AudioStream {
            private var cursor=written
            private var stopped=false
            override fun getFormat()=delegate.format
            override fun close() { stopped=true }
            override fun read(size: Int): ByteBuffer=synchronized(this@PcmFanout) {
                if(closed || stopped)return@synchronized BufferUtils.createByteBuffer(0)
                val count=minOf(size,capacity/2).let { it-it%2 }
                cursor=maxOf(cursor,written-capacity)
                while(!eof && cursor+count>written) {
                    val bytes=delegate.read(minOf(count,capacity/2))
                    if(!bytes.hasRemaining()) { eof=true;delegate.close();break }
                    while(bytes.hasRemaining()) { ring[(written%capacity).toInt()]=bytes.get();written++ }
                }
                cursor=maxOf(cursor,written-capacity)
                val result=BufferUtils.createByteBuffer(minOf(count.toLong(),written-cursor).toInt())
                while(result.hasRemaining()) { result.put(ring[(cursor%capacity).toInt()]);cursor++ }
                result.flip();result
            }
        }
    }
}
