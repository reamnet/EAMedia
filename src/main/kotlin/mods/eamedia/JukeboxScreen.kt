package mods.eamedia

import mods.eln.i18n.I18N.tr
import mods.eln.misc.Utils
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class JukeboxScreen(private val render: MediaRender): Screen(Component.literal(tr("Powered Web Jukebox"))) {
    private lateinit var address: EditBox
    private lateinit var voltage: Button
    private lateinit var enabled: Button
    private var error=false
    private var left=0;private var top=0
    override fun isPauseScreen()=false
    override fun renderBackground(g: GuiGraphics,x: Int,y: Int,partial: Float) { }
    override fun init() {
        left=(width-304)/2;top=(height-174)/2
        fun button(x: Int,y: Int,w: Int,label: String,action: ()->Unit)=addRenderableWidget(Button.builder(Component.literal(label)) { action() }.bounds(left+x,top+y,w,20).build())
        address=addRenderableWidget(EditBox(font,left+8,top+32,288,20,Component.literal(tr("HTTPS audio or YouTube address"))).apply { setMaxLength(2048);value=render.mediaUrl })
        button(8,58,140,tr("Play / restart")) { play() };button(156,58,140,tr("Stop")) { render.clientSendId(87) }
        voltage=button(8,84,68,"") { settings(if(render.settings[0]==120.0)240.0 else 120.0,render.settings[1]) }
        button(82,84,24,"-") { settings(render.settings[0],(render.settings[1]-.1).coerceAtLeast(0.0)) }
        button(112,84,24,"+") { settings(render.settings[0],(render.settings[1]+.1).coerceAtMost(1.0)) }
        enabled=button(142,84,78,"") { render.clientSendBoolean(81,!render.enabled) }
        button(226,84,70,tr("Reset trip")) { render.clientSendId(82) }
    }
    private fun settings(volts: Double,volume: Double) {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes);render.preparePacketForServer(out);out.writeByte(80);out.writeDouble(volts);out.writeDouble(volume);render.sendPacketToServer(bytes)
    }
    private fun play() { val url=WebAudioUrl.parse(address.value);error=url==null;if(url!=null)render.clientSendString(86,url.toASCIIString()) }
    override fun keyPressed(key: Int,scan: Int,mods: Int): Boolean {
        if(key==GLFW.GLFW_KEY_ENTER && address.isFocused) { play();return true }
        return super.keyPressed(key,scan,mods)
    }
    override fun tick() {
        val p=minecraft?.player
        if(p==null || render.tileEntity.isRemoved || p.distanceToSqr(render.tileEntity.blockPos.x+.5,render.tileEntity.blockPos.y+.5,render.tileEntity.blockPos.z+.5)>64)onClose()
    }
    override fun render(g: GuiGraphics,x: Int,y: Int,partial: Float) {
        g.fill(0,0,width,height,0xc014171c.toInt());g.fill(left,top,left+304,top+174,0xff242a33.toInt())
        g.drawString(font,title,left+8,top+10,-1,false)
        voltage.message=Component.literal(Utils.plotVolt("",render.settings[0]).trim());voltage.active=render.cord.endpoint==null
        enabled.message=Component.literal(if(render.enabled)tr("Disable") else tr("Enable"))
        val status=if(error)tr("Use a complete HTTPS audio or YouTube address") else render.webAudio.status()
        font.split(Component.literal(status),288).take(2).forEachIndexed { i,line->g.drawString(font,line,left+8,top+114+i*11,-1,false) }
        g.drawString(font,tr("Supply: %1$; %2$; volume %3$ percent",Utils.plotVolt("",render.stats[0]).trim(),Utils.plotPower("",render.stats[1]).trim(),(render.settings[1]*100).toInt()),left+8,top+144,0xffcccccc.toInt(),false)
        super.render(g,x,y,partial)
    }
}
