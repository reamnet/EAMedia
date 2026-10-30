# Powered Browser Television

EA Media 1.0.7 uses **client hosting by default**. One nearby player's client
runs the full interactive browser; everyone else receives its shared picture and
audio. The server validates controls, forwards approved website traffic and relays
bounded picture/audio packets. It does not launch Chromium, Xvfb or a video
encoder in this mode. Every viewer sees the
same page, rather than opening an independent webpage.

## Install and hosting mode

Update the server and every client to 1.0.7 together (required protocol 6).
Use the full `EAMedia-1.0.7.jar` on clients. The Minecraft server can use the much
smaller `EAMedia-1.0.7-server.jar` in the default client mode; install one variant.
ELN 3.0.60+ remains supported and no core update is required.

The first nearby observer hosts the TV. Taking control does not transfer hosting.
If the host disconnects or leaves 32 blocks, another viewer takes over. A new host
reloads the shared address and attempts to resume ordinary video from the saved
playback position. Arbitrary page forms, live calls, cookies and login state do
not transfer between clients. With no nearby viewer, client hosting stops.
The host's browser profile belongs to that client. This is the tradeoff for
keeping browser rendering and video decoding off the Minecraft server.

The host displays its browser texture directly, with updates capped at 30 fps.
Other viewers receive a full 960×540 JPEG mirror capped at 30 fps. JPEG quality
starts at 0.65, with the 1.0.3 per-frame allowance of up to 131,072 bytes; it is
reduced only if needed to fit that limit. There is no 360p downscaling.
This is a target/cap, not a promised frame rate; the host's CPU, network, page and client performance matter.
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

## Website traffic through the server

The hosting client renders the browser, but HTTP/HTTPS website connections and
DNS resolution now run through the Minecraft server. The website sees the
server's outbound address (or its configured upstream proxy), rather than the
hosting client's direct connection. Traffic travels over the existing game
connection; there is no new public proxy port, container or client password.
Your Compose port mapping stays `25565:25565`.

Only a logged-in player currently hosting at least one loaded, powered TV
receives routing permission. Viewer/control access alone is insufficient. Loss
of the last hosted TV or player disconnect revokes it; stale permissions expire. HTTPS stays encrypted
between Chromium and the website: the mod does not substitute certificates,
terminate website TLS or move cookies/login state onto the server.

The server owner approves destinations in:

```text
config/eamedia-web-domains.txt
```

With your Compose volume, that is `./data/config/eamedia-web-domains.txt` on the
host. The file is created on first server start with YouTube-related domains.
Each line permits a domain and its subdomains. Add other specific sites and
restart the server; for example:

```text
# Keep the existing YouTube entries, then add approved websites:
example.com
video.example.net
```

Up to 32 entries are supported. To allow **all public websites** in EA Media 1.0.10+, add a literal `*` on its own line (or replace the other entries with it):

```text
*
```

Restart the Minecraft server after saving. With the Compose service named `minecraft`:

```bash
docker compose restart minecraft
```

Install 1.0.10 on the server and all clients. This setting belongs to the server owner; a TV viewer cannot enable it from the browser. It retains server routing, active-host authorization, connection/bandwidth limits, and rejection of local/private destinations and ports other than 80/443. The default remains the domain list above. Remove `*` and restart to return to specific domains.

In domain-list mode, page resources, embedded players, login providers and CDNs also need approval if they use other domains. The server logs newly blocked domains
(without full URLs or cookies) to help the owner update the file. Private/local addresses and ports other
than 80/443 are rejected even when a domain is listed. A blocked main page shows
“This website is not approved by the server.” YouTube/other sites can still
change their required endpoints, codecs, sign-in flows or DRM behavior.

Routing fails closed: if the server route is unavailable or a destination is
not approved, the browser does not switch to a direct client connection. QUIC
and unproxied WebRTC UDP are disabled for the browser engine. To verify an exit
address, the server owner can approve an IP-check website such as
`api.ipify.org`, restart and open `https://api.ipify.org/` on the TV.

The pinned MCEF bridge exposes engine-wide proxy settings, so other mods using
that same Chromium engine share this routing policy and require an active TV
host permission. Ordinary desktop browsers and Minecraft's other networking are
unaffected. The jukebox's separate media-tool downloads remain client-side.

The server uses bounded buffers, per-stream credits and connection limits.
DNS/connect and socket forwarding run on I/O workers; payload handling uses the
network thread, rather than the simulation thread. Forwarded web traffic still
uses server bandwidth, Minecraft packet processing and the same TCP connection
as gameplay. The hosting client still receives the source media and uploads the
shared picture. This moves the website exit point; it does not eliminate client
rendering/network costs or guarantee a performance gain.

An administrator can disable this route with `-Deamedia.webRouting=false` and a
restart. It disables client-hosted TV web access rather than enabling a direct
fallback. A deliberately configured server `HTTPS_PROXY`/`HTTP_PROXY` upstream
is honored; its own routing and DNS policy also applies.

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

The video budget allows 3,932,160 bytes/s (about 31.5 Mbit/s) per TV at the
maximum JPEG size and frame rate, plus up to 120 kB/s audio. Actual JPEG traffic
depends on the page and is usually lower. The host uploads one stream, and
server outgoing traffic scales with other viewers. This
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

## Browser verification pages and web test servers (1.0.11)

Restart every client after installing 1.0.11: Chromium startup settings cannot be
changed by simply toggling a TV. The bundled engine is Chromium 116. The browser now keeps its native Chromium version
in the user agent, instead of MCEF's replacement `MCEF/2` product. Explicit user
agent overrides are preserved. Normal web-origin and TLS checks are enabled.
Turnstile frames and Cloudflare challenge infrastructure are excluded from the
TV's audio-capture instrumentation. No CAPTCHA-solving or clearance-cookie
sharing is performed. Cloudflare can still reject the bundled embedded engine or
the Minecraft server's public IP; successful Rumble verification is not guaranteed.
MCEF 2.1.6 remains the newest published NeoForge 1.21.1 build checked for this patch.

Server-routed browsing now allows public web endpoints on **80, 443, 8080 and
8443**. This includes the alternate HTTP/WebSocket ports commonly used by Ookla
Speedtest servers. The server owner can replace this set using a JVM property such
as `-Deamedia.webPorts=80,443`; up to sixteen explicit ports are accepted. The
website-domain file (including an existing `*` choice), public-address checks and
host permission still apply. Additional blocked hosts/ports are reported once per
peer in the Minecraft server log, without page contents or cookies.

A speed test in the TV measures the complete server-routed Minecraft connection,
including the mod's bandwidth limit and transport overhead. It is not a direct ISP
speed measurement. The route remains TCP-based; sites that require UDP/WebRTC test
traffic may be unsupported. No website traffic falls back to the player's direct
connection.
