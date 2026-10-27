# Bundled browser bridge

ELN includes the unmodified CinemaMod MCEF NeoForge 2.1.6-1.21.1 jar as a nested dependency.
MCEF is copyright CinemaMod Group, LGPL-2.1-or-later. See `mcef-license.txt`.
The jar contains the CinemaMod Java CEF API; see `java-cef-license.txt`.

- MCEF source/build: https://github.com/CinemaMod/mcef/tree/1.21.1
- Original release: https://modrinth.com/mod/mcef/version/euXLgkSB
- Maven coordinate: `com.cinemamod:mcef-neoforge:2.1.6-1.21.1`
- Java CEF revision recorded by the bridge:
  https://github.com/CinemaMod/java-cef/tree/a78e832f9f13c2c688caea3d04d8b84fcd238d94

Chromium native libraries are bundled as verified archive chunks under `assets/eamedia/chromium/`.
The addon extracts its matching platform entirely from local resources and bypasses MCEF's
network checksum/download step. The original runtimes and their license files remain unmodified.
Their exact publisher URLs, archive hashes, file hashes and native architecture checks are in the
bundled manifest. Dedicated servers do not extract or start Chromium.
