# EA Media 1.0.1: Visible TV and jukebox cabinets

Fixes both invisible placed media blocks and empty inventory previews. After the addon split,
`MediaDescriptor` still asked `Eln.obj` for the cabinet models. ELN's model-folder discovery only
walks the core mod file, so these addon-owned models resolved to its empty fallback. The TV's
independent browser picture and jukebox audio could still work without a cabinet.

Each media descriptor now loads its own OBJ/MTL resources directly, caches the parsed model,
and verifies that its main cabinet parts exist. Both static bodies and item previews continue
using the normal baked mesh path, with the original materials/colors. No device IDs, recipes,
power behavior, saved settings or media backends change. Core ELN remains at 3.0.60.
Existing devices do not need to be broken or replaced; restart the client with the updated addon.

## Validation

All **18 addon tests** pass with no failures, errors or skips:
[unit results](verification/release-1.0.1/units.json). The new regression demonstrates the empty
core model-folder fallback and verifies that each descriptor loads all the required cabinet parts
without putting either model into that core registry.

The opt-in `-Deln.mediaModelChecks=true` campaign runs from the **actual packaged addon jar**,
installed in the core development client's mods folder. It requires nonempty vertex data from
each actual baked terrain mesh and nonempty baked item quads in GUI, ground, fixed, first-person
and third-person contexts. It repeats both checks after an actual resource reload. This avoids
the previous test's weak assumption that a registered baked body necessarily contains geometry.
Native checks use Sodium 0.8.13, Iris 1.8.14-beta.1, BSL v10.1.8 and Distant Horizons 3.3.3.

All **five packaged-client checks pass**:
[native report](verification/release-1.0.1/native.json). The actual baked terrain bodies contain
1,512 jukebox vertices and 168 TV vertices; each item context has 384 and 48 quads respectively,
both before and after resource reload. Screenshots confirm the visible cabinets and item icons:

- [Placed TV and jukebox](verification/release-1.0.1/media-cabinets-world.png)
- [Inventory previews](verification/release-1.0.1/media-inventory.png)

The [packaging audit](verification/release-1.0.1/packaging.json) verifies both models/recipes,
the unchanged publisher MCEF checksum, all four packaged browser archive hashes and the small
server variant without native payloads. Native execution was tested on Linux x64.

Source-only prerelease. Build from the EA Media checkout with a full JDK 21:

```bash
./gradlew --no-daemon --max-workers=2 build
```

Outputs: `build/libs/EAMedia-1.0.1.jar` and `build/libs/EAMedia-1.0.1-server.jar`.
The existing core 3.0.60 build is sufficient; [full build instructions](building.md).
