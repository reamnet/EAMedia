# Powered Browser Television

The Powered Browser Television is a wide, freestanding 16:9 TV with a working Chromium browser.
Webpages, HTML5 video, direct video streams and video websites render on the placed screen with
stereo audio. The cabinet, stand and inventory preview use baked geometry; only the screen is live.

Connect a 120 V cord or a placed cable to the blue input on the left or bottom. The controls can
select 240 V before attaching a matching 240 V cord. The load is 150 W while on and 2 W in standby
at nominal voltage. It uses passive resistance, so input power follows the actual supply voltage.
An insufficient or lost supply stops the browser; restoring power reloads the selected page.

## Controls

Right-click the TV to open its browser controls. Paste a complete `https://` or `http://` address
and press **Go** or Enter. Click the picture to interact with the webpage. Mouse clicks, dragging,
scrolling, keyboard input and browser text shortcuts are forwarded to Chromium. **Ctrl+L** selects
the address bar. Back, forward and reload navigate the shared TV.

**TV off** stops playback. **TV on** opens the address in the bar. The voltage button selects
120/240 V when no cord is attached. Minus/plus adjust the TV's volume. Escape closes the controls;
the same browser continues playing on the placed TV.

The TV uses one shared browser screen. See **Shared screen (1.0.3)** below for control, hosting and network details.

## Browser installation and sound

EA Media includes the MCEF 2.1.6-1.21.1 bridge **and its matching Chromium runtimes inside the jar**.
A separate MCEF installation and first-launch runtime download are unnecessary. EAMedia verifies
and extracts only the current platform locally, with pinned SHA-256 checks for archive parts and
all extracted files. A corrupt cache is repaired from the jar. The browser installer performs no
checksum or runtime network requests. Websites themselves still need network access.

The browser bundle supports Windows x86-64, Linux x86-64, and macOS Intel/Apple Silicon. The
publisher's Linux/Windows ARM64 archives for this pinned revision contain x86-64 binaries, so
those two platforms are not falsely advertised as supported. Native OS libraries such as Linux
GTK/NSS/audio still come from the operating system. Dedicated servers never extract or launch
Chromium and can use the much smaller `-server.jar`.

FFmpeg, yt-dlp and QuickJS are bundled separately for jukebox playback. Source URLs, pinned
revision, hashes and original runtime licenses are recorded in `assets/eamedia/chromium/`.
The refresh script is `tools/media/bundle_chromium.py`; ordinary builds use committed resources.

The hosting client uses Chromium's normal stereo output device; other viewers play the captured PCM stream through Minecraft. ELN applies TV volume, distance attenuation,
and Minecraft's Master and Records sliders inside each webpage to HTML5 media and newly connected
Web Audio output. The webpage's own mute button remains independent. Audio is stereo rather than
a directional OpenAL sound. Unusual pages that replace browser APIs or create audio before the
control script is installed can behave differently from ordinary HTML5 video players.

Streaming-site support depends on the website and the embedded Chromium build. JavaScript and
ordinary browser video are supported; this does not guarantee DRM-protected subscription services,
every codec, or websites that require a newer browser. File picker dialogs and external popup
windows are not opened; ordinary HTTP(S) popup links open in the TV's current page instead.

## Performance and checks

The browser viewport is 960×540, with screen texture uploads capped at 24 per second. At most two
TVs are active per client; only the elected host opens Chromium for each TV. Moving beyond 32 blocks, losing power or unloading/breaking the TV
closes its browser and releases its texture and audio resources. Watching streaming video still
has CPU/GPU costs; a live video screen cannot be baked into static terrain.

For the opt-in native test, serve the original local video/audio fixture:

```bash
python3 tools/media/tv_test_server.py
```

Launch the development client with `-Deln.televisionChecks=true` and
`-Deln.tvTestUrl=http://127.0.0.1:8765/index.html`. It creates a separate world and writes its
report and screenshots under `run/client/television-checks/`. Leave these flags off during normal
play. The fixture video can be regenerated with FFmpeg:

```bash
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=24 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 -t 12 \
  -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -b:v 300k \
  -c:a libopus tools/media/tv_fixture/test.webm
```

The bridge license and source/build links are in `assets/eln/browser-notices/` inside the jar.

## Go-button fix

ELN 3.0.60 accepts the addon's standalone screen controls without requiring an inventory menu.
Earlier core versions rejected Go/settings packets for the TV's plain screen, leaving both its
picture and in-world screen black. Controls still require the player to be in the same dimension
and within eight blocks. This addon requires ELN 3.0.60 or newer in the 3.0 series.

## Shared screen (1.0.3)

Each powered TV now elects one nearby client to run its browser. That client sends the rendered
picture and captured audio through the server to the other viewers. Viewers do not open private
copies of the page: links, forms, videos, pause/seek actions and browser navigation use the same
browser instance. The placed screen and browser controls show that shared picture.

Right-click to request control. Only one player controls a TV at a time; close its controls to
release control. **Take control** retries after the other player finishes. The browser host can
remain a spectator while another player controls it. Back/forward use the TV's shared URL history;
Go/reload navigate for everyone. Clipboard paste sends the controlling player's text.

Mirrored pictures use bounded JPEG frames, up to 960×540 at 12 frames/second, with stereo 24 kHz
PCM audio and bounded buffers. Compression and network delay mean this is not a lossless,
frame-perfect video feed. The server relays data without running Chromium or decoding images.
Budget limits cap incoming video near 800 kB/s and audio near 120 kB/s per TV; outgoing bandwidth
scales with viewers. Each client may watch two TVs within 32 blocks. Power loss closes the browser
and clears its stream. A late viewer receives the current picture.

The browser runs with the elected host's website session. Other viewers can see that page and,
when given control, interact with it. Cookies are not copied between players. When the host leaves,
another viewer opens the shared URL and attempts to resume ordinary media at the saved position;
arbitrary form state, logins and browser memory do not migrate. Reloading or losing power has the
same page-state limitation. Protected media and websites that prevent audio capture may have a
visible picture without mirrored audio. Streaming support still depends on Chromium and the site.
