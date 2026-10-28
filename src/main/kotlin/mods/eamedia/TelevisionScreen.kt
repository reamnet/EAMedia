package mods.eamedia

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import mods.eln.i18n.I18N.tr
import mods.eln.misc.Utils
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.min

/** The same browser texture is interactive here and continues playing on the placed TV. */
class TelevisionScreen(private val render: MediaRender): Screen(Component.literal(tr("Powered Browser Television"))) {
    private val tv get()=render.television
    private lateinit var address: EditBox
    private lateinit var power: Button
    private lateinit var voltage: Button
    private var pageFocus=false
    private var pictureX=0;private var pictureY=36;private var pictureW=0;private var pictureH=0
    private var error=false
    private var nextClaim=0L
    private lateinit var takeControl: Button
    private val pressed=HashSet<Int>()
    override fun isPauseScreen()=false
    // Screen.render calls this after our video quad. The TV draws its own opaque background;
    // vanilla's backdrop blur must not blur the browser picture or its status text.
    override fun renderBackground(g: GuiGraphics,x: Int,y: Int,partial: Float) { }
    override fun init() {
        fun button(x: Int,y: Int,w: Int,text: String,action: ()->Unit)=addRenderableWidget(Button.builder(Component.literal(text)) { action() }.bounds(x,y,w,20).build())
        button(8,8,24,"<") { tv.command(TelevisionCommand.BACK) };button(36,8,24,">") { tv.command(TelevisionCommand.FORWARD) }
        button(64,8,50,tr("Reload")) { tv.command(TelevisionCommand.RELOAD) }
        address=addRenderableWidget(EditBox(font,118,8,(width-174).coerceAtLeast(60),20,Component.literal(tr("Web address"))).apply {
            setMaxLength(2048);value=render.mediaUrl
            if(value.isBlank())value="https://www.youtube.com/"
        })
        button(width-48,8,40,tr("Go")) { navigate() }
        power=button(8,height-28,72,"") {
            if(render.mediaRequested)render.clientSendId(87) else navigate()
        }
        voltage=button(84,height-28,62,"") { settings(if(render.settings[0]==120.0)240.0 else 120.0,render.settings[1]) }
        button(150,height-28,24,"-") { settings(render.settings[0],(render.settings[1]-.1).coerceAtLeast(0.0)) }
        button(178,height-28,24,"+") { settings(render.settings[0],(render.settings[1]+.1).coerceAtMost(1.0)) }
        takeControl=button(width-116,height-28,108,tr("Take control")) { tv.command(TelevisionCommand.CLAIM) }
        val scale=min((width-16)/16.0,(height-92)/9.0).coerceAtLeast(1.0)
        pictureW=(scale*16).toInt();pictureH=(scale*9).toInt();pictureX=(width-pictureW)/2;pictureY=36
        tv.focused=false
        tv.command(TelevisionCommand.CLAIM)
    }
    private fun settings(volts: Double,volume: Double) {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
        render.preparePacketForServer(out);out.writeByte(80);out.writeDouble(volts);out.writeDouble(volume);render.sendPacketToServer(bytes)
    }
    private fun navigate() {
        val parsed=TelevisionUrl.parse(address.value)
        error=parsed==null
        if(parsed!=null) { tv.command(TelevisionCommand.NAVIGATE,parsed.toASCIIString());address.isFocused=false;pageFocus=true;tv.focused=true }
    }
    private fun inside(x: Double,y: Double)=x>=pictureX && x<pictureX+pictureW && y>=pictureY && y<pictureY+pictureH
    private fun bx(x: Double)=((x-pictureX)*TelevisionBrowser.WIDTH/pictureW).toInt().coerceIn(0,TelevisionBrowser.WIDTH-1)
    private fun by(y: Double)=((y-pictureY)*TelevisionBrowser.HEIGHT/pictureH).toInt().coerceIn(0,TelevisionBrowser.HEIGHT-1)
    override fun tick() {
        if(minecraft?.player==null || render.tileEntity.isRemoved || minecraft!!.player!!.distanceToSqr(render.tileEntity.blockPos.x+.5,render.tileEntity.blockPos.y+.5,render.tileEntity.blockPos.z+.5)>64)onClose()
    }
    override fun render(g: GuiGraphics,x: Int,y: Int,partial: Float) {
        val now=System.nanoTime()
        if(now>=nextClaim && (tv.isController || render.tvController==null)) { nextClaim=now+1_000_000_000L;tv.command(TelevisionCommand.CLAIM) }
        g.fill(0,0,width,height,0xff14171c.toInt())
        g.fill(pictureX,pictureY,pictureX+pictureW,pictureY+pictureH,0xff000000.toInt())
        tv.texture()?.let { texture->
            g.flush();RenderSystem.setShader(GameRenderer::getPositionTexColorShader);RenderSystem.setShaderTexture(0,texture)
            val m=g.pose().last().pose();val b=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR)
            b.addVertex(m,pictureX.toFloat(),(pictureY+pictureH).toFloat(),0f).setUv(0f,1f).setColor(-1)
            b.addVertex(m,(pictureX+pictureW).toFloat(),(pictureY+pictureH).toFloat(),0f).setUv(1f,1f).setColor(-1)
            b.addVertex(m,(pictureX+pictureW).toFloat(),pictureY.toFloat(),0f).setUv(1f,0f).setColor(-1)
            b.addVertex(m,pictureX.toFloat(),pictureY.toFloat(),0f).setUv(0f,0f).setColor(-1)
            BufferUploader.drawWithShader(b.buildOrThrow())
        }
        power.message=Component.literal(if(render.mediaRequested)tr("TV off") else tr("TV on"))
        voltage.message=Component.literal(Utils.plotVolt("",render.settings[0]).trim());voltage.active=render.cord.endpoint==null
        takeControl.message=Component.literal(if(tv.isController)tr("You control TV") else tr("Take control"));takeControl.active=!tv.isController
        g.drawString(font,font.plainSubstrByWidth(if(error)tr("Use a complete HTTP or HTTPS address") else tv.status(),width-16),8,height-56,if(error)0xffff7777.toInt() else 0xffdddddd.toInt(),false)
        g.drawString(font,font.plainSubstrByWidth(tr("Supply: %1$; %2$; volume %3$ percent",Utils.plotVolt("",render.stats[0]).trim(),Utils.plotPower("",render.stats[1]).trim(),(render.settings[1]*100).toInt()),width-16),8,height-44,0xffaaaaaa.toInt(),false)
        super.render(g,x,y,partial)
    }
    override fun mouseClicked(x: Double,y: Double,button: Int): Boolean {
        if(inside(x,y) && tv.texture()!=null && tv.isController) {
            address.isFocused=false;pageFocus=true;tv.focused=true;pressed.add(button)
            tv.sendInput("down","x" to bx(x),"y" to by(y),"button" to button);return true
        }
        pageFocus=false;tv.focused=false;tv.browser?.setFocus(false)
        return super.mouseClicked(x,y,button)
    }
    override fun mouseReleased(x: Double,y: Double,button: Int): Boolean {
        if(pressed.remove(button)) { tv.sendInput("up","x" to bx(x),"y" to by(y),"button" to button);return true }
        return super.mouseReleased(x,y,button)
    }
    override fun mouseMoved(x: Double,y: Double) {
        tv.focused=inside(x,y);if(inside(x,y) || pressed.isNotEmpty())tv.sendInput("move","x" to bx(x),"y" to by(y))
        else GLFW.glfwSetCursor(minecraft!!.window.window,0)
        super.mouseMoved(x,y)
    }
    override fun mouseDragged(x: Double,y: Double,button: Int,dx: Double,dy: Double): Boolean {
        if(button in pressed) { tv.sendInput("move","x" to bx(x),"y" to by(y));return true }
        return super.mouseDragged(x,y,button,dx,dy)
    }
    override fun mouseScrolled(x: Double,y: Double,dx: Double,dy: Double): Boolean {
        if(inside(x,y)) { tv.sendInput("wheel","x" to bx(x),"y" to by(y),"delta" to dy.coerceIn(-20.0,20.0));return true }
        return super.mouseScrolled(x,y,dx,dy)
    }
    override fun keyPressed(key: Int,scan: Int,mods: Int): Boolean {
        if(key==GLFW.GLFW_KEY_ESCAPE) { onClose();return true }
        if(key==GLFW.GLFW_KEY_L && hasControlDown()) { pageFocus=false;tv.focused=false;address.isFocused=true;tv.browser?.setFocus(false);address.setCursorPosition(address.value.length);address.setHighlightPos(0);return true }
        if(address.isFocused && (key==GLFW.GLFW_KEY_ENTER || key==GLFW.GLFW_KEY_KP_ENTER)) { navigate();return true }
        if(pageFocus && tv.isController && key==GLFW.GLFW_KEY_V && hasControlDown()) {
            minecraft!!.keyboardHandler.clipboard.take(4096).chunked(256).forEach { tv.sendInput("paste","text" to it) };return true
        }
        if(pageFocus && tv.texture()!=null && tv.isController) { tv.sendInput("keyDown","key" to key,"scan" to scan,"mods" to mods);return true }
        return super.keyPressed(key,scan,mods)
    }
    override fun keyReleased(key: Int,scan: Int,mods: Int): Boolean {
        if(pageFocus && tv.texture()!=null && tv.isController) { tv.sendInput("keyUp","key" to key,"scan" to scan,"mods" to mods);return true }
        return super.keyReleased(key,scan,mods)
    }
    override fun charTyped(c: Char,mods: Int): Boolean {
        if(pageFocus && tv.texture()!=null && tv.isController) { tv.sendInput("char","text" to c.toString(),"mods" to mods);return true }
        return super.charTyped(c,mods)
    }
    override fun removed() {
        pressed.forEach { tv.sendInput("up","x" to 0,"y" to 0,"button" to it) };pressed.clear()
        tv.command(TelevisionCommand.RELEASE)
        tv.focused=false;tv.browser?.setFocus(false);GLFW.glfwSetCursor(minecraft!!.window.window,0)
    }
    override fun onClose() { super.onClose() }
}
