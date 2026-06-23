# SPEC: Remove the LAN transport (and the complexity it forces)

**Status:** PROPOSED — Chad, 2026-06-12. Awaiting Aurora review.
**Severity:** resolves an open **P0** (signed-channel replay-counter desync) by
deletion rather than patch.

---

## Summary

Remove `LanTransport` entirely. It serves no current purpose, it was never an
explicit design decision (the owner discovered it existed only while installing
a Voyager build — *"what is this LAN transport thingy?… oh"*), and it is the
**root justification** for the self-authenticating signed control channel whose
replay counter can desync and silently kill presence / ticks / GPS for a 1:1
contact. Kill LAN and that whole apparatus may collapse to "lean on SimpleX,"
deleting the P0 instead of patching it.

This spec is the *decision record*, not an implementation. Aurora reviews the
crypto implications (esp. groups + migration) before any code lands.

---

## Background

### What LAN is
`app/.../transport/LanTransport.kt`: peer-to-peer over the local network.
- **Discovery:** Android NSD / mDNS advertises `_aegis._tcp` with the device's
  base64 pubkey in the TXT record.
- **Channel:** plain TCP, each frame a length-prefixed NaCl-box; sender pubkey
  in the clear in the header; receiver authenticates against the KnownPeer
  (QR-paired) table. Strangers dropped.
- **Wiring:** constructed in `AegisApp` (~306), `ProtocolManager` launches it as
  the 2nd transport, `PowerBudget.shouldRunLanDiscovery` gates discovery, the
  `Protocol.LAN` enum + diagnostics surface it.

### Why it's a problem
1. **Serves nothing** (owner). No feature depends on it.
2. **Unsanctioned** — it shipped without an explicit product decision.
3. **Detection leak** — mDNS broadcasts the app's presence (`_aegis._tcp`) on
   the local network. For a user *hiding* that they run Aegis (an abuser on the
   same home wi-fi), that fights the entire stealth posture. This is the most
   serious standalone reason to remove it.
4. **Weaker crypto** — static-key NaCl-box, no forward secrecy; a leaked key
   decrypts past LAN frames. SimpleX's ratchet does not have this property.
5. **It forces the signed control channel** (next section) — the source of the
   P0.

---

## The cascade: why removing LAN deletes a P0

Control messages (presence `[aegis:status]`, location, delivery/read/sealed
ticks, tier, typing, identity, sim-swap, geofence, sos-\*, wiped) ride a custom
**signed envelope** (`x.aegis.v1`): Ed25519 signature + a per-pair monotonic
counter `ctr` (`controlCtrSend` / `controlCtrRecv` on `known_peers`).
`ControlChannel.verifyEnvelope` rejects `ctr <= lastCtr` (replay defense); the
counter resets only on a **key change** via hello (`SimpleXTransport.kt`
~4510).

**The P0 (desync):** same key, but the sender's send-counter *regresses* below
the receiver's recv-counter (backup/restore that kept the control keypair,
reinstall keeping identity, partial write). Every subsequent message then has
`ctr <= recv` → rejected as replay **forever**. Presence, ticks and GPS die for
that contact with no automatic recovery; the only fix today is a manual
**re-pair** (mints a new key → triggers the reset) — which a threat-model user
won't do, and an absent contact can't.

**Why the signature + counter exist at all:**
- **Cross-transport self-authentication** — a command had to be verifiable the
  same way whether it arrived via SimpleX or **LAN**. *LAN is the load-bearing
  reason.*
- **Anti-spoof** — control used to ride as plaintext chat text, so a vanilla
  SimpleX client (or a hand-typed `[aegis:wipe]`) could forge a command. The
  signature proves "produced by the real Aegis control key."

