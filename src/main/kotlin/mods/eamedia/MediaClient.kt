package mods.eamedia

import mods.eln.Eln
import mods.eln.client.gl.GL11
import mods.eln.client.itemrender.IItemRenderer
import mods.eln.client.terrain.BakedNodeRenderer
import mods.eln.client.terrain.TerrainModels
import mods.eln.i18n.I18N.tr
import mods.eln.misc.*
import mods.eln.node.transparent.*
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import java.io.DataInputStream

class MediaDescriptor(val kind: MediaKind): TransparentNodeDescriptor(kind.title,MediaElement::class.java,MediaRender::class.java) {
    // ELN's model folder only scans the core mod file, not addon jars. Own this
    // model here so both terrain bodies and item previews receive real geometry.
    internal val model by lazy {
        Obj3D().also {
            check(it.loadFile("${kind.model}/${kind.model}.obj")) { "Could not load EA Media model ${kind.model}" }
            check(it.getPart("Case")!=null && it.getPart("Base")!=null) { "Incomplete EA Media model ${kind.model}" }
        }
    }
    init { creativeTab=Eln.creativeTabMachines }
    override fun mustHaveFloor()=false
    fun draw(front: Direction,on: Boolean)=preserveMatrix {
        front.inverse.glRotateZnRef();GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D)
            fun part(name: String,r: Float,g: Float,b: Float) { GL11.glColor3f(r,g,b);model.getPart(name)?.drawNoBind() }
            part("Base",.18f,.20f,.22f);part("Case",.58f,.62f,.65f);part("Trim",.12f,.14f,.16f);part("Screen",.025f,.10f,.12f)
            part("Input",.13f,.38f,.8f);part("Output",.96f,.48f,.07f);part("Terminal",.66f,.46f,.20f);part("Red",.74f,.06f,.04f)
            if(on)UtilsClient.disableLight()
            part("Indicator",.06f,if(on).8f else .14f,.06f)
            if(on)UtilsClient.enableLight()
        } finally { GL11.glPopAttrib() }
    }
    override fun handleRenderType(item: ItemStack,type: IItemRenderer.ItemRenderType)=true
    override fun shouldUseRenderHelper(type: IItemRenderer.ItemRenderType,item: ItemStack,helper: IItemRenderer.ItemRendererHelper)=true
    override fun renderItem(type: IItemRenderer.ItemRenderType,item: ItemStack,vararg data: Any)=draw(Direction.ZN,false)
    override fun addInformation(stack: ItemStack?,player: Player?,list: MutableList<String>,advanced: Boolean) {
        list.add(tr("Blue left or bottom: power input; 120/240 V cords or fixed cables"))
        list.add(if(kind==MediaKind.TV)tr("Interactive Chromium TV: webpages, video and stereo audio") else tr("Powered HTTPS / YouTube jukebox with bundled media tools"))
    }
}

class MediaRender(entity: TransparentNodeEntity,descriptor: TransparentNodeDescriptor): TransparentNodeElementRender(entity,descriptor),BakedNodeRenderer {
    val kind=(descriptor as MediaDescriptor).kind
    var settings=kind.defaults;var enabled=true;var trip=false;var working=false
    val stats=DoubleArray(2)
    var mediaUrl="";var mediaRequested=false;var mediaRevision=0
    var tvSession: java.util.UUID?=null;var tvController: java.util.UUID?=null;var tvInteractive=false
    var tvHost: java.util.UUID?=null;var tvHostEpoch=java.util.UUID(0,0)
    var tvPlayback=TelevisionPlayback();var tvSampleNanos=0L;var tvBack=false;var tvForward=false
    var tvServerStatus="OFF"
    val webAudio by lazy { WebJukeboxAudio(this,tileEntity) }
    val television by lazy { TelevisionBrowser(this) }
    override fun refresh(deltaT: Float) { super.refresh(deltaT);if(kind==MediaKind.TV)television.update() else webAudio.update() }
    override fun destructor() { if(kind==MediaKind.TV)television.close() else webAudio.close();super.destructor() }
    override fun draw() {
        TerrainModels.body(this,listOf(front),setOf("Indicator")) { (transparentNodeDescriptor as MediaDescriptor).draw(front ?: Direction.ZP,working) }
        if(kind==MediaKind.TV && !TerrainModels.isDiscovering)television.drawWorld()
    }
    override fun networkUnserialize(input: DataInputStream) {
        super.networkUnserialize(input);settings.indices.forEach { settings[it]=input.readDouble() };enabled=input.readBoolean();trip=input.readBoolean();working=input.readBoolean()
        stats.indices.forEach { stats[it]=input.readDouble() };mediaUrl=input.readUTF();mediaRequested=input.readBoolean();mediaRevision=input.readInt()
        if(kind==MediaKind.TV) {
            tvSession=java.util.UUID.fromString(input.readUTF());tvController=input.readUTF().takeIf { it.isNotBlank() }?.let(java.util.UUID::fromString);tvInteractive=input.readBoolean()
            tvHost=input.readUTF().takeIf { it.isNotBlank() }?.let(java.util.UUID::fromString);tvHostEpoch=java.util.UUID.fromString(input.readUTF())
            tvPlayback=TelevisionPlayback(input.readDouble(),input.readBoolean(),input.readDouble(),input.readDouble(),input.readBoolean(),input.readBoolean());tvSampleNanos=System.nanoTime()
            tvBack=input.readBoolean();tvForward=input.readBoolean()
            tvServerStatus=input.readUTF()
        }
    }
    override fun newGuiDraw(side: Direction,player: Player)=if(kind==MediaKind.TV)TelevisionScreen(this) else JukeboxScreen(this)
}
