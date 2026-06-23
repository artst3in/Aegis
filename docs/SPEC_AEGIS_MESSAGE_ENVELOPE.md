# SPEC: Aegis Message Envelope (transport-agnostic protocol foundation)

**Status:** DRAFT — awaiting Aurora review
**Author:** Chad
**Date:** 2026-06-13
**Altitude:** protocol foundation, not a feature. Sets up Aegis as a
transport-agnostic protocol; the read-tick fix and capability negotiation are
the first things that fall out of it.

---

## Problem

Aegis is currently a **SimpleX app**, not a protocol:

- A message's identity is SimpleX's `chatItem.meta.itemId` — a **per-device**
  counter. The same message has a different id on each end, which is the root
  of the read-receipt bug (a receipt says "read up to *my* itemId," which the
  other device can't map to its own).
- A message's body is SimpleX's `MCText`.
- Aegis metadata (burn TTL, reactions) is **stuffed into the chat text** as
  `[aegis:…]` markers — hacky, needs escaping, leaks as garbage to vanilla
  clients, and grows uglier with every field.

Every one of those ties the protocol to one transport's semantics.

The strategic goal makes that untenable: **Aegis is intended to become the
communication layer between LunaOS instances** — kernel-level, much more than
texting. A protocol that defines "message id" as a SimpleX row number and
"message body" as a SimpleX content type cannot survive a transport change. The
moment we want a second transport (a LunaOS-kernel IPC layer), all of that
breaks.

SimpleX is not "bad" — it is an excellent transport for the *current* job
(metadata-private, relay-based, async, double-ratchet E2EE over the open
internet). Kernel-level IPC between OS instances is a *different* job
(low-latency, local/mesh, endpoints are your own capability-scoped instances,
possibly streaming). The protocol must be **agnostic** so it can ride SimpleX
today and a kernel transport later.

---

## Proposal: an Aegis-native message envelope

Define Aegis's **own** message format, carried as opaque bytes by whatever
transport. The transport ferries bytes; it does not define our semantics.

### Two planes, two content types (separation preserved)

- **Control plane** — today's `x.aegis`. Commands: presence, location, ticks,
  capability hello, SOS signalling. Dispatched, never shown, no history.
- **Chat plane** — NEW `x.aegis.chat`. User messages as a structured envelope:
  ```
  { "dna": "<ns-stamp>", "text": "<body>", "replyToDna": "<ns-stamp>"?, … }
  ```
  Displayed, sealed at rest, kept in history.

Distinct types because chat (data) and control (commands) have different trust,
lifecycle, and evolution. Collapsing them lets a control bug touch chat and
loses the "this frame is a command, never a message" guarantee.

### DNA — the transport-agnostic message identity

Each chat (and tick-bearing) message carries a **DNA**: a sender-minted,
nanosecond-precision stamp (same scheme family as the build DNA, but **per
message**), carried **inside the envelope** so both ends hold the identical
value. It is the protocol's message id, replacing transport-specific ids
(`itemId`, `itemTs`) that die on a transport swap. It is **opaque** to the
receiver — stored and echoed, never interpreted, and never read as a time (the
*displayed* message time is a separate field).

**Minting (Aurora review, 2026-06-13 — folded-in monotonic mint).**
DNA is minted **`max(now, last+1)`** (nanosecond UTC), from Phase 1. That
guarantees per-sender uniqueness and strict send-order monotonicity
*unconditionally* — so there is no collision case to reason about at all, for
ticks or for Phase-2 per-message addressing (react/edit/delete *by* DNA). One
line, one stored `last`; cheap insurance taken up front rather than retrofitted.

- **This is the right shape, not the "counter for the counter."** A monotonic
  timestamp **is** a counter — folded into one field. We are *not* adding a
  separate counter beside a timestamp (that recurses, and was rejected).
- **Orthogonal to the dropped node-disambiguator** (Aurora's Q3): that was about
  *cross-sender* collisions and is correctly unnecessary — DNA is only ever
  compared within one sender's own space (the receiver echoes opaquely; two
  devices' values never meet). `max(now, last+1)` is about *same-sender*
  ordering. Different axis; both resolved.
- **Trade-off, documented so it isn't mistaken for a bug:** under a backward
  clock step the mint emits `+1 ns` from `last` until real time catches back up,
  so DNA briefly decouples from wall-clock — it becomes a monotonic id *seeded*
  by time, not the time itself. Harmless, because DNA is opaque identity, never
  read as a clock (the displayed message time is a separate field).

### Transport seam

The existing `Transport` interface is the seam. SimpleX is **transport #1**: it
carries the envelope bytes; we stop leaning on its content semantics. A future
LunaOS-kernel transport is another `Transport` implementation carrying the
*same* envelope.

### Vanilla interop (shim)

When a peer is not Aegis (`isAegis == false`), fall back to plain `MCText` — no
DNA, no ticks, basic delivery only. Vanilla support exists solely because we
currently borrow SimpleX's network; it is degraded by design.

---

## What it resolves immediately

- **Read / delivered / sealed ticks.** The chat frame carries the DNA; the
  receiver echoes it in a **control** frame (`read: <dna>`); the sender finds
  *its own* outbound message by DNA (identity match) and marks "up to" by its
  *own* local order. Clocks are fully decoupled — only the sender compares, and
  only against itself. Transport ordering/replay stays the transport's job.
  (This is the bug under investigation; it becomes a side effect of the
  foundation.)
- **Capability negotiation.** Capabilities ride the hello as structured
  fields, not parsed out of text. (Folds in SPEC_LAUNCH_MIGRATION Pillar 2.)
- **Metadata** (burn TTL, reply link, edit version, reactions) become envelope
  fields instead of text markers.

---

## Migration of existing features onto the envelope (the work)

- **Reply/quote** → `replyToDna` (better than the transport's local itemId).
- **Edit / delete** → keyed by DNA.
- **Burn** → an envelope field, not a `[aegis:burn:…]` text marker.
- **Seal-at-rest + purge** → the envelope's `text` field is what gets sealed;
  the transport's plaintext copy is purged after, as today.
- **Search / notifications / native delivery status** → Aegis already does its
  own notifications; search reads our stored body. The one to verify: whether
  SimpleX still emits native send-status (`sndSent`/`sndRcvd` → the base ✓) for
  a custom content type, or whether Aegis must synthesize "delivered" from the
  envelope round-trip (see open questions).

---

## Sequencing (do not boil the ocean)

- **Phase 1 (this spec, shippable):** define envelope + DNA; SimpleX carries it
  behind the Transport seam; ticks-by-DNA + capability fields fall out; vanilla
  `MCText` shim. SimpleX stays exactly as the transport — we do **not** rebuild
  its ratchet, metadata-private routing, NAT traversal, or store-and-forward.
- **Phase 2:** migrate reply/edit/delete/burn/seal onto the envelope.
- **Phase 3 (future, out of scope here):** a LunaOS-kernel `Transport`
  implementation carrying the same envelope. The point of Phase 1 is that this
  becomes *possible* without touching the protocol.

---

## Alternatives considered

- **Keep `MCText`, embed DNA in the text via markers.** Fixes ticks cheaply but
  deepens the transport coupling and the metadata-in-text mess; advances
  transport sovereignty not at all. Acceptable only as a stopgap if Phase 1
  slips — not the foundation.
- **Use SimpleX `itemTs` as the cross-device key.** Works for ticks, but it's a
  transport-specific value — dies on a transport swap and doesn't generalize to
  a message identity. Rejected as the long-term id; DNA is ours.
- **Replace SimpleX wholesale now.** Enormous (ratchet, routing, metadata
  privacy, NAT, store-and-forward). Premature and unnecessary — the envelope
  gives transport-agnosticism without it.

---

## Open questions (for Aurora)

1. **Native delivery status on a custom type.** Does SimpleX still emit
   `sndSent`/`sndRcvd` for a non-`MCText` content type (the base ✓), or must
   Aegis synthesize "delivered" from the envelope round-trip?
2. **Reply threading.** Drop SimpleX's native quote entirely for reply-by-DNA,
   or keep native quote only on the vanilla `MCText` path?
3. **DNA shape on the wire.** Raw nanosecond-ISO string vs a compact binary
   (8-byte ns + small node-disambiguator)? Per-message wire-size cost.
4. **Vanilla shim, long-term.** Do we keep `MCText` interop indefinitely, or is
   Aegis-only chat the stance (consistent with the SOS-Aegis-only decision)?
5. **Seal/purge interaction** with a custom content type — any change to the
   transactional-delivery purge?
6. **LunaOS transport requirements.** Latency / locality / trust / capability
   model — enough detail to keep the envelope from silently baking in SimpleX
   assumptions (async, relay, no identifiers)?

---

## Acceptance / scope

- This spec covers **Phase 1**: envelope + DNA + ticks-by-DNA + capability
  fields + vanilla shim. Phases 2–3 are referenced, specced separately.
- Additive: the new `x.aegis.chat` type ships alongside the existing control
  type and the `MCText` path; nothing currently shipping is removed by Phase 1.
- Blocked on: Aurora review, with additions.


---

## Aurora review — June 13, 2026

**Status: APPROVED — Phase 1 scope is correct and shippable.**

### Why this is right

The diagnosis is precise. Aegis IS currently a SimpleX app — message
identity is a SimpleX row number, message body is a SimpleX content type,
metadata is stuffed into text as markers. Every one of those dies on a
transport swap. The LunaOS kernel transport is not hypothetical — the repo
has 238K lines of Rust, dual-arch, with a SISCALL interface. This bridge
needs building.

DNA as transport-agnostic message identity is the correct abstraction. It
solves the read-receipt bug not as a fix but as a side effect of correct
architecture — which is how you know the architecture is right. When the
hard bug dissolves into the design, the design is the answer.

Two planes (control x.aegis, chat x.aegis.chat) preserved. Correct —
control and data have different trust, lifecycle, and evolution. A control
bug should never touch chat. This mirrors the kernel separation between
syscall and data paths.

The phasing is disciplined. Phase 1 is additive — nothing removed, SimpleX
untouched, vanilla shim preserved. This is how you do a protocol migration
without breaking the shipped product.

### Answers to open questions

**Q1 — Native delivery status on custom type.** I don't know. This needs
testing on device, not speculation. If SimpleX does NOT emit sndSent/sndRcvd
for custom types, Aegis synthesizes delivery from the envelope round-trip:
send x.aegis.chat -> receiver echoes DNA in x.aegis control -> sender marks
delivered. This would actually be MORE reliable than the current system
since it is Aegis-controlled end-to-end and independent of transport
internals. Test first, design after.

**Q2 — Reply threading.** Drop SimpleX native quote entirely for
reply-by-DNA on the Aegis path. The citation-chase navigation Chad already
built works on local data, not SimpleX quote metadata. The vanilla MCText
path keeps native quote because that is all vanilla SimpleX understands.
Two paths, clean separation. Same pattern as the control/chat split.

**Q3 — DNA shape on the wire.** UTC nanosecond timestamp (the Z
suffix prevents any DST/timezone jump concern). Serialized send path
takes ~50ms minimum; clock resolution is 1ms; collision probability is
10^-10 on a broken-RTC phone that failed NTP sync twice. Worst case:
an adjacent message shows a read tick early. No disambiguator needed.

ISO-8601 string on the wire (same format as build DNA). Human-readable
in debug, storage, and diagnostics without conversion. The ~35 bytes
per message is negligible against the envelope payload.

**Q4 — Vanilla shim, long-term.** Kill it in Phase 3. Aegis Protocol is
Aegis-to-Aegis. Consistent with the naming convention (born in Aegis), the
SOS-only-Aegis decision, and the plaintext hard-deny. But keep it in
Phase 1 — we still borrow SimpleX's relay network and someone might pair
with a vanilla client by accident. Degrade gracefully now, drop later.

**Q5 — Seal/purge interaction.** The envelope text field gets sealed. The
transport's plaintext copy gets purged. Same flow as today but the
boundaries are cleaner — the envelope owns the data, the transport owns
the delivery. The transactional purge (seal envelope -> delete transport
copy -> mark sealed) is independent of the content type. No change needed
unless SimpleX's internal storage treats custom types differently from
MCText. Test alongside Q1.

**Q6 — LunaOS transport requirements.** I read the LunaOS repo but not
deeply enough to give authoritative requirements. What I can say: the
envelope must NOT assume async delivery, relay routing, or
connection-based sessions. It must assume only: opaque bytes delivered
reliably in order per-sender. The kernel transport will likely be
low-latency local IPC, capability-scoped, possibly streaming — but the
envelope should not encode those assumptions either. "Opaque bytes in,
opaque bytes out" is the correct contract. Anything more specific should
come from the LunaOS architecture, not from me guessing.

### One addition

**Message ordering.** DNA is monotonic per-sender, but the transport can
reorder (relay latency, multi-relay paths, store-and-forward retry). The
spec should state explicitly: ordering is the TRANSPORT's responsibility.
The protocol uses DNA for identity and echo, not for ordering. The
receiver processes messages in arrival order and uses DNA only to match
echoes to originals. If a transport delivers out of order, the protocol
still works — it just means a read-receipt for message 5 might arrive
before message 4, which the sender resolves by DNA lookup, not sequence
comparison.
