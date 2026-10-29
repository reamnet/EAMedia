package mods.eamedia

import com.cinemamod.mcef.MCEF
import com.cinemamod.mcef.MCEFBrowser
import com.cinemamod.mcef.MCEFClient
import mods.eln.Eln
import mods.eln.client.gl.FixedFunction
import mods.eln.client.gl.GL11
import mods.eln.client.terrain.TerrainModels
import mods.eln.i18n.I18N.tr
import mods.eln.misc.Direction
import mods.eln.misc.UtilsClient
import mods.eln.misc.preserveMatrix
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.*
import org.cef.handler.*
import org.cef.network.CefRequest
import java.awt.Rectangle
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Vector
import com.google.gson.JsonParser
import org.cef.browser.CefMessageRouter
import net.neoforged.neoforge.network.PacketDistributor

/** Only the elected host creates Chromium; every other viewer uses its relayed picture and sound. */
class TelevisionBrowser(private val render: MediaRender): AutoCloseable {
    private val mc get()=Minecraft.getInstance()
    var browser: MCEFBrowser?=null;private set
    private var client: MCEFClient?=null
    private var revision=Int.MIN_VALUE
    private var failed=false
    private var capacity=false
    var focused=false
    var paintedFrames=0L;private set
    private val location=ResourceLocation.fromNamespaceAndPath("eln","tv/browser_${nextId++}")
    private var registered=false
    val mirror=TelevisionMirror(render)
    private var router: CefMessageRouter?=null
    private var browserEpoch: java.util.UUID?=null
    private var pendingPage: String?=null
    private var reportSequence=0L
    private var lastReport=0L
    private var lastMove=0L
    private var nextObserve=0L
    private var observing=false
    private var inputController: java.util.UUID?=null
    private var lastInput=0L
    private val heldButtons=HashSet<Int>();private val heldKeys=HashSet<Int>()
    val isController get()=render.tvController==mc.player?.uuid && mc.player!=null
    val isHost get()=!render.tvServerHosted && render.tvHost==mc.player?.uuid && mc.player!=null
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
    fun remoteInput(value: String,controller: java.util.UUID) {
        if(!isHost || !TelevisionNetwork.validInput(value))return
        val b=browser ?: return
        if(inputController!=controller) { releaseInputs();inputController=controller }
        lastInput=System.nanoTime()
        val d=JsonParser.parseString(value).asJsonObject
        fun i(name: String)=d[name].asInt
        when(d["type"].asString) {
            "back"->b.goBack()
            "forward"->b.goForward()
            "reload"->b.reload()
            "move"->b.sendMouseMove(i("x"),i("y"))
            "down"->{ b.setFocus(true);heldButtons+=i("button");b.sendMousePress(i("x"),i("y"),i("button")) }
            "up"->{ heldButtons-=i("button");b.sendMouseRelease(i("x"),i("y"),i("button")) }
            "wheel"->b.sendMouseWheel(i("x"),i("y"),d["delta"].asDouble,0)
            "keyDown"->{
                // Paste the controller's text, never the host computer's clipboard.
                if((i("key")==86 && i("mods") and 10!=0) || (i("key")==260 && i("mods") and 1!=0))return
                b.setFocus(true);heldKeys+=i("key");b.sendKeyPress(i("key"),i("scan").toLong(),i("mods"))
            }
            "keyUp"->{ heldKeys-=i("key");b.sendKeyRelease(i("key"),i("scan").toLong(),i("mods")) }
            "char"->{ b.setFocus(true);d["text"].asString.forEach { b.sendKeyTyped(it,i("mods")) } }
            "paste"->{ b.setFocus(true);val f=b.focusedFrame ?: b.mainFrame;f?.executeJavaScript("document.execCommand('insertText',false,"+com.google.gson.Gson().toJson(d["text"].asString)+");",f.url,0) }
        }
    }
    private fun releaseInputs() {
        browser?.let { b->heldButtons.forEach { b.sendMouseRelease(0,0,it) };heldKeys.forEach { b.sendKeyRelease(it,0,0) };b.setFocus(false) }
        heldButtons.clear();heldKeys.clear()
    }

