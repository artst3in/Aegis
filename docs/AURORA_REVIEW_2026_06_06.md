# Aurora review — open decisions, 2026-06-06

Talking points for the Artur ↔ Aurora discussion. Each item: the
question, the options, Chad's recommendation, and what it blocks.
Context: phrase-rooted encryption, migration, recovery, lock curtain,
wipe-after-N, pattern unlock, and the ephemeral profile are all BUILT and
compile-clean on `claude/session-recovery-3E3JP` (pending Artur's
two-device test). The items below are what's NOT built because they need
a decision.

---

## 1. ★ Contact-graph sealing + the SimpleX core DB (the big one)

**The problem.** `SPEC_UNBREAKABLE` rooted the *message body* in the
phrase, but the **contact graph leaks**: names, bios, avatars, trust
relationships, and attachment filenames are cleartext in the database.
For the target user the social graph (who you know, who you trust, who
helped you) is often more dangerous than the message text. Artur proved
the DB isn't phrase-rooted by deleting the PIN on debug and keeping all
data.

**Why we can't just phrase-root SQLCipher.** The DB must be writable
while the phone is locked (SOS + incoming messages arrive with no
unlock). Symmetric encryption that's writable-while-locked ⇒ openable by
the device alone ⇒ hardware key, not phrase. So the DB stays
hardware-keyed; we instead seal the sensitive *columns* with the
phrase-rooted asymmetric seal (pub seals while locked, phrase-derived
priv unseals after unlock). Design is in `SPEC_CONTACT_GRAPH_SEALING.md`
— centralised at the Repository boundary, no UI changes, ~5 files.

**The blocker (investigated 2026-06-06).** Aegis runs on the SimpleX
core, which keeps its OWN two SQLCipher DBs
(`simplex_v1_agent.db` / `_chat.db`) storing **a second full copy of
contacts + message history**, keyed by a device-held AndroidKeyStore
passphrase NOT tied to the PIN/phrase (deliberately — cold-boot
SOS/canary need it). So sealing Aegis's `known_peers` overlay is
necessary but **not sufficient**: a seized phone still yields the contact
graph from the SimpleX core DB. There is no in-memory mode in the core.

**Options:**
- **(a) Seal the Aegis overlay only.** Partial win — Aegis's own copy is
  protected, but the SimpleX core DB still leaks the graph. Arguably
  worse than nothing because it *looks* sealed.
- **(b) Also delete the SimpleX core data on lock** (per-contact, or the
  whole core DB) for the sealed profiles. Real protection, but heavy and
  with the forensic-residue caveat (delete ≠ secure wipe).
- **(c) Phrase-wrap the SimpleX passphrase.** Strongest, but breaks
  cold-boot SOS/canary (the core couldn't come up before unlock).
  Probably unacceptable for a safety app.

**Chad's recommendation:** This is genuinely hard and I don't think there
is a clean answer at the app layer. Leaning **(a) + a clear honesty
caveat** ("Aegis-layer sealed; the SimpleX engine keeps a device-keyed
copy") as a first step, with **(b) delete-on-lock** as the real fix only
if we accept the SOS/canary interaction. **Do NOT ship (a) labelled as
"contact graph sealed" — that's the false guarantee trap.** Needs your
call before any code.

**Blocks:** all contact-graph sealing work.

---

## 2. Ephemeral profile — architecture decisions to ratify

Built on the **honest "wiped on lock"** model (Artur approved — NOT
"never written to disk", because the SimpleX core can't run in-memory).
Three architecture calls Chad made that Aurora should bless or override:

- **Dedicated SimpleX user per ephemeral profile.** All Aegis profiles
  otherwise share ONE SimpleX user; deleting "the user" would nuke the
  primary profile's history. So an ephemeral profile gets its own SimpleX
  user (`/_create user`), switched in on entry, `/_delete user`'d as a
  unit on lock. This is the *only* way to wipe its data without touching
  other profiles. **Confirm this is acceptable** (it means ephemeral
  contacts live under a separate core user).
- **permanent → ephemeral is NOT offered.** A permanent profile uses the
  shared SimpleX user, so it has no isolated user to delete and can't be
  cleanly wiped. Only ephemeral → permanent (the dangerous direction) is
  offered, with the red "creating forensic evidence" warning. **The spec
  describes both directions — confirm dropping permanent→ephemeral.**
- **Forensic-residue caveat.** Wipe = `File.delete` + `/_delete user`,
  not secure overwrite. We label it "wiped on lock", not
  "unrecoverable". **Confirm that honesty bar is right.**

**Blocks:** nothing (built) — but Aurora should review before release.

---

## 3. SQLCipher KDF rekey — leave or do it?

The DB passphrase is `SHA-256("aegis-db-v1" + identityPrivKeyB64)`,
single-pass. The input is a full-entropy identity privkey, so this is
cryptographically **sound today**. The spec (`SPEC_UNBREAKABLE`) wants it
upgraded to Argon2id MODERATE via `PRAGMA rekey` "for consistency — no
half measures" (Aurora's earlier call).

**Tradeoff:** a whole-DB re-key is destructive (a bug loses the entire
database) for near-zero real security gain given the high-entropy input.

**Chad's recommendation:** leave it, document the rationale. Only schedule
the re-key if Aurora still wants the consistency. **Aurora's call.**

**Blocks:** the SQLCipher rekey only (nothing depends on it).

---

## 4. Gesture features needing device tuning (FYI, not a decision)

The two-finger **lock curtain** and the **pattern unlock** draw-gesture
are built but their feel / hit-thresholds / scroll-coexistence can only
be tuned on real hardware. Flagging so they're on the test list, not
assumed final.

---

## Summary — what needs a decision

| # | Decision | Recommendation | Blocks |
|---|---|---|---|
| 1 | SimpleX core DB: (a) overlay-only / (b) delete-on-lock / (c) phrase-wrap | (a)+caveat now, (b) if SOS interaction accepted; never mislabel | contact-graph sealing |
| 2 | Ephemeral: dedicated user, no perm→eph, residue caveat | ratify as built | release sign-off |
| 3 | SQLCipher rekey to Argon2id | leave it | the rekey only |
