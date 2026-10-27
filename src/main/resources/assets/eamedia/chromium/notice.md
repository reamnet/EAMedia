# Bundled Chromium runtimes

These CinemaMod Java CEF runtime distributions are packaged as an aggregate with EAMedia.
Source/build revision: https://github.com/CinemaMod/java-cef/tree/a78e832f9f13c2c688caea3d04d8b84fcd238d94 .
Publisher distributions: https://mcef-download.cinemamod.com/java-cef-builds/a78e832f9f13c2c688caea3d04d8b84fcd238d94/ .
Chromium/CEF and their components retain their original licenses. Original notice/license files
inside each archive are preserved on extraction. `manifest.json` records exact publisher URLs,
SHA-256 hashes, file lists and checked native architectures. Each `.bin` is a segment of the packaged compressed archive;
`tools/media/bundle_chromium.py` verifies publisher checksums before packaging and splitting into
files below GitHub's per-file size limit. macOS archive normalization is described below. Regular builds never fetch
Chromium and the running addon never fetches browser executables or checksum markers.

Compatible native bundles: Linux x64, Windows x64, macOS Intel and macOS Apple Silicon.
The publisher's Linux/Windows ARM64 archives at this revision contain x64 binaries, including
`libjcef.so`/`jcef.dll`. They are recorded in `unsupportedPlatforms` for transparency and are not
bundled or advertised as native ARM64 support. The jukebox's separate media tools retain their
own supported platform matrix and licenses.

The macOS source archives use GNU sparse tar records that Minecraft's bundled Commons
Compress 1.26 cannot reliably expand. The maintenance bundler converts those records into
ordinary tar files before chunking. Extracted file contents, modes and original license files
are preserved and individually hash-verified. The manifest records both the original publisher
archive checksum and the repacked payload checksum.
