// One host captures this document. Viewers receive pixels/PCM, never a second webpage session.
(() => {
    if (window.__eaTVCapture || typeof window.eaTvCapture !== 'function') return;
    window.__eaTVCapture = true;
    const send = value => {
        try { window.eaTvCapture({request: value, onSuccess: () => {}, onFailure: () => {}}); } catch (_) {}
    };
    const attached = new WeakSet(), inGraph = new WeakSet(), buses = new WeakMap(), elementGains = new WeakMap();
    const speakerSinks = new WeakMap(), elementSpeakers = new WeakMap();
    let context, processor;
    const audio = () => {
        if (context) return context;
        context = new AudioContext({sampleRate: 24000});
        processor = context.createScriptProcessor(2048, 2, 2);
        const silent = context.createGain(); silent.gain.value = 0;
        processor.connect(silent); silent.connect(context.destination);
        processor.onaudioprocess = event => {
            const input = event.inputBuffer, count = Math.min(input.length, 2048);
            const left = input.getChannelData(0), right = input.getChannelData(Math.min(1, input.numberOfChannels - 1));
            const bytes = new Uint8Array(count * 4), view = new DataView(bytes.buffer);
            let energy = 0;
            for (let i = 0; i < count; i++) {
                const l = Math.max(-1, Math.min(1, left[i])), r = Math.max(-1, Math.min(1, right[i]));
                view.setInt16(i * 4, Math.round(l * 32767), true); view.setInt16(i * 4 + 2, Math.round(r * 32767), true);
                energy += l * l + r * r;
            }
            // Empty/silent documents generate no network sound traffic.
            if (energy > .000001) send('pcm:' + btoa(String.fromCharCode(...bytes)));
        };
        context.resume().catch(() => {});
        return context;
    };
    const stream = (mediaStream, media) => {
        const tracks = mediaStream.getAudioTracks();
        if (!tracks.length) return false;
        const c = audio(), source = c.createMediaStreamSource(new MediaStream(tracks));
        if (media) {
            // captureStream ignores the element's speaker volume/mute. Apply the page's
            // own controls here; individual Minecraft volume is applied only by viewers.
            const gain = c.createGain();
            gain.gain.value = media.muted ? 0 : media.volume;
            elementGains.set(media, gain);source.connect(gain);gain.connect(processor);
            let detached = false;
            const detach = () => {
                if (detached) return;
                detached = true;source.disconnect();gain.disconnect();
                if (elementGains.get(media) === gain) { elementGains.delete(media);attached.delete(media); }
            };
            // A playlist/SPA may reuse its video element with a new audio track.
            // Reattach that source instead of leaving its ended stream marked attached.
            tracks.forEach(track => track.addEventListener?.('ended', detach, {once: true}));
            mediaStream.addEventListener?.('addtrack', event => { if (!tracks.includes(event.track)) detach(); });
            mediaStream.addEventListener?.('removetrack', event => { if (tracks.includes(event.track)) detach(); });
        } else source.connect(processor);
        return true;
    };
    // Host and viewers all listen through Minecraft. Keep document capture independent
    // of that client's volume, while routing the host's native speakers into zero gain.
    const connect = AudioNode.prototype.connect;
    AudioNode.prototype.connect = function(destination, ...args) {
        if (destination instanceof AudioDestinationNode && destination.context !== context) {
            try {
                let bus = buses.get(destination.context);
                if (!bus) { bus = destination.context.createMediaStreamDestination(); buses.set(destination.context, bus); stream(bus.stream); }
                connect.call(this, bus);
            } catch (_) {}
        }
        if (window.__eaTVRelayAudio && destination instanceof AudioDestinationNode) {
            let sink = speakerSinks.get(destination.context);
            if (!sink) {
                sink = destination.context.createGain(); sink.gain.value = 0;
                speakerSinks.set(destination.context, sink); connect.call(sink, destination);
            }
            connect.call(this, sink, ...args); return destination;
        }
        return connect.call(this, destination, ...args);
    };
    const makeElementSource = AudioContext.prototype.createMediaElementSource;
    AudioContext.prototype.createMediaElementSource = function(media) {
        inGraph.add(media); return makeElementSource.call(this, media);
    };
    const resumed = new WeakSet();
    const update = () => {
        const media = Array.from(document.querySelectorAll('video,audio'));
        media.forEach(m => {
            if (window.__eaTVRelayAudio && !attached.has(m) && !inGraph.has(m) && m.readyState >= 2) {
                try {
                    // Once Web Audio owns an element, captureStream can become silent.
                    // Capture its actual source directly and let only our silent processor
                    // reach the native device. Element volume/mute remain page controls.
                    const source = makeElementSource.call(audio(), m);
                    source.connect(processor); elementSpeakers.set(m, source); attached.add(m);
                } catch (_) {}
            } else if (!window.__eaTVRelayAudio && !attached.has(m) && !inGraph.has(m) && m.readyState >= 2 && typeof m.captureStream === 'function') {
                try { if (stream(m.captureStream(), m)) attached.add(m); } catch (_) {}
            }
            const gain = elementGains.get(m);
            if (gain) gain.gain.value = m.muted ? 0 : m.volume;
            const resume = window.__eaTVResume;
            if (!resumed.has(m) && m.readyState >= 1) {
                resumed.add(m);
                if (resume?.known) {
                    try {
                        const target = Math.max(0, resume.position);
                        if (Math.abs(m.currentTime - target) > .75) m.currentTime = Number.isFinite(m.duration) ? Math.min(target, Math.max(0, m.duration - .05)) : target;
                        m.playbackRate = resume.rate;
                        if (resume.paused) m.pause(); else m.play().catch(() => {});
                    } catch (_) {}
                }
            }
        });
        const primary = media.sort((a, b) => {
            const area = m => { const r = m.getBoundingClientRect(); return (m.tagName === 'VIDEO' ? 1000000 : 0) + r.width * r.height; };
            return area(b) - area(a);
        })[0];
        if (primary && Number.isFinite(primary.currentTime)) send('state:' + JSON.stringify({
            position: primary.currentTime, paused: primary.paused, rate: primary.playbackRate,
            duration: Number.isFinite(primary.duration) ? Math.max(0, primary.duration) : 0, live: !Number.isFinite(primary.duration)
        }));
    };
    document.addEventListener('play', () => { audio().resume().catch(() => {}); update(); }, true);
    document.addEventListener('loadedmetadata', update, true);
    document.addEventListener('volumechange', update, true);
    setInterval(update, 500); update();
})();
