package mods.eamedia

import mods.eln.api.media.RemoteSwitchableDevice
import mods.eln.api.media.StandaloneDeviceControls
import mods.eln.i18n.I18N.tr
import mods.eln.misc.*
import mods.eln.node.NodeBase
import mods.eln.node.NodePeriodicPublishProcess
import mods.eln.node.transparent.*
import mods.eln.sim.IProcess
import mods.eln.sim.mna.component.Resistor
import mods.eln.sim.nbt.*
import mods.eln.transparentnode.cord.*
import mods.eln.transparentnode.industrial.IndustrialEnergy
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs

/** Passive electrical device; media rendering stays outside the electrical simulation. */
class MediaElement(node: TransparentNode,descriptor: TransparentNodeDescriptor): TransparentNodeElement(node,descriptor),CordDevice,RemoteSwitchableDevice,StandaloneDeviceControls {
    val kind=(descriptor as MediaDescriptor).kind
    var settings=kind.defaults;private set
    var enabled=true;private set
    var trip=false;private set
    var working=false;private set
    var mediaUrl="";private set
    var mediaRequested=false;private set
    var mediaRevision=0;private set
    var lifetimeJoules=0.0;private set
    val sharedTv=SharedTelevision()
    val mirrorRelay=TelevisionRelay()
    var tvServerStatus="OFF"
    private val history=ArrayList<String>()
    private var historyIndex=-1
    private var legacy=CompoundTag()
    val input=NbtElectricalLoad("industrialInput").apply { serialResistance=.0001 }
    val resistor=Resistor(input,null).apply { resistance=1e19 }
    val signal=NbtElectricalGateInput("industrialControl")
    val tripSignal=NbtElectricalGateOutput("industrialTrip")
    private val tripSource=NbtElectricalGateOutputProcess("industrialTrip",tripSignal)
    override val cord=ApplianceCord(this,input)
    val nominal get()=settings[0]
    override val cordVoltage get()=nominal
    override fun acceptsCord(rating: CordRating)=rating.accepts(nominal) && kind.watts/nominal<=rating.amps
    init {
        electricalLoadList.addAll(listOf(input,signal,tripSignal))
        electricalComponentList.addAll(listOf(resistor,tripSource))
        slowPreProcessList.add(IProcess { prepare() })
        electricalProcessList.add(IProcess(::account))
        slowProcessList.add(NodePeriodicPublishProcess(node,1.0,.5))
    }
    override fun initialize() { connect() }
    private fun loadedWorld(): ServerLevel? {
        val c=node?.coordinate ?: return null
        return DimensionIds.serverLevel(c.dimension)?.takeIf { it.chunkSource.getChunkNow(c.x shr 4,c.z shr 4)!=null }
    }
    internal fun prepare(loaded: Boolean=loadedWorld()!=null) {
        working=enabled && !trip && loaded && signal.normalized<.6 && mediaRequested && kind.address(mediaUrl)!=null
        var demand=if(working)kind.watts else if(enabled)2.0 else 0.0
        working=working && abs(input.voltage)>=nominal*.8
        if(!input.voltage.isFinite() || abs(input.voltage)>nominal*1.3) { working=false;demand=0.0 }
        if(kind==MediaKind.TV) { sharedTv.running(working);if(!working || sharedTv.activeViewers.isEmpty())mirrorRelay.drop() }
        resistor.resistance=IndustrialEnergy.resistance(nominal,demand)
        tripSource.state(trip)
    }
    internal fun account(dt: Double) { if(dt.isFinite() && dt>0 && resistor.power.isFinite())lifetimeJoules+=(resistor.power*dt).coerceAtLeast(0.0) }
    fun applySettings(values: DoubleArray): Boolean {
        if(!kind.valid(values) || cord.plugged && values[0]!=nominal)return false
        settings=values.copyOf();prepare();needPublish();return true
    }
    fun setEnabled(value: Boolean) { enabled=value;prepare();needPublish() }
    override fun setRemoteEnabled(enabled: Boolean)=setEnabled(enabled)
    fun playMedia(value: String)=playMedia(value,true)
    private fun playMedia(value: String,remember: Boolean): Boolean {
        val url=kind.address(value) ?: return false
        mediaUrl=url.toASCIIString();mediaRequested=true;mediaRevision++
        if(kind==MediaKind.TV) {
            sharedTv.navigate()
            if(remember && history.getOrNull(historyIndex)!=mediaUrl) {
                while(history.size>historyIndex+1)history.removeAt(history.lastIndex)
                history.add(mediaUrl);if(history.size>32)history.removeAt(0);historyIndex=history.lastIndex
            }
        }
        prepare();needPublish();return true
    }
    internal fun navigateHistory(action: Int) {
        ServerTelevisions.navigate(this,action)
    }
    internal fun browserPageChanged(url: String) {
        if(mediaUrl==url)return
        mediaUrl=url
        if(history.getOrNull(historyIndex)!=url){while(history.size>historyIndex+1)history.removeAt(history.lastIndex);history.add(url);if(history.size>32)history.removeAt(0);historyIndex=history.lastIndex}
        needPublish()
    }
    fun stopMedia() { mediaRequested=false;prepare();needPublish() }
    override fun getElectricalLoad(side: Direction,lrdu: LRDU)=when {
        side==front && lrdu==LRDU.Up->tripSignal
        side==front && lrdu==LRDU.Down->signal
        (side==front.left() || side==Direction.YN) && lrdu==LRDU.Down && !cord.plugged->input
        else->null
    }
    override fun getConnectionMask(side: Direction,lrdu: LRDU)=when(getElectricalLoad(side,lrdu)) {
        null->0;signal->NodeBase.maskElectricalInputGate;tripSignal->NodeBase.maskElectricalOutputGate;else->NodeBase.maskElectricalPower
    }
    override fun hasGui()=true
    override fun multiMeterString(side: Direction)=Utils.plotUIP(input.voltage,input.current)
    override fun getWaila()=linkedMapOf(tr("Supply") to Utils.plotVolt("",input.voltage),tr("Power") to Utils.plotPower("",resistor.power),tr("Status") to if(enabled)tr("Enabled") else tr("Disabled"))
    private fun save(tag: CompoundTag) {
        tag.merge(legacy.copy())
        settings.forEachIndexed { i,v->tag.putDouble("industrialSetting$i",v) }
        tag.putBoolean("industrialEnabled",enabled);tag.putBoolean("industrialTrip",trip);tag.putDouble("industrialLifetime",lifetimeJoules)
        tag.putString("mediaUrl",mediaUrl);tag.putBoolean("mediaRequested",mediaRequested);tag.putInt("mediaRevision",mediaRevision);cord.write(tag)
        if(kind==MediaKind.TV) {
            sharedTv.write(tag);val urls=net.minecraft.nbt.ListTag();history.forEach { urls.add(net.minecraft.nbt.StringTag.valueOf(it)) }
            tag.put("televisionHistory",urls);tag.putInt("televisionHistoryIndex",historyIndex)
        }
    }
    private fun load(tag: CompoundTag) {
        legacy=tag.copy()
        val p=DoubleArray(2) { if(tag.contains("industrialSetting$it"))tag.getDouble("industrialSetting$it") else kind.defaults[it] }
        if(kind.valid(p))settings=p
        enabled=!tag.contains("industrialEnabled") || tag.getBoolean("industrialEnabled");trip=tag.getBoolean("industrialTrip")
        lifetimeJoules=tag.getDouble("industrialLifetime").takeIf { it.isFinite() && it>=0 } ?: 0.0
        mediaUrl=tag.getString("mediaUrl").takeIf { kind.address(it)!=null } ?: "";mediaRequested=tag.getBoolean("mediaRequested");mediaRevision=tag.getInt("mediaRevision")
        if(kind==MediaKind.TV) {
            sharedTv.read(tag);history.clear()
            val urls=tag.getList("televisionHistory",8);for(i in 0 until minOf(32,urls.size))TelevisionUrl.parse(urls.getString(i))?.toASCIIString()?.let(history::add)
            historyIndex=tag.getInt("televisionHistoryIndex").coerceIn(-1,history.lastIndex)
            if(history.isEmpty() && mediaUrl.isNotEmpty()) { history.add(mediaUrl);historyIndex=0 }
        }
        cord.read(tag);prepare()
    }
    override fun readFromNBT(tag: CompoundTag) { super.readFromNBT(tag);load(tag) }
    override fun writeToNBT(tag: CompoundTag) { save(tag);super.writeToNBT(tag) }
    override fun readItemStackNBT(tag: CompoundTag?) { if(tag!=null)load(tag) }
    override fun getItemStackNBT()=CompoundTag().also(::save).apply { remove("applianceCord") }
    override fun networkSerialize(out: DataOutputStream) {
        super.networkSerialize(out);settings.forEach(out::writeDouble);out.writeBoolean(enabled);out.writeBoolean(trip);out.writeBoolean(working)
        out.writeDouble(input.voltage);out.writeDouble(resistor.power);out.writeUTF(mediaUrl);out.writeBoolean(mediaRequested);out.writeInt(mediaRevision)
        if(kind==MediaKind.TV) {
            out.writeUTF(sharedTv.session.toString());out.writeUTF(sharedTv.controller?.toString() ?: "");out.writeBoolean(sharedTv.interactive)
            out.writeUTF(sharedTv.host?.toString() ?: "");out.writeUTF(sharedTv.hostEpoch.toString())
            val s=sharedTv.snapshot();out.writeDouble(s.position);out.writeBoolean(s.paused);out.writeDouble(s.rate);out.writeDouble(s.duration);out.writeBoolean(s.live);out.writeBoolean(s.known)
            out.writeBoolean(historyIndex>0);out.writeBoolean(historyIndex>=0 && historyIndex<history.lastIndex)
            out.writeUTF(tvServerStatus);out.writeBoolean(sharedTv.serverHosted)
        }
    }
    override fun networkUnserialize(input: DataInputStream): Byte {
        val id=super.networkUnserialize(input)
        when(id.toInt()) {
            80->applySettings(doubleArrayOf(input.readDouble(),input.readDouble()))
            81->setEnabled(input.readBoolean())
            82->{ trip=false;prepare();needPublish() }
            86->{ val url=input.readUTF();if(kind!=MediaKind.TV)playMedia(url) }
            87->stopMedia()
        }
        return id
    }
}
