# Powered Web Jukebox (EA Media 1.0.0)

The jukebox uses 120 V by default, with 240 V selectable in its GUI. Connect a matching appliance
cord or a placed cable to its blue input. Playback enabled draws 25 W; standby draws 2 W.
Loss of power stops audio. Its stationary cabinet and speakers use the baked terrain renderer.

Open the GUI, paste a direct **HTTPS audio URL** or a **YouTube video URL**, and press
**Play / restart**. Stop ends playback; volume adjusts the block's level. Nearby listeners hear a
positional sound within 32 blocks, governed by Minecraft's **Jukebox/Note Blocks (Records)** and
master-volume sliders. Muting those sliders stops web-audio preparation on that client.

## Built-in media tools

FFmpeg, yt-dlp and the QuickJS JavaScript runtime needed by current YouTube extraction are **inside
the EA Media client jar**. Players do not need to install Python, FFmpeg, yt-dlp, QuickJS, or another media mod.
Direct Vorbis OGG uses Minecraft's decoder; other audio formats use the bundled FFmpeg. YouTube
uses the bundled yt-dlp/QuickJS and FFmpeg. The first relevant playback extracts only the client's
matching tools into `eln-media-runtime/` in the game directory. Extraction uses local jar resources
and does not download executables. Subsequent uses reuse the verified cache.

Supported bundles: Windows x86-64, Linux x86-64/ARM64 and macOS Intel/Apple Silicon. Each archive
and extracted executable has a pinned SHA-256; changed/corrupt cached tools are repaired from the
jar. All setup runs on background audio workers. The jar includes all supported platforms and is
therefore substantially larger, while each client extracts only its own platform.

The dedicated server needs no tool installation and does not execute the programs. It simulates
power and shares the chosen media URL. Optional advanced client `config/Eln.cfg` JSON keys
`media.ffmpegPath`, `media.ytdlpPath` and `media.quickjsPath` can override individual executable paths;
leaving them absent/empty uses the bundle. No PATH configuration is required.

## Playback and limits

Preparation downloads the selected track on background workers, then plays a local decoded
stream. This avoids network reads on the game, render and audio threads. Only HTTPS media is
accepted; direct requests reject private/local network destinations and redirects to non-HTTPS
addresses. Each source/decoded track is limited to 64 MiB, YouTube selections are limited to one
video of at most one hour, and up to four web jukebox jobs can be active on one client. Temporary
tracks are removed when playback closes. Downloads/conversions stop on power loss, leaving
range, block unload, or Stop. The extracted tool cache remains available for later playback.

This is a song/video-file player rather than an indefinite live-radio stream. A track starts after
preparation, and clients prepare independently; frame-exact multiplayer synchronization is not
provided. A completed song shows **Song finished**; press Play to restart. Media websites can
change their player/extraction requirements; updating the bundled yt-dlp may then be necessary.
Tool extraction itself works offline; fetching a selected HTTPS/YouTube track needs network access.

The original tool notices, per-platform FFmpeg build/source records and full license texts are
bundled under `assets/eln/media-runtime/notices/`, alongside a manifest and `notice.md`. The
refresh script is `tools/media/bundle_runtime.py`; ordinary builds use the already bundled files.
