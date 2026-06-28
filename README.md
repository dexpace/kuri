# kuri
A URI/URL parsing and manipulation library for Java and Kotlin.

## Building

```
./gradlew build
```

This compiles all targets, runs the full quality gate (ktlint, detekt, explicit-API
check, binary-compatibility validator, and an 80% Kover line-coverage floor), and
executes all host-runnable tests.

**After an intentional public-API change only**, regenerate the API snapshot and commit
the updated `api/*.api` file alongside the source change:

```
./gradlew apiDump
```

### Native target support by host

The build defines the following native targets: `macosArm64`, `iosArm64`,
`iosSimulatorArm64`, `iosX64`, `watchosArm64`, `watchosSimulatorArm64`, `tvosArm64`,
`tvosSimulatorArm64`, `linuxX64`, `linuxArm64`, and `mingwX64`.

All targets compile on any host. Test *execution*, however, requires a matching OS or
simulator:

| Target(s) | Runs on |
|---|---|
| `macosArm64`, `iosSimulatorArm64`, `watchosSimulatorArm64`, `tvosSimulatorArm64` | macOS Apple Silicon (this host) |
| `iosX64` | macOS x86-64 (or `iosX64` simulator on x86 Mac) |
| `linuxX64`, `linuxArm64` | Linux (CI matrix) |
| `mingwX64` | Windows (CI matrix) |

On macOS Apple Silicon the following test tasks are **skipped** (no cross-OS test
runner available on this host); their compile and link steps still pass:
`linuxX64Test`, `linuxArm64Test`, `mingwX64Test`, `iosX64Test`.

The full matrix (all link + test tasks) runs in CI across Linux, macOS, and Windows
runners.
