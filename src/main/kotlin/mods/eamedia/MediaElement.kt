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

/** Passive electrical device. Media I/O is entirely in the addon's client renderer. */
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
    fun playMedia(value: String): Boolean {
        val url=kind.address(value) ?: return false
        mediaUrl=url.toASCIIString();mediaRequested=true;mediaRevision++;prepare();needPublish();return true
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
    }
    private fun load(tag: CompoundTag) {
        legacy=tag.copy()
        val p=DoubleArray(2) { if(tag.contains("industrialSetting$it"))tag.getDouble("industrialSetting$it") else kind.defaults[it] }
        if(kind.valid(p))settings=p
        enabled=!tag.contains("industrialEnabled") || tag.getBoolean("industrialEnabled");trip=tag.getBoolean("industrialTrip")
        lifetimeJoules=tag.getDouble("industrialLifetime").takeIf { it.isFinite() && it>=0 } ?: 0.0
        mediaUrl=tag.getString("mediaUrl").takeIf { kind.address(it)!=null } ?: "";mediaRequested=tag.getBoolean("mediaRequested");mediaRevision=tag.getInt("mediaRevision")
        cord.read(tag);prepare()
    }
    override fun readFromNBT(tag: CompoundTag) { super.readFromNBT(tag);load(tag) }
    override fun writeToNBT(tag: CompoundTag) { save(tag);super.writeToNBT(tag) }
    override fun readItemStackNBT(tag: CompoundTag?) { if(tag!=null)load(tag) }
    override fun getItemStackNBT()=CompoundTag().also(::save).apply { remove("applianceCord") }
    override fun networkSerialize(out: DataOutputStream) {
        super.networkSerialize(out);settings.forEach(out::writeDouble);out.writeBoolean(enabled);out.writeBoolean(trip);out.writeBoolean(working)
        out.writeDouble(input.voltage);out.writeDouble(resistor.power);out.writeUTF(mediaUrl);out.writeBoolean(mediaRequested);out.writeInt(mediaRevision)
    }
    override fun networkUnserialize(input: DataInputStream): Byte {
        val id=super.networkUnserialize(input)
        when(id.toInt()) {
            80->applySettings(doubleArrayOf(input.readDouble(),input.readDouble()))
            81->setEnabled(input.readBoolean())
            82->{ trip=false;prepare();needPublish() }
            86->playMedia(input.readUTF())
            87->stopMedia()
        }
        return id
    }
}
