package mods.eamedia

import mods.eln.Eln
import mods.eln.api.media.ReservedMediaDevice
import mods.eln.api.media.StandaloneDeviceControls
import mods.eln.client.gl.GL11
import mods.eln.client.itemrender.IItemRenderer
import mods.eln.client.terrain.TerrainModels
import mods.eln.i18n.I18N.tr
import mods.eln.misc.*
import mods.eln.node.transparent.*
import mods.eln.sim.IProcess
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.ref.WeakReference
import java.util.UUID

/** Sources and endpoints are loaded objects only. Resolving a channel never loads a chunk. */
object SpeakerLinks {
    private val sources=java.util.Collections.newSetFromMap(java.util.WeakHashMap<MediaElement,Boolean>())
    fun register(source: MediaElement) { if(source.kind==MediaKind.JUKEBOX)sources.add(source) }
    private fun live(source: MediaElement): Boolean {
        val w=source.loadedWorld() ?: return false;val c=source.node?.coordinate ?: return false
        return ((w.getBlockEntity(BlockPos(c.x,c.y,c.z)) as? TransparentNodeEntity)?.node as? TransparentNode)?.element===source
    }
    fun source(speaker: SpeakerElement): MediaElement? {
        val c=speaker.node?.coordinate ?: return null
        return sources.filter { it.speakerChannel==speaker.channel && live(it) && it.node?.coordinate?.dimension==c.dimension && distance(c,it.node!!.coordinate)<=64.0*64 }.minWithOrNull(compareBy<MediaElement> { distance(c,it.node!!.coordinate) }.thenBy { it.sharedTv.session })
    }
    private fun distance(a: Coordinate,b: Coordinate)=(a.x-b.x).toDouble().let { it*it }+(a.y-b.y).toDouble().let { it*it }+(a.z-b.z).toDouble().let { it*it }
    fun canHear(player: ServerPlayer,source: MediaElement,endpoint: BlockPos?): Boolean {
        val c=source.node?.coordinate ?: return false;val at=endpoint ?: BlockPos(c.x,c.y,c.z)
        if(!live(source) || source.loadedWorld()!==player.level() || player.distanceToSqr(at.x+.5,at.y+.5,at.z+.5)>1024)return false
        if(at==BlockPos(c.x,c.y,c.z))return true
        val w=player.serverLevel();if(w.chunkSource.getChunkNow(at.x shr 4,at.z shr 4)==null)return false
        val s=((w.getBlockEntity(at) as? TransparentNodeEntity)?.node as? TransparentNode)?.element as? SpeakerElement ?: return false
        return s.volume>0 && source(s)===source
    }
}

