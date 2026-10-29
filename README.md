# Electrical Age Media

Optional powered **browser TV** and **web jukebox** for [Electrical Age](https://github.com/reamnet/ELN1.21.1ForkPatch)
on Minecraft 1.21.1 / NeoForge. Install ELN 3.0.60+ and EA Media on both sides to enable them;
leave EA Media out for a lightweight ELN installation without either device.

EA Media 1.0.7 provides 960×540 TV pictures at up to 30 fps for the host and shared viewers. It defaults to client-hosted TVs with a shared picture, controls and Minecraft audio for every viewer. TV websites now load through the Minecraft server using its approved-domain list; the server also relays the shared stream without running Chromium. Server-owned browser hosting remains an optional JVM setting; see the TV guide.

The client jar contains Chromium, MCEF, FFmpeg, yt-dlp and QuickJS. Matching tools extract
locally, with hash verification; no first-launch browser/tool download is needed. A small
`-server.jar` supplies device logic on dedicated servers without the native archives.

- [Install, migrate and build](docs/building.md)
- [TV controls and browser support](docs/powered-browser-tv.md)
- [Jukebox controls and bundled tools](docs/powered-web-jukebox.md)

The devices keep their original `eln:*` IDs, recipes, model appearance and saved settings.
Both cabinets and inventory previews are baked; the TV's live video texture remains dynamic.
Use **1.0.1 or newer**: it fixes invisible cabinets by loading the addon's models independently
of the core model registry. Existing blocks do not need replacement.

Imported ELN code is LGPL-3.0-only, and imported models follow the original CC BY-NC-SA 3.0
asset license. See [LICENSE.md](LICENSE.md). Bundled third-party components retain their own
licenses and source/build notices under `assets/eln/media-runtime/notices/`,
`assets/eln/browser-notices/`, and the original licenses inside the Chromium archives.
The initial empty repository's GPL-2.0 license is retained as a historical record in
[docs/original-repository-license.txt](docs/original-repository-license.txt); no code from that
empty repository was imported.
