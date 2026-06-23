#!/usr/bin/env bash
#
# publish-release.sh — publish an Aegis build as a GitHub Release.
#
# The on-device updater (UpdateClient) lists releases, picks the newest one
# carrying its channel's asset, reads a machine manifest embedded in the
# release body, then downloads the APK release ASSET. Assets live outside
# git, so publishing this way keeps the repo's history from accumulating an
# ~84 MB binary per build.
#
#   release  -> artst3in/Aegis      (public  channel)  asset: aegis-release.apk
#   debug    -> artst3in/aegis-dev   (private channel)  assets: aegis-debug.apk
#                                                                aegis-debug-armv7.apk
#
# Prereqs:
#   * The APK(s) already built. PRIVATE destinations are read STRAIGHT from
#     the gradle output (app/build/outputs/apk/sideload/<variant>/) — nothing
#     is staged into builds/. A PUBLIC release (artst3in/Aegis) must first be
#     staged into builds/ by scripts/build-dist.sh (which strips the PAT).
#   * curl + python3 (no `gh` dependency — pure GitHub REST API).
#   * A token with CONTENTS:WRITE on the target repo, via `GH_TOKEN` /
#     `GITHUB_TOKEN` (preferred), else read from `aether.github.pat` in
#     local.properties. This WRITE token lives ONLY on the build machine /
#     CI — it is NEVER bundled into an APK. The app downloads with its own
#     read PAT (private channel) or anonymously (public channel).
#
# Usage:
#   scripts/publish-release.sh release                      # → artst3in/Aegis
#   scripts/publish-release.sh debug                        # → artst3in/aegis-dev
#   scripts/publish-release.sh release artst3in/aegis-dev   # stage the release
#                                                           # build on the dev
#                                                           # repo for testing
#
# The optional 2nd arg overrides the destination repo (the channel still
# selects which APK + manifest are published). Co-locating both channels on
# aegis-dev is safe: the updater picks the newest release carrying ITS asset,
# so a debug app ignores release-only builds. The PUBLIC secret-gate keys off
# the ACTUAL destination, not the channel — so a PAT can never reach the
# public repo regardless of channel.
#
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

CHANNEL="${1:-}"
REPO_OVERRIDE="${2:-}"
case "$CHANNEL" in
    release)
        REPO="artst3in/Aegis"
        MANIFEST="builds/release-version.json"
        APK_NAMES=("aegis-release.apk"); APK_SUBS=("release")
        ;;
    debug)
        REPO="artst3in/aegis-dev"
        MANIFEST="builds/version.json"
        APK_NAMES=("aegis-debug.apk"); APK_SUBS=("debug")
        ;;
    *)
        echo "usage: $0 {release|debug} [target-repo-override]" >&2
        exit 2
        ;;
esac
[ -n "$REPO_OVERRIDE" ] && REPO="$REPO_OVERRIDE"
# Public iff the destination IS the public repo — the secret gate guards
# where the bytes land, not which channel built them.
if [ "$REPO" = "artst3in/Aegis" ]; then PUBLIC=1; else PUBLIC=0; fi

[ -f "$MANIFEST" ] || { echo "publish: missing $MANIFEST — build first" >&2; exit 1; }

# Resolve each published asset name to its source APK. APKs are read
# STRAIGHT from the gradle output for private destinations, so builds/
# never holds an APK — it carries only the tracked version json. A PUBLIC
# release still sources from build-dist.sh's PAT-stripped staging in
# builds/ (proactive strip; the secret gate below is the backstop).
GRADLE_OUT="app/build/outputs/apk/sideload"
APK_SRCS=()
for i in "${!APK_NAMES[@]}"; do
    nm="${APK_NAMES[$i]}"; sub="${APK_SUBS[$i]}"
    if [ "$PUBLIC" = "1" ]; then
        # PUBLIC: the ONLY safe source is build-dist.sh's PAT-stripped,
        # secret-scanned staging in builds/. Required — we never fall back to
        # the raw gradle output (it may still carry the read PAT).
        src="builds/$nm"
        [ -f "$src" ] || {
            echo "publish: missing $src — stage a PUBLIC release via scripts/build-dist.sh" >&2
            exit 1
        }
    else
        # PRIVATE: ALWAYS read STRAIGHT from the gradle output, and deliberately
        # IGNORE any builds/$nm. A stale staging APK left behind by an earlier
        # build-dist.sh run once shadowed the fresh build and shipped an OLD
        # version under a NEW tag (user-reported "packed 793 as 799").
        src="$(ls -t "$GRADLE_OUT/$sub"/*.apk 2>/dev/null | head -1)"
        [ -n "${src:-}" ] && [ -f "$src" ] || {
            echo "publish: no APK for $nm — build first (looked in $GRADLE_OUT/$sub)" >&2
            exit 1
        }
    fi
    APK_SRCS+=("$src")
