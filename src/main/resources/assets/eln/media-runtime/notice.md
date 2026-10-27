# Bundled third-party media programs

These are unmodified, independently executed programs, packaged as an aggregate with Electrical
Age. Their licenses are independent of the mod's source-code and graphics licenses. The original
copyright/license texts and distributor build records are in `notices/`.

- **yt-dlp 2026.10.26**: https://github.com/yt-dlp/yt-dlp/tree/2026.10.26 . The standalone
  PyInstaller executables are distributed under GPLv3+ because of bundled components. See
  `notices/yt-dlp-readme.md`, `notices/yt-dlp-license.txt` and the complete bundled-component
  copyright/license list in `notices/yt-dlp-third_party_licenses.txt`.
- **FFmpeg static distribution b6.1.1**: https://github.com/eugeneware/ffmpeg-static/tree/b6.1.1 .
  This distribution contains different FFmpeg versions/builds by platform. The exact source URLs,
  archive/executable hashes and sizes are in `manifest.json`; original per-platform README and
  license files are retained. GPLv2/v3 and LGPLv2.1/v3 full texts are also supplied.
- **QuickJS-NG 0.17.0**: https://github.com/quickjs-ng/quickjs/tree/v0.17.0 . MIT; see
  `notices/quickjs-license.txt`. This small embedded engine supplies yt-dlp's JavaScript support.

## Source and build access

The following original source/build locations accompany the binary distribution:

- yt-dlp source: https://github.com/yt-dlp/yt-dlp/archive/refs/tags/2026.10.26.tar.gz .
  Its complete third-party notices name the bundled dependencies and their source projects.
- yt-dlp release workflow/build specifications:
  https://github.com/yt-dlp/yt-dlp/tree/2026.10.26/.github/workflows .
- FFmpeg distributor scripts/metadata:
  https://github.com/eugeneware/ffmpeg-static/tree/b6.1.1 .
- FFmpeg Linux builds and source distribution: https://johnvansickle.com/ffmpeg/ and
  https://johnvansickle.com/ffmpeg/release-source/ . Linked library/compiler versions are recorded
  in the original Linux README files. FFmpeg 7.0.2 source is available at
  https://ffmpeg.org/releases/ffmpeg-7.0.2.tar.xz .
- FFmpeg Windows build/source details: https://www.gyan.dev/ffmpeg/builds/ and the exact source
  commit https://github.com/FFmpeg/FFmpeg/commit/e38092ef93 . The original Windows build record
  includes enabled libraries and their version/source information.
- FFmpeg macOS Intel build/source metadata: https://evermeet.cx/ffmpeg/ . The original JSON
  README records FFmpeg 8.0 and linked libraries/source URLs. FFmpeg 8.0 source is available at
  https://ffmpeg.org/releases/ffmpeg-8.0.tar.xz .
- FFmpeg macOS ARM distributor/build details:
  https://github.com/eugeneware/ffmpeg-static/tree/b6.1.1 . The supplied original FFmpeg license
  and README describe its component/source terms.
- QuickJS source and build specifications:
  https://github.com/quickjs-ng/quickjs/tree/v0.17.0 .

`tools/media/bundle_runtime.py` regenerates the pinned archive manifest from the selected original
releases and verifies every publisher SHA-256 before packaging. No media-tool archive is fetched
by the running mod. Only the selected song/video URL is accessed for playback.
