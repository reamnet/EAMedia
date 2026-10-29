package mods.eamedia.mixin;

import com.mojang.blaze3d.audio.Channel;
import mods.eamedia.TelevisionPcmQueue;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A brief client stall can empty the live TV buffers. Vanilla refills them but
 * never restarts the stopped OpenAL source. Resume only our still-open streams,
 * on the same sound-executor thread; ordinary game sounds are unaffected. */
@Mixin(Channel.class)
public abstract class TelevisionChannelMixin {
    @Shadow private AudioStream stream;
    @Shadow @Final private int source;
    @Unique private boolean eamedia$explicitStop;

    @Inject(method="stop",at=@At("HEAD"))
    private void eamedia$rememberExplicitStop(CallbackInfo callback){eamedia$explicitStop=true;}

    @Inject(method="play",at=@At("HEAD"))
    private void eamedia$rememberPlay(CallbackInfo callback){eamedia$explicitStop=false;}

    @Inject(method="updateStream",at=@At("TAIL"))
    private void eamedia$resumeLiveTelevision(CallbackInfo callback) {
        if(!eamedia$explicitStop && stream instanceof TelevisionPcmQueue pcm && !pcm.isClosed()
                && AL10.alGetSourcei(source,AL10.AL_SOURCE_STATE)==AL10.AL_STOPPED
                && AL10.alGetSourcei(source,AL10.AL_BUFFERS_QUEUED)>0) {
            AL10.alSourcePlay(source);
            pcm.onChannelResumed();
        }
    }
}
