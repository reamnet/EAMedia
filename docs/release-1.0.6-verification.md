# EA Media 1.0.6: 960×540 shared TV at up to 30 fps

The default client-hosted TV now keeps the full 960×540 browser image for the
host and mirrored viewers, with both paths capped at 30 fps. Version 1.0.5
reduced viewer images to 640×360 and capped their stream at 20 fps. This release
removes that downscaling and restores 1.0.3's JPEG allowance: start at quality
0.65 and reduce it only if a frame exceeds 131,072 bytes. Mirrored pictures are
still JPEG-compressed; they are not pixel-identical to the host's local texture.

The client encoder and server relay share the frame-rate, resolution and budget
constants. The relay accepts up to 3,932,160 video bytes/s, enough for 30 maximum
size frames per second, with its existing two-frame burst capacity. Packet
fragments remain capped at 24,000 bytes and a frame at six fragments, so the
individual Minecraft payload limit is unchanged. Encoders/decoders still run
outside the render thread, allow at most one pending frame per TV and drop stale
work rather than accumulating playback delay. Capture pacing follows the host's
paint cadence to avoid losing alternate frames due to callback timing jitter.

The larger stream can use about 31.5 Mbit/s of host upload per TV, plus audio and
packet overhead, at the maximum frame size and rate. Server outgoing traffic
scales with other viewers. Actual usage depends on JPEG size. Source page frame
rate, host/client performance and network capacity determine delivered fps;
30 fps is an upper limit, not a measured guarantee on every machine.

Client hosting remains the default. Audio mixing, local mute, stopped-stream
recovery, handoff and electrical behavior retain the 1.0.5 implementation.
Optional software-rendered server-owned hosting still has its 12 fps cap.
Required protocol 5 prevents an older 1.0.5 relay from silently throttling the new
stream; update the server and all clients together. No core update, dependency
or Gradle wrapper/configuration change was required.

## Verification

The final release build passes **40 tests in 9 suites**, with no failures,
errors or skips. [Unit totals](verification/release-1.0.6/units.json) and
[packaging checks](verification/release-1.0.6/packaging.json) are retained.
Both jar variants contain exactly the final compiled classes and browser script;
the full jar includes Chromium and the small server jar omits its archives.

The image regression encodes a detailed 960×540 noise image, decodes it and
checks full dimensions and the bounded frame size. A new deterministic test
passes every fragment of 60 maximum-size frames through the actual relay's
budget at 30 fps, then repeats after a session reset. It also verifies that an
oversized burst is rejected. Existing bounds, fragmentation, clock, audio,
lease, saved-state, model and packaging tests remain in the suite.

Native multiplayer/audio observations from 1.0.5 remain documented in
[the previous verification](release-1.0.5-verification.md). They are not claimed
as native 30 fps measurements for this release. No new GPU, live YouTube/DRM or
physical-speaker performance benchmark was performed.

[TV settings and bandwidth](powered-browser-tv.md) · [Build instructions](building.md) ·
[Docker Compose example](compose-client-tv.yaml)
