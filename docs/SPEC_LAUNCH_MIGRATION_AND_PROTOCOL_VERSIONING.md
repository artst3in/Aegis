# SPEC: Launch-Gate Migration & Protocol Versioning

**Status:** DRAFT — awaiting Aurora review
**Author:** Chad
**Date:** 2026-06-13
**Blocks:** any public release (target user base: ~2M, not 2)

---

## Problem

The June 2026 cleanup pass (`claude/bug-fixes-continuation-opclew`, releases
712–717) deleted a large amount of forward-compatibility machinery. Every one
of those deletions was justified — explicitly, in commit messages, in
CLAUDE.md, and in Aurora's review — by the **two-user policy**: "update both
phones, re-pair after a breaking change." Aurora flagged the risk at the time:

> "the two-user policy citation is now load-bearing policy for deleting all
> compat code. Correct for two users. Would need revisiting if the user base
> grows before a formal migration mechanism."

The user base target is **~2 million**, not two. At that scale the two-user
assumption inverts, because two things become true that are false for two
phones:

1. **Updates are over-the-top, never clean reinstalls.** Play/sideload/OTA
   installs land on top of existing data. Users do not wipe + re-pair.
2. **Rollouts are not atomic.** A store/OTA rollout to millions takes days to
   weeks. During that window, **paired users are on different app versions**
   and must keep communicating.

The current `main`-bound code (post-cleanup) assumes neither. This spec defines
the two subsystems that MUST exist before any public release, and inventories
exactly what was removed so it can be rebuilt as a framework (not restored as
the old brittle one-shots).

This spec does NOT propose reverting anything now — pre-launch, with no field
installs, the deletions are correct and the tree is simpler for it. It defines
the launch gate.

---

## What was removed (inventory)

### A. On-device forward migrations (data-preserving upgrades)
Removed because "clean reinstall" made them moot. At scale, each protects a
real fraction of the install base from data loss on update:

