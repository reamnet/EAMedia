# Install and build EA Media

EA Media 1.0.8 requires Minecraft 1.21.1, NeoForge 21.1.249+, Kotlin for Forge NeoForge 5.12.0+,
and ELN 3.0.67+ in the 3.0 series. Kotlin for Forge remains a separate installation.

## Install

Use the full `EAMedia-1.0.8.jar` on clients. The smaller
`EAMedia-1.0.8-server.jar` supports TVs and jukebox logic in the default client-hosted
mode. Only optional server-owned TV hosting needs the full jar on the server.
Install exactly one variant, and install the addon on all clients and the server.
Do not install a different MCEF version alongside the included, pinned 2.1.6-1.21.1 bridge.

This addon release requires core ELN 3.0.67 for the new wall-TV item. All players and the server must use EA Media 1.0.8 for the new shared-screen network protocol. The TV and jukebox retain their old `eln:*` item IDs,
recipes, saved URLs, voltage, volume and cord links. Without the addon, core hides their recipes
and creative entries and keeps existing devices as inactive placeholders. Those placeholders
preserve saved data so reinstalling the addon restores it. Back up worlds before updating mods.

## Ubuntu 24.04 build

Use a full JDK 21. Build core first; keep the repositories beside one another:

```bash
sudo apt update
sudo apt install -y git openjdk-21-jdk-headless
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)"
export PATH="$JAVA_HOME/bin:$PATH"

git clone https://github.com/reamnet/ELN1.21.1ForkPatch.git
git clone https://github.com/reamnet/EAMedia.git
cd ELN1.21.1ForkPatch
./gradlew --no-daemon --max-workers=2 build
cd ../EAMedia
./gradlew --no-daemon --max-workers=2 build -PelnJar="$PWD/../ELN1.21.1ForkPatch/build/libs/ElectricalAge-3.0.67.jar"
```

Core: `ELN1.21.1ForkPatch/build/libs/ElectricalAge-3.0.67.jar`.
Addon: `EAMedia/build/libs/EAMedia-1.0.8.jar` and `EAMedia-1.0.8-server.jar`.
The core jar is only a compile/runtime reference; it is never embedded in the addon.
For another core location, pass `-PelnJar=/absolute/path/ElectricalAge-3.0.67.jar` to Gradle.
For these exact sources, check out core tag `v3.0.67` and addon tag `v1.0.8` before building.

TVs now use client hosting by default; Docker servers need no Xvfb or browser
libraries in that mode. See [TV hosting and Docker setup](powered-browser-tv.md)
for the shared-screen tradeoffs and optional server-owned browser mode.

The repository already contains the native payloads for every verified supported platform.
Ordinary builds do not run the maintenance download scripts. Browser support: Windows/Linux
x86-64 and macOS x86-64/ARM64. Jukebox tool support additionally includes Linux ARM64.
The full client jar is large because it includes all platforms; each client extracts only its own.
First-use extraction happens in the game directory, so that directory needs free disk space.
Browser pages and selected audio still need their normal internet connection.

Rebuilding the same version replaces its jar. To keep a separate local build use
`-PmodVersion=1.0.8-local.1`; `build` retains earlier versions, while `clean` deletes `build/`.
