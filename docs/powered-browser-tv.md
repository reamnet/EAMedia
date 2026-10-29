# Powered Browser Television

EA Media 1.0.5 uses **client hosting by default**. One nearby player's client
runs the full interactive browser; everyone else receives its shared picture and
audio. The server validates controls and relays bounded encoded packets. It does
not launch Chromium, Xvfb or a video encoder in this mode. Every viewer sees the
same page, rather than opening an independent webpage.

## Install and hosting mode

Update the server and every client to 1.0.5 together (required protocol 4).
Use the full `EAMedia-1.0.5.jar` on clients. The Minecraft server can use the much
smaller `EAMedia-1.0.5-server.jar` in the default client mode; install one variant.
ELN 3.0.60+ remains supported and no core update is required.

The first nearby observer hosts the TV. Taking control does not transfer hosting.
If the host disconnects or leaves 32 blocks, another viewer takes over. A new host
reloads the shared address and attempts to resume ordinary video from the saved
playback position. Arbitrary page forms, live calls, cookies and login state do
not transfer between clients. With no nearby viewer, client hosting stops.
The host's browser profile belongs to that client. This is the tradeoff for
keeping browser rendering and video decoding off the Minecraft server.

The host displays its browser texture directly, with updates capped at 30 fps.
Other viewers receive a 640×360 JPEG mirror capped at 20 fps, using lower JPEG
quality when needed to fit the existing byte budget. This is a target/cap, not a
promised frame rate; the host's CPU, network, page and client performance matter.
Conversion to Minecraft's pixel format runs off the render thread. Queues stay
bounded and discard old frames rather than accumulating playback delay.

## Docker Compose

Default client hosting works with the ordinary `itzg/minecraft-server:java21`
image. The extra Xvfb image is unnecessary. Keep your current world volume,
ports, memory, NeoForge and Minecraft settings, and use:

```yaml
services:
  minecraft:
    image: itzg/minecraft-server:java21
    # Remove build: if it was only used to add TV browser dependencies.
    environment:
      JVM_OPTS: "-Deamedia.tvHosting=client"
```

Merge that property into any existing `JVM_OPTS`; retain the other environment
entries in your Compose file. Omitting the property also selects client mode.
Replace the server addon jar and recreate the service:

```bash
docker compose up -d --force-recreate minecraft
```

The earlier custom image can also be retained; its browser libraries simply go
unused in client mode. Existing browser cache directories can remain in `/data`.
No extra ports, GPU, display service or host audio device are required.

## Controls and audio

Open the TV, paste a complete HTTP/HTTPS address and press **Go**. The controller
can click, type, paste, drag, scroll and use back/forward/reload. **Take control**
requests the one interactive lease; **Ctrl+L** selects the address bar. Closing
the controls leaves the TV playing. Pasted text comes from the controller; the
host computer's clipboard is never read. File dialogs and executable URL schemes
are disabled; popups navigate the same browser.

Both host and viewers play captured stereo 24 kHz PCM through Minecraft's sound
engine. The host's webpage speaker output is routed through zero gain while its
document capture remains available. The TV volume is shared; each player's
**Master** and **Jukebox/Note Blocks** sliders affect only their local playback.
Muting the host must not mute the other viewers' captured audio. Stopped streams
are recreated, and short OpenAL underruns resume the existing TV channel.

The baked cabinet and electrical connections remain unchanged: 120/240 V cord or
fixed blue input, 150 W while on and 2 W standby at nominal voltage. Power loss
closes playback. Power return reloads and attempts ordinary media resume where
the website allows it. Browser session, electrical and item IDs remain compatible.

Approximate stream limits remain 800 kB/s JPEG and 120 kB/s audio per TV. The host
uploads one stream, and server outgoing traffic scales with other viewers. This
is a compressed screen mirror, rather than a direct original-quality video feed.
Some websites prevent document audio capture. The pinned native capture callback
is not used because that upstream implementation leaks native allocations.
Protected codecs, DRM and site login policies can still limit playback.

## Optional server hosting

To retain the 1.0.4 server-owned browser, set the **server's** JVM property to
`-Deamedia.tvHosting=server` and restart. This mode needs the **full addon jar on
the server**, plus Xvfb/native libraries on headless Linux. It uses software
rendering and a 960×540 mirror capped at 12 fps; it is not the recommended mode
for a server that struggled with video playback. Native CPU/memory and bandwidth
are shared with Minecraft, even though browser I/O runs outside the tick thread.

For Ubuntu 24.04, install these packages inside the container/image or on a
non-container server:

```bash
sudo apt update
sudo apt install -y xvfb tar libgtk-3-0t64 libnss3 libnspr4 libasound2t64 \
  libcups2t64 libxtst6 libxkbcommon0 \
  libx11-6 libxcomposite1 libxdamage1 libxrandr2 libgbm1 libxshmfence1 \
  fonts-dejavu-core fonts-liberation
```

Use a normal non-root server account. The server extracts its already-bundled
Chromium and starts a private Xvfb display when `DISPLAY` is unset. No physical
speaker is required. Profiles/logs live in the world's
`eamedia-browser/<dimension>/<position>/`; the runtime lives in `eamedia-server/`.

The default server-mode limit is two active browsers; override it with
`-Deamedia.serverBrowserLimit=4` (range 1–8). A server-hosted TV keeps its page
alive without viewers while loaded and powered. Chunk unload, power loss,
removal and server stop close the worker. No mode force-loads chunks.
