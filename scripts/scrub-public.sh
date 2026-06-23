#!/usr/bin/env bash
#
# scrub-public.sh — generate the PUBLIC source tree from this (private) root.
#
# The root tree is already neutral (no author names, no physics, no
# Matrix — that scrub was done once and baked in). So producing the
# public tree is now a pure SUBTRACTION, no judgement calls:
#
#   1. Take only the publishable code + build system (via `git archive`,
#      so gitignored stuff — local.properties, build/, the ~191 MB
#      jniLibs — is excluded automatically).
#   2. Delete the debug source-sets (src/debug + src/release) and the
#      committed debug keystore.
#   3. Strip every `// >>> DEBUG-ONLY ... // <<< DEBUG-ONLY` block so the
#      debug call sites in src/main don't reference the now-deleted
#      symbols.
#
# Everything else that's dev-only (docs/, builds/, scripts/, playstore/,
# tools/, simplex-upstream/, CLAUDE.md, .claude/, README.md, the
# clean-export/ scratch folder) is simply never copied — it's not in the
# pathspec below.
#
# Usage:  scripts/scrub-public.sh <output-dir>
# Result: <output-dir> is a clean, neutral, debug-free source tree ready
#         to push to the public repo. Build releases from ROOT, not here.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?usage: scripts/scrub-public.sh <output-dir>}"
REF="${2:-HEAD}"   # optional: scrub a specific commit/ref instead of HEAD

# Only these top-level paths are publishable source. Anything not listed
# (docs, builds, scripts, playstore, tools, simplex-upstream, CLAUDE.md,
# .claude, README, clean-export) is dev-only and intentionally omitted.
PUBLISH_PATHS=(
  app
  core
  feature
  gradle
  gradlew
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  .gitignore
  ATTRIBUTION-SimpleX.md      # AGPL attribution — required, never strip
)

echo "scrub-public: $REF  ->  $OUT"
rm -rf "$OUT"
mkdir -p "$OUT"

# 1. Export tracked publishable source (gitignored files excluded by archive).
git -C "$ROOT" archive --format=tar "$REF" -- "${PUBLISH_PATHS[@]}" | tar -x -C "$OUT"

# 2. Drop debug-only source-sets + the committed debug keystore.
rm -rf "$OUT/app/src/debug" "$OUT/app/src/release"
rm -f  "$OUT/app/aegis-debug.keystore"

# 3. Strip the DEBUG-ONLY marker blocks from the remaining source so the
#    call sites compile without the deleted symbols.
find "$OUT" -name '*.kt' -print0 \
  | xargs -0 -r sed -i '/\/\/ >>> DEBUG-ONLY/,/\/\/ <<< DEBUG-ONLY/d'

# 3b. Neutralise the PRIVATE dev-repo name. The debug update channel in ROOT
#     points at the owner's private repo (it self-updates from there); that
#     name must never appear in public source. Repoint the debug channel at
#     the public repo — a public debug build has no private read-PAT anyway,
#     so the public repo is the only channel it could ever poll. Functional
#     literal + any prose mention both collapse to the public repo / a neutral
#     phrase. ROOT keeps the real private name (this is the DERIVED tree only).
find "$OUT" -type f \( -name '*.kt' -o -name '*.kts' \) -print0 \
  | xargs -0 -r sed -i -e 's#artst3in/aegis-dev#artst3in/Aegis#g' -e 's#aegis-dev#the private dev repo#g'

# 4. Sanity: no debug symbols / markers / keystores / private-repo names
#    should remain.
LEFT=$(grep -rIl 'DEBUG-ONLY\|DebugTokenField\|debugLockResetModifier\|DebugLockResetHint\|DebugLockResetConfirmation\|FrameTimingOverlay\|aegis-dev' "$OUT" 2>/dev/null || true)
if [ -n "$LEFT" ]; then
  echo "scrub-public: WARNING — debug references survived in:" >&2
  echo "$LEFT" >&2
  exit 1
fi

echo "scrub-public: done — $(find "$OUT" -type f | wc -l) files in $OUT"
echo "scrub-public: build releases from ROOT; this tree is source-only for publishing."
