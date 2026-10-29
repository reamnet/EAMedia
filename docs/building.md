# Install and build EA Media

EA Media 1.0.4 requires Minecraft 1.21.1, NeoForge 21.1.249+, Kotlin for Forge NeoForge 5.12.0+,
and ELN 3.0.60+ in the 3.0 series. Kotlin for Forge remains a separate installation.

## Install

Use the full `EAMedia-1.0.4.jar` on clients. Use that same full jar on a server that hosts TVs. The smaller
`EAMedia-1.0.4-server.jar` retains device logic but has no Chromium payload, so it
cannot host TV browsers. It can serve a jukebox-only installation. Install exactly one variant. Install the addon on the server and all clients when enabling media.
Do not install a different MCEF version alongside the included, pinned 2.1.6-1.21.1 bridge.

Update core ELN and the addon together. All players and the server must use EA Media 1.0.4 for the new shared-screen network protocol. The TV and jukebox retain their old `eln:*` item IDs,
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
./gradlew --no-daemon --max-workers=2 build -PelnJar="$PWD/../ELN1.21.1ForkPatch/build/libs/ElectricalAge-3.0.66.jar"
```

Core: `ELN1.21.1ForkPatch/build/libs/ElectricalAge-3.0.66.jar`.
Addon: `EAMedia/build/libs/EAMedia-1.0.4.jar` and `EAMedia-1.0.4-server.jar`.
The core jar is only a compile/runtime reference; it is never embedded in the addon.
For another core location, pass `-PelnJar=/absolute/path/ElectricalAge-3.0.60.jar` to Gradle.
For these exact sources, check out core tag `v3.0.66` and addon tag `v1.0.4` before building.

For the Ubuntu server browser, install Xvfb and the native libraries listed in
[server TV setup](powered-browser-tv.md#server-setup). TV webpages now execute on
the server; changing controllers never transfers hosting to a player.

The repository already contains the native payloads for every verified supported platform.
Ordinary builds do not run the maintenance download scripts. Browser support: Windows/Linux
x86-64 and macOS x86-64/ARM64. Jukebox tool support additionally includes Linux ARM64.
The full client jar is large because it includes all platforms; each client extracts only its own.
First-use extraction happens in the game directory, so that directory needs free disk space.
Browser pages and selected audio still need their normal internet connection.

Rebuilding the same version replaces its jar. To keep a separate local build use
`-PmodVersion=1.0.4-local.1`; `build` retains earlier versions, while `clean` deletes `build/`.
