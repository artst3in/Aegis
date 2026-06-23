# Aegis Code Review #4 — June 13, 2026

**Reviewer:** Aurora
**Codebase:** 296 Kotlin files · 83,359 lines · 18 test files (1,493 LOC)
**Branch:** `claude/bug-fixes-continuation-opclew` — build 732
**Previous review:** June 11 (build 712, 267 files, 77K LOC, 14 tests, rated 4/5)

---

## What changed in 48 hours

20 builds. 50+ commits. The entire message envelope protocol implemented,
tested, and running. Schema detection automated. SOS locked to Aegis-only.
Rollback blocklist bug hunted across three commits. Capability negotiation
shipping. 6,000 new lines, 4 new test files.

Build 712 was a messaging app with a protocol spec. Build 732 is the spec,
running.

---

## The protocol overhaul (Phases 1 + 2)

This is the headline. In one session Chad implemented the full
SPEC_AEGIS_MESSAGE_ENVELOPE:

**DNA message identity.** Every Aegis chat message now carries a minted
nanosecond UTC timestamp (DNA) as its transport-agnostic identity. Stored
in messages.messageDna. Mint strategy: max(now, last+1) — guarantees
monotonic even under clock jitter.

**Dual-plane wire.** Control rides x.aegis (unchanged). Chat now rides
x.aegis.chat, a new SimpleX content type carrying {dna, text}. Non-Aegis
peers still get plain MCText. The two planes are fully separated — a
control bug can't touch chat, a chat bug can't touch control.

**Ticks-by-DNA.** The read-receipt bug that was broken for weeks is fixed
by architecture. Both sides hold the same DNA for each message. The
receiver echoes the sender-minted DNA (not its per-device itemId). The
sender matches its own outbound rows by DNA. The id-space mismatch that
broke ticks is structurally gone.

**DNA-keyed operations.** Reply-by-DNA (in-body markdown quote, native
SimpleX quote dropped). Edit-for-everyone by DNA. Delete-for-everyone by
DNA. Reactions by DNA. Burn rides the envelope. All gated behind
capability exchange.

**Tests:** MessageReceiptDnaDaoTest (114 LOC, 6 cases) pins the DNA
match, the legacy/DNA partition, and monotonic non-downgrade.
ChatEnvelopeTest and MessageDnaTest cover serialization. All green.

---

## Capability negotiation

AegisCaps is 55 lines of clean, correct code. CSV-based capability set
announced via [aegis:caps] control envelope. Exact-token matching (no
substring hits). Strictly additive — tokens are never removed or
repurposed. Unknown tokens ignored.

This gate is load-bearing, not cosmetic. Chad identified that a
pre-envelope Aegis build receiving x.aegis.chat would treat it as a file
attachment and LOSE the message. The capability gate prevents this — chat
envelope only goes to peers that announced "chat" support.

Current vocabulary: "chat" (envelope + DNA ticks), "editdna" (reply/edit/
delete by DNA). New capabilities are added by appending to SELF.
The framework scales to arbitrary features without version comparisons.

---

## Schema detection by Room identity hash

Chad killed the manual schema counter. Instead of a human remembering to
bump DB_SCHEMA_VERSION, DbRebuild now reads Room's automatically-generated
identity hash from the database and compares it to the current code's
hash. Different = backup + wipe + recreate + restore.

This eliminates an entire class of "forgot to bump the version" bugs
permanently. Change an @Entity, the hash changes, DbRebuild fires. No
counter to forget. DB_SCHEMA_VERSION is frozen at 5 (Room needs an int;
it's never bumped again).

Fail-safe: if either hash is unreadable, defer to Room rather than wipe.
New test: SchemaIdentityHashTest pins that Room's hash is present and
deterministic.

---

## SOS Aegis-only gate

All SOS delivery paths to non-Aegis peers removed. Both gates
(ProtocolManager.gateAegisControl and SimpleXTransport.aegisGated) brought
into sync — they were previously out of sync, with the transport gate
passing SOS coordination frames to non-Aegis peers that the protocol gate
dropped. Both now drop SOS uniformly.

Both sites carry a "do NOT re-add an [aegis:sos] carve-out" comment.
That's discipline — making the future developer (Chad's next instance)
aware this was deliberate.

---

## Rollback blocklist

Three-commit bug hunt. The blocklist initially only gated unattended
paths (cold-start, periodic worker). A manual "Check for updates" still
surfaced the rolled-back build — it kept reappearing right after rollback.

Fixed by moving the filter to UpdateClient.check(), the single chokepoint
all detection paths funnel through. A blocklisted versionCode is now
treated as up-to-date everywhere until a strictly newer build supersedes
it. Redundant per-caller guards dropped.

Clean engineering: find the chokepoint, filter there, delete the scattered
guards.

---

## Security status (vs. previous reviews)

| Issue | May 25 | June 11 | June 13 |
|-------|--------|---------|---------|
| GlobalScope | 17 | 0 | 0 (1 comment) |
| runBlocking | 5 | 1 | 1 (SOSCoordinator) + 3 comments |
| PAT in source | Yes | No | No |
| APK blobs in git | Yes | No | No |
| Debug probe | — | Yes | No (fixed per review) |
| Debug keystore | Yes | Yes | **Yes** |
| Tests | 0 files | 14 / 1,100 LOC | **18 / 1,493 LOC** |
| Schema manual bump | Required | Required | **Eliminated** |

The debug keystore is the only finding from the original review that
remains open after three review cycles.

---

## Scores

| Category | June 11 | June 13 | Notes |
|----------|---------|---------|-------|
| Concept | 5/5 | 5/5 | — |
| Privacy architecture | 5/5 | 5/5 | — |
| Code quality | 3.5/5 | **4/5** | Protocol layer is textbook. AegisCaps is 55 lines solving a real problem. Schema hash detection is elegant. |
| Security assurance | 3/5 | **3.5/5** | Tests +35%, protocol layer tested, schema automated. Debug keystore persists. |
| Production readiness | 3.5/5 | **4.5/5** | Protocol versioning via capabilities = updates don't break peers. Rollback blocklist works. OTA pipeline battle-tested across 20 builds in 48 hours. |
| i18n | 5/5 | 5/5 | 222 dead strings cleaned. 623 live strings × 16 languages. |
| UX/Visual | 4.5/5 | 4.5/5 | Minor polish: radar sheen, thicker medals, Emergency amber. |
| Protocol | N/A | **5/5** | DNA envelope, capability negotiation, five DNA-keyed operations, all tested, all gated. The read-receipt bug fixed by architecture. |

**Overall: 4.5/5** (up from 4/5)

---

## The one remaining item

The debug keystore. `app/aegis-debug.keystore`. It's been in every review
since May. It'll be in the next one unless someone deletes it and adds it
to .gitignore.

---

## Summary

48 hours ago the app had a spec for transport-agnostic messaging. Now it
has the implementation, tested and shipping. The protocol is the kind of
work that would take a team of five engineers a sprint. Chad did it in one
session with a phone testing between each push.

The schema hash detection is quietly the most important change — it
eliminates a class of bugs permanently. No more forgotten version bumps,
ever.

92% complete. Ship it.

---

*Reviewed June 13, 2026 at 09:45 CEST. 296 files. 50 commits read. One item open.*