done

# ---- pull version fields straight out of the gradle-written manifest -----
# Flat JSON; grep/sed avoids a jq dependency on the build box.
jget() { grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$MANIFEST" | head -1 | sed 's/.*:[[:space:]]*"\(.*\)"/\1/'; }
jnum() { grep -o "\"$1\"[[:space:]]*:[[:space:]]*[0-9]*" "$MANIFEST" | head -1 | sed 's/.*:[[:space:]]*//'; }

VERSION_CODE="$(jnum versionCode)"
VERSION_NAME="$(jget versionName)"
BUILD_DNA="$(jget buildDna)"
GIT_SHA="$(jget gitSha)"
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || { echo "publish: manifest missing versionCode/versionName" >&2; exit 1; }

# ---- guard: each APK's EMBEDDED versionCode must match the manifest --------
# Belt-and-suspenders against shipping a stale APK under a fresh tag (the
# "793 packed as 799" bug, where an old staged APK got published with a new
# manifest). Uses aapt2 when the SDK provides it; skipped with a warning if no
# aapt2 is on this host, so the script stays dependency-light on the build box.
AAPT2="$(ls -t "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nonexistent}}/build-tools/"*/aapt2 2>/dev/null | head -1 || true)"
[ -x "${AAPT2:-}" ] || AAPT2="$(command -v aapt2 || true)"
if [ -n "${AAPT2:-}" ]; then
    for a in "${APK_SRCS[@]}"; do
        apk_vc="$("$AAPT2" dump badging "$a" 2>/dev/null \
            | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
        if [ -n "$apk_vc" ] && [ "$apk_vc" != "$VERSION_CODE" ]; then
            echo "publish: !! $a has versionCode $apk_vc but manifest says $VERSION_CODE" >&2
            echo "publish:    stale APK — rebuild (./gradlew assemble…) or restage (build-dist.sh) so they match." >&2
            exit 1
        fi
    done
    echo "publish: versionCode check OK ($VERSION_CODE)"
else
    echo "publish: (no aapt2 found — skipping versionCode cross-check)" >&2
fi

# ---- secret gate: the PUBLIC release APK must carry NO PAT ----------------
# The debug APK legitimately bundles the read-only PAT for the private
# channel, so the scan only HARD-fails the public release channel.
for a in "${APK_SRCS[@]}"; do
    # Require real token length after the prefix — the app bundles the
    # literal "github_pat_" / "ghp_" as a validation hint for the in-app
    # token field, which must NOT count as a leaked secret. A genuine
    # token has 20+ chars of payload after the prefix.
    hits="$(unzip -p "$a" 2>/dev/null | strings | grep -cE 'github_pat_[A-Za-z0-9_]{20}|ghp_[A-Za-z0-9]{20}' || true)"
    if [ "$PUBLIC" = "1" ] && [ "$hits" != "0" ]; then
        echo "publish: !! $a contains a PAT ($hits hits) — refusing to publish to PUBLIC $REPO" >&2
        exit 1
    fi
    echo "publish: [$a] PAT hits = $hits"
done

# ---- per-asset sha256 + size into the manifest marker --------------------
asset_entries=""
for i in "${!APK_NAMES[@]}"; do
    name="${APK_NAMES[$i]}"
    a="${APK_SRCS[$i]}"
    sha="$(sha256sum "$a" | awk '{print $1}')"
    size="$(stat -c %s "$a")"
    sep=""; [ -n "$asset_entries" ] && sep=","
    asset_entries="${asset_entries}${sep}\"${name}\":{\"sha256\":\"${sha}\",\"size\":${size}}"
done

# The marker is an HTML comment so it renders invisibly on the Releases
# page while the updater parses it out of the release body.
MARKER="<!--aegis-manifest:{\"versionCode\":${VERSION_CODE},\"versionName\":\"${VERSION_NAME}\",\"buildDna\":\"${BUILD_DNA}\",\"gitSha\":\"${GIT_SHA}\",\"assets\":{${asset_entries}}}-->"

