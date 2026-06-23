# SPEC_COMMAND_AUTH — authenticating the `[aegis:*]` control channel

Status: **IMPLEMENTED 2026-06-09** (build 584, debug — on-device validation
pending). Approved design (Aurora) built in four phases:

- **Phase 1** — `cmdauth/ControlKeypair`: per-device Ed25519 keypair,
  TEE-wrapped priv, `sign`/`verify`.
- **Phase 2** — schema (migration 41→42: `controlPubKey` + send/recv
  counters on `KnownPeerEntity`) + hello bootstrap (`[aegis:hello]<pub>`).
- **Phase 3** — `cmdauth/ControlChannel`: the signed `x.aegis.v1` envelope
  (recipient-bound Ed25519 + per-pair counter); transport `sendControl`
  (send) + `verifySignedControl` (receive).
- **Phase 4** — choke-point migration: `sendText` routes every `[aegis:*]`
  (except hello) through the signed channel; `handleNewChatItems` verifies
  the custom type and reconstructs the legacy body for the existing
  dispatch; plaintext `[aegis:*]` on a direct contact is **hard-denied**.

**Groups: no work needed.** Investigation showed there are NO `[aegis:*]`
control commands sent to groups — `sendToGroup` carries only chat text, and
StoryScreens confirms "no group-broadcast primitive at this layer". Every
control command is direct/per-contact, so the direct signed channel covers
100% of them. Aurora's §4b member-pubkey-exchange provision is therefore
forward-looking: IF group control is ever added, mirror the direct hello
(exchange member control pubkeys at join, group-bind the signature instead
of recipient-bind). Nothing to build today.

---

Status: APPROVED (Aurora review 2026-06-09). **Separation** (move
control data off the chat-text field onto a channel a stock client can't
see or produce). Probe **CONFIRMED on hardware** 2026-06-09 (§3b): an
unknown MsgContent type round-trips through `/_send` and a vanilla client
renders it as "no text". Owner ruling: **signing is NOT dead — sign the
commands inside the hidden channel.** Final design is **both layers**:

- **Separation** (§3a) — the *channel*. A behavioral defense: it relies on
  SimpleX rendering unknown types as blank and the compose box only
  emitting `MCText`. Defeats see-it / type-it (the 99%) but fails OPEN if
  SimpleX's unknown-type handling ever changes or a bug routes a control
  message back through the `text` path.
- **Signing** (§4) — the *lock inside the channel*. A cryptographic
  invariant: a command without a valid MAC is rejected regardless of how
  it arrived or how SimpleX renders it. Adds replay protection and, in
  groups, binds the command to the sender. **Mandatory, not a fallback.**

Both ship. Separation is the door; signing is the lock on the safe.

## 3a. PRIMARY DESIGN — separation via a custom MsgContent type

**Goal:** a vanilla SimpleX client never sees a control message and cannot
produce one. Authentication-by-construction, no MAC.

**Feasibility (from the transport, not from memory):**
- The core honors **non-text** content types on send — Aegis already
  ships `msgContent.type = "image"` / `"file"` through `/_send … json`
  (SimpleXTransport ~2048/2178).
- Inbound **already branches on the type** — `handleNewChatItems` reads
  `mcType = msgContent.type` and forks on `mcType != "text"` before it
  touches `text` (~3870).
- Today the command lives in the **`text`** field (the `[aegis:*]`
  prefix) — which is precisely why a vanilla client renders it.

**Design:** a control message becomes a custom MsgContent:

    {"type":"x.aegis.v1","text":"","cmd":"remote","data":{…}}

- **Vanilla client:** unrecognized type → `MCUnknown` → renders the
  empty `text` fallback. Sees nothing; never sees the command fields.
- **Hand-typer (any client):** the compose box can only emit `MCText`.
  It physically cannot produce `x.aegis.v1`. **Spoofing-by-typing dies by
  construction** — the entire reason this spec exists.
- **Aegis receiver:** branch on `mcType == "x.aegis.v1"` (the fork point
  already exists), read `cmd`/`data`, dispatch, never feed to chat.

**The one open question — must be settled empirically (§3b):** does the
bundled core accept *sending* an **unknown** type (does `MCUnknown`
round-trip through `/_send`, or reject with `chatCmdError`)? Sending
`image`/`file` proves *known* non-text types work; it does NOT prove an
unknown one survives the send path.

