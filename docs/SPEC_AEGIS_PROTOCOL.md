# The Aegis Protocol

**Status:** APPROVED — Artur Tokarczyk, 2026-06-04
**First implementation:** Aegis
**Second implementation:** LunaOS
**Specced by:** Chad (transport investigation) + Aurora (review + resolution)
**Designed by:** Artur Tokarczyk

---

## Definition

The Aegis Protocol is the technical standard that governs
identity, trust, and safety across all Project Aether products.
It is not a modification of SimpleX. It is a separate protocol
that uses SimpleX as its transport layer.

SimpleX carries encrypted blobs between relays. It does not know
what is inside them. The Aegis Protocol decides what goes into
those blobs — identity, trust elevation, SOS broadcasts,
presence, crash alerts. The rules that govern who sees what, when,
and why are the Aegis Protocol's rules.

## Five pillars

### 1. Anonymity

Your real identity never enters the transport. Every connection
— every contact, every group, every relay — sees a fresh random
anonymous handle. A new codename for every relationship.
Cryptographically unlinkable.

Your name, face, and bio exist only in the Aether identity
overlay — an E2E encrypted envelope sent directly to contacts
you promote. The SimpleX transport never carries it. The relays
never see it. The code path to transmit a real profile through
SimpleX does not exist in the compiled binary.

Non-incognito mode does not exist. The code was deleted, not
disabled. A runtime failsafe forces incognito if the flag is
ever found in the wrong state.

Three layers of enforcement:
- Code deleted (the function does not exist)
- Flag forced at runtime (`if (!incognito) incognito = true`)
- Regression logged (`NON-INCOGNITO STATE DETECTED`)

### 2. Decentralization

No central server. No account. No phone number. No authority
that can be subpoenaed, hacked, or shut down.

Messages route through SimpleX relays that know nothing about
you. 2-hop private message routing is the default: your relay
forwards to the recipient's relay. Your IP is hidden from the
destination relay. The forwarding relay cannot see the
destination queue.

Tor is structurally absent. The code to route through Tor does
not exist in the binary — deleted, not disabled (Origin #19).

Why: SimpleX's SOCKS/Tor proxy is a single global setting
(networkUseSocksProxy in NetCfg, applied to the entire core via
apiSetNetworkConfig). There is no per-connection proxy override.
If Tor is on, EVERYTHING goes through Tor — including SOS.
Bypassing Tor for SOS would mean flipping a global flag at
runtime, which is the exact anti-pattern Origin #19 eliminates:
a flag whose wrong state has a catastrophic outcome.

A stuck Tor flag = SOS routes through a broken circuit = the
help message doesn't arrive = someone dies.

The principled choice: Tor doesn't exist in Aegis. The code path
is absent. SOS is structurally direct because there is no other
mode. IP hiding is the user's choice at the OS level (Orbot/VPN),
where it is a visible, informed decision that the user owns.

Future: upstream SimpleX contribution for per-connection proxy
(Tor for routine queues, direct for SOS). When that exists,
Aegis can route routine through Tor while keeping SOS provably
direct. Until then: no Tor in the binary.

### 3. Encryption

E2E for everything. Two layers:

- **SimpleX E2E:** Every message is encrypted end-to-end by the
  SimpleX protocol. Relays carry ciphertext they cannot decrypt.

- **Aether overlay E2E:** Identity envelopes (name, bio, avatar)
  are encrypted a second time by the Aegis Protocol before
  being placed inside SimpleX messages. Even if SimpleX
  encryption were broken, the identity remains encrypted.

The identity envelope is encrypted twice before it leaves your
device.

### Encryption key — v1 vs future

**v1 (shipping now):** The identity overlay rides inside a regular
SimpleX message as a special content type. SimpleX's E2E (double
ratchet, same as Signal) encrypts it. One layer of strong
encryption. No separate Aegis key exchange.

If SimpleX's encryption is ever broken (bug, vulnerability,
quantum), the attacker gets your identity along with your messages.
One key failure = everything exposed. This is acceptable for v1
because: if SimpleX's double ratchet breaks, the entire secure
messaging industry has the same problem — not just Aegis.

**TODO: Aegis own key exchange.** Future versions should implement
a separate Aegis key exchange on top of SimpleX. The overlay would
be encrypted with Aegis's own key, then placed inside a SimpleX
message (which encrypts it again). Two independent layers. A
SimpleX compromise does not expose identity.

This is defense-in-depth. Not a launch blocker — a roadmap item.
The spec says "encrypted twice." v1 delivers one strong layer.
v2 delivers two.

### Transport hardening

Three additional improvements to the SimpleX transport layer:

