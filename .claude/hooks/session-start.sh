#!/bin/bash
# SessionStart hook for Claude Code on the web.
#
# Why this exists:
#   Aegis builds need three native SimpleX libraries
#   (libsimplex.so / libsupport.so / libapp-lib.so) under
#   app/src/main/jniLibs/<abi>/. They are deliberately NOT committed to
#   git — libsimplex.so alone is ~183 MB, over GitHub's 100 MB per-file
#   limit (see scripts/fetch-simplex-libs.sh). A fresh web container
#   therefore starts WITHOUT them, and `:app:assembleDebug` will happily
#   produce a coreless ~3 MB APK that installs but has no SimpleX core.
#   That is exactly the trap a previous session hit.
#
# What it does (a fresh web container ships none of these):
#   1. Android SDK — wires an existing one, or installs a minimal SDK
#      (cmdline-tools + platform-35 + build-tools;35.0.0 + platform-tools)
#      so `:app:assembleSideload*` can actually compile here.
#   2. Release signing + self-update secrets — materializes the keystore,
#      its password, and the read-only bundled PAT FROM ENV into the
#      gitignored places the build reads. Values live ONLY in the
#      environment's secret config, never in the repo.
#   3. SimpleX native libs — restores libsimplex/libsupport/libapp-lib via
#      scripts/fetch-simplex-libs.sh (arm64-v8a + armeabi-v7a, pinned
#      SimpleX v6.5.1), the same script human devs run once per checkout.
#
# Guarantees / non-goals:
#   - Idempotent: a cached/resumed container that already has the SDK,
#     libs, or wired secrets skips that step (no re-download, no re-decode).
#   - Remote-only: local checkouts manage their own SDK + libs and may have
#     pinned a different ABI/version, so we never touch them.
#   - Never fatal, never leaks: the hook itself never aborts the session.
#     A missing SDK is left for Gradle to report; an absent keystore lets
#     code still COMPILE (packaging then fails — see the keystore note
#     below). Secret VALUES are only ever written to gitignored files —
#     never echoed to stdout, never committed.
set -euo pipefail

# Only run inside Claude Code on the web. Local devs run the fetch
# script themselves; bailing here keeps this hook a no-op on laptops.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# $CLAUDE_PROJECT_DIR is the repo root in remote sessions; fall back to
# walking up from this script so the hook also works if invoked directly.
ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd "$ROOT"

# --- Android SDK: wire an existing one, else install it --------------
# local.properties carries sdk.dir but is gitignored, so a fresh checkout
# lacks it. A fresh web container ALSO ships no SDK at all, and the build
# needs compileSdk 35 + build-tools 35.0.0 — so if none is found we
# install a minimal one rather than leave Gradle to fail. Never fatal: a
# download failure degrades to "Gradle reports the missing SDK".
is_sdk() {
  [ -n "${1:-}" ] && { [ -d "$1/platform-tools" ] || \
                       [ -d "$1/cmdline-tools" ]  || \
                       [ -d "$1/platforms" ]; }
}

# Install a minimal SDK into $1. Pinned cmdline-tools build (kept literal
# so a registry float can't silently change it); sdkmanager then pulls the
# platform + build-tools the Gradle build is configured against.
install_sdk() {
  local sdk="$1" tmp
  echo "session-start: no Android SDK found — installing minimal SDK to $sdk (~450 MB, one-time)..."
  command -v unzip >/dev/null 2>&1 || { echo "session-start: unzip missing — cannot install SDK." >&2; return 1; }
  mkdir -p "$sdk/cmdline-tools"
  tmp="$(mktemp -d)"
  if ! curl -fsSL -o "$tmp/cli.zip" \
       "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"; then
    echo "session-start: cmdline-tools download failed." >&2; rm -rf "$tmp"; return 1
  fi
  unzip -q -o "$tmp/cli.zip" -d "$sdk/cmdline-tools"
  rm -rf "$sdk/cmdline-tools/latest" "$tmp"
  mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest"
  local mgr="$sdk/cmdline-tools/latest/bin/sdkmanager"
  yes | "$mgr" --licenses >/dev/null 2>&1 || true
  "$mgr" "platform-tools" "platforms;android-35" "build-tools;35.0.0" >/dev/null 2>&1 || true
}

SDK_HOME=""
for cand in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
            "$HOME/android-sdk" /home/user/android-sdk \
            /opt/android-sdk /usr/lib/android-sdk; do
  if is_sdk "$cand"; then SDK_HOME="$cand"; break; fi
