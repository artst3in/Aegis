#!/usr/bin/env bash
#
# build-dist.sh — build BOTH distributable artifacts from ROOT, in one go.
#
#   1. Sideload release APK  -> builds/aegis-release.apk
#        applicationId app.aether.aegis, FULL self-update permissions,
#        the OTA updater active. This is the censorship-resistant channel
#        (polls artst3in/Aegis for updates).
#
#   2. Play Store AAB        -> builds/aegis-play.aab
#        Same applicationId, but the self-update permission cluster is
#        stripped (src/play manifest) and SELF_UPDATE=false makes the
#        updater dormant — what Google Play requires.
#
# Both are built with NO PAT in local.properties (public distribution),
# signed with the rotated release key, and secret-scanned before they're
# allowed to land in builds/. local.properties is restored on exit no
# matter how the script ends.
#
# Usage:  scripts/build-dist.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
APKSIGNER="${APKSIGNER:-/opt/android-sdk/build-tools/35.0.0/apksigner}"

# ---- strip the PAT for the build; restore it on ANY exit -------------
LP="$ROOT/local.properties"
if [ -f "$LP" ]; then
    cp "$LP" "$LP.distbak"
    trap 'mv -f "$LP.distbak" "$LP" 2>/dev/null || true' EXIT
    grep -vi 'github.pat' "$LP" > "$LP.nopat" && mv "$LP.nopat" "$LP"
fi
echo "build-dist: PAT lines in local.properties during build = $(grep -ci 'github.pat' "$LP" 2>/dev/null || echo 0)"

# ---- build both variants ---------------------------------------------
echo "build-dist: assembling sideload APK + Play AAB…"
./gradlew :app:assembleSideloadRelease :app:bundlePlayRelease

APK_SRC="$(ls -t app/build/outputs/apk/sideload/release/*.apk | head -1)"
AAB_SRC="app/build/outputs/bundle/playRelease/app-play-release.aab"
mkdir -p builds
cp "$APK_SRC" builds/aegis-release.apk
cp "$AAB_SRC" builds/aegis-play.aab
echo "build-dist: copied -> builds/aegis-release.apk, builds/aegis-play.aab"

# ---- verify: zero secrets, right signer, right permission split ------
fail=0

scan_secrets() {
    local f="$1" name="$2" hits
    hits="$(unzip -p "$f" 2>/dev/null | strings | grep -c 'github_pat_\|ghp_' || true)"
    echo "build-dist: [$name] github_pat_/ghp_ hits = $hits"
    [ "$hits" = "0" ] || { echo "  !! SECRET FOUND in $name — DO NOT SHIP"; fail=1; }
}

AAPT="${AAPT:-$(ls "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/*/aapt 2>/dev/null | tail -1)}"

perm_count() {
    # The APK manifest is binary AXML (UTF-16 string pool) — `strings`
    # misses it, so decode it properly with aapt. The AAB manifest is
    # protobuf (UTF-8), so a strings grep of base/manifest is reliable.
    local f="$1"
    if [[ "$f" == *.apk ]]; then
        "$AAPT" dump permissions "$f" 2>/dev/null \
            | grep -c 'UPDATE_PACKAGES_WITHOUT_USER_ACTION' || true
    else
        unzip -p "$f" base/manifest/AndroidManifest.xml 2>/dev/null \
            | strings | grep -c 'UPDATE_PACKAGES_WITHOUT_USER_ACTION' || true
    fi
}

scan_secrets builds/aegis-release.apk "sideload APK"
scan_secrets builds/aegis-play.aab    "Play AAB"

echo "build-dist: --- signer (expect rotated key 1C:78:1A…) ---"
if [ -x "$APKSIGNER" ]; then
    "$APKSIGNER" verify --print-certs builds/aegis-release.apk 2>/dev/null | grep -i 'sha-256' | head -1
fi

echo "build-dist: --- self-update permission split ---"
side_perm="$(perm_count builds/aegis-release.apk)"
play_perm="$(perm_count builds/aegis-play.aab)"
echo "build-dist: UPDATE_PACKAGES in sideload APK = $side_perm (expect >=1)"
echo "build-dist: UPDATE_PACKAGES in Play AAB     = $play_perm (expect 0)"
[ "$side_perm" -ge 1 ] || { echo "  !! sideload APK lost its self-update permission"; fail=1; }
[ "$play_perm" = "0" ] || { echo "  !! Play AAB still carries the restricted permission — Play will reject"; fail=1; }

echo "build-dist: --- sizes ---"
ls -la builds/aegis-release.apk builds/aegis-play.aab | awk '{print "  "$NF" "$5" bytes ("$5/1048576" MiB)"}'

if [ "$fail" != "0" ]; then
    echo "build-dist: FAILED verification — artifacts are NOT safe to ship."
    exit 1
fi
echo "build-dist: OK — both artifacts verified clean."
