# Building FT8OK from source

For developers and coding assistants. Operators don't need this: the [README](../README.md#install) explains how to install a release.

## Toolchain

| | Version |
|---|---|
| JDK | 17 |
| Android SDK | platform 36 (compileSdk 36, targetSdk 35, minSdk 26) |
| Android NDK | 28.2.13676358 |
| CMake (SDK package) | 4.0.2 |
| Gradle | 9.8.1, fetched by the wrapper |

Set `JAVA_HOME` (JDK 17) and `ANDROID_HOME` (the SDK), or write `sdk.dir` in a `local.properties` at the repo root. Gradle fails at configuration time with "SDK location not found" otherwise. Install the NDK and CMake versions above with the SDK manager.

## Build

The app has two product flavors, so a task must name one:

```
./gradlew :app:assembleFt8Debug      # FT8OK, com.ft8ok
./gradlew :app:assembleFt4Debug      # FT4OK, com.ft4ok
./gradlew :app:installFt8Debug       # build and install on a connected phone (adb)
```

Use `gradlew.bat` on Windows. APKs land in `app/build/outputs/apk/<flavor>/debug/`. The native decoder is built for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`.

## Test

Most of the logic runs as plain JVM tests, with no phone or radio:

```
./gradlew :core-model:testDebugUnitTest :core-dsp:testDebugUnitTest :core-time:testDebugUnitTest \
          :core-qso:testDebugUnitTest :core-log:testDebugUnitTest :data:testDebugUnitTest \
          :audio:testDebugUnitTest :radio:testDebugUnitTest \
          :app:testFt8DebugUnitTest :app:testFt4DebugUnitTest
./gradlew lint
```

Every `assemble` and `check` also runs a guard that **fails the build if the merged manifest holds any permission but `RECORD_AUDIO`** (and AndroidX's internal, grant-nothing receiver permission). `INTERNET` can never slip in through a library ([ADR 0002](adr/0002-offline-no-internet.md)).

## Rules the code depends on

Read [ARCHITECTURE.md](ARCHITECTURE.md) and the [decision records](adr/) before changing anything. In short:

- **Pure core, thin shell.** `core-*` modules contain no Android types and never import `app`, `data`, `audio` or `radio`. That is what keeps the JVM tests possible.
- **No FT8 or FT4 numbers outside `Mode`** ([ADR 0006](adr/0006-mode-abstraction.md)). Slot length, start, duration, decode passes, late-start limit and hard stop all live there.
- **No network, ever.** No `INTERNET` permission and no libraries that need one.
- **Foreground only** ([ADR 0003](adr/0003-foreground-only.md)): leaving the screen stops receive and disarms TX.
- **Dial frequencies come from one table**, built from [FREQUENCIES.md](reference/FREQUENCIES.md).
- **Borrowed code keeps its provenance.** `core-dsp/src/main/cpp/ft8_lib/` is vendored `ft8_lib` (MIT), with FT8OK changes marked in place; ports from WSJT-X are noted in [THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md).
- **Never key a real transmitter from a test.** Hardware tests use a dummy load.

## Release builds and signing

Android installs an update over an existing app only if both are signed with the same key, and only if its `versionCode` is higher (`app/build.gradle.kts`: 1.0 = 100, 1.1 = 110). A user moving between differently signed builds has to uninstall, which **deletes the logbook**: export ADIF first.

`assembleFt8Release` and `assembleFt4Release` sign with the key named in a properties file outside the repository:

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Pass it with `-Pft8okSigning=<path>` or the `FT8OK_SIGNING` environment variable; the default is `~/keys/ft8ok-release.properties`. Without it, release APKs come out unsigned. Keys are not in this repository and never should be. A build signed with your own key installs as a separate lineage: it cannot update an official release on the same phone.
