package mods.eamedia

import mods.eamedia.server.BrowserWorker
import org.cef.CefSettings
import org.junit.Test
import kotlin.test.*

class BrowserCompatibilityTest {
    @Test fun actualChromiumVersionIsRestoredWithoutInventingAVersionOrOverridingUserSettings() {
        val native=CefSettings().apply { user_agent_product="MCEF/2" }
        assertSame(native,BrowserCompatibility.settings(native));assertEquals("",native.user_agent_product)
        assertTrue(native.user_agent.isNullOrBlank())
        val custom=CefSettings().apply { user_agent="Operator browser identity";user_agent_product="custom" }
        BrowserCompatibility.settings(custom);assertEquals("Operator browser identity",custom.user_agent);assertEquals("custom",custom.user_agent_product)
    }
    @Test fun verificationFramesAreLeftAloneWhileCrossOriginMediaFramesStillCapture() {
        for(url in listOf("https://challenges.cloudflare.com/turnstile/v0/x","https://example.org/cdn-cgi/challenge-platform/h/x","about:blank"))
            assertFalse(BrowserWorker.CapturePolicy.allows(url),url)
        for(url in listOf("https://rumble.com/v123-video.html","https://www.youtube.com/embed/abc","https://video.example.org/player","https://challenges.cloudflare.com.example.org/player"))
            assertTrue(BrowserWorker.CapturePolicy.allows(url),url)
    }
}