data class JukeboxSnapshot(val pos: BlockPos,val session: UUID,val epoch: UUID,val revision: Int,val active: Boolean,val browser: Boolean,val url: String,val volume: Double) {
    fun write(out: DataOutputStream) { out.writeLong(pos.asLong());out.writeUTF(session.toString());out.writeUTF(epoch.toString());out.writeInt(revision);out.writeBoolean(active);out.writeBoolean(browser);out.writeUTF(if(browser)"" else url);out.writeDouble(volume) }
    companion object {
        fun read(input: DataInputStream)=JukeboxSnapshot(BlockPos.of(input.readLong()),UUID.fromString(input.readUTF()),UUID.fromString(input.readUTF()),input.readInt(),input.readBoolean(),input.readBoolean(),input.readUTF(),input.readDouble().coerceIn(0.0,1.0))
        fun of(source: MediaElement): JukeboxSnapshot { val c=source.node!!.coordinate;return JukeboxSnapshot(BlockPos(c.x,c.y,c.z),source.sharedTv.session,source.sharedTv.hostEpoch,source.mediaRevision,source.working && source.mediaRequested,source.browserMode,if(source.browserMode)"" else source.mediaUrl,source.settings[1]) }
        fun of(source: MediaRender)=source.tvSession?.let { JukeboxSnapshot(source.tileEntity.blockPos,it,source.tvHostEpoch,source.mediaRevision,source.working && source.mediaRequested,source.browserMode,if(source.browserMode)"" else source.mediaUrl,source.settings[1]) }
    }
}
class SpeakerElement(node: TransparentNode,descriptor: TransparentNodeDescriptor): TransparentNodeElement(node,descriptor),StandaloneDeviceControls {
    override val inventory=TransparentNodeElementInventory(0,1,this)
    var channel=1;private set
    var volume=1.0;private set
    var linked: JukeboxSnapshot?=null;private set
    private var timer=0.0
    init { slowProcessList.add(IProcess { dt->timer+=dt;if(timer>=.5) { timer=0.0;val next=SpeakerLinks.source(this)?.let(JukeboxSnapshot::of);if(next!=linked) { linked=next;needPublish() } } }) }
    override fun initialize() { connect() }
    override fun hasGui()=true
    override fun getWaila()=linkedMapOf(tr("Channel") to channel.toString(),tr("Status") to if(linked?.active==true)tr("Playing") else tr("Waiting for linked jukebox"))
    override fun writeToNBT(tag: CompoundTag) { super.writeToNBT(tag);tag.putInt("speakerChannel",channel);tag.putDouble("speakerVolume",volume) }
    override fun readFromNBT(tag: CompoundTag) { super.readFromNBT(tag);load(tag) }
    override fun readItemStackNBT(tag: CompoundTag?) { if(tag!=null)load(tag) }
    override fun getItemStackNBT()=CompoundTag().apply { putInt("speakerChannel",channel);putDouble("speakerVolume",volume) }
    private fun load(tag: CompoundTag) { channel=tag.getInt("speakerChannel").takeIf { it in 1..9999 } ?: 1;volume=tag.getDouble("speakerVolume").takeIf { tag.contains("speakerVolume") && it.isFinite() }?.coerceIn(0.0,1.0) ?: 1.0 }
    override fun networkSerialize(out: DataOutputStream) { super.networkSerialize(out);out.writeInt(channel);out.writeDouble(volume);out.writeBoolean(linked!=null);linked?.write(out) }
    override fun networkUnserialize(input: DataInputStream): Byte {
        val id=super.networkUnserialize(input)
        if(id.toInt()==80) { val n=input.readInt();if(n in 1..9999)channel=n }
        if(id.toInt()==81) { val n=input.readDouble();if(n.isFinite())volume=n.coerceIn(0.0,1.0) }
        linked=SpeakerLinks.source(this)?.let(JukeboxSnapshot::of);needPublish();return id
    }
}
class SpeakerDescriptor(val ceiling: Boolean): TransparentNodeDescriptor((if(ceiling)ReservedMediaDevice.CEILING_SPEAKER else ReservedMediaDevice.FLOOR_SPEAKER).title,SpeakerElement::class.java,SpeakerRender::class.java) {
    val modelName=if(ceiling)"jukebox_ceiling_speaker" else "jukebox_standing_speaker"
    private val model by lazy { Obj3D().also { check(it.loadFile("$modelName/$modelName.obj")) } }
    init { creativeTab=Eln.creativeTabMachines }
    override fun mustHaveFloor()=!ceiling
    override fun mustHaveCeiling()=ceiling
    override fun addCollisionBoxesToList(box: AABB,list: MutableList<AABB?>,world: net.minecraft.world.level.Level?,x: Int,y: Int,z: Int) {
        val bounds=(if(ceiling)AABB(.15,.85,.15,.85,1.0,.85) else AABB(.22,0.0,.22,.78,1.0,.78)).move(x.toDouble(),y.toDouble(),z.toDouble())
        val shifted=bounds.move(world?.let { mountingOffset(it,BlockPos(x,y,z),Direction.ZP) } ?: net.minecraft.world.phys.Vec3.ZERO)
        if(box.intersects(shifted))list.add(shifted)
    }
    fun draw(front: Direction,active: Boolean)=preserveMatrix {
        front.inverse.glRotateZnRef();GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D)
            fun p(n: String,r: Float,g: Float,b: Float) { GL11.glColor3f(r,g,b);model.getPart(n)?.drawNoBind() }
            p("Case",if(ceiling).86f else .12f,if(ceiling).87f else .14f,if(ceiling).89f else .17f);p("Grille",.26f,.29f,.32f);p("Cone",.06f,.07f,.08f);p("Trim",.54f,.59f,.64f)
            if(active)UtilsClient.disableLight();p("Indicator",.1f,if(active).85f else .2f,.4f);if(active)UtilsClient.enableLight()
        } finally { GL11.glPopAttrib() }
    }
    override fun handleRenderType(item: ItemStack,type: IItemRenderer.ItemRenderType)=true
    override fun shouldUseRenderHelper(type: IItemRenderer.ItemRenderType,item: ItemStack,helper: IItemRenderer.ItemRendererHelper)=true
    override fun renderItem(type: IItemRenderer.ItemRenderType,item: ItemStack,vararg data: Any)=draw(Direction.ZN,false)
    override fun addInformation(stack: ItemStack?,player: Player?,list: MutableList<String>,advanced: Boolean) {
        list.add(tr("Match the jukebox's channel; nearest loaded source within 64 blocks"))
        list.add(tr("Passive audio endpoint: no separate electrical connection; Records volume slider"))
    }
}
class SpeakerRender(entity: TransparentNodeEntity,descriptor: TransparentNodeDescriptor): TransparentNodeElementRender(entity,descriptor),mods.eln.client.terrain.BakedNodeRenderer {
    override val inventory=TransparentNodeElementInventory(0,1,this)
    var channel=1;var volume=1.0;var linked: JukeboxSnapshot?=null
    override fun networkUnserialize(input: DataInputStream) { super.networkUnserialize(input);channel=input.readInt();volume=input.readDouble();linked=if(input.readBoolean())JukeboxSnapshot.read(input) else null }
    override fun refresh(deltaT: Float) { super.refresh(deltaT);linked?.let { JukeboxAudioClient.register(this,tileEntity,it,volume) } ?: JukeboxAudioClient.remove(this) }
    override fun destructor() { JukeboxAudioClient.remove(this);super.destructor() }
    override fun draw()=TerrainModels.body(this,listOf(front),setOf("Indicator")) { (transparentNodeDescriptor as SpeakerDescriptor).draw(front ?: Direction.ZP,linked?.active==true && volume>0) }
    override fun newGuiDraw(side: Direction,player: Player)=SpeakerScreen(this)
}
class SpeakerScreen(private val speaker: SpeakerRender): net.minecraft.client.gui.screens.Screen(Component.literal(tr("Jukebox speaker"))) {
    private lateinit var channel: net.minecraft.client.gui.components.EditBox
    override fun isPauseScreen()=false
    override fun init() {
        val x=width/2-120;val y=height/2-65
        channel=addRenderableWidget(net.minecraft.client.gui.components.EditBox(font,x+10,y+30,155,20,Component.literal(tr("Channel"))).apply { setMaxLength(4);value=speaker.channel.toString() })
        fun button(bx: Int,by: Int,w: Int,text: String,f: ()->Unit)=addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal(text)) { f() }.bounds(x+bx,y+by,w,20).build())
        button(170,30,60,tr("Apply")) { channel.value.toIntOrNull()?.takeIf { it in 1..9999 }?.let { speaker.clientSendInt(80,it) } }
        button(10,60,100,tr("Volume -")) { volume((speaker.volume-.1).coerceAtLeast(0.0)) };button(120,60,110,tr("Volume +")) { volume((speaker.volume+.1).coerceAtMost(1.0)) }
    }
    private fun volume(value: Double) { val b=java.io.ByteArrayOutputStream();val o=DataOutputStream(b);speaker.preparePacketForServer(o);o.writeByte(81);o.writeDouble(value);speaker.sendPacketToServer(b) }
    override fun tick() { val p=minecraft?.player;if(p==null || speaker.tileEntity.isRemoved || p.distanceToSqr(speaker.tileEntity.blockPos.center)>64)onClose() }
    override fun render(g: net.minecraft.client.gui.GuiGraphics,x: Int,y: Int,partial: Float) {
        val l=width/2-120;val t=height/2-65;g.fill(l,t,l+240,t+130,0xff202d38.toInt());g.drawString(font,title,l+10,t+10,-1,false)
        val s=speaker.linked;g.drawString(font,if(s?.active==true)tr("Playing; volume %1$%",(speaker.volume*100).toInt()) else tr("Waiting for linked jukebox"),l+10,t+90,-1,false)
        super.render(g,x,y,partial)
    }
}
