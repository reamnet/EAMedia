package mods.eamedia

import org.cef.CefSettings

/** Keep the bundled engine's real Chromium version; never pretend to be a newer browser. */
object BrowserCompatibility {
    @JvmStatic fun settings(settings: CefSettings): CefSettings {
        // MCEF replaces Chrome/<version> with MCEF/2. Sites then cannot recognize the
        // engine at all. An empty product restores CEF's native Chrome version token.
        if(settings.user_agent.isNullOrBlank() && settings.user_agent_product=="MCEF/2")settings.user_agent_product=""
        return settings
    }
}
