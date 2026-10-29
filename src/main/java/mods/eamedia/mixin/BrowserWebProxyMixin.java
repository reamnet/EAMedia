package mods.eamedia.mixin;

import mods.eamedia.web.BrowserWebProxy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Pinned JCEF has only engine-wide proxy flags. No direct fallback is configured;
 * the server separately requires an active TV host and an approved destination. */
@Mixin(targets="com.cinemamod.mcef.CefUtil",remap=false)
public abstract class BrowserWebProxyMixin {
    @ModifyArg(method="init",at=@At(value="INVOKE",target="Lorg/cef/CefApp;startup([Ljava/lang/String;)Z"),index=0)
    private static String[] eamedia$routeStartup(String[] original){return BrowserWebProxy.arguments(original);}

    @ModifyArg(method="init",at=@At(value="INVOKE",target="Lorg/cef/CefApp;getInstance([Ljava/lang/String;Lorg/cef/CefSettings;)Lorg/cef/CefApp;"),index=0)
    private static String[] eamedia$routeEngine(String[] original){return BrowserWebProxy.arguments(original);}
}