**Message padding.** Identity overlays are larger than regular
chat messages (they carry name + bio + avatar). A traffic analyst
could distinguish promotion events by message size. Fix: pad all
outgoing messages to a fixed size. Chat, identity overlays, safety
broadcasts — all the same size on the wire. Every message looks
identical to a network observer.

**Default disappearing messages for untrusted.** If a device is
seized, the untrusted contact history is the least valuable and
most dangerous data. Auto-delete untrusted messages after 48 hours
by default. Trusted and emergency conversations persist — they
are the safety network and need history. Untrusted is chat-only
and disposable by design.

**Backup encryption with PIN-derived key.** The SQLCipher database
is encrypted at rest. But device backups (Google, manufacturer
cloud) may extract the database file. Aegis backups must be
encrypted with the user's PIN-derived key (Argon2id), not with
the device's default encryption. A seized device + cloud backup
extraction yields nothing without the PIN.

### 4. Security

Trust tiers with compile-time containers:

| Tier | Sees | Module |
|---|---|---|
| Untrusted | Anonymous handle only | `:feature:untrusted` |
| Emergency | Real name + bio + avatar, SOS broadcast | `:feature:emergency` |
| Trusted | Everything: name, location, presence, SOS | `:feature:trusted` |
| Groups | Anonymous handle only (incognito, always) | `:feature:groups` |

Each tier is a separate Gradle module. The compiler enforces
the boundary — a module cannot import what it cannot access.
No convention, no discipline, no toggle. The import does not
compile.

