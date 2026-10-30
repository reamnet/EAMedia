# EAMedia 1.0.9 build-dependency correction

The 1.0.8 build script retained an ELN 3.0.60 default despite requiring 3.0.67 at runtime. With an older jar still present, an ordinary build passed the file-existence check and failed in Kotlin with unresolved `WALL_TV` and `CompactUpsElement` references. The 1.0.8 verification used an explicit `-PelnJar=...3.0.67.jar`, so it did not exercise that default.

The default now points to the sibling core repository's 3.0.67 jar. `verifyEln` checks the actual embedded version and both required API markers before compilation, including for explicit overrides. Missing files, invalid archives and older/renamed jars receive a direct dependency error with the correct build command.

Verification covers a normal build with no `-PelnJar`, an explicitly selected current jar, older 3.0.60/3.0.66 jars, a renamed old jar, a missing path and an invalid archive. No gameplay, browser routing, model or network-protocol changes are made. Core remains 3.0.67.

The default build completed successfully with **56 tests passing**. All **six dependency-selection checks** passed, including rejecting ELN 3.0.60 before Kotlin compilation. All **205 packaged runtime-class and asset entries** matched 1.0.8 byte for byte. Reports and local artifact hashes are under `docs/verification/release-1.0.9/`.
