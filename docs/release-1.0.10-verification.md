# EA Media 1.0.10

Requires ELN 3.0.68+ in the 3.0 series. The default build path selects that exact
core version and verifies the jar's embedded version and required APIs before
compiling. Install 1.0.10 on the server and clients; media protocol 8 advertises
the optional public-website policy consistently.

The three original media cabinets gain the shared core equipment finishes,
softened casing edges, rear ventilation and construction details. The jukebox
has twin speaker grilles. Screen planes, picture dimensions, fixed cable/cord
connections, saved item IDs and shared browser behavior retain their existing
positions and settings. Regenerate using `tools/models/refresh_media.py` with
the core checkout beside the addon; ordinary builds use committed assets.

Server owners can opt into all public TV websites by adding a literal `*` to
`config/eamedia-web-domains.txt` and restarting. The default remains the specific
domain list. The option retains public-address checks after DNS resolution,
HTTP/HTTPS port restrictions, authenticated active-host permissions, bounded
connections and bandwidth, and the existing upstream proxy behavior. It does
not expose a public proxy port or give viewers a way to edit the server policy.

See [TV configuration](powered-browser-tv.md) for the Compose command and exact
file location. Verification results are recorded under
`docs/verification/release-1.0.10/`.


## Results

The default build selected `ElectricalAge-3.0.68.jar` without a `-PelnJar` override
and passed **58 tests across 11 suites**, with no failures, errors or skipped tests.
The policy checks exercise public-site opt-in and removal, mixed/private DNS answers,
non-web ports, client status synchronization and an actual HTTP round trip through
SOCKS and the Minecraft packet codec. The test transport allows loopback only via an
explicit development-only fixture setting; separate production-policy checks reject
those addresses. No claim is made about every live website, codecs or DRM.

The combined packaged native run with core 3.0.68 loaded all **225** refreshed OBJ
meshes (222 core and three addon models) with atlas-backed materials and finite
positions/UVs/normals. All three media models produce nonempty baked previews in five
display contexts, including after a resource/atlas reload. The combined run checks
755 item-display cases and the core's actual refrigerator menu/thermal behavior.
It uses Sodium 0.8.13, Iris 1.8.14-beta.1 and Distant Horizons 3.3.3, with shaders off.
This is rendering/correctness verification on a software GPU, not an FPS benchmark
or a new public-YouTube playback test.

[Unit totals](verification/release-1.0.10/units.json),
[native model/preview checks](verification/release-1.0.10/native.json),
[model hashes](verification/release-1.0.10/models.json), and
[packaging](verification/release-1.0.10/packaging.json) record the results.
The release has source only; the owner attaches compiled jars separately.