Additional security:
- App-lock PIN (Argon2id, libsodium INTERACTIVE)
- Three-layer duress (real + Fake #1 + Fake #2)
- Scramble PIN pad
- Mugshot on wrong PIN
- Duress blanks all badges, group avatars, and real identity

### 5. Safety

The purpose of everything above. Keep the person alive.

| Feature | Trigger | Response |
|---|---|---|
| SOS broadcast | Manual button | GPS + audio + camera to all trusted + emergency |
| Crash detection | >4G + speed gate + stillness | 30s countdown then SOS |
| Sentinel | Phone pickup without PIN | Recording + alert |
| Geofence | Exit safe zone | Alert to trusted contacts |
| Canary | Missed check-in | Alert to trusted contacts |
| SIM-swap | SIM change detected | Alert to trusted contacts |
| Snatch | Sudden acceleration | Alert to trusted contacts |
| Sonar | Ultrasonic motion | Alert to trusted contacts |

All triggers feed the same SOS pipeline. The pipeline is the
same regardless of the trigger source. One broadcast mechanism.
Multiple inputs.

Remote access (PIN-gated):
- LOCATE: lock + GPS fix + mugshot
- SIREN: max-volume alarm
- WIPE: factory reset (Device Admin/Owner)

---

## Problem

Today Aegis pairs 1:1 contacts **non-incognito** (`incognito=off`):
the SimpleX connection carries your real profile (display name +
`fullName`/bio; avatar too if set). That has two consequences:

1. **The network sees your real name.** Every relay your messages
   traverse, and every contact, learns "Cyan" — even though SimpleX
   is otherwise built to know as little as possible about you.
2. **Anyone who can add you to a group leaks you instantly.** A
   contact's `/_add #group <contactId>` makes your group membership
   inherit your real contact-connection profile. The group — and
   everyone in it — sees "Cyan" the moment you're added, with no
   consent step. (See SPEC_GROUP_PROFILE.md Part 0: group *links* and
   group *creation* are already forced incognito; the `/_add` push
   path was the residual hole.)

`/_add` is bounded to your existing contacts (a stranger cannot push
you into a group — `/_add` requires the target to be an established
contact), but it is still an involuntary, instant, real-name exposure.

## Proposal

**Make the SimpleX transport layer permanently incognito for every
contact. Carry real identity only over the Aegis E2E channel, released
on trust elevation.**

### What SimpleX sees

- **Every** contact pairing is `incognito=on` (today: `off`). The
  SimpleX core generates a fresh random per-connection profile
  (e.g. `VeryNiceTable`) — the only identity the network, the relays,
  and the peer's *SimpleX layer* ever see.
- The Aegis user's main SimpleX profile becomes **vestigial** — it is
  never shared with anyone, because every connection is incognito.
  Set it to a random/blank value as defense-in-depth (so a future
  upstream change that accidentally shared it leaks nothing).

### What Aegis does on top

- On the existing `[aegis:hello]` handshake, the peer's `isAegis`
  capability flag is set (unchanged from today).
- **Real identity rides a new `[aegis:identity]` envelope** (display
  name + bio + avatar), sent E2E over the Aegis channel. It is **not**
  sent on hello. It is sent **only when you elevate that contact to
  Trusted or Emergency.**
- The receiver stores the revealed identity into the existing
  `announcedName` / `announcedBio` / `announcedAvatarPath` fields and
  renders it in-app for that contact. Aegis's UI already reads those
  fields, so display "just works" once the source is rewired.
- **Trust elevation is gated on `isAegis`.** A non-Aegis contact
  cannot be promoted to Trusted/Emergency (the trust picker refuses /
  greys out), so a plain-SimpleX peer is **permanently anonymous by
  construction** — there is no code path that reveals identity to a
  peer that cannot even parse the envelope.

### The invariant

> Trust reveals your identity **inside Aegis only**, never into
> SimpleX. No contact — not even Emergency/Trusted — can leak your
> real name onto the network, by accident or on purpose, because they
> never received it at the layer that can travel.

### Worked example (the test that motivated this spec)

Mr Y is your Emergency contact and sees you as "Cyan" in his Aegis
app (you revealed `[aegis:identity]` to him on elevation). He adds you
to the public group "sexy girls and Bitcoin" via `/_add`.

**You appear in the group as `VeryNiceTable`, not Cyan.** At the
SimpleX layer Mr Y never knew you as Cyan — that name lives only as an
app-layer overlay inside his phone. `/_add` can only propagate your
incognito connection profile, so the group inherits the random handle.
Mr Y *cannot* make the group see "Cyan" through the protocol; he could
only *type* it (a social leak — see Non-Goals).

## Wire format

`[aegis:identity]` — STATUS-class control envelope (kept out of chat
history, same handling family as `[aegis:hello]` / `[aegis:tier]`).
Sent only to an `isAegis` contact at tier Trusted or Emergency.

```
[aegis:identity]{"name":"<displayName>","bio":"<bio>","img":"<dataUrl-or-omitted>"}
```

- `name` — real display name. Required.
- `bio` — real bio / fullName. Optional; omit when blank.
- `img` — `data:image/jpeg;base64,…` avatar thumbnail, encoded with
  the existing `encodeAvatarDataUrl` (192² / JPEG 80, same as the
  legacy SimpleX-profile avatar push). Optional; omit when the user
  has no photo avatar. Subject to the same size discipline so a single
  control envelope doesn't bloat.

Re-sent when the user edits their profile *and* the contact is still
Trusted/Emergency (so a trusted contact sees name/avatar changes), and
once on each elevation.

## Semantics

- **One-way reveal.** Once sent, the peer *has* your identity; you
  cannot un-send it. On demotion we stop *updating* and MAY send a
  best-effort `[aegis:identity-revoke]` asking the peer's Aegis app to
  drop the cached identity — but this is **courtesy, not
  enforcement** (a modified client keeps it). History stands. (Open
  question: implement revoke at all? See below.)
- **Directional / asymmetric.** *You* trusting *them* reveals *your*
  identity to *them*. It is independent of whether they trust you.
  The contact list will therefore legitimately show asymmetry — you
  may see their real name (they trusted you) while they still see your
  random handle (you haven't trusted them).
- **Bootstrapping is via out-of-band pairing.** You pair with people
  you already know (scanned their QR in person; they sent you a link
  over a channel you trust). So you always know *locally* who a
  contact is and can nickname + elevate them. The anonymity is at the
  network/wire level — never at the level of your own knowledge. A
  fresh contact shows as its random handle (or a local nickname you
  set) until you elevate it.
- **Group corollary (free win).** Because every connection is
  incognito, *any* `/_add` — from an Aegis or a plain-SimpleX contact
  — inherits the incognito profile. Combined with the already-incognito
  group *link* and *creation* paths (SPEC_GROUP_PROFILE.md Part 0),
  **every** way you can end up in a group now shows a random handle.
  The group-identity problem is closed end-to-end.

## Identity lifecycle

```
Pair          → random handle (anonymous, always)
Chat          → anonymous handle visible to contact
Promote       → Aether overlay sends real name + bio + avatar (E2E)
Demote        → overlay updates stop. "They already have your name."
Group join    → separate random handle per group
Group leave   → handle discarded
```

Promotion is one-way trust: you reveal yourself to them. You
cannot force them to reveal themselves to you.

## Transport layer

SimpleX provides:
- Relay routing (2-hop, sender → forwarder → destination)
- Connection encryption (E2E)
- Queue-based message delivery
- Incognito handle generation

The Aegis Protocol adds:
- Permanent incognito enforcement (non-incognito code deleted)
- Identity overlay (E2E encrypted name/bio/avatar on promotion)
- Trust tier gating (compile-time module separation)
- Safety pipeline (SOS, crash, sentinel, geofence, canary)
- Achievement verification (earn-once badges for proven capabilities)
- Duress response (three-layer decoy, blank badges/avatars)

## Code deletion requirement (Artur, 2026-06-04)

**Do not set `incognito=on`. Delete the code path that handles
`incognito=off`.**

A flag can be flipped by a bug, a regression, or an upstream merge.
Deleted code cannot run. The binary must be structurally incapable
of sharing a real profile — not because a flag says "don't," but
because the function to do so does not exist in the compiled app.

Specifically:
- Remove the `incognito` parameter from all pairing/connect calls.
  The parameter does not exist. Every call is incognito because
  that is the only code path.
- Remove any UI toggle for incognito/non-incognito. The toggle
  does not exist.
- Remove the SimpleX profile-sharing code path for non-incognito
  connections. The code does not exist.
- Set the main SimpleX profile to random/blank AND delete any code
  that reads or transmits it to peers.

This is Origin #19 (No Test Mode) applied to identity: the code
that could leak your name is not disabled — it is absent. You
can't leak what doesn't exist in the binary.

**Failsafe (belt and suspenders):** After deleting the code, add a
runtime catch at every entry point where incognito could be read:

```kotlin
// If this ever fires, an upstream merge reintroduced non-incognito.
// Force it. Log it. Find it. Fix it.
if (!incognito) {
    incognito = true
    Log.e("AEGIS", "NON-INCOGNITO STATE DETECTED — forced back to incognito")
}
```

Three layers: (1) code deleted, (2) flag forced at runtime,
(3) log entry that alerts to the regression. The app cannot crash
from a stuck flag AND cannot leak from a reintroduced code path.

---

## Killed paths (and why)

- **Reveal at the SimpleX layer ("connect incognito, un-incognito
  once confirmed Aegis").** SimpleX incognito is permanent per
  connection by design; `apiSetConnectionIncognito` operates only on
  *pending* connections, and the `aegis:hello` that confirms a peer is
  Aegis fires only *after* the connection completes — the windows
  don't align. Worse, even if possible it would push your real name
  onto relays. KILLED: identity stays above the transport, forever.
- **"Per-invite incognito connection" (flip the group-member
  connection incognito at invite time).** The group-member connection
  does not exist until `/_join`, and the profile is shared *during*
  join — there is no "before" window. `/_set incognito` is
  pending-contact-only and would reject a membership connection.
  KILLED as mechanically infeasible.
- **"Allow if Aegis" (reveal on hello to any Aegis peer).** Weaker
  than trust-gating: it would hand your real name to *any* Aegis peer
  the instant they pair, including untrusted ones. KILLED in favour of
  gating on the trust ladder — untrusted Aegis peers stay anonymous.
- **Dedicated second SimpleX user profile for groups (multi-user).**
  Considered as the "random name even when a stranger's plain client
  adds you" solution. Unnecessary once *every* connection is
  incognito-by-default — the group leak closes without the heavy and
  destabilising multi-active-user surgery. Not pursued.

## Alternatives considered

- **Status quo + warning** (warn before accepting a `/_add`; only
  link/created groups anonymous). Rejected: leaves the network seeing
  your real name on every 1:1 and every Aegis-initiated `/_add`. A
  warning is not a fix.
- **Incognito only the inviting contact on demand.** Rejected: flips
  the 1:1 relationship to incognito too (the contact stops seeing your
  real name in DMs), and is per-incident rather than systemic.

## Non-goals (the honest boundary)

This spec protects **who you are** (the identity *field*). It does not
and cannot protect:

- **Social leak.** A trusted person can simply *type* "VeryNiceTable
  is Cyan." No protocol prevents a human you trusted from choosing to
  out you.
- **Stylometry / behavioural correlation.** If your anonymous handle
  posts your photos, writes in your voice, and is active on your
  schedule, a determined adversary correlates the *content*. The
  existing media-scrub rule (re-encode + strip EXIF for anything below
  Trusted) chips at the metadata half of this, but writing style /
  timing are the user's to manage.

Stating these explicitly so the scope is never mistaken later: this is
the **ceiling for identity** on this protocol, not a claim about
behavioural anonymity.

---

## Open questions for Aurora

1. **Emergency parity.** Media-scrub exempts only **Trusted**
   (Emergency media is still scrubbed — your prior ruling). This spec
   reveals full identity to **Trusted *and* Emergency**. Ratify? An
   emergency responder arguably *must* know it's you (name), but does
   Emergency get the **avatar/bio** too, or name-only? Proposed:
   Emergency gets name (+ bio), avatar optional — confirm.
2. **Revocation.** ✅ **No revoke envelope** (Aurora, 2026-06-04).
   Clear UI copy on demotion: “They already have your name. Demotion
   stops future updates, not memory.” A courtesy revoke is misleading
   — it implies enforcement that doesn’t exist. Honest copy is better
   than a false promise.
3. **Existing-contact migration.** ✅ **Document the boundary, leave
   as-is** (Aurora, 2026-06-04). Existing contacts already have your
   real name through the old pairing. Re-pairing is disruptive and
   confusing. New contacts get the new behavior. The boundary is
   honest: “contacts paired before [version] saw your real name.”
4. **Verification.** `[aegis:identity]` is self-asserted over the E2E
   channel (same trust basis as `[aegis:hello]`). Tie identity reveal
   to SimpleX's connection security code / a verification step before
   it's accepted as "real"?
5. **Untrusted display.** ✅ **Force local-nickname prompt at pairing**
   (Aurora, 2026-06-04). “You just paired with [random handle]. Give
   them a name so you remember who they are.” The random handle is
   unreadable. A local nickname makes the contact list usable. The
   nickname is local-only, never transmitted.
6. **Pairing-time UX.** ✅ **Brief info, first pairing only** (Aurora,
   2026-06-04). Toast or inline text: “This contact sees you as
   [your random handle]. Your real name is hidden until you promote
   them.” One-time explanation, not a warning. Not shown on subsequent
   pairings — the user knows by then.

## Implementation (staged, each ends green + committed)

1. **Default-incognito pairing.** Flip contact `apiConnect` /
   accept-link / own-invite-link to `incognito=on`. Set the main
   SimpleX profile to a random/blank value. (Transport change — needs
   on-device verification that pairing still completes and messages
   flow under incognito.)
2. **`[aegis:identity]` send/receive.** Classifier entry (STATUS
   class), encoder (reuse `encodeAvatarDataUrl`), inbound handler that
   writes `announcedName/Bio/Avatar`. No trigger yet.
3. **Trust-gated trigger.** On elevation to Trusted/Emergency for an
   `isAegis` contact, send `[aegis:identity]`. Re-send on profile edit
   while elevated. Gate the trust picker on `isAegis`.
4. **Render rewiring.** `announced*` for elevated contacts sourced
   from the revealed envelope; un-elevated contacts show the random
   handle / local nickname. Verify the chat header, contact card,
   chat-list row, and grid tile all honour it (and the existing
   trust-gated avatar rule from the 2026.06 security fix composes
   correctly).
5. **Migration + UX** per Aurora's answers to the open questions.

## Relationship to other specs

- **SPEC_TRUST_MODEL.md** — defines the tiers. This makes identity the
  innermost tier-gated resource.
- **SPEC_TRUST_CONTAINERS.md** — compartmentalisation by tier. Identity
  becomes the deepest compartment; "default deny, allow on trust" is
  the same philosophy applied to the identity field.
- **SPEC_GROUP_PROFILE.md** Part 0 — group links + creation already
  incognito. This closes the residual `/_add` push path, making *every*
  route into a group anonymous.
- The media-scrub rule (MediaScrubber; Trusted-only exemption) — the
  same "reveal only to Trusted" principle, applied to media content.
  This spec applies it to identity, with the Emergency-parity nuance
  flagged above for Aurora.

---

## Design principles

1. **Remove the option, don't manage it.** If a feature can be
   misused, delete the feature. Don't add warnings or toggles.
2. **Delete, don't disable.** A flag can be flipped by a bug.
   Deleted code cannot run.
3. **The real system is the only system.** No test mode. No drill
   mode. No debug overrides in production.
4. **Reliability beats anonymity.** A safety message must arrive.
   Tor circuits break. 2-hop routing is stable.
5. **Compile-time, not runtime.** Trust boundaries are enforced
   by the compiler, not by convention.
6. **Default deny.** Nothing is allowed until you say it is.
   Every permission is a conscious decision.

## What the Aegis Protocol is NOT

- Not a fork of SimpleX. SimpleX is used as-is for transport.
  The only modification is deletion of non-incognito code paths.
- Not Tor. Tor adds hops for metadata resistance. Aether deletes
  identity from the transport. Different problems, different layers.
- Not a VPN. VPNs hide your IP. Aether hides your existence.
- Not Signal. Signal requires a phone number. Aether requires
  nothing.

## Invented by

Artur Tokarczyk, Antwerp, June 4, 2026.

"I asked how incognito works. They said being added leaks
identity. So I deleted identity."