done
# Nothing on disk → install into $HOME/android-sdk and adopt it.
if [ -z "$SDK_HOME" ] && install_sdk "$HOME/android-sdk" && is_sdk "$HOME/android-sdk"; then
  SDK_HOME="$HOME/android-sdk"
fi
if [ -n "$SDK_HOME" ]; then
  if [ ! -f local.properties ] || ! grep -q '^sdk.dir=' local.properties 2>/dev/null; then
    echo "sdk.dir=$SDK_HOME" >> local.properties
    echo "session-start: wrote sdk.dir=$SDK_HOME to local.properties"
  fi
  # Persist for the session so `./gradlew` works without re-export.
  echo "export ANDROID_HOME=$SDK_HOME" >> "${CLAUDE_ENV_FILE:-/dev/null}"
fi

# --- Release signing + self-update secrets (from env, never committed) -
# "Wire secrets into the env" model: the real values live ONLY in the
# environment's secret config (set in the Claude Code web UI). This hook
# materializes them into the gitignored spots the build + publish scripts
# already read. None set → code still COMPILES, but AGP hard-fails the
# packaging step without a keystore (SigningConfig "aegis" missing
# storeFile), so no APK is produced until the keystore is deposited.
#
# Env vars (NAMES ONLY here — values set out-of-band, never in the repo):
#   AEGIS_RELEASE_KEYSTORE_B64    base64 of aegisrelease.keystore
#   AEGIS_RELEASE_STORE_PASSWORD  keystore/key password
#   AEGIS_RELEASE_KEY_ALIAS       alias inside the store (default: aegis)
#   AETHER_GITHUB_PAT             READ-only PAT bundled into RELEASE for
#                                 unattended self-update (private channel)
#   GH_TOKEN                      CONTENTS:WRITE token — read straight from
#                                 env by scripts/publish-release.sh; the
#                                 write token is NEVER written to a file or
#                                 bundled into an APK.
SIGN_DIR="$HOME/.signing"
if [ -n "${AEGIS_RELEASE_KEYSTORE_B64:-}" ] && [ -n "${AEGIS_RELEASE_STORE_PASSWORD:-}" ]; then
  mkdir -p "$SIGN_DIR"; chmod 700 "$SIGN_DIR"
  # Decode/write quietly — the bytes and the password must never hit stdout.
  printf '%s' "$AEGIS_RELEASE_KEYSTORE_B64" | base64 -d > "$SIGN_DIR/aegisrelease.keystore" 2>/dev/null
  printf '%s' "$AEGIS_RELEASE_STORE_PASSWORD" > "$SIGN_DIR/aegis-store-password"
  chmod 600 "$SIGN_DIR/aegisrelease.keystore" "$SIGN_DIR/aegis-store-password"
  # Point the build at them. Strip any prior lines first so re-runs are
  # idempotent and a stale path can't linger.
  touch local.properties
  sed -i '/^aegis\.releaseStoreFile=/d;/^aegis\.releaseStorePasswordFile=/d;/^aegis\.releaseKeyAlias=/d' local.properties
  {
    echo "aegis.releaseStoreFile=$SIGN_DIR/aegisrelease.keystore"
    echo "aegis.releaseStorePasswordFile=$SIGN_DIR/aegis-store-password"
    echo "aegis.releaseKeyAlias=${AEGIS_RELEASE_KEY_ALIAS:-aegis}"
  } >> local.properties
  echo "session-start: release signing wired (keystore present)."
else
  echo "session-start: no release keystore in env — code compiles but APK packaging will FAIL (set AEGIS_RELEASE_KEYSTORE_B64 to build APKs)."
fi
# READ-only bundled PAT for the RELEASE self-update channel. Goes in
# local.properties (gitignored); build.gradle.kts bundles it into the
# RELEASE variant only. NOTE: the public distributable APK must carry NO
# PAT — build it via scripts/build-dist.sh, which strips this.
if [ -n "${AETHER_GITHUB_PAT:-}" ]; then
  touch local.properties
  sed -i '/^aether\.github\.pat=/d' local.properties
  echo "aether.github.pat=$AETHER_GITHUB_PAT" >> local.properties
fi

# --- SimpleX native libs --------------------------------------------
# arm64-v8a is the release ABI; if its core is present we treat the
# checkout as good and skip the download. (fetch-simplex-libs.sh is
# itself safe to re-run, but skipping keeps resumed sessions fast.)
if [ -f app/src/main/jniLibs/arm64-v8a/libsimplex.so ]; then
  echo "session-start: SimpleX libs already present — skipping fetch."
  exit 0
fi

echo "session-start: fetching SimpleX native libs (one-time, ~130 MB)..."
bash scripts/fetch-simplex-libs.sh
echo "session-start: SimpleX libs ready."
