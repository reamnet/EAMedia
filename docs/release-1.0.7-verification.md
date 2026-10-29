# EA Media 1.0.7: Server-routed TV website traffic

Client-hosted TVs now open their HTTP/HTTPS connections through the Minecraft
server. The client still runs Chromium, decodes media and supplies the shared
960×540 picture/audio stream at the existing up-to-30-fps cap. The server routes
website bytes and DNS; it does not render the browser or decode website media.
There is no new public listening port, Docker container or proxy password.

## Routing and permission

The pinned MCEF/JCEF bridge has engine-wide proxy settings rather than a
per-browser proxy preference API. Before its native startup, a client-only mixin
points Chromium at an IPv4-loopback SOCKS5 endpoint. Conflicting proxy flags are
removed; no direct fallback is configured. Client DNS mapping, disabled QUIC and
the WebRTC IP-handling policy prevent ordinary browser DNS/UDP bypasses. Other
mods using that same CEF engine share this policy; ordinary desktop browsers and
Minecraft's other network activity do not. Jukebox tool downloads are unchanged.

The endpoint carries small TCP fragments over the existing authenticated game
connection. The server grants permission only to a logged-in connection currently
hosting a loaded, powered TV. Being a viewer or controller alone is insufficient.
Hosting observations renew a 15-second grant. Handoff replaces its owner;
disconnect and loss of the last hosted TV revoke it. Stale grants expire and
close their streams. Player UUID plus actual connection identity prevent old
connection packets from gaining a new session's access.

Server operators approve destinations in `config/eamedia-web-domains.txt`,
created with specific YouTube-related domains. It accepts at most 32 domains,
including their subdomains, and no unrestricted wildcard mode. Approval runs
before DNS; every resolved address must be public. Private, loopback, link-local,
multicast, shared-address and common reserved ranges are rejected, as are destination
ports other than 80/443. Ordinary connections use a validated numeric address,
avoiding a second DNS lookup at connect. An intentionally configured server
HTTP/HTTPS upstream proxy is honored and applies its own final routing policy.
Newly blocked domain names are logged sparingly; request URLs, headers and bodies
are not logged. Packet diagnostics redact byte contents.

HTTPS is passed through without the mod terminating website TLS or substituting
certificates. Cookies and login state remain in the hosting client's browser.
Raw website data is not written to a server-side browsing cache. Website sign-in,
CDN and embedded-player domains also need operator approval if outside the
initial list. A blocked main URL displays a clear approval message. A missing or
disabled route fails closed instead of switching to the client's direct network.

## Bounded I/O

Each direction has a 64 KiB receive/credit window. Credit is returned after a
socket write, so slow peers apply backpressure. A fixed ring buffer accepts small
fragments without accumulating an unbounded queue. TCP end-of-stream drains
pending data before half-closing the other side, avoiding truncated responses.
Each packet carries at most 16 KiB; streams are capped at 64 per player and 256
server-wide. DNS/connect tasks use eight bounded workers. Forwarding uses Java
21 virtual threads with condition waits that do not pin their carrier threads.
Connection attempts and byte rates are bounded per player.

Payload handling uses NeoForge's network-thread mode. DNS, connect, socket reads,
writes and waits never run on the simulation thread. The server tick only checks
hosting permissions and initiates cleanup. The connection shares Minecraft's
bandwidth and packet/compression work; routing is not a promise of lower MSPT or
higher FPS. The hosting client still receives source media and uploads pictures.

Status can arrive before the client has installed its player during login.
The new handler checks packet direction before accessing a player, so that
transition does not disconnect a second viewer. Failed configured-upstream
connections close their sockets. HTTP and HTTPS certificate checks are retained.

## Verification

The final build passes **52 tests in 10 suites**, with no failures,
errors or skips. [Unit totals](verification/release-1.0.7/units.json) and
[packaging checks](verification/release-1.0.7/packaging.json) are retained.
Both jar variants match the final compiled classes, browser script and mixin
configuration; only the full jar contains Chromium archives.

Tests cover approved-domain boundaries and invalid wildcard entries; approval
before DNS; private/mixed-address rejection; host ownership, revocation and lease
expiry; packet limits; byte-content redaction; real HTTP bodies larger than the
window; TLS passthrough with the original origin certificate; absent-route
failure without a direct connection; overflow/credit bounds; startup proxy
flags; status arriving before a client player exists; and configured-upstream
success/failure cleanup. Existing TV, audio, frame-rate budget, model, migration
and bundled-runtime tests remain in the suite.

A final native campaign produced **32 passing observations**. A reserved
hostname (`fixture.eamedia.test`) was mapped only by the server's explicitly
gated development resolver and approved only in the isolated test server's
operator file. Chromium's client resolver could not resolve that hostname.
Successful page/video loading and over one megabyte recorded by the server's
forwarder therefore exercise the actual server route, not a direct fallback.
The test confirms shared controls and PCM consumption on two clients, denial of
a non-host viewer's SOCKS request, rejection of an unapproved URL, restoration
of the approved URL, routed host handoff, immediate permission loss on power-off
and recovery on power-on. No server browser worker is used. [Native observations](verification/release-1.0.7/native.json)
and a [controls screenshot](verification/release-1.0.7/server-routed-controls.png)
are retained. The development loopback/extra-port exception requires both
explicit test flags and is unavailable in normal server operation.

The native test uses a dedicated localhost server and two separate clients on
Linux x64 / Java 21 with core ELN 3.0.63, Sodium 0.8.13, Iris 1.8.14 beta and
Distant Horizons 3.3.3. Shaders and DH distant generation/rendering are disabled.
A private display, software graphics and OpenAL No Output/ALSA null devices let
transport and audio consumption be observed without physical speakers. A 360p,
30-fps local video supplies the test media; no production FPS/MSPT benchmark,
physical-speaker measurement, live YouTube/DRM test or Windows/macOS native run
is claimed. The release build references the declared core 3.0.60 minimum.

Update the server and every client together: required protocol 6 includes the
new tunnel payload. The full client jar and small server jar retain their roles;
client-mode servers can use either variant. No core update, dependency addition
or Gradle configuration/wrapper change was needed. The domain file maps to
`./data/config/eamedia-web-domains.txt` in the documented Compose volume.

[TV routing and domain setup](powered-browser-tv.md#website-traffic-through-the-server) ·
[Compose example](compose-client-tv.yaml) · [Build instructions](building.md)
