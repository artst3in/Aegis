# Building Aegis

## Requirements

- Android Studio Ladybug (2024.2.1) or later — bundles a compatible JDK 17
- JDK 17 (if building from the command line)
- Android SDK, platform 35 (API level 35) + build-tools 35.0.0
- Android NDK (only if rebuilding the SimpleX native libraries; the
  prebuilt `.so` files ship in the tree, so a normal build does not need it)
- Git

Toolchain pinned by the project: Android Gradle Plugin 8.7.0, Gradle 8.11,
Kotlin 2.1.0. `compileSdk`/`targetSdk` = 35, `minSdk` = 29 (Android 10 — the
floor for GrapheneOS).

## Clone

```
git clone https://github.com/artst3in/Aegis.git
cd aegis-dev
```

## Build variants

The app has one flavor dimension, `channel`, crossed with the standard
debug/release build types:

| Flavor | What it changes | Update channel |
|--------|-----------------|----------------|
| `sideload` | self-update ON (in-app OTA) | GitHub Releases |
| `play` | self-update OFF (Play handles updates) | Play Store |

That yields `sideloadDebug`, `sideloadRelease`, `playDebug`, `playRelease`.
The three artifacts that actually get shipped:

| Task | Output |
|------|--------|
| `:app:assembleSideloadDebug` | `app/build/outputs/apk/sideload/debug/` — dev/test APK |
| `:app:assembleSideloadRelease` | `app/build/outputs/apk/sideload/release/` — sideload OTA APK |
| `:app:bundlePlayRelease` | `app/build/outputs/bundle/playRelease/` — Play Store AAB |

Build the debug + release APKs in a single gradle invocation so their
monotonic `versionCode` floors line up:

```
./gradlew :app:assembleSideloadRelease :app:assembleSideloadDebug
```

## Signing

There is no committed debug keystore — **every** variant (debug included)
signs with the release signing config (`aegis`). AGP hard-fails packaging if
the keystore is absent, so you can compile without it but cannot produce an
APK/AAB.

Point the build at the keystore via `local.properties` (gitignored). The
keystore and its password file live OUTSIDE the repo:

```
aegis.releaseStoreFile=/path/to/aegisrelease.keystore
aegis.releaseStorePasswordFile=/path/to/aegis-store-password
aegis.releaseKeyAlias=aegis
```

`aether.github.pat` (optional, gitignored) bundles a READ-only token into the
`release` self-update channel so it can poll the private release repo. A
public/distributable APK must carry NO token — `local.properties` is bundled
into release builds, so strip it first.

Never commit `local.properties`, the keystore, or any token.

## SimpleX upstream

The `simplex-upstream/` directory holds the SimpleX Chat library
integration. The native libraries (`libsimplex.so`, `libsupport.so`,
`libapp-lib.so`) are extracted from an official SimpleX Chat release and
redistributed unchanged — they are NOT rebuilt from source by this gradle
build (that needs a GHC cross-compile toolchain). See
[ATTRIBUTION-SimpleX.md](ATTRIBUTION-SimpleX.md).

## Troubleshooting

**`SigningConfig "aegis" is missing required property "storeFile"`:** the
release keystore isn't wired. Set the `aegis.releaseStoreFile` /
`aegis.releaseStorePasswordFile` paths in `local.properties` and confirm the
files exist.

**Build fails on first run:** run `./gradlew clean` then rebuild. The SimpleX
native libraries can need a clean build on first checkout.

**Tests:** `./gradlew :app:testSideloadDebugUnitTest` for the JVM unit tests.
