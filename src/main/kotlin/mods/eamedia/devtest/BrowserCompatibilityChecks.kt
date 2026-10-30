package mods.eamedia.devtest

import com.cinemamod.mcef.MCEF
import com.cinemamod.mcef.MCEFBrowser
import com.cinemamod.mcef.MCEFClient
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import mods.eamedia.server.BrowserWorker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import java.nio.file.Files

/** Opt-in native startup/settings/SOP/audio acceptance, without contacting a public website. */
@EventBusSubscriber(modid="eamedia",value=[Dist.CLIENT])
object BrowserCompatibilityChecks {
    private var ticks=0;private var done=false
    private var browser:MCEFBrowser?=null;private var client:MCEFClient?=null
    private var router:CefMessageRouter?=null
    @Volatile private var state=""
    private val pcm=java.util.concurrent.atomic.AtomicLong()
    private val mc get()=Minecraft.getInstance()
    private fun finish(error:Throwable?) {
        done=true
        val output=mc.gameDirectory.toPath().resolve("browser-compatibility-checks")
        Files.createDirectories(output)
        Files.writeString(output.resolve("report.json"),GsonBuilder().setPrettyPrinting().create().toJson(mapOf("browser" to state,"capturedPcmPackets" to pcm.get(),"failure" to error?.stackTraceToString())))
        browser?.close();router?.let { client?.handle?.removeMessageRouter(it);it.dispose() };client?.handle?.dispose()
        if(error!=null)mods.eln.Eln.LOGGER.error("BROWSER_COMPATIBILITY FAIL",error) else mods.eln.Eln.LOGGER.info("BROWSER_COMPATIBILITY COMPLETE {}",state)
        mc.execute { mc.stop();kotlin.concurrent.thread { Thread.sleep(1500);kotlin.system.exitProcess(if(error==null)0 else 1) } }
    }
    @SubscribeEvent fun tick(event:ClientTickEvent.Post) {
        if(!java.lang.Boolean.getBoolean("eamedia.browserCompatibilityChecks") || done)return
        try {
            check(++ticks<2000) { "Native compatibility check timed out: $state" }
            if(browser==null && mc.screen is TitleScreen && mc.overlay==null && MCEF.isInitialized()) {
                val c=MCEFClient(MCEF.getApp().handle.createClient());client=c;c.handle.removeAudioHandler()
                router=CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig("eaTvCapture","eaTvCaptureCancel"),object:CefMessageRouterHandlerAdapter() {
                    override fun onQuery(b:CefBrowser,frame:CefFrame,id:Long,request:String,persistent:Boolean,callback:CefQueryCallback):Boolean {
                        callback.success("")
                        if(request.startsWith("pcm:"))pcm.incrementAndGet()
                        if(request.startsWith("native:"))state=request.substring(7)
                        return true
                    }
                }).also { c.handle.addMessageRouter(it) }
                val capture=checkNotNull(javaClass.getResourceAsStream("/assets/eln/browser/mirror-capture.js")).bufferedReader().use { it.readText() }
                c.addLoadHandler(object:CefLoadHandlerAdapter() {
                    override fun onLoadEnd(b:CefBrowser,frame:CefFrame,status:Int) {
                        if(!frame.isMain)return
                        frame.executeJavaScript("window.__eaTVRelayAudio=true;\n"+capture+"""
                            const audio=new AudioContext({sampleRate:24000});
                            const oscillator=audio.createOscillator();oscillator.frequency.value=440;
                            oscillator.connect(audio.destination);oscillator.start();audio.resume();
                            const child=document.createElement('iframe');child.src='data:text/html,other-origin';
                            child.onload=()=>{
                                let blocked=false;try { void child.contentWindow.document.body.innerText; } catch(error) { blocked=error.name==='SecurityError'; }
                                window.eaTvCapture({request:'native:'+JSON.stringify({ua:navigator.userAgent,crossOriginBlocked:blocked}),onSuccess:()=>{},onFailure:()=>{}});
                            };
                            document.body.appendChild(child);
                        """,frame.url,0)
                    }
                })
                browser=MCEFBrowser(c,"data:text/html,<html><body>EA Media native compatibility fixture</body></html>",false).apply { setCloseAllowed();createImmediately();resize(960,540) }
            }
            if(state.isNotEmpty() && pcm.get()>2) {
                val value=JsonParser.parseString(state).asJsonObject
                check(value["ua"].asString.contains("Chrome/") && !value["ua"].asString.contains("MCEF/2"))
                check(value["crossOriginBlocked"].asBoolean) { "Cross-origin browser security was disabled" }
                check(!BrowserWorker.CapturePolicy.allows("https://challenges.cloudflare.com/turnstile/v0/test"))
                finish(null)
            }
        }catch(error:Throwable){finish(error)}
    }
}
