# Code Review — Aurora — May 26, 2026

Reviewed 156 files, 36,945 lines. Focus: security-critical paths first, then code quality, then architecture.

## Security — What's Good

**PeerCrypto** — NaCl box (X25519 + XSalsa20 + Poly1305) via LazySodium. Random nonce prepended to ciphertext. Clean implementation. No issues found.

**LockStore** — Constant-time PIN comparison across all three slots (real, duress 1, duress 2). Salted hashes. Escalating lockout (5 → 30s → 1m → 5m → 15m). Three-layer duress with self-cleaning DURESS_2. The multi-profile `findMatchingProfile()` hashes every profile every time to prevent timing leaks on which profile matched. Well-designed.

**VaultCrypto** — AES-256-GCM with 12-byte random IV stored separately. Domain-separated key derivation (separate from auth hash despite sharing salt). Failed decryption returns null (no oracle). Key wipe helper for heap hygiene. Solid threat model documentation in the comments.

## Security — Improvements

### 1. Identity key in plaintext file (MEDIUM)
`IdentityStore` writes the Curve25519 private key as base64 to `identity.key`. The only protection is Android FDE (full disk encryption). An attacker with root on a running device reads it directly.

**Fix:** Wrap the key with Android Keystore. Generate an AES key in Keystore (hardware-backed on most modern phones), encrypt the identity key at rest, decrypt only when needed. LazySodium stays as the crypto engine; Keystore just protects the key material on disk.

### 2. PIN hashing: SHA-256 loop instead of Argon2id (LOW)
The comment acknowledges this is "theatre for a 6-digit secret behind a rate limit" — which is true. But LazySodium already ships `crypto_pwhash` (Argon2id). Switching costs one function call and gets proper memory-hard hashing for free. No new dependency needed.

### 3. GitHub PAT in BuildConfig (MEDIUM)
`BuildConfig.BUNDLED_GITHUB_PAT` is baked into the APK at compile time. Anyone who decompiles the APK (trivial with apktool) gets the PAT. This PAT has read access to the private `aegis-dev` repo.

**Fix options:**
- Rotate the PAT regularly and accept the exposure window
- Move to a short-lived token endpoint (overkill for now)
- Strip the PAT from public/shared APKs, only bundle for private builds
- Use a read-only PAT scoped to releases only (not full repo access)

### 4. Volume SOS trigger false positives (HIGH — already filed)
`HardwareSOSTrigger.kt` fires on 5× volume press. Volume buttons are pressed accidentally in pockets. Bug filed in `docs/BUG_VOLUME_SOS.md`. Power button ×4 (`PowerButtonSOSReceiver.kt`) is the correct trigger. Remove or disable volume trigger.

## Code Quality

### 5. Force unwraps: 20 instances (LOW-MEDIUM)
All in UI code. Most are guarded by null checks in the enclosing `if` block, so they won't crash in practice. But `!!` anywhere is a latent NPE waiting for a refactor to break the guard.

Worst offenders:
- `SOSScreen.kt:208-209` — `decoy.latitude!!` / `decoy.longitude!!` — crashes if decoy has no GPS
- `CallIsland.kt:87` — `call.connectedAt!!` — crashes if call state is inconsistent
- `AegisBottomNav.kt:69` — `byRoute["SOS"]!!` — crashes if nav routes change

**Fix:** Replace all `!!` with `?.let { }`, `?: return`, or `requireNotNull()` with a meaningful message.

### 6. Raw Thread in AegisApp (LOW)
`AegisApp.kt:287` uses `Thread { }` directly. The rest of the codebase uses Kotlin coroutines. One outlier.

**Fix:** Convert to `CoroutineScope(Dispatchers.IO).launch { }`.

### 7. Unchecked casts: 38 instances (LOW)
Typical Android Kotlin pattern (casting `Activity`, `View`, etc.). No actionable items found in security-critical code.

## Architecture — Observations

### 8. WireGuard dependency unused (LOW)
`com.wireguard.android:tunnel:1.0.20230706` is in `build.gradle.kts` but only the `Key` class is imported (for Curve25519 key generation). The tunnel library is ~2.7MB of native code (`libwg-go.so`) that ships in every APK but does nothing.

**Fix:** Replace the two WireGuard `Key` usages with LazySodium's Curve25519 key generation. Drop the dependency entirely. Saves ~3MB per APK.

### 9. Multi-profile Phase 1 already done (POSITIVE)
`ProfileRegistry`, `ProfileRoot`, profile-aware `LockStore`, profile-aware `IdentityStore` — the invisible refactor is already in place. Adding profile 2 is now an incremental step, not surgery. Good foundation.

### 10. Transport fallback chain is sound (POSITIVE)
`ProtocolManager` tries SimpleX → LAN → queue. Health monitoring per transport. Messages drain on reconnect. Clean design.

## Summary

| Priority | Item | Effort |
|----------|------|--------|
| HIGH | Remove volume SOS trigger | 30 min |
| MEDIUM | Identity key → Android Keystore | 2-3 hours |
| MEDIUM | Scope/rotate GitHub PAT | 30 min |
| LOW | SHA-256 → Argon2id for PINs | 1 hour |
| LOW | Fix 20 force unwraps | 1-2 hours |
| LOW | Drop WireGuard dependency | 1 hour |
| LOW | Thread → coroutine in AegisApp | 15 min |

Overall: the security architecture is well-designed. The crypto is correct. The duress system is clever. The main gaps are operational (PAT exposure, identity key storage) not cryptographic. Code quality is high for the pace of development — 37K lines in weeks with clear comments throughout.

---

*Reviewed by Aurora. dε/dt ≤ 0*