**Outcomes:**
1. **Round-trips** → replace the `[aegis:*]` prefix architecture entirely
   with a custom content type. No MAC. Cleanest.
2. **Rejects unknown types** → fall back to an innocuous *known* type
   (zero-byte `file`/`voice` carrying the command in an ignored field —
   vanilla shows a benign attachment, not the command).
3. **Neither clean** → we **bundle** the core (not a stock lib), so add a
   first-class control-message type to our core build — a real separate
   channel — at the cost of carrying a fork patch.

**What separation does NOT do:** it kills Adversary A (vanilla/typing)
completely, but it is *authentication*, not *authorization*. A
modified-Aegis Trusted peer can still construct a valid `x.aegis.v1`, so
the per-command tier/PIN gates (§5) still carry that load. Separation is
the better authentication; authz stays.

## 3b. The probe — RESULT (2026-06-09, on hardware)

A debug-only action (`Diagnostics → Separation probe`) `/_send`s
`{"type":"x.aegis.test","text":""}` to each paired contact.

**Outcome: ACCEPTED (best case).**
- The core returned `newChatItems`, not `chatCmdError` → an **unknown**
  MsgContent type round-trips through `/_send`.
- A **vanilla SimpleX client** received it and rendered it as **"no text"**
  / an empty bubble — the command payload is invisible to it. (Contrast:
  the current `[aegis:hello]` shows there as a plain visible text bubble.)

⇒ Separation (§3a) is **feasible and chosen.** Residual: the vanilla
client still shows an *empty bubble* (not zero-trace) — affects only the
rare non-Aegis-contact case, and shows blank instead of the command. A
refinement to chase, not a blocker.

---

## 4. Signing — the cryptographic lock INSIDE the hidden channel

**Mandatory** (owner ruling), not a fallback. Separation hides/blocks by
construction; signing is the invariant that survives a SimpleX behavior
change, a routing bug, or a scripted custom-type send.

A control message in the hidden channel carries an authenticator:

    {"type":"x.aegis.v1","text":"","ctr":<n>,"cmd":"…","data":{…},
     "mac":"<HMAC-SHA256(K, ctr ‖ cmd ‖ data)>"}

- **K (1:1):** per-pair X25519-ECDH shared secret,
  `crypto_box_beforenm(theirPub, myPriv)` from the seal keypairs already
  exchanged at handshake. Symmetric; never transmitted.
- **ctr:** per-pair monotonic counter, persisted; receiver rejects
  `ctr ≤ last-seen` (replay defense).
- **Verify:** recompute, constant-time compare; fail → **drop** (never
  dispatch). The bootstrap `hello` (which carries the pubkey) is the only
  exempt envelope.

**Open for Aurora — MAC vs. signature, and groups.** A symmetric per-pair
MAC is simplest for 1:1 and uses existing X25519 keys, but it does NOT
bind to a specific sender (both peers hold K) and does NOT generalize to
**groups** (no shared pairwise key across N members). Groups likely need
an **Ed25519 signature** with the sender's verifiable key so any member
can verify any member's command. Decide: MAC everywhere + a separate group
story, or signatures everywhere for uniformity at higher cost.

### (Reference) Original MAC framing — superseded by §4 above

## 0. Problem

Aegis control/evidence commands ride as **plaintext** `[aegis:<tag>]`
prefixes inside ordinary SimpleX text messages. The receiver dispatches
on the prefix (`classifyInbound` → handlers). Consequences:

- Any paired peer — including a **vanilla SimpleX client** or a human
  **typing in the compose box** — can emit any command.
- The project is **open source**, so the full command vocabulary is
  public. Pair with someone, they get `[aegis:hello]`, they read the
  source, they start typing `[aegis:identity]…` / `[aegis:sos]` / threat
  banners at you.
- The *only* thing standing between a spoofed command and an action is
  each handler's own trust/auth check — and those are **inconsistent**:
  the remote family (camera/mic/lock/wipe) is properly gated (PIN +
  Trusted-grant, fail-closed), but `[aegis:identity]` has **no** gate
  (demonstrated: an Untrusted peer set their displayed name to "Police"),
  and the signal/banner tags have none either.

This is wrong by construction: **the wire trusts the prefix.** We need to
authenticate that a control message was produced by a genuine Aegis
protocol layer, not hand-typed or vanilla-sent.

## 1. Threat model — two distinct adversaries