    fun update() {
        val tile=render.tileEntity
        val distance=mc.player?.distanceToSqr(tile.blockPos.x+.5,tile.blockPos.y+.5,tile.blockPos.z+.5) ?: Double.POSITIVE_INFINITY
        if(tile.isRemoved || !render.working || !render.mediaRequested || distance>32.0*32.0) { close();return }
        mirror.update()
        val now=System.nanoTime()
        if(now>=nextObserve) { nextObserve=now+1_000_000_000L;observing=true;command(TelevisionCommand.OBSERVE) }
        if(!isHost) { closeBrowser();return }
        mirror.pumpAudio()
        // Input carries the server-validated controller, so delayed display metadata
        // cannot blur the browser immediately after the new controller clicks it.
        if(inputController!=null && render.tvController!=inputController && System.nanoTime()-lastInput>1_000_000_000L) {
            releaseInputs();inputController=null
        }
        if(revision!=render.mediaRevision || browserEpoch!=render.tvHostEpoch) {
            val preserve=pendingPage==render.mediaUrl && browser?.url==render.mediaUrl && browserEpoch==render.tvHostEpoch
            if(!preserve)closeBrowser()
            pendingPage=null;revision=render.mediaRevision;browserEpoch=render.tvHostEpoch;failed=false
        }
        if(!MCEF.isInitialized() || failed || !mods.eamedia.web.BrowserWebProxy.client.enabled || !approvedPage())return
        if(browser==null) {
            if(!active.contains(this)){capacity=active.size>=MAX_BROWSERS;if(capacity)return;active.add(this)}
            val url=TelevisionUrl.parse(render.mediaUrl)?.toASCIIString() ?: return
            try {
                // A private CEF client keeps our lifecycle/handlers independent of other MCEF mods.
                // The pinned native capture handler leaks allocations. Document capture feeds
                // Minecraft audio for everyone, including the host; page speakers stay silent.
                val c=MCEFClient(MCEF.getApp().handle.createClient());client=c;c.handle.removeAudioHandler()
                router=CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig("eaTvCapture","eaTvCaptureCancel"),object: CefMessageRouterHandlerAdapter() {
                    override fun onQuery(b: CefBrowser,frame: CefFrame,id: Long,request: String,persistent: Boolean,callback: CefQueryCallback): Boolean {
                        if(b!==browser || persistent || request.length>16384) { callback.failure(1,"Unavailable");return true }
                        callback.success("")
                        when {
                            request.startsWith("pcm:")->mirror.captureAudio(frame.identifier,request.substring(4))
                            request.startsWith("state:") && request.length<=1030->{
                                val value=request.substring(6);val now=System.nanoTime()
                                if(now-lastReport>500_000_000 && TelevisionNetwork.parsePlayback(value)!=null) {
                                    lastReport=now;mc.execute { if(isHost && b===browser)command(TelevisionCommand.REPORT,value,++reportSequence) }
                                }
                            }
                        }
                        return true
                    }
                }).also { c.handle.addMessageRouter(it) }
                val initialUrl=url
                val initialPlayback=render.tvPlayback
                fun install(frame: CefFrame) {
                    val s=if(frame.url==initialUrl)initialPlayback else TelevisionPlayback()
                    val resume=com.google.gson.Gson().toJson(mapOf("position" to s.position,"paused" to s.paused,"rate" to s.rate,"known" to s.known))
                    frame.executeJavaScript("window.__eaTVRelayAudio=true;window.__eaTVResume=$resume;\n"+captureTemplate,frame.url.orEmpty(),0)
                }
                c.addLoadHandler(object: CefLoadHandlerAdapter() {
                    override fun onLoadStart(b: CefBrowser,frame: CefFrame,transition: CefRequest.TransitionType) {
                        install(frame)
                    }
                    override fun onLoadEnd(b: CefBrowser,frame: CefFrame,status: Int) { install(frame) }
                })
                c.addDisplayHandler(object: CefDisplayHandlerAdapter() {
                    override fun onAddressChange(b: CefBrowser,frame: CefFrame,url: String) {
                        if(!frame.isMain || TelevisionUrl.parse(url)==null)return
                        mc.execute { if(b===browser && isHost && url!=render.mediaUrl && url!=pendingPage) { pendingPage=url;command(TelevisionCommand.PAGE_CHANGED,url) } }
                    }
                })
                c.handle.addRequestHandler(object: CefRequestHandlerAdapter() {
                    override fun onBeforeBrowse(b: CefBrowser,frame: CefFrame,request: CefRequest,gesture: Boolean,redirect: Boolean)=
                        TelevisionUrl.parse(request.url)==null && request.url!="about:blank"
                })
                c.handle.addLifeSpanHandler(object: CefLifeSpanHandlerAdapter() {
                    override fun onBeforePopup(b: CefBrowser,frame: CefFrame,url: String,name: String): Boolean {
                        if(TelevisionUrl.parse(url)!=null)mc.execute { browser?.loadURL(url) }
                        return true
                    }
                })
                c.addContextMenuHandler(object: CefContextMenuHandlerAdapter() {
                    override fun onBeforeContextMenu(b: CefBrowser,frame: CefFrame,params: CefContextMenuParams,model: CefMenuModel) { model.clear() }
                })
                c.handle.addDialogHandler(object: CefDialogHandler {
                    override fun onFileDialog(b: CefBrowser,mode: CefDialogHandler.FileDialogMode,title: String,defaultPath: String,filters: Vector<String>,callback: CefFileDialogCallback): Boolean { callback.Cancel();return true }
                })
                val b=object: MCEFBrowser(c,url,false) {
                    private var nextPaint=0L
                    override fun onPaint(b: CefBrowser,popup: Boolean,rects: Array<Rectangle>,buffer: ByteBuffer,width: Int,height: Int) {
                        if(!popup) {
                            mirror.capture(buffer,width,height,if(showPopup)popupGraphics else null,if(showPopup)popupSize else null)
                            val now=System.nanoTime();if(now<nextPaint)return
                            nextPaint=maxOf(nextPaint+1_000_000_000L/TelevisionStream.FPS,now);paintedFrames++
                            // A skipped dirty rectangle must be included on the next accepted frame.
                            super.onPaint(b,false,arrayOf(Rectangle(0,0,width,height)),buffer,width,height)
                        } else super.onPaint(b,true,rects,buffer,width,height)
                    }
                }
                b.setCursorChangeListener { type->if(focused)b.setCursor(org.cef.misc.CefCursorType.fromId(type)) }
                b.setCloseAllowed();browser=b;b.createImmediately();b.resize(WIDTH,HEIGHT)
                mc.textureManager.register(location,object: AbstractTexture() {
                    override fun load(resources: ResourceManager) { }
                    override fun getId()=b.renderer.textureID
                    override fun releaseId() { } // MCEF owns and deletes this texture.
                });registered=true
            } catch(e: Exception) { closeBrowser();failed=true;Eln.LOGGER.error("Could not create TV browser",e) }
        }
    }

    private fun approvedPage()=TelevisionUrl.parse(render.mediaUrl)?.host?.let{mods.eamedia.web.BrowserWebProxy.client.domains.allows(it)} ?: false
    fun status(): String=when {
        !render.mediaRequested->tr("Enter a webpage or video address and turn on the TV")
        !render.working->tr("Waiting for electrical power")
        !render.tvServerHosted && !mods.eamedia.web.BrowserWebProxy.client.enabled->if(mods.eamedia.web.BrowserWebProxy.client.connected)tr("The server has disabled TV web routing") else tr("Waiting for the server web route")
        !render.tvServerHosted && !approvedPage()->tr("This website is not approved by the server")
        render.tvServerHosted && render.tvServerStatus=="RUNTIME_MISSING"->tr("Install the full EA Media jar on the server for TV playback")
        render.tvServerHosted && render.tvServerStatus=="DISPLAY_MISSING"->tr("The server needs Xvfb for its browser; see server setup")
        render.tvServerHosted && render.tvServerStatus=="FAILED"->tr("Server browser failed; check its worker log")
        render.tvServerHosted && render.tvServerStatus=="CAPACITY"->tr("The server browser limit has been reached")
        !isHost && mirror.receivedFrames>0->if(isController)tr("Shared TV; you control the browser") else tr("Shared TV; take control to interact")
        !isHost->tr("Waiting for the shared TV picture")
        !MCEF.isInitialized()->tr("Browser engine unavailable; check MCEF setup")
        failed->tr("Browser could not start; turn the TV off and on to retry")
        capacity->tr("Two nearby TVs are already active")
        browser==null->tr("Waiting for viewer within 32 blocks")
        browser!!.isLoading->tr("Loading webpage...")
        else->if(isController)tr("Shared TV; you control the browser") else tr("Shared TV; take control to interact")
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
    fun texture()=if(isHost && browser!=null && paintedFrames>0)location else mirror.texture()
    private fun closeBrowser() {
        focused=false
        releaseInputs();inputController=null
        browser?.close();browser=null
        router?.let { client?.handle?.removeMessageRouter(it);it.dispose() };router=null
        client?.handle?.dispose();client=null
        if(registered)mc.textureManager.release(location)
        registered=false;paintedFrames=0;active.remove(this);mirror.stopCapture()
    }
    override fun close() {
        if(observing){observing=false;command(TelevisionCommand.LEAVE)}
        closeBrowser();mirror.close();capacity=false
    }
    companion object {
        const val WIDTH=TelevisionStream.WIDTH
        const val HEIGHT=TelevisionStream.HEIGHT
        private const val MAX_BROWSERS=2
        private var nextId=0
        private val active=Collections.newSetFromMap(IdentityHashMap<TelevisionBrowser,Boolean>())
        private val captureTemplate by lazy { checkNotNull(TelevisionBrowser::class.java.getResourceAsStream("/assets/eln/browser/mirror-capture.js")).bufferedReader().use { it.readText() } }
    }
}
