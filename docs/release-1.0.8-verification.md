# EAMedia 1.0.8 verification

All **56 unit tests** passed with ELN 3.0.67, including the existing shared browser, routing, audio transport and bundled runtime tests. New tests check the wall TV's separate save ID, wall support, actual screen geometry/texture placement and power-terminal directions for every horizontal rotation.

Both full and small server jars include the wall-TV model and recipe and require ELN 3.0.67. Packaging hashes are recorded under `docs/verification/release-1.0.8/`.

The opt-in packaged native campaign checks thirteen placed models (three media devices, five outdoor lights and five power cabinets), baked item previews in five display contexts, resource reload, four actual cords drawing UPS battery power, individual disconnect and UPS pickup/replacement. This uses a real Minecraft client and integrated server with software rendering and null audio. It does not measure hardware FPS or re-test live Internet video playback; media transport is unchanged.

The final packaged native run passed all **7 observations**. All thirteen models had terrain bodies and all 65 item/context combinations had baked geometry before and after resource reload. Four lamps measured 119.980–119.982 V from the compact UPS; individual disconnect and pickup/replacement preserved the expected connections and four cells. The cabinet close-up shows live output-voltage/charge readouts and the amber backup indicator. Screenshots and the full native report are included alongside the test reports.
