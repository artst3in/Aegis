# Code Review: LAN Removal + Protocol Simplification

**Reviewer:** Aurora
**Date:** June 12, 2026
**Scope:** 10 commits on `claude/bug-fixes-continuation-opclew` from `df64293` to `2548df62`
**LOC delta:** 1,439 lines deleted, 246 added. Net -1,193 lines.

---

## Summary

Chad executed the full SPEC_LAN_REMOVAL option (b) in one session: kill LAN transport, drop the 1:1 signed-control signature + counter (killing the P0 desync by deletion), strip all compat paths, migrate the schema, remove four dead code artifacts, and finally strip the SimpleX core-DB legacy passphrase. Ten commits, each isolated and revertible, each building on the previous. The codebase is 1,193 lines lighter and a P0 bug is gone.

---

## Commit-by-commit

### Step 1 — LAN removal (5beb8e0a) ✓ CLEAN

LanTransport.kt (248 LOC) and PeerCrypto.kt (62 LOC) deleted. Protocol enum defaults unknown values to SIMPLEX (no migration needed for serialized "LAN" rows). Cleanly unwound from AegisApp, ProtocolManager, PowerBudget, Diagnostics, Capabilities, ChatScreen.

**Verified:** mDNS `_aegis._tcp` broadcast is gone. The detection leak is closed.

### Step 2 — Drop 1:1 signature + counter (eabe9e17) ✓ CLEAN

ControlChannel gets `buildPlainEnvelope` / `readPlain` for the unsigned 1:1 path. Code is trivially correct — `buildPlainEnvelope` is pure JSON construction, `readPlain` returns null on missing cmd. Signed primitives (`buildEnvelope` / `verifyEnvelope`) kept for future group path with full KDoc explaining why. The counter that desynced no longer exists. Legacy signed envelopes are tolerated in transit (read, signature ignored).

**Verified in ControlChannel.kt:** the 1:1 path never touches key material. The P0 failure mode (counter desync) is structurally impossible — there is no counter.

### Step 3 — Strip compat + hello on control channel (7cc85593) ✓ CLEAN with one note

Every pre-build accommodation removed. v1 PTT wire shape gone. Hello moves from plaintext to x.aegis.v1 control channel. Plaintext `[aegis:*]` hard-deny now has ZERO exceptions — any `[aegis:*]` arriving as plain text (not through the x.aegis.v1 envelope) is dropped as a hand-typed spoof.

**Verified in SimpleXTransport.handleNewChatItems:** the check is correctly ordered — `controlText == null && groupKey == null && text.startsWith("[aegis:")` → drop. A message that came through the x.aegis.v1 path has `controlText != null` and passes. A group message has `groupKey != null` and passes (group control still rides text until the signed group path lands). Only a direct plaintext spoof is denied.

**Note:** the two-user policy citation ("update both phones") is now load-bearing policy for deleting all compat code. Correct for two users. Would need revisiting if the user base grows before a formal migration mechanism.

### Step 4 — Schema 44→45 (2e10c8c9) ✓ CLEAN

Drop `controlCtrSend` / `controlCtrRecv` columns. Two files, 10 lines. DbRebuild handles the column drop automatically on version bump (dump → recreate → restore matching columns). DbRebuild has both unit and instrumented tests covering this exact path.

**Note for Artur:** schema should reset to 1 (discussed). No migration code remains; every install is a clean slate.

### Step 5 — Remove dead Family object (7ea4b9d3) ✓ CLEAN

`Family.members` was permanently empty, `Family.getByIdentifier` returned null always. Pre-2026 compile-time shim. Five call sites unwound. The live `FamilyMember` data class (load-bearing UI model) correctly kept.

### Step 6 — LAN comment cleanup (bf0248e6) ✓ TRIVIAL

One-line doc fix.

### Step 7 — Remove Argon2id rekey migration (ebfccba0) ✓ CLEAN with caveat

