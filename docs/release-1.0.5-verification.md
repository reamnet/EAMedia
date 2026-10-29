# EA Media 1.0.5: Client-hosted TV playback

The 1.0.4 server browser uses software rendering, document audio capture and a
960×540 JPEG mirror capped at 12 fps. Those browser and encoding processes can
struggle with video independently of Minecraft's tick rate. The report concerns
TV picture/audio, rather than whole-server tick lag; source code identifies this
costly path, but no profile of the user's actual container was available.

## Changes

Client hosting is the default again. The server elects one nearby observer,
validates controls and relays bounded encoded data; it does not start a browser,
extract its native payload or create Xvfb in this mode. Taking control leaves
hosting on the same client. Other players still view that one browser, rather
than loading separate pages. Server hosting remains available through the
server JVM property `-Deamedia.tvHosting=server`.

The host displays its native browser texture directly, capped at 30 fps.
Mirrors are resized to 640×360 off-thread and encoded up to 20 fps, with JPEG
quality reduced to stay below 40,000 bytes per frame. This fits the unchanged
800,000-byte/s video budget. Viewer pixel conversion moves off the render
thread; texture storage is reused while dimensions remain the same. These are
caps, not guaranteed playback rates on all machines.

All players, including the host, use Minecraft's PCM sound path and local volume
controls. HTML media on a client host enters the capture processor directly,
whose only native output has zero gain. A prototype that redirected native
speakers while retaining `captureStream()` yielded silent capture in native
Chromium; the final path avoids that combination. Page Web Audio is tapped and
its speaker connection diverted through zero gain. Per-document audio is mixed
onto one bounded 24 kHz stereo clock. The older native audio callback remains
unused because the pinned upstream implementation leaks native allocations.

The host's Minecraft mute no longer changes the page's captured volume. Stream
recreation and short OpenAL underrun recovery from 1.0.4 are retained. A late
render frame can catch up at most 200 ms of audio, within the bounded sample
queue and the server's audio burst budget, rather than always sending only one
50 ms packet after a slow frame.

Host leases last 15 seconds to tolerate short stalls; controller leases remain
separate. An observer checks whether the host is still online, in the same
world and within range, replacing an invalid host without waiting for lease
expiry. A player can send their own leave notification after moving out of
range. Session, epoch, revision, sender, packet size and byte-budget checks still
protect the relay. Server-owned mode rejects client media publishers.

A new host reloads the shared address and attempts ordinary media resume.
Arbitrary forms, browser JavaScript state, cookies and logins cannot migrate
between client browsers. No viewer means no client browser; no chunks are
force-loaded. Users who need a browser that persists without viewers can opt
into the more costly server mode.

## Verification

The release build passes **39 tests in 9 suites**, with no failures, errors or
skips. Regressions cover both host modes, report authority, host/controller
independence, lease expiry, restart/save state, frame bounds and fragmentation,
byte budgets, JPEG dimensions/size, page gain/speaker routing, PCM mixing,
stopped queues and bounded catch-up after slow frames. No dependency or Gradle
wrapper change was needed. The only Gradle build-file edit corrects comments
about the small server jar.

A final localhost campaign produced **44 passing observations** using a dedicated
server and two separate Minecraft clients. The source video is 640×360 at 30 fps
with stereo audio. Checks confirm one client browser, no server browser worker,
remote clicks/typing, an unchanged host/epoch during controller changes, audible
PCM consumption on both clients, webpage mute, independent host mute/unmute,
sound-engine resets, host departure/handoff and electrical power recovery.

Both clients run Sodium 0.8.13, Iris 1.8.14 beta (shaders disabled) and Distant
Horizons 3.3.3, with core ELN 3.0.63. The build uses the declared ELN 3.0.60
minimum. Java 21, Linux x64, a private virtual display, llvmpipe and an OpenAL
No Output device provide functional checks without physical speakers. The
container shares four CPU cores between the server and two clients. Its short
rate sample was approximately 4.33 host paints/s and 4.08 mirror decodes/s;
this constrained software-rendered run does **not** demonstrate smooth 20/30 fps
or quantify a performance gain on the user's machine. Public YouTube/DRM sites,
physical speaker output, Windows/macOS native execution and optional server-mode
playback were not re-tested in this release.

[Unit totals](verification/release-1.0.5/units.json),
[native observations](verification/release-1.0.5/native.json),
[raw rate sample](verification/release-1.0.5/rates.json) and
[packaging verification](verification/release-1.0.5/packaging.json) are retained.
Both jar variants match the final compiled classes and browser script; only the
full jar contains Chromium archives. The isolated worker input shim is absent
from the root of both addon jars.

[TV hosting, Docker setup and tradeoffs](powered-browser-tv.md) ·
[Complete Compose example](compose-client-tv.yaml) · [Build instructions](building.md)

Protocol 4 requires updating the server and every client together. Clients use
the full `EAMedia-1.0.5.jar`; default client-mode servers may use the small
`EAMedia-1.0.5-server.jar` or the full jar. Optional server-hosted TVs require the
full jar and native browser setup. Item IDs, models, electrical behavior and
jukebox playback remain unchanged. ELN 3.0.60+ remains supported; no core update
is required.
