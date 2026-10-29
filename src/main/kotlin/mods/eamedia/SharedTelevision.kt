package mods.eamedia

import net.minecraft.nbt.CompoundTag
import java.util.UUID

/** Server-owned clock for a placed TV. Clients decode video; they never choose a private timeline. */
data class TelevisionPlayback(val position: Double=0.0,val paused: Boolean=false,val rate: Double=1.0,
                              val duration: Double=0.0,val live: Boolean=false,val known: Boolean=false) {
    fun valid()=position.isFinite() && position in 0.0..MAX_SECONDS && rate.isFinite() && rate in .25..4.0 &&
        duration.isFinite() && duration in 0.0..MAX_SECONDS
    companion object { const val MAX_SECONDS=31_536_000.0 }
}

/** Client hosting is the default; server rendering is an explicit server administrator option. */
object TelevisionHosting {
    val serverHosted get()=System.getProperty("eamedia.tvHosting","client").equals("server",ignoreCase=true)
}

class SharedTelevision(val serverHosted: Boolean=TelevisionHosting.serverHosted,private val clock: ()->Long={ System.nanoTime()/1_000_000 }) {
    /** Replaced on every element load, preventing delayed controls from reaching a replacement TV. */
    val session=UUID.randomUUID()
    private var playback=TelevisionPlayback()
    private var sampled=clock()
    private var running=false
    private var owner: UUID?=null
    private var ownerUntil=0L
    private var serverOnline=false
    private var hostId: UUID?=null
    private var hostUntil=0L
    var hostEpoch=UUID.randomUUID();private set
    private val viewers=LinkedHashMap<UUID,Long>()
    private var lastReport=Long.MIN_VALUE
    private var sequence=-1L
    val controller get()=owner.takeIf { clock()<ownerUntil }
    val host get()=if(serverHosted) { if(serverOnline)SERVER_HOST else null } else hostId.takeIf { clock()<hostUntil }
    val interactive get()=controller!=null
    val activeViewers get()=viewers.filterValues { clock()<it }.keys
    fun snapshot(): TelevisionPlayback {
        val elapsed=if(running && !playback.paused && playback.known)(clock()-sampled).coerceAtLeast(0)/1000.0*playback.rate else 0.0
        var position=(playback.position+elapsed).coerceAtMost(TelevisionPlayback.MAX_SECONDS)
        if(!playback.live && playback.duration>0)position=position.coerceAtMost(playback.duration)
        return playback.copy(position=position)
    }
    fun running(value: Boolean) {
        if(running!=value) { playback=snapshot();sampled=clock();running=value }
    }
    fun navigate() { playback=TelevisionPlayback();sampled=clock();lastReport=Long.MIN_VALUE;sequence=-1 }
    fun claim(player: UUID,interactive: Boolean): Boolean {
        val now=clock()
        if(!interactive) {
            viewers.entries.removeIf { it.value<=now };viewers[player]=now+LEASE_MILLIS
            if(!serverHosted) {
                if(host==null) { hostId=player;hostUntil=now+HOST_LEASE_MILLIS;hostEpoch=UUID.randomUUID();lastReport=Long.MIN_VALUE;sequence=-1 }
                else if(hostId==player)hostUntil=now+HOST_LEASE_MILLIS
            }
            return true
        }
        if(controller!=null && controller!=player)return false
        owner=player;ownerUntil=now+LEASE_MILLIS
        return true
    }
    fun release(player: UUID) { if(controller==player)ownerUntil=0 }
    fun leave(player: UUID) { viewers.remove(player);release(player);if(!serverHosted && host==player)hostUntil=0 }
    fun startServerHost(){if(!serverHosted)return;serverOnline=true;hostEpoch=UUID.randomUUID();lastReport=Long.MIN_VALUE;sequence=-1}
    fun stopServerHost(){serverOnline=false}
    fun report(player: UUID,next: TelevisionPlayback,nextSequence: Long): Boolean {
        val now=clock()
        if(host!=player || !next.known || !next.valid() || nextSequence<=sequence)return false
        // Browser callbacks are untrusted. Bound updates even if a page floods its JS bridge.
        if(lastReport!=Long.MIN_VALUE && now-lastReport<MIN_REPORT_MILLIS)return false
        playback=next;sampled=now;lastReport=now;sequence=nextSequence
        return true
    }
    fun write(tag: CompoundTag) {
        val s=snapshot();val data=CompoundTag()
        data.putDouble("position",s.position);data.putBoolean("paused",s.paused);data.putDouble("rate",s.rate)
        data.putDouble("duration",s.duration);data.putBoolean("live",s.live);data.putBoolean("known",s.known)
        tag.put("televisionPlayback",data)
    }
    fun read(tag: CompoundTag) {
        val d=tag.getCompound("televisionPlayback")
        val state=TelevisionPlayback(d.getDouble("position"),d.getBoolean("paused"),if(d.contains("rate"))d.getDouble("rate") else 1.0,
            d.getDouble("duration"),d.getBoolean("live"),d.getBoolean("known"))
        playback=state.takeIf { it.valid() } ?: TelevisionPlayback();sampled=clock();running=false
        owner=null;ownerUntil=0;serverOnline=false;hostId=null;hostUntil=0;hostEpoch=UUID.randomUUID();viewers.clear();lastReport=Long.MIN_VALUE;sequence=-1
    }
    companion object {
        val SERVER_HOST=UUID(0,1)
        const val LEASE_MILLIS=6000L;const val HOST_LEASE_MILLIS=15000L;const val MIN_REPORT_MILLIS=100L
    }
}
