# EA Media 1.0.0: Optional TV/jukebox with offline browser bundles

Moves the powered browser TV, powered web jukebox, their device logic/controls/models/recipes,
MCEF Java bridge, FFmpeg, yt-dlp and QuickJS out of ELN into an optional addon. The original
`eln:*` item names and group 126 slots 9–10 stay intact. Installing core alone hides the two
devices and retains their saved settings in inactive placeholders; adding EAMedia restores them.

ELN 3.0.60 is required. It fixes the original TV Go/settings rejection: a plain browser screen
has no inventory menu. Standalone media controls now validate the player and eight-block range
without requiring an industrial inventory. Other industrial inventory controls retain their guard.

## Bundles and installation

The full client jar includes matching Chromium for Linux x86-64, Windows x86-64, macOS Intel
and macOS Apple Silicon. Native binaries were checked for their actual CPU architecture. At this
revision the publisher's Windows/Linux ARM64 archives contain x86-64 binaries; those are recorded
as unsupported rather than packaged under a misleading native architecture.

The installer verifies local archive chunks and every extracted file with pinned SHA-256 hashes.
It repairs a corrupt cache from the jar and intercepts MCEF's normal checksum/download step so
startup does not contact a browser-download server. The pinned unmodified MCEF bridge is exactly
2.1.6-1.21.1, matching Java CEF revision `a78e832f9f13c2c688caea3d04d8b84fcd238d94`.
Native OS libraries still come from the operating system; websites/audio need network access.

The source macOS archives contain GNU sparse tar records that Minecraft's Commons Compress
1.26 fails to expand reliably. The maintenance bundler normalizes those tar records, retaining
every file byte and executable mode. Both publisher and packaged archive hashes are recorded.
Ordinary Gradle builds use committed payloads and do not run either maintenance downloader.
Original licenses, source/build URLs and per-file hashes are retained with the bundles.

The optional `-server.jar` contains the same saved-device logic and handshake, with the small Java
browser bridge but no Chromium or media-tool payloads. It never extracts or executes client tools.
Use one addon variant per installation: full jar on clients; small server jar on dedicated servers.

## Verification

All **17 addon tests** pass without failures, errors or skips:
[unit results](verification/release-1.0.0/units.json). They cover stable legacy registry IDs,
placeholder-to-addon saved-data restoration, URL restrictions, matching media-tool hashes,
actual Linux media-tool execution and volume-script behavior, all four complete browser archive
hashes, extraction/verification of every platform's files, cache repair/reuse and path rejection.
Extraction checks for other operating systems do not claim native execution on those systems.

The real client campaign passes **10 checks** with NeoForge 21.1.249, Sodium 0.8.13,
Iris 1.8.14-beta.1, BSL v10.1.8 and Distant Horizons 3.3.3:
[native report](verification/release-1.0.0/native.json). It uses the actual Go and volume buttons,
decodes moving WebM video with nonzero audio samples, forwards mouse/keyboard input, retains
playback after closing controls, stops/restarts on actual placed-cable power loss/restoration,
releases the browser beyond 32 blocks, rejects remote controls beyond eight blocks, and starts
an HTTPS jukebox stream using its real Play button. Measured TV draw is about **150 W**.

Both a fresh cache and a reused cache initialize Chromium while the MCEF download mirror points
to an unavailable localhost port: [offline setup proof](verification/release-1.0.0/offline-browser.json).
The native renderer/audio test ran on Linux x64 with a headless audio device; physical speaker
output, live YouTube playback, DRM services and other operating systems are not claimed.

- [Playing TV in the world](verification/release-1.0.0/tv-playing-world.png)
- [Browser controls](verification/release-1.0.0/tv-browser-controls.png)
- [Power loss](verification/release-1.0.0/tv-power-off.png)
- [Jukebox controls](verification/release-1.0.0/jukebox-controls.png)

The final [packaging audit](verification/release-1.0.0/packaging.json) verifies the pinned
publisher MCEF SHA-512, every packaged Chromium archive hash, both device models/recipes,
and absence of the ELN core jar/compiler/parser libraries from the addon. Measured outputs:
full client jar 764,441,710 bytes; dedicated-server variant 359,334 bytes.
The [real dedicated-server check](verification/release-1.0.0/server.json) loads that small jar,
starts and stops cleanly after 40 ticks, without initializing Chromium or extracting media tools.

The [build guide](building.md) explains the paired core/addon builds and server variant.
This is a source-only prerelease; compile the jars and attach them to the release.
