// Original ELN TV controls. Installed separately in each browser document, never on the server.
(() => {
    window.__elnTVGain = __ELN_GAIN__;
    if (!window.__elnTVVolume) {
        const states = new WeakMap(), mixers = new WeakMap(), gains = new Set();
        const connect = AudioNode.prototype.connect;
        // Web Audio players also receive the TV's master gain, independently of their own gain.
        AudioNode.prototype.connect = function (destination, ...args) {
            if (destination instanceof AudioDestinationNode) {
                let node = mixers.get(destination.context);
                if (!node) {
                    node = destination.context.createGain();
                    node.gain.value = window.__elnTVGain;
                    mixers.set(destination.context, node);
                    gains.add(node);
                    connect.call(node, destination);
                }
                connect.call(this, node, ...args);
                return destination;
            }
            return connect.call(this, destination, ...args);
        };
        window.__elnTVVolume = () => {
            document.querySelectorAll('video,audio').forEach(media => {
                let state = states.get(media);
                if (!state) {
                    state = { desired: media.volume, applied: -1 };
                    states.set(media, state);
                } else if (Math.abs(media.volume - state.applied) > .0001) {
                    state.desired = media.volume;
                }
                state.applied = Math.max(0, Math.min(1, state.desired * window.__elnTVGain));
                media.volume = state.applied;
                // Keep the webpage's own mute button independent of the TV's volume.
            });
            gains.forEach(node => {
                if (node.context?.state === "closed") gains.delete(node);
                else node.gain.value = window.__elnTVGain;
            });
        };
        document.addEventListener('play', window.__elnTVVolume, true);
        setInterval(window.__elnTVVolume, 250);
    }
    window.__elnTVVolume();
})();
