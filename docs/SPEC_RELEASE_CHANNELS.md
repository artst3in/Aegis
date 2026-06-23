# SPEC: Release Channels

**Status:** IMPLEMENTED — Chad 2026-06-02. Build types, keystore,
BuildConfig fields, source-set split for the lock-screen emergency
PIN reset, applicationIdSuffix all landed in 2026.06.281. The PAT
field gating to debug-only + the channel indicator on
UpdateSettings landed in the follow-up commit. The Stable / Beta
channel selector deliberately not implemented today because the
beta channel infrastructure (public Aegis repo with pre-release
tags) doesn't exist yet — there's nothing for the toggle to switch
between. When the public Aegis repo lands, a follow-up adds the
toggle + the "Beta builds may be unstable" warning dialog.
**Owner:** Chad (implementation), Aurora (spec)

## Problem

Debug, beta, and stable builds need separate distribution paths.
Debug features (private repo token, experimental update channel)
must not exist in public builds — not hidden, not gated, not
present in the binary at all.

## Solution

Android build types. Compile-time separation. Two APKs from one
codebase.

## Build types

### debug

Built with `assembleDebug`. For the developer only.

Includes:
- GitHub token field (private repo auto-update)
- Debug update channel (checks aegis-dev pre-release tags)
- Any experimental/diagnostic UI gated by BuildConfig.DEBUG
- Debuggable flag (ADB attach, logcat)

Distributed: never published. Developer sideloads or auto-updates
from aegis-dev (private repo).

### release

Built with `assembleRelease`. For testers and the public.

Includes:
- Stable channel (checks public Aegis repo release tags)
- Beta channel (checks public Aegis repo pre-release tags)
- No token field. No private repo access. No debug code.
- R8/ProGuard minification. Not debuggable.

Distributed:
- Public Aegis repo (GitHub releases + pre-releases)
- Play Store (releases only)

## Channels (user-facing)

### In debug builds

| Channel | Source | Description |
|---|---|---|
| Debug | aegis-dev (private) | Latest build. May break. |

No channel selector needed — debug builds always check the
private repo. One channel, automatic.

### In release builds

| Channel | Source | Description |
|---|---|---|
| Stable | Aegis (public) + Play Store | Tested, promoted. Default. |
| Beta | Aegis (public) pre-releases | Opt-in, warning on enable. |

Toggle in Settings. Default: Stable. Beta shows a warning on
every enable:

    "Beta builds may be unstable. Your data could be affected.
    You can switch back to Stable at any time. Continue?"

## Repos

| Repo | Visibility | Contains |
|---|---|---|
| aegis-dev | Private | Source code, specs, patents, debug releases |
| Aegis | Public | README, privacy policy, stable + beta APKs |

## Build and release flow

1. Chad builds `assembleDebug` → pushes APK to aegis-dev tag.
   Developer's phone auto-updates.

2. Developer tests. When ready, Chad builds `assembleRelease`
   → pushes APK to public Aegis repo as **pre-release** tag.
   Beta users get it.

3. Beta users validate. When stable, promote the pre-release
   to a **release** tag + upload same APK to Play Store.

## Implementation

### Source sets

Debug-only code lives in `app/src/debug/java/`. This directory
does not exist in release builds — the compiler never sees it.

Minimum files in `app/src/debug/`:
- `DebugUpdateChannel.kt` — private repo update checker
- `DebugSettingsSection.kt` — token field composable

### Build config

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "HAS_DEBUG_CHANNEL", "true")
            buildConfigField("String", "UPDATE_REPO", "\"artst3in/aegis-dev\"")
        }
        release {
            buildConfigField("boolean", "HAS_DEBUG_CHANNEL", "false")
            buildConfigField("String", "UPDATE_REPO", "\"artst3in/Aegis\"")
            isMinifyEnabled = true
            proguardFiles(...)
        }
    }
}
```

### applicationIdSuffix

Debug builds use `app.aether.aegis.debug`. This allows debug
and release to be installed side-by-side on the same device.
The developer can run both simultaneously — debug for testing,
release for daily use.

## Signing

One keystore. Both build types signed with the same key.
Upload this key to Play App Signing so Play Store APKs match
GitHub APKs. Cross-channel updates work (beta → stable)
without reinstall.

Debug builds also use this key (not Android's default debug
key) so the developer can update between debug and release
without uninstall.

## What this replaces

The current single-build approach where debug features are
gated at runtime (token field visible to everyone, experimental
gate via 7-tap). After this spec ships, debug features are
removed at compile time — they do not exist in release binaries.
