# SPEC_TESTING — Aegis test plan

Status: PROPOSED (Chad) — awaiting Aurora review + additions.

## 0. Why this exists

Aegis ships with **one** test (`RecoveryPhraseTest`, BIP39 Trezor
vectors). For a security app that is a liability, not a footnote. The
failure modes here are not "a button is the wrong colour" — they are
silent and catastrophic:

- A broken **trust gate** leaks your live location to a contact you
  marked Untrusted.
- A broken **seal** writes plaintext messages to disk that you believe
  are encrypted.
- A broken **PIN/duress** check lets a coercer in, or fails to fire the
  silent SOS.
- A broken **SOS target query** sends your panic alert to *nobody*.
- A broken **`[aegis:*]` classifier** leaks control envelopes (audio
  chunks, remote commands) into the chat as readable bubbles.
- A broken **remote-auth gate** lets an unauthorised peer drive the
  device, or never trips the auto-revoke.

Every one of those has either already regressed once this cycle or sits
one careless edit away. This spec defines the coverage that turns those
into red CI instead of a field incident.

## 1. Test taxonomy & tooling

| Tier | Source set | Runs on | Use for | Needs |
|---|---|---|---|---|
| **Unit (JVM)** | `app/src/test` (+ per-module `src/test`) | host JVM | pure logic, no Android types | `junit` (present) |
| **Robolectric** | `src/test` | host JVM (shadowed Android) | `Context`, `SharedPreferences`, simple framework | `robolectric` dep |
| **Instrumented** | `src/androidTest` | device/emulator | AndroidKeyStore, SQLCipher, native NaCl, biometric, WebRTC | `androidTest` source set + `androidx.test:*` |

Support libraries to add when we reach the tiers that need them
(deferred — see §6):

- `org.robolectric:robolectric` — Android framework on the JVM.
- `org.jetbrains.kotlinx:kotlinx-coroutines-test` — `runTest` for
  `suspend` paths (transport, repository, gate).
- `com.google.truth:truth` — readable assertions (optional but worth it).
- `androidx.room:room-testing` + in-memory DB — DAO/trust-query tests.
- `io.mockk:mockk` — mock `Repository`/transport at unit boundaries.
- `androidx.test.ext:junit`, `androidx.test:core` — instrumented base.

## 2. Conventions

- **Mirror the package.** `…/admin/ShieldTier.kt` →
  `app/src/test/java/app/aether/aegis/admin/ShieldTierEngineTest.kt`.
- **One behaviour per `@Test`**, named as the invariant it defends:
  `untrusted_peer_is_never_in_sos_targets()`, not `test3()`.
- **Given / When / Then** body structure; arrange at top, single act,
  asserts last.
- **Security invariants get a comment** linking back to the spec they
  enforce (`// SPEC_TRUST_MODEL §routine`).
- No sleeps; drive time with injected clocks / fake `now` params.
- Deterministic only — no network, no real RNG where a vector exists.

## 3. Coverage matrix

Priority key: **P0** = security-critical, a regression is a breach;
**P1** = important, a regression is a real bug; **P2** = polish.

### P0 — must have