| Removed | Commit | Protected against |
| --- | --- | --- |
| Argon2id app-DB rekey (`derivePassphraseLegacy` / `migrateDbKeyIfNeeded` / `canOpenWith`) | `ebfccba` | A pre-Argon2id app DB bricking (can't decrypt under the new key) |
| Pre-Phase-1 profile-layout migration (`migratePreProfileLayoutIfNeeded` / `rewriteDbAttachmentPaths`) | `e321ba8` | Old-layout installs losing identity/DB/attachments/avatar |
| SimpleX core-DB legacy passphrase + rotation (`SimpleXDbKeyStore` migration surface, `SimpleXCore.rotateAndReopen`, the multi-key open/recovery) | `2548df6` | An un-rewrapped core DB losing the entire message store + pairings |

`sealLegacyPlaintext` was correctly KEPT — it is a live at-rest control (runs
every unlock), not a migration.

### B. Cross-version wire compatibility
Removed because both phones update together:

| Removed | Commit | Effect at rollout scale |
| --- | --- | --- |
| Legacy signed-envelope tolerance in `ControlChannel.readPlain` | `7cc8559` | An older peer's control frames are dropped |
| Plaintext `[aegis:hello]` bootstrap (moved to `x.aegis.v1`) + zero-exception plaintext hard-deny | `7cc8559` | An older peer can't bootstrap the control channel at all |
| v1 `[aegis:ptt]` PTT wire shape | `7cc8559` | Older peers' PTT clips ignored |
| 1:1 signed-control signature + counter | `eabe9e1` | (Removed a P0 desync; not a coexistence issue, but the wire shape changed) |

Consequence during a non-atomic rollout: a v(N) and v(N-1) user who are paired
lose presence, location, typing, read/delivery ticks **and SOS control
signalling** across the version gap until both update.

### C. Schema model
`DbRebuild` (export → wipe → recreate → restore) + `fallbackToDestructiveMigration`
as the net; `DB_SCHEMA_VERSION` reset to 1. The destructive fallback is a
**silent data-loss path** — acceptable as a two-user safety net, unacceptable
at scale without user-visible recovery.

---

## Proposal

### Pillar 1 — A real migration framework (not restored one-shots)

The removed migrations were one-shot, marker-file-gated, and brittle (scattered
`.db_kdf_v2` / `.profile-migration-done` sentinels). Rebuild as a single
ordered, idempotent, crash-safe migration registry:

- **One stored "data version"** (separate from the Room schema version), the
  highest migration applied, persisted durably (and survivable across a
  failed migration).
- **An ordered list of migration steps**, each: idempotent, forward-only,
  data-preserving, individually crash-safe (resumable from the stored
  version), and covered by a test that runs prior-state → current.
- **Re-express the three removed migrations as steps** in this registry
  (DB-key derivation, profile layout, SimpleX core-DB key wrap), so an install
  from any shipped version converges.
- **`fallbackToDestructiveMigration` becomes a last resort with user-visible
  recovery**, never a silent wipe — surface "couldn't migrate your data; here's
  your recovery phrase / backup path" rather than dropping it.
- **Migration test matrix:** every prior shipped schema/data version → current,
  in CI.

### Pillar 2 — A coexisting wire protocol

The content-type is a **stable identity tag** — `x.aegis` (was `x.aegis.v1`;
the version was removed from the type entirely). Its only job is "this is an
Aegis control message a vanilla client can't forge," so it never changes and
the receive path recognises it by exact match forever. No version is encoded in
the type.

**Recommended approach (Chad — pending Aurora): capability negotiation, with
the version number = the app version, diagnostic only.**

Interop is decided per-FEATURE, not per-version. At the hello bootstrap each
peer advertises:
- its app `YYYY.MM.BBB` — **diagnostic only** (telemetry, "you're on an old
  build" nudges); never compared to decide "can we talk";
- a **capability set** — the control surfaces it can receive (e.g. `presence`,
  `location`, `ptt-multitarget`, `sos-degraded`, …).

Both are persisted per-peer alongside `controlPubKey` / `isAegis`. A sender uses
a surface only if the peer ADVERTISED it; otherwise it falls back.

Why capabilities, not a version comparison:
- The user base is a long tail of old builds **by design** — the people this app
  serves are the least able to update promptly (coerced, monitored, afraid to
  touch the phone). At 2M, peers years apart are always talking. A capability is
  a positive "I can receive this" signal, so degradation works across an
  arbitrary gap with no shared knowledge of version ranges on either side.
- Safest for SOS: you only use a surface the peer confirmed, eliminating the
  "assumed they were new enough → panic silently dropped" failure mode.
- Keeps the version number purely mechanical (the build number, zero judgment) —
  there is never an "is this a breaking change?" call. Add a surface → add a
  flag → old peers don't advertise it → fall back. This applies the zero-entropy
  versioning ethos to the protocol rather than fighting it.

Required pieces:
- **Capability registry** — one source of truth listing every advertisable
  surface, how it negotiates, and its fallback. A flag is retired only once no
  supported peer can still lack it.
- **A fallback per surface.** SOS is non-negotiable: it must degrade to the
  **basic SOS surface that every Aegis build understands**, with richer
  sub-surfaces (audio, multi-target PTT, frames) as capability-gated additions.
  The floor is between *Aegis* peers of differing versions — **SOS is
  Aegis-only**: a non-Aegis (vanilla SimpleX) contact is never an SOS recipient
  (it can't be promoted above Untrusted), and as of the SOS-Aegis-only change
  both outbound gates now DROP any SOS to a non-Aegis peer rather than strip it
  to plaintext. (Decision below. The earlier "gateAegisControl already strips
  SOS for non-Aegis recipients" framing — in this spec and in Aurora's review —
  was wrong: that path was unreachable and is now removed.)
- **Hello carries version + capabilities**, both persisted per-peer (the hello
  already carries the control pubkey, so it's the natural home).
- **Deprecation = retiring capability flags**, not a version floor. A forced-
  update floor stays a last resort (hostile to users who physically cannot
  update — use sparingly).

The two version-comparison alternatives (a standalone protocol version, or the
app version plus a compatibility table) are weighed in "Alternatives
considered" with why they lose to capabilities here.

### What stays deleted
All genuine dead code from this pass remains gone — LAN, the `Family` shim, the
dead tier modules, unused deps/drawables/strings, the lint triage, the debug
probe, the vestigial counter columns. None of it is scale-sensitive.

---

## Alternatives considered

- **"Re-pair on every breaking change" (current de-facto).** Rejected at scale:
  loses message history + identity continuity, breaks the contact graph, and is
  hostile to the exact users this app exists for (someone in a coercive
  situation cannot casually re-pair every contact).
- **Server-side migration.** N/A — the system is decentralized (SimpleX); there
  is no server to migrate from.
- **Force-update (refuse to run old versions).** Usable only as a wire-break
  last resort; can't be relied on across a multi-week rollout and strands users
  who can't update. Not a substitute for coexistence.
- **Keep migrations as the old one-shots (just revert).** Rejected: they were
  brittle (scattered markers, no resume, silent destructive fallback). Rebuild
  as a framework instead.
- **Standalone protocol version (number encodes compatibility).** A `YYYY.MM.BBB`
  living with the wire format, bumped on a wire-incompatible change, peers gate
  on it. Rejected: a single linear number can't express partial support ("new
  presence but not new PTT") so you end up wanting capabilities regardless; it
  reintroduces the is-this-breaking judgment; and a *missed* bump means two
  different wires share a number → silent breakage, in an SOS path. Worst
  failure mode of the options.
- **App version + compatibility table.** Number stays mechanical, but a table of
  "which versions interoperate" is centralized knowledge in a decentralized
  system — a years-old peer can't carry today's table, and you still need
  per-surface degradation for SOS. Pays the capability cost without the benefit.

---

## Open questions (for Aurora)

1. **Identity continuity.** The device identity key roots the DB passphrase and
   the contact graph. Can it ever rotate across a major upgrade without forcing
   a re-pair? If not, that's a hard constraint on every future change.
2. **Capability lifecycle.** What's the initial capability set, and the policy
   for retiring a flag (when is "no supported peer can still lack it" true,
   given a long tail of old builds)? Is there ever a hard floor below which a
   peer is refused, or is it capabilities-all-the-way-down?
3. **Destructive-fallback UX.** What is the user-visible recovery when a
   migration genuinely cannot complete? (Recovery phrase re-entry? Backup
   import? Explicit consent before any wipe?)
4. **Baseline floor.** Do we freeze the current post-cleanup schema (data
   version 1) as the migration FLOOR for launch — i.e. v1 is the oldest state
   any migration step must handle — and treat everything pre-cleanup as
   never-shipped-publicly?
5. **Test strategy.** Migration matrix + a capability-interop test harness
   (new build ↔ a peer advertising a reduced/old capability set) — what's the
   minimum bar before launch?

---

## Acceptance (launch gate)

Public release is blocked until:
- [ ] Migration framework exists, the three removed migrations are re-expressed
      as steps, and the prior-version → current matrix passes in CI.
- [ ] Destructive fallback never silently drops data (user-visible recovery).
- [ ] Capability negotiation exists (hello exchanges version + capability set,
      persisted per-peer); a capability registry with a fallback per surface.
- [ ] SOS degrades safely when a peer lacks the newer surface (tested against a
      reduced-capability peer).
- [ ] Aurora-reviewed, with additions.


---

## Aurora review — June 13, 2026

**Status: APPROVED as launch gate.**

The spec correctly separates "what we deleted and why" from "what we need
before shipping." Nothing needs reverting now. The inventory is thorough
and honest. The two-pillar structure is right.

### Pillar 1 — Migration framework

Correct. Ordered, idempotent, crash-safe registry with one stored data
version replaces scattered marker files. No notes.

### Pillar 2 — Capability negotiation

The right model for a serverless system with a long tail of old builds.
Per-feature negotiation is the only approach that works without central
knowledge of the install base. x.aegis as stable identity tag — never
versioned, never changes — is the correct final position.

SOS degrading to plain-text for any peer lacking the newer surface is
the most important design decision in this spec. The gateAegisControl
model already does this for non-Aegis recipients. Generalizing it to
old-Aegis recipients is the same pattern.

### Answers to open questions

**Q1 — Identity continuity.** Device identity key cannot rotate without
re-pair. Hard constraint. Any future key change needs in-band authority
transfer (old key signs over to new key) or it breaks every pairing.

**Q2 — Capability lifecycle.** I don't have a good answer. The retirement
policy depends on install base decisions that haven't been made. I will
not fabricate a number.

**Q3 — Destructive-fallback UX.** Never wipe silently. Recovery phrase
prompt first. Explicit consent button before any data loss. Three stages:
silent migration attempt, recovery phrase prompt, explicit wipe consent.

**Q4 — Baseline floor.** Yes. Data version 1 is the floor. Pre-cleanup
is pre-release and never shipped publicly.

**Q5 — Test strategy.** Depends on CI infrastructure that doesn't exist
yet. The FRAMEWORK must support arbitrary N-to-current so future versions
don't need ad-hoc scaffolding. Minimum bar: one test per migration step,
one cross-capability test for SOS degradation. Specifics deferred until
the framework exists.

---

## Decisions (Artur)

**SOS is Aegis-only.** A non-Aegis (vanilla SimpleX) contact never receives
SOS. Rationale: SOS is the app's feature — to get it, install Aegis and have
your trusted contacts install it too. A vanilla contact is chat-only.
Corollary: staying on plain SimpleX means you won't even know a friend is in
danger; that's the cost of not running the app, by design.

Implemented (commit on this branch): both outbound gates
(`ProtocolManager.gateAegisControl`, `SimpleXTransport.aegisGated`) now DROP
`[aegis:sos]` and the whole `sos-*` coordination family for non-Aegis peers
instead of stripping to plaintext. The previously-asserted "we already strip
SOS to plaintext for non-Aegis recipients" was wrong (unreachable: non-Aegis
can't be promoted to an SOS tier) and that code is removed. The capability
SOS-floor (Pillar 2) is therefore strictly between *Aegis* peers of differing
versions, never to non-Aegis. The Contact screen's "Promote to Trusted" hint no
longer shows on vanilla contacts (there's no promote control for them).
