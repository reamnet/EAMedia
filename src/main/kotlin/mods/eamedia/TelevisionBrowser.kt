package mods.eamedia

import com.cinemamod.mcef.MCEFBrowser
import mods.eln.client.gl.FixedFunction
import mods.eln.client.gl.GL11
import mods.eln.client.terrain.TerrainModels
import mods.eln.i18n.I18N.tr
import mods.eln.misc.Direction
import mods.eln.misc.UtilsClient
import mods.eln.misc.preserveMatrix
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.network.PacketDistributor

/** Every client is a viewer. Only the dedicated server worker opens the webpage. */
class TelevisionBrowser(private val render: MediaRender): AutoCloseable {
    private val mc get()=Minecraft.getInstance()
    val mirror=TelevisionMirror(render)
    var focused=false
    val isController get()=render.tvController==mc.player?.uuid && mc.player!=null
    val isHost get()=false
    // Retained for the old opt-in native probes: a client must never own a browser now.
    val browser: MCEFBrowser? get()=null
    val paintedFrames get()=mirror.receivedFrames
    private var observing=false
    private var nextObserve=0L
    private var lastMove=0L
    fun command(action: Int,value: String="",sequence: Long=0) {
        if(mc.connection==null || mc.player==null)return
        val session=render.tvSession ?: return
        PacketDistributor.sendToServer(TelevisionCommand(render.tileEntity.blockPos,session,render.mediaRevision,action,value,sequence))
    }
    fun sendInput(type: String,vararg values: Pair<String,Any>) {
        if(!isController)return
        if(type=="move") { val now=System.nanoTime();if(now-lastMove<33_000_000)return;lastMove=now }
        val value=com.google.gson.Gson().toJson(linkedMapOf<String,Any>("type" to type).apply { putAll(values) })
        command(TelevisionCommand.INPUT,value)
    }
    fun update() {
        val p=render.tileEntity.blockPos
        val distance=mc.player?.distanceToSqr(p.x+.5,p.y+.5,p.z+.5) ?: Double.POSITIVE_INFINITY
        if(render.tileEntity.isRemoved || !render.working || !render.mediaRequested || distance>1024){close();return}
        mirror.update()
        val now=System.nanoTime()
        if(now>=nextObserve){nextObserve=now+1_000_000_000L;observing=true;command(TelevisionCommand.OBSERVE)}
    }
    fun status(): String=when {
        !render.mediaRequested->tr("Enter a webpage or video address and turn on the TV")
        !render.working->tr("Waiting for electrical power")
        render.tvServerStatus=="RUNTIME_MISSING"->tr("Install the full EA Media jar on the server for TV playback")
        render.tvServerStatus=="DISPLAY_MISSING"->tr("The server needs Xvfb for its browser; see server setup")
        render.tvServerStatus=="FAILED"->tr("Server browser failed; check its worker log")
        render.tvServerStatus=="CAPACITY"->tr("The server browser limit has been reached")
        mirror.receivedFrames>0->if(isController)tr("Server TV; you control the browser") else tr("Server TV; take control to interact")
        else->tr("Waiting for the server browser picture")
    }
    fun drawWorld()=TerrainModels.dynamic {
        val picture=texture() ?: return@dynamic
        if(!render.working)return@dynamic
        preserveMatrix {
            (render.front ?: Direction.ZP).inverse.glRotateZnRef();GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
            try {
                FixedFunction.bindTexture(picture);GL11.glEnable(GL11.GL_TEXTURE_2D);UtilsClient.disableLight();GL11.glColor3f(1f,1f,1f)
                GL11.glBegin(GL11.GL_QUADS);GL11.glNormal3f(0f,0f,1f)
                GL11.glTexCoord2f(0f,1f);GL11.glVertex3d(-1.28,-.12,.141)
                GL11.glTexCoord2f(1f,1f);GL11.glVertex3d(1.28,-.12,.141)
                GL11.glTexCoord2f(1f,0f);GL11.glVertex3d(1.28,1.32,.141)
                GL11.glTexCoord2f(0f,0f);GL11.glVertex3d(-1.28,1.32,.141)
                GL11.glEnd()
            } finally { UtilsClient.enableLight();GL11.glPopAttrib() }
        }
    }
    fun texture()=mirror.texture()
    override fun close(){if(observing){observing=false;command(TelevisionCommand.LEAVE)};mirror.close();focused=false}
    companion object { const val WIDTH=960;const val HEIGHT=540 }
}
