package mods.eamedia.mixin;

import com.cinemamod.mcef.MCEFDownloader;
import mods.eamedia.BundledChromium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;

/** Satisfy the upstream install check locally; neither checksum nor runtime is fetched. */
@Mixin(value = MCEFDownloader.class, remap = false)
public abstract class BundledChromiumMixin {
    @Inject(method = "downloadJavaCefChecksum", at = @At("HEAD"), cancellable = true, remap = false)
    private void eamedia$installLocalRuntime(CallbackInfoReturnable<Boolean> callback) throws IOException {
        BundledChromium.ensureInstalled();
        callback.setReturnValue(true);
    }
}