| # | Area | Unit under test | Key cases | Tier | Blocked by |
|---|---|---|---|---|---|
| 1 | Crypto seal | `SealCrypto.seal/unseal` | round-trip restores plaintext; wrong priv → null; tampered ciphertext → null; sealed ≠ plaintext bytes | Instrumented (native NaCl) | §6 androidTest |
| 2 | Attachment seal | `ChatAttachmentSeal` | DEK seal/unseal round-trip; `.enc` path produces viewable temp; wrong key fails closed | Instrumented | §6 |
| 3 | Recovery phrase | `RecoveryPhrase` | Trezor vectors (✅ exists); wrong phrase → different seed; checksum rejects bad mnemonic; 24-word entropy round-trips | JVM | — (extend existing) |
| 4 | PIN / duress | `LockStore` / `PinKeypair` | correct PIN unlocks; duress PIN detected & distinct; wrong PIN count → lockout; duress never equals real; ephemeral wipe on duress | Robolectric (+ instrumented for keystore) | §6 |
| 5 | Trust gating | `KnownPeerDao` / `Repository.trustedTargets`, `sosTargets` | routine = TRUSTED only; sos = TRUSTED∪EMERGENCY; UNTRUSTED in neither; blocked excluded | Instrumented (Room+SQLCipher) | §6 |
| 6 | Outbound capability gate | `ProtocolManager.gateAegisControl` | non-Aegis peer drops `[aegis:typing/status/location]`; keeps `[aegis:hello]`/`[aegis:remote]`; strips `[aegis:sos]`/`[aegis:burn]` to inner text; Aegis peer passes all | JVM | `private`→`internal` (§6) |
| 7 | Inbound classification | `SimpleXTransport.classifyInbound` | every known `[aegis:*]` → STATUS/typed (never TEXT); **unknown `[aegis:foo]` → STATUS** (leak guard); `[aegis:badges]` → STATUS; plain text → TEXT; `[aegis:sos]` strips prefix | JVM | `private`→`internal` (§6) |
| 8 | Chat-leak hiding | `lastMsgPreview` + chat-row filter | `[aegis:sos-audio]` attachment → "Voice" not raw tag; `[aegis:*]` text → empty; ordinary text unaffected; `You:` prefix on self | JVM | — ✅ writable now |
| 9 | Remote auth gate | `RemoteAccessGate` | N wrong PINs → `recordFailure` returns true at threshold → auto-revoke; revoked sender dropped; `validateSession` rejects unknown/expired/revoked sid; `clearFailures` resets; session sweep expires | Robolectric | §6 (Context-backed revoke store) |
| 10 | Remote auth flow | `RemoteAccessHandler.handleAuth` | correct PIN **and** grant → session; correct PIN, no grant → denied (indistinguishable from wrong PIN); operator badge credited on `onAuthOk` not `handleAuth`; WIPE requires fresh re-auth window | Robolectric + mock transport | §6 |
| 11 | Shield tier math | `ShieldTierEngine.tierFor` | 0→None, 1→Bronze, 2..8→Silver, 9→Gold, 10→Cyan; clamps; monotonic | JVM | — ✅ writable now |
| 12 | Version scheme | versionCode packing + floor | `YYYY*100000+MM*1000+BBB`; floor = max(raw, lastShipped+1); month reset; strictly increasing | JVM | extract pure helper from `build.gradle.kts` (§6) |

### P1 — important

| # | Area | Unit | Cases | Tier |
|---|---|---|---|---|
| 13 | SOS targeting | `SOSHandler` / `Repository.sosTargets` | broadcasts only to TRUSTED∪EMERGENCY; zero-recipient case surfaces; cancel is idempotent + still emits cancellation | Instrumented/Robolectric |
| 14 | SOS audio rotation | `SOSHandler` segment loop | first segment 15s, then 60s; each ships tagged `[aegis:sos-audio]`; stop closes current recorder cleanly | Robolectric (fake recorder) |
| 15 | SOS coordination | `SOSCoordinator` | roster/response/distance/closest/arrived parse + route; victim-voice handling | JVM/Robolectric |
| 16 | Geofence | `GeofenceEvaluator` | enter/exit transitions; hysteresis; no-flap at boundary | JVM |
| 17 | SIM-swap | `SimSwapMonitor` | new SIM serial → alert; same SIM → silent; first-boot baseline | Robolectric |
| 18 | Canary | `CanaryWorker` | fires after deadline w/o check-in; check-in resets; disabled = inert | Robolectric |
| 19 | Power Voyager curve | `PowerBudget` gates + `sosGpsIntervalMs` | each subsystem off at its documented floor; hysteresis ceiling; charging overrides all | Robolectric |
| 20 | Decoy determinism | `DecoyFixtures` / `DecoyBadges` | same seed → same peers/badges (no flicker); duress fixtures never overlap real keys | JVM |
| 21 | Update client | `UpdateClient.gitBlobShaOf`, version compare | sha matches git blob hash; newer-only installs; known-bad blocked | JVM/Robolectric |
| 22 | Boot health | `BootHealthMonitor` | N failed boots → rollback + known-bad mark; 60s alive → markSuccess clears | Robolectric |
| 23 | Presence | `peerStatusFor` | online/away/offline by last-active windows; boundaries | JVM |
| 24 | Achievements | `AchievementStore` / `Achievements` | unlock-once idempotent; broadcast only to trusted; resync no-op when empty | Robolectric |
| 25 | Incognito alias | per-contact alias generator | deterministic per (self,peer); stable across calls | JVM |