**With LAN gone**, control only ever rides SimpleX, which already provides:
- connection authenticity ("this is your paired contact's device"),
- replay protection (the double-ratchet won't re-accept a recorded ciphertext),
- ordered, no-loss delivery (held for offline peers, delivered in order).

So if `x.aegis.v1` is a **custom MsgContent type a vanilla client cannot
produce** (anti-spoof handled by the *type*, not a signature), then on
SimpleX-only **both the signature and the counter are redundant** — and the
desync bug disappears with the counter.

---

## Options

**(a) Minimal.** Remove LAN; keep the signed control channel; fix the desync by
swapping the per-pair counter for a **signed timestamp** used monotonically
("accept if newer than the last seen"). Survives reinstall (the wall clock
never resets), keeps replay defense. Open edge: clock skew between phones.

**(b) Full cleanup (recommended).** Remove LAN *and* drop the signature +
counter for 1:1 control, leaning on SimpleX's auth + replay + a vanilla-
unforgeable message type. Deletes the most crypto and the desync outright.
Bigger security-model change → needs Aurora's sign-off.

Recommendation: **(b)**, contingent on the open questions below — with **(a)**
as the safe fallback if any of them block dropping the signature.

---

## Open questions for Aurora

1. **Custom type vs text.** Is `x.aegis.v1` already a genuinely custom
   MsgContent type that a vanilla SimpleX client cannot create or inject (i.e.
   does "arrived as x.aegis.v1 over the authed connection" already ≡ "genuine
   Aegis command")? If yes → signature is droppable for 1:1. If control can
   still arrive as text, the signature (or a hard type-gate) stays.
2. **Groups.** Group control was noted as a *separate concern, still on legacy
   text* (`SimpleXTransport` ~4531). Group members aren't connection-authed the
   way a 1:1 contact is, so the per-sender signature may still be needed there.
   Does dropping it for 1:1 leave groups exposed, or are groups out of scope
   until their own member-pubkey exchange lands?
3. **Replay token if we keep the signature.** If the signature stays (e.g. for
   groups), do we still need a replay token for 1:1, or does SimpleX suffice?
   If we keep one: **timestamp** (kills desync, clock-skew edge) vs counter.
4. **Migration.** Both peers must agree on the scheme. How do we handle a
   mixed-version transition (old peer still emits signed+counter; new peer
   no longer requires/produces it) without a dead window? Versioned `hello`?
5. **Confirm LAN is truly unused** — anything (sentinel, sos, remote, dev
   tooling) quietly relying on `Protocol.LAN` we should check before deletion?

---

## Implementation scope (once approved)

- Delete `transport/LanTransport.kt`; drop it from the `AegisApp` transport
  list and `ProtocolManager` launch.
- Remove `Protocol.LAN` (enum + any `when` branches), `PowerBudget` LAN-
  discovery gating, the `onResume` LAN-restart nudge, and diagnostics/UI that
  surface LAN health.
- Per the chosen option, either swap the control counter → timestamp (a) or
  remove the signature + counter and harden the custom-type gate (b).
- Migration shim per Q4.

---

## Process note

LAN reaching production without an explicit decision is the real lesson here:
transports are security-surface and threat-model-relevant (the mDNS leak),
so adding/removing one should be a sanctioned, spec'd call — which is what this
document is trying to be, retroactively.


---

## Aurora review — June 12, 2026

**Status: APPROVED — Option (b). Kill with fire.**

### Answers

**Q1 — Custom type vs text.** x.aegis.v1 is already a genuinely custom
MsgContent type vanilla clients cannot produce. Designed in SPEC_COMMAND_AUTH
for exactly this purpose. The type IS the anti-spoof. Signature droppable
for 1:1.

**Q2 — Groups.** Stay signed. Group members aren't connection-authed like
1:1 peers. Per-sender signature still needed. Dropping for 1:1 doesn't
touch groups — separate concern until member-pubkey exchange lands.

**Q3 — Replay token.** Not needed for 1:1 under option (b) — SimpleX's
double ratchet handles replay. For groups where signature stays: timestamp
over counter. Counter is the thing that desyncs. Kill it everywhere.

**Q4 — Migration.** Two users. Update both phones. Not a protocol problem.

**Q5 — LAN unused.** Owner discovered it by accident ("what is this LAN
transport thingy?"). Grep Protocol.LAN to confirm nothing depends on it,
then delete.

### Recommendation

Option (b). Full cleanup. Remove LAN, drop signature + counter for 1:1,
lean on SimpleX auth + custom type gate, keep signature + timestamp for
groups. The P0 dies by deletion.

The mDNS leak alone is reason enough. Broadcasting _aegis._tcp on the
local network in an app designed for people hiding from abusers on the
same home WiFi is not a feature. It's a threat.