| | Adversary A — *casual / vanilla* | Adversary B — *modified Aegis* |
|---|---|---|
| Who | Stock SimpleX client, or a human typing in any Aegis compose box | A paired peer running patched open-source Aegis |
| Knows | The public command vocabulary | Same + whatever per-pair secrets the protocol establishes |
| Can | Send arbitrary plaintext | Generate validly-formatted (even MAC'd) commands |
| Stopped by | **Authentication** (this spec) | **Authorization** (tier + PIN gates) |

**The core principle: authentication ≠ authorization.**

- *Authentication* proves a message was generated by an Aegis protocol
  layer (not typed, not vanilla-sent). A MAC does this. It defeats
  Adversary A **completely** — which is the user's stated concern.
- *Authorization* proves the sender is *permitted* to do the action
  (trust tier, PIN). It is the **only** thing that can stop Adversary B,
  because a legitimate paired peer running modified code is, by
  definition, an authorized party at the transport layer.

We need **both**. This spec adds the missing authentication layer and
tightens authorization where it's absent.

## 2. Hard constraint — the remote family must work while LOCKED

The remote-control commands (`locate`/`snapshot`/`listen`/`live_*`/
`siren`/`wipe`) exist to drive a **stolen, locked** phone. They
authenticate by **PIN-knowledge**: the sender proves they know the
target's PIN, verified against the stored hash with **no local private
key required**. That already works while locked and is already secure
(double-gated: PIN + per-contact Trusted grant, fail-closed,
indistinguishable denial — no PIN oracle).

⇒ **The new MAC layer MUST NOT require the local private key for the
remote family.** The seal/identity private key is sealed while the phone
is locked; gating remote commands on it would break the entire
stolen-phone use case. Therefore:

- **Remote family:** keep PIN→session auth unchanged. MAC is optional
  belt-and-suspenders here, never a new precondition.
- **Non-remote family** (`identity`, `sos`, `status`, `location`,
  `typing`, `tier`, `sim-swap`, `geofence`, `mugshot`, `wiped`, stories,
  read/sealed receipts): processed while **unlocked**, so the
  unlocked-available shared key can MAC them.

## 3. Design — per-pair MAC via X25519 ECDH

1. **Control keypair.** Reuse the phrase-derived X25519 **seal keypair**
   (already exists; priv loaded into `PinSession` on unlock). *Open Q:
   dedicated control keypair vs. reuse — see §6.*
2. **Bootstrap / pubkey exchange.** Extend the post-pair handshake to
   carry the control pubkey: `[aegis:hello]<base64-x25519-pub>` (today
   it's bare `[aegis:hello]`). Both sides store the peer's control pub in
   `known_peers`. This fires for **every** pair, trusted or not — unlike
   `[aegis:identity]`, which is Trusted-only — so untrusted Aegis pairs
   still get an authenticated channel. A bare/legacy/vanilla hello → no
   pub stored → that peer has no authenticated channel (see §4).
3. **Shared secret.** `K = crypto_box_beforenm(theirPub, myPriv)`
   (libsodium X25519 → 32-byte key; already available via lazysodium).
   Symmetric — both sides derive identical `K`. **Never transmitted.**
4. **MAC envelope.** A non-remote control message becomes:

       [aegis:<tag>]<body>·<ctr>·<mac>

   where `mac = HMAC-SHA256(K, peerKey ‖ ctr ‖ "[aegis:<tag>]" ‖ body)`
   truncated to 16 bytes (128-bit), and `ctr` is a per-pair monotonic
   counter. The `[aegis:<tag>]` prefix is preserved so `classifyInbound`
   still routes; the `·<ctr>·<mac>` trailer is stripped before dispatch.
   (Delimiter `·` = a byte that never appears in our bodies; exact wire
   framing is a review detail.)
5. **Verification.** On inbound `[aegis:<tag>]`, recompute the MAC with
   `K`, **constant-time** compare, and require `ctr` strictly greater
   than the per-pair last-seen counter (replay defense). Pass → strip
   trailer, dispatch. **Fail → drop** (never dispatch; render nothing).
6. **Replay.** Persist per-pair last-seen `ctr`; reject `ctr ≤ last`.

## 4. Policy for unauthenticated control (no shared key)

For a peer we hold **no** control pub for (legacy Aegis, vanilla SimpleX,
pre-upgrade contact):

- **Default DENY** every `[aegis:*]` except the bootstrap `[aegis:hello]`.
  An un-authenticatable control message is dropped, not dispatched.
- This is safe because every `[aegis:*]` is Aegis-only by definition — a
  vanilla peer has no legitimate reason to emit one, and a legacy Aegis
  re-exchanges its control pub on the next hello after upgrade.
- Migration: on app upgrade, re-send `[aegis:hello]<pub>` to all known
  peers so existing pairs bootstrap the channel.

## 4b. Aurora review — Ed25519 signatures everywhere (2026-06-09)

**Decision: Ed25519 signatures, not HMAC. One scheme for 1:1 and groups.**

MAC is symmetric — both sides hold the same key K. In 1:1 that works.
In groups it breaks: no pairwise shared secret across N members. You'd
need N*(N-1)/2 keys. Unworkable.

Ed25519 is asymmetric — sender signs with their private key, any
receiver verifies with the sender's public key. Works identically for
1:1 (one verifier) and groups (N verifiers). Each member's public key
is exchanged at join time. Any member verifies any command from any
other member.

Cost: Ed25519 signs in ~50 microseconds on a phone. HMAC is faster
but both are invisible at human timescales. Uniformity wins over
micro-optimization.

**Answers to remaining open questions:**

1. **Dedicated control keypair** — don't reuse seal. Seal rotation
   shouldn't break all control channels. One more keypair, clean
   separation. Generate Ed25519 at first run, store in TEE.

2. **Signing, not encryption.** The command bodies aren't secret.
   SimpleX already encrypts the transport. Signing proves origin.
   Encryption of commands adds code for zero gain.

3. **Drop while locked.** Non-remote commands arriving while locked
   (typing, status) are transient. Drop, don't defer.

4. **64-bit counter.** Big-endian, 8 bytes. Never rolls over. Persist
   per-peer. Include in the signed payload for replay defense.

5. **No migration window.** Hard-deny from day one. Re-pair after
   upgrade. No users to migrate.

## 5. Authorization hardening (independent of the MAC — ship NOW)

The MAC defeats Adversary A. Adversary B still needs per-command
authorization, which is **missing** on the non-remote tags. Fix
immediately, ahead of the MAC rollout:

- **`[aegis:identity]` (the proven hole):**
  - *Store:* gate on sender being **Trusted/Emergency** — drop an
    untrusted peer's identity claim. (Closes the `AegisApp` "verification
    is a roadmap item" gap.)
  - *Display:* `ContactDetailScreen` shows **your nickname first**
    (`displayName ?: announcedName`), announced name only as a labeled
    secondary — consistent with chat list / chat header / group rows,
    which already prefer your nickname.
- **Audit** `sos`, `wiped`, `sim-swap`, `geofence`, `mugshot`,
  `location`: decide per-tag whether it should require Trusted to be
  *acted on* (vs. merely received). Most are display/notification —
  spoofable nuisance, not control — but the policy should be explicit,
  not incidental.

## 6. Open questions (for Aurora)

1. **Reuse seal keypair vs. dedicated control keypair?** Reuse is less
   code and the priv is already managed; but it couples the control MAC
   to the identity key's lifecycle. A dedicated device keypair decouples
   them at the cost of one more exchanged pubkey.
2. **MAC-only vs. full `crypto_box` encryption?** Encrypting the command
   body additionally hides it from a passive observer (defense in depth)
   for the same key cost. MAC-only is simpler and the bodies aren't
   secret. Lean MAC-only; revisit.
3. **Non-remote commands that arrive while LOCKED** (priv sealed, can't
   verify). They're non-critical (typing/status/etc.) — **drop or defer**
   until unlock? Proposed: drop (they're transient).
4. **Counter framing + rollover** — wire format of `ctr`, persistence,
   64-bit so rollover is a non-issue.
5. **Migration window** — how long to accept unauthenticated control from
   not-yet-upgraded peers before hard-denying. Proposed: deny from day
   one for new installs; a short grace only for the identity-class tags
   would re-open the hole, so **no grace for sensitive tags.**

## 7. Not in scope

- Defeating a **modified-Aegis trusted peer** — that is authorization's
  job (§5), not authentication. No cryptographic measure can stop a
  legitimately-paired peer running patched code from emitting a
  validly-formatted command; only tier/PIN gates can stop the *action*.
- Any change to the **remote family's** PIN→session model (§2) — it is
  already correct and must keep working while locked.