### P2 — polish

- Formatters: `fmtBytes`, `formatBytes`, `humanSize`, `ageString`,
  `shortAge`, `formatRelTime`, `dayLabel` — boundary values.
- `Message.peerKey`, status mappers, tier/colour mappers (no Compose).
- `NetworkHealth.computeNetworkHealth` verdict transitions.

## 4. Security invariants to assert explicitly (the "never" list)

These deserve named tests that fail loudly:

1. An UNTRUSTED peer is **never** returned by `trustedTargets()` or
   `sosTargets()`.
2. A `[aegis:*]`-prefixed body is **never** classified TEXT (inbound) and
   **never** rendered as a chat bubble (outbound copy).
3. `gateAegisControl` **never** sends a control envelope to a peer whose
   `isAegis = false` (except the hello/remote bootstrap carve-outs).
4. A duress PIN is **never** equal to the real PIN, and entering it
   **never** reveals the real volume.
5. Remote AUTH **never** opens a session without BOTH correct PIN AND a
   live grant; a wrong-PIN and a not-granted response are
   indistinguishable on the wire.
6. `SealCrypto.unseal` with the wrong key **never** returns plaintext
   (returns null — fails closed).
7. versionCode is **never** non-increasing across two consecutive builds
   in the same month.

## 5. Rollout order

1. **Batch 1 (now, zero-conflict, JVM, existing deps):** #8 partial
   (`lastMsgPreview`), #11 (`ShieldTierEngine`), P2 formatters, #23
   presence, #20 decoy determinism, #3 recovery-phrase extensions.
   These touch **no** main source and need **no** new deps.
2. **Batch 2 (after i18n lands): visibility tweaks + JVM:** flip
   `classifyInbound` and `gateAegisControl` to `internal`; add #6, #7,
   #12 (extract version helper), #25.
3. **Batch 3: add Robolectric dep →** #4, #9, #10, #14, #17–19, #21,
   #22, #24.
4. **Batch 4: add `androidTest` →** #1, #2, #5, #13 (the crypto + Room +
   trust-query crown jewels).

## 6. Prep work required (the blockers, itemised)

- **Visibility:** `SimpleXTransport.classifyInbound` and
  `ProtocolManager.gateAegisControl` are `private`. Flip to `internal`
  (1 word each) so the same-module test set can call them. Tiny, but it
  is a main-source edit → do **after** Aurora's i18n pass to avoid
  conflicts.
- **Version helper:** the versionCode math lives inline in
  `app/build.gradle.kts`. Extract the pure packing/floor function to a
  testable `app/src/main/.../build/VersionScheme.kt` (or a `buildSrc`
  unit) so #12 can assert it.
- **Test deps:** add the §1 support libraries to `app/build.gradle.kts`
  (and the relevant module build files). `build.gradle.kts` is **not**
  an i18n target, so this is conflict-safe and can land independently if
  desired.
- **androidTest source set:** create `app/src/androidTest/...` for the
  instrumented crown-jewel tests (#1, #2, #5).
- **Seams for mocking:** `RemoteAccessHandler` and `SOSHandler` reach
  `AegisApp.instance` directly; a thin injected boundary (or Robolectric
  application) is needed for #10/#13/#14.

## 7. Definition of done

- Every P0 row has at least the named "never" invariant test green.
- CI runs `:app:testDebugUnitTest` on every push (JVM + Robolectric).
- Instrumented suite (Batch 4) runs on a nightly/emulator job.
- Coverage is tracked but not gated by a % — the named invariants are
  the bar, not a number.
