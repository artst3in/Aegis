# Git history cleanup — APK blob strip

User report: "we are probably at over 5 GB now". Actual current
state: `.git` directory is 1.4 GB, of which the pack files are
337 MB and loose objects are ~1.1 GB. Dominated by the 62 APK
blobs accumulated across release commits (each commit adds
~177 MB of binary that never gets reused).

The OS-level estimate may be higher if .git was previously larger
before gc — git lets stale objects sit around until a manual
`gc --prune=now`.

## Plan

Strip every `builds/aegis-debug*.apk` from history except HEAD,
keep version.json (text), then force-push.

### Steps

1. **Backup the current APKs** outside the repo so they survive
   the rewrite.
   ```
   mkdir -p /tmp/aegis-apk-backup
   cp builds/aegis-debug*.apk /tmp/aegis-apk-backup/
   ```

2. **Install git-filter-repo** (the modern replacement for
   filter-branch — orders of magnitude faster and safer):
   ```
   pip3 install git-filter-repo
   ```

3. **Rewrite history** to drop the APK blobs from every commit:
   ```
   git filter-repo --invert-paths \
       --path-glob 'builds/aegis-debug*.apk' \
       --force
   ```
   This removes the path from EVERY commit (including HEAD). The
   commit SHAs all change. Refs (`main`, tags) move to the new
   SHAs automatically.

4. **Restore the current APKs** as a fresh commit so the OTA
   path keeps working:
   ```
   cp /tmp/aegis-apk-backup/*.apk builds/
   git add builds/aegis-debug*.apk
   git commit -m "RELEASE artifacts — re-added after history rewrite"
   ```

5. **Garbage-collect** locally so .git shrinks immediately:
   ```
   git reflog expire --expire=now --all
   git gc --prune=now --aggressive
   ```

6. **Force-push**:
   ```
   git push --force-with-lease origin main
   ```
   `--with-lease` instead of bare `--force` so we don't blow away
   commits anyone else pushed in the meantime.

### Expected savings

97 commits in history. 62 carry an APK pair (~177 MB combined).
Stripping all but HEAD: 61 × 177 MB ≈ **10.8 GB of compressed
history** removed (the loose-object expansion is what was
pushing .git toward 1.4 GB). Result: .git drops to ~50 MB —
text-only history plus the two HEAD APKs.

### Cost / risk

- **Destructive force-push.** Every commit SHA after the
  rewrite is different. Anyone with a clone (Aurora's machine,
  Zippy's local checkout, any CI runner) has to delete + reclone.
  Pulls will fail until they do.
- **GitHub UI links break.** PRs / issue comments referencing
  pre-rewrite SHAs become dead. Aegis doesn't use PRs heavily
  so likely fine.
- **The webhook activity feed** for the repo will replay events
  against the new SHAs. Minor visual noise, no functional loss.

### Long-term fix (optional follow-up)

Stop committing APKs to `main`. Two options:

1. **GitHub Releases for binaries.** UpdateClient already
   targets a release tag; switch from raw-blob URL to
   `/releases/download/<tag>/aegis-debug.apk`. Free, fast,
   versioned. Requires releases to actually be CUT (the
   `release create` API call) on every push that should be
   user-installable.

2. **Git LFS.** Track `builds/*.apk` via `.gitattributes` so
   the binaries land in the LFS store instead of the regular
   pack. History stays small. Costs LFS storage + bandwidth
   on GitHub.

Option 1 fits Aegis's flow better — releases are explicit user-
visible events and the OTA path already understands "latest
release".

### Decision needed

The destructive force-push is the gate. Once you say "go" I:

1. Verify nothing else is mid-push.
2. Run the rewrite locally.
3. Confirm the rewritten repo still builds + the OTA-relevant
   files are intact.
4. Force-push.
5. Tell anyone with a clone to nuke + reclone.

Pinging on this; no action yet.
