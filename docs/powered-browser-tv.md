# Powered Browser Television

EA Media 1.0.4 hosts the TV's full interactive Chromium browser on the Minecraft
server, in a separate worker process. Every player receives its picture and PCM
audio. Players control that same page; none is elected to run the TV in their
own browser. Disconnecting the first viewer or changing controller does not
replace the server browser, cookies, forms or playback session.

The baked cabinet and electrical connections are unchanged: 120/240 V cord or
fixed blue input, 150 W while on and 2 W standby at nominal voltage. Power loss
closes the worker. Power return opens the shared address and resumes ordinary
media from its saved position where the website permits it. Reloading a page
cannot preserve arbitrary JavaScript memory or live calls.

## Server setup

Install the **full `EAMedia-1.0.4.jar` on the server and every client**. The small
`-server.jar` excludes Chromium and cannot host TVs; it is suitable for jukebox
logic when no TV is needed. Update every installation together: this release
requires the new server-owned TV protocol. ELN 3.0.60+ in the 3.0 series remains
the core requirement; the core does not need another media dependency.

On Ubuntu 24.04, install the native OS dependencies once:

```bash
sudo apt update
sudo apt install -y xvfb tar libgtk-3-0t64 libnss3 libnspr4 libasound2t64 \
  libcups2t64 libxtst6 libxkbcommon0 \
  libx11-6 libxcomposite1 libxdamage1 libxrandr2 libgbm1 libxshmfence1 \
  fonts-dejavu-core fonts-liberation
```

Use a normal non-root server account. The server extracts its own platform's
already-bundled Chromium; it does not download a browser. On headless Linux it
starts a private Xvfb display automatically, with TCP listening disabled. An
existing `DISPLAY` can also be used. No monitor, desktop Minecraft client, GPU
or physical audio device is required. The browser uses an ALSA null output;
its captured sound is played through each viewer's Minecraft sound engine.

Startup/extraction can take a while the first time. The TV shows whether it is
starting, missing the full jar, missing Xvfb, or unable to start its worker.
Logs are under the world's `eamedia-browser/<dimension>/<position>/worker.log`
and `chromium.log`; the shared runtime is under `eamedia-server/`.

The default limit is **two active server TV browsers**. Set
`-Deamedia.serverBrowserLimit=4` in the server's JVM arguments to allow four
(the supported range is 1–8). Each browser consumes native memory and video
CPU, potentially several hundred MB, in addition to its bounded Java worker
heap. Moving hosting to the server increases its CPU and outgoing bandwidth;
separate processes keep rendering/extraction/socket waits off the tick thread,
but still share the machine's CPU and memory. Turn unused TVs off.

A worker starts when a powered TV is first observed. It then remains running
while its chunk stays loaded and power remains available, including when all
viewers leave. With no viewers it stops forwarding encoded video/audio while
keeping the page alive. This does not force-load a chunk. Server stop, chunk
unload, power loss and block removal close the worker and its child processes.
Browser profiles/cookies belong to the server TV, not any player's personal
browser. They persist in that world's browser directory.

## Controls and audio

Open the TV, paste a complete HTTP/HTTPS address and press **Go**. The controller
can click, type, paste, drag, scroll and use back/forward/reload. **Take control**
requests the one interactive lease; **Ctrl+L** selects the address bar. Closing
the controls leaves the TV playing. The controller's pasted text is forwarded;
the server's clipboard is never read. File dialogs and executable URL schemes
are disabled. Popups navigate the same TV instead of creating extra windows.

The TV minus/plus buttons adjust its shared volume. Each player's Minecraft
**Master** and **Jukebox/Note Blocks** sliders affect only their own listening.
Page mute/volume remain page controls. Muting one client does not mute the
server capture or other viewers. Audio streams restart after local sound-engine
stops, volume restoration or device/resource resets instead of holding a dead
stream reference. Leaving 32 blocks stops that client's viewing/audio.

The stream remains bounded to JPEG up to 960×540 at 12 fps and stereo 24 kHz
PCM, with bounded queues and stale-session rejection. Compression and network
latency apply. Approximate incoming limits are 800 kB/s video and 120 kB/s audio
per TV; server outbound traffic scales with the number of viewers.

Websites still need access from the server's network. Embedded Chromium does
not guarantee support for every codec, DRM service, sign-in flow or site policy.
Protected media and sites that prevent document audio capture may not supply
sound. The pinned native audio callback is deliberately unused because that
upstream implementation leaks memory and exposes only one channel.
