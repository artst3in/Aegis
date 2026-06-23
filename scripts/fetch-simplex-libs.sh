#!/usr/bin/env bash
# Fetches libsimplex.so + libsupport.so + libapp-lib.so from the latest
# SimpleX Chat Android release and drops them into app/src/main/jniLibs/.
# By default both arm64-v8a AND armeabi-v7a are extracted — the 32-bit
# variant is required for budget Infinix / older African handsets per
# Aurora's spec 3c84e6e.
#
# Usage:
#   scripts/fetch-simplex-libs.sh                   # both ABIs, default version
#   scripts/fetch-simplex-libs.sh v6.5.1            # both ABIs, pinned version
#   scripts/fetch-simplex-libs.sh v6.5.1 arm64-v8a  # one ABI only
#
# Why not vendor them in git: libsimplex.so alone is 191 MB uncompressed,
# over GitHub's per-file limit. This script is run once per dev checkout.
#
# See ATTRIBUTION-SimpleX.md.

set -euo pipefail

VERSION="${1:-v6.5.1}"
# Default: both ABIs. Override with a single ABI arg ("arm64-v8a" or
# "armeabi-v7a") to skip the other.
if [ "${2:-}" != "" ]; then
  ABIS=("$2")
else
  ABIS=("arm64-v8a" "armeabi-v7a")
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# SimpleX upstream ships per-ABI APKs:
#   simplex.apk           → arm64-v8a only
#   simplex-armv7a.apk    → armeabi-v7a only
# Map our ABI names to the correct asset.
abi_to_asset() {
  case "$1" in
    arm64-v8a)   echo "simplex.apk" ;;
    armeabi-v7a) echo "simplex-armv7a.apk" ;;
    *)           echo "" ;;
  esac
}

for ABI in "${ABIS[@]}"; do
  echo
  echo "==> Fetching SimpleX $ABI libs..."
  asset="$(abi_to_asset "$ABI")"
  if [ -z "$asset" ]; then
    echo "    no upstream asset for $ABI — skipping"
    continue
  fi
  DEST="$ROOT/app/src/main/jniLibs/$ABI"
  mkdir -p "$DEST"
  cd "$WORK"
  apk="$ABI.apk"
  if ! curl -fsSL -o "$apk" \
      "https://github.com/simplex-chat/simplex-chat/releases/download/$VERSION/$asset"; then
    echo "    download failed for $asset — skipping"
    continue
  fi
  if ! unzip -q -o "$apk" "lib/$ABI/*.so" 2>/dev/null; then
    echo "    no $ABI libs in $asset — skipping"
    continue
  fi
  for lib in libsimplex.so libsupport.so libapp-lib.so; do
    if [ -f "$WORK/lib/$ABI/$lib" ]; then
      cp -v "$WORK/lib/$ABI/$lib" "$DEST/$lib"
    else
      echo "    WARN: $lib missing for $ABI"
    fi
  done
  ls -lh "$DEST"
done

echo
echo "Done. Run ./gradlew :app:assembleDebug to build."