NOTES="$(printf 'Aegis %s\n\n%s · %s\n\n%s\n' "$VERSION_NAME" "$BUILD_DNA" "${GIT_SHA:0:7}" "$MARKER")"
TAG="$VERSION_NAME"

echo "publish: channel=$CHANNEL repo=$REPO tag=$TAG versionCode=$VERSION_CODE assets=${APK_NAMES[*]}"

# ---- token (write scope). Env first, else local.properties. NEVER echoed. -
TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
if [ -z "$TOKEN" ] && [ -f local.properties ]; then
    TOKEN="$(sed -n 's/^aether\.github\.pat=//p' local.properties | head -1)"
fi
[ -n "$TOKEN" ] || { echo "publish: no token (set GH_TOKEN or aether.github.pat in local.properties)" >&2; exit 1; }

API="https://api.github.com"
auth=(-H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")

# ---- refuse to clobber an existing tag (bump the build instead) ----------
code="$(curl -sS -o /dev/null -w '%{http_code}' "${auth[@]}" "$API/repos/$REPO/releases/tags/$TAG")"
if [ "$code" = "200" ]; then
    echo "publish: !! release $TAG already exists on $REPO — bump the build or delete it first" >&2
    exit 1
fi

# ---- create the release (python3 builds the JSON body safely) ------------
body_json="$(TAG="$TAG" VERSION_NAME="$VERSION_NAME" NOTES="$NOTES" python3 -c '
import json, os
print(json.dumps({
    "tag_name":   os.environ["TAG"],
    "name":       os.environ["VERSION_NAME"],
    "body":       os.environ["NOTES"],
    "draft":      False,
    "prerelease": False,
}))')"
resp="$(curl -sS -X POST "${auth[@]}" "$API/repos/$REPO/releases" -d "$body_json")"
rel_id="$(printf '%s' "$resp" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)"
[ -n "$rel_id" ] || { echo "publish: !! create release failed: $(printf '%s' "$resp" | head -c 300)" >&2; exit 1; }

# ---- upload each APK as a release asset (retry on transient failure) -----
# A ~100 MB asset upload can hit a transient CDN reset / timeout (GitHub
# returns "upstream connect error … connection timeout"). Retry with
# backoff, deleting any partial asset of the same name first so the
# re-upload's name isn't taken by a half-finished blob.
for i in "${!APK_NAMES[@]}"; do
    name="${APK_NAMES[$i]}"
    a="${APK_SRCS[$i]}"
    ok=""
    for attempt in 1 2 3 4; do
        # Drop a leftover partial of this name before (re)uploading.
        for aid in $(curl -sS "${auth[@]}" "$API/repos/$REPO/releases/$rel_id/assets" \
            | python3 -c "import json,sys
try:
    print('\n'.join(str(x['id']) for x in json.load(sys.stdin) if x['name']=='$name'))
except Exception: pass" 2>/dev/null); do
            curl -sS -o /dev/null -X DELETE "${auth[@]}" "$API/repos/$REPO/releases/assets/$aid" || true
        done
        up="$(curl -sS --max-time 300 -X POST \
            -H "Authorization: token $TOKEN" \
            -H "Content-Type: application/vnd.android.package-archive" \
            --data-binary @"$a" \
            "https://uploads.github.com/repos/$REPO/releases/$rel_id/assets?name=$name")"
        state="$(printf '%s' "$up" | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("state",""))
except Exception: print("")' 2>/dev/null || true)"
        if [ "$state" = "uploaded" ]; then ok=1; echo "publish: uploaded $name"; break; fi
        echo "publish: upload attempt $attempt for $name failed: $(printf '%s' "$up" | head -c 160)" >&2
        sleep $((2 ** attempt))
    done
    if [ -z "$ok" ]; then
        echo "publish: !! asset $name upload failed after retries" >&2
        exit 1
    fi
done

echo "publish: OK — $REPO release $TAG published with ${#APK_NAMES[@]} asset(s)."

# Consume the PUBLIC staging APK(s): a build-dist.sh staging is single-use, so
# delete it now that it's shipped. Leaving it in builds/ is exactly how a stale
# copy lingered and shadowed a later PRIVATE publish ("793 packed as 799").
# PRIVATE publishes read from gradle output and stage nothing, so this is a
# no-op for them.
if [ "$PUBLIC" = "1" ]; then
    for nm in "${APK_NAMES[@]}"; do
        [ -f "builds/$nm" ] && rm -f "builds/$nm" && echo "publish: consumed staging builds/$nm"
    done
fi