82 lines deleted from AegisDatabase. `derivePassphraseLegacy` / `migrateDbKeyIfNeeded` / `canOpenWith` gone. Destructive fallback covers old DBs.

**Caveat:** the destructive fallback means an old-key DB is wiped silently. Correct per two-user policy (install is wiped + re-paired anyway), but worth noting that this is a one-way door — once this build runs, a pre-Argon2id DB is unrecoverable.

### Step 8 — Remove profile-layout migration (e321ba85) ✓ CLEAN

164 lines deleted from ProfileRegistry. One-time marker-gated file-move migration. New installs start on the profile layout. Dead code.

### Step 9 — Remove SimpleX core-DB legacy passphrase (2548df62) ⚠️ NEEDS ON-DEVICE VALIDATION

This is the dangerous one. 304 lines deleted across 7 files.

**What changed:**
- AegisApp resolves the wrapped key ONCE: `if (ks.hasWrappedPassphrase) ks.loadPassphrase() else ks.generateAndPersist()`. Shared with both eager init and transport. Single source of truth.
- SimpleXTransport.start() simplified to `ensureInitialised(wrappedKey)` — no candidate list, no rotation.
- SimpleXCore.rotateAndReopen deleted entirely.
- SimpleXDbKeyStore stripped to: `hasWrappedPassphrase`, `loadPassphrase`, `generateAndPersist`, `importPlaintext`. Everything else gone.

**Verified in SimpleXDbKeyStore.kt:** the remaining code is correct. AES-256-GCM with random IV, persisted as `iv||ciphertext` with atomic write (tmp + rename), owner-only file permissions. AndroidKeyStore key without user-authentication requirement (correct — the core must boot cold for canary/geofence/SOS).

**Verified in SimpleXCore.kt:** `ensureInitialised` is a thin idempotent wrapper over `tryOpenDb`. `tryOpenDb` uses `chatMigrateInit` which is the upstream path. Clean.

**Risk:** if sideloaded over an existing install without wiping, the core DB may be under the old legacy passphrase. The new code only tries the wrapped key. Chad correctly flags this: "NEEDS ON-DEVICE VALIDATION" and "easily revertible — isolated to this commit."

---

## Issues found

### 1. Stale comment (low severity)

`SimpleXTransport.kt` line 378 still references "DB passphrase migration deliberately NOT invoked here" with a paragraph about `runOneTimeDbMigration` and "candidate-list-try recovery." All of that code was just deleted. The comment is now orphaned and misleading.

**Fix:** delete the comment block (lines ~378-395).

### 2. Debug probe not stripped (medium severity)

`SimpleXTransport.probeSeparationChannel()` is marked `DEBUG-ONLY (stripped for public build)` but the stripping mechanism isn't visible. If R8 doesn't remove it (and R8 fullMode is disabled per the VerifyError workaround), a reverse-engineer could invoke it to probe the control channel. It sends `x.aegis.test` to every paired contact.

**Fix:** delete the method before public release, or gate it behind `BuildConfig.DEBUG`.

### 3. Schema version should be 1 (per Artur)

`DB_SCHEMA_VERSION = 45` in AegisDatabase.kt. No migration code exists. Every install is a clean slate. The version number is legacy debt. Should reset to 1 when cleanup is complete.

---

## What's correct

- ControlChannel separation between plain (1:1) and signed (group) is architecturally clean
- The plaintext hard-deny in handleNewChatItems is correctly ordered
- HelloBroadcaster correctly uses controlPubKey as the "bootstrapped" marker
- SingleXDbKeyStore's crypto is correct (AES-256-GCM, AndroidKeyStore, atomic persist)
- The key resolution single-source-of-truth pattern in AegisApp is correct
- Every commit is isolated and independently revertible
- Every commit message explains what, why, and the failure mode
- Total: 1,439 lines of dead code, compat paths, and legacy migrations removed

---

*Reviewed at 18:30 CEST. 10 commits read. All source files verified. Three issues found, none blocking.*
