# EA Media 1.0.2: TV status LED below the screen

Moves the TV's small green power indicator from the middle of the picture onto the lower
bezel. The TV previously inherited the jukebox's indicator position, placing a green box
in front of its browser screen. It is now a thin 30 × 8 mm LED, projecting just 3 mm from
the cabinet. The live picture stays clear.

Both the model generator and shipped OBJ are updated. Comparing model parts confirms that
only the TV indicator changed; the remaining TV geometry and entire jukebox model are identical.
The [measured bounds](verification/release-1.0.2/indicator-bounds.json) put every indicator vertex
below the screen and behind its picture plane. [Packaging checks](verification/release-1.0.2/packaging.json)
confirm the generated model and correct version are present in both addon variants.

All five existing [packaged-client model checks](verification/release-1.0.2/native.json) pass
with Sodium, Iris/BSL and Distant Horizons, including baked item previews and resource reload.
The [in-world screenshot](verification/release-1.0.2/media-cabinets-world.png) shows the clear screen
and indicator mounted below it.

This is a model-only correction. IDs, saved settings, playback and power behavior remain unchanged.
Core ELN 3.0.60 remains compatible; rebuild the addon only:

```bash
./gradlew --no-daemon --max-workers=2 build
```

Outputs: `build/libs/EAMedia-1.0.2.jar` and `build/libs/EAMedia-1.0.2-server.jar`.
Source-only prerelease; attach your compiled jars. [Build instructions](building.md).
