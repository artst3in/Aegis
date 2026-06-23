# Anonymous Groups — Architecture

Reference: docs/SPEC_TRUST_MODEL.md §"Anonymous Groups". This
document is the full architecture for the **Anonymous** group type;
**Regular** groups continue to use SimpleX's native group behaviour
without modification and need no architectural spec of their own.

## What an Anonymous Group is

A chat surface where members do not know each other's real
SimpleX identities. Each member appears to every other member as
a rotating pseudonym (e.g., `Mask-7F3A2`); the underlying SimpleX
addresses, display names, and announced profiles are never
exchanged across the group.

The system is **chat-only** — no location, presence, status,
sensors, or SOS broadcasts flow through it. That rule is shared
with Regular groups and inherited from the Trust Model.

## Threat model

We protect against three concrete attackers. We do not pretend to
protect against the fourth — and we are explicit about that.

| Attack | Defended? |
|--------|-----------|
| External observer with traffic visibility tries to enumerate members | Yes — SimpleX already hides routing metadata; member-identity is also hidden at the application layer |
| Member A tries to learn member B's real identity | Yes — A's device only ever stores B's current-session pseudonym + the creator's address |
| Member A's device is seized; forensics try to enumerate the rest of the group | Yes — A's device has no record of other members' SimpleX addresses, real names, or persistent identifiers |
| An ex-member's device is seized post-kick / post-leave | Yes — forced perfect erasure (see §"Forced perfect erasure"): the moment membership ends, the group key, message history, attachments, and pseudonym mapping are wiped, leaving the device indistinguishable from one that was never in the group |
| The group **creator's** device is seized | **No.** The creator runs the routing layer and necessarily knows every member's address. See §"Centre-of-trust limitation". |

We intentionally choose the simpler "trust-the-creator" design
over a randomised-relay or mixnet design. The latter is buildable
on SimpleX but requires either dedicated infrastructure (which
contradicts the no-server-you-don't-control principle) or
multi-hop coordination among members (which fails the
"acquaintance can use Aegis" usability bar). Anonymous groups in
Aegis are anonymous against four parties out of five — that's
honest and shippable. We document the fifth in plain English.

## Roles

Two roles inside an Anonymous group.

**Creator.** The user who founded the group. Runs the routing
layer for the group's lifetime. Knows every member's real
SimpleX address (needed to forward messages to them) and every
member's current pseudonym (needed to validate inbound traffic).
Cannot be transferred — if the creator deletes the group, the
group dissolves.

**Member.** Everyone else. Joined the group via an invite link
the creator generated. Member's device has only:

  - Their own current-session pseudonym
  - The creator's SimpleX address (the only routing target they
    know about)
  - The group's id and display name
  - Per-message log entries tagged with sender-pseudonym

The member's device specifically does **not** have:

  - Other members' SimpleX addresses
  - Other members' real announced names
  - Any persistent identifier that links a pseudonym across
    sessions
  - A roster of "who is in this group"

## Pseudonym scheme

Each member generates a fresh pseudonym at the start of every
**session** — defined as a cold app start, OR a vault re-lock /
re-unlock cycle, whichever comes first. Within one session the
pseudonym is stable so thread context works ("Mask-7F3A2 said X,
let me reply to them"). Across sessions the pseudonym is
unlinkable: `Mask-7F3A2` yesterday and `Mask-2B91F` today might
be the same person or might not, and the system gives no signal
either way.

**Format.** `Mask-` + 5 uppercase hex characters drawn from a CSPRNG.
2²⁰ name space — collisions within one session are detected by
the creator (see §"Collision handling") and resolved by re-rolling.

**Storage.** The local mapping `(groupId → my pseudonym for this
session)` lives in memory only, with an optional non-persistent
SharedPreferences mirror keyed on a session nonce that's reset
on cold start. On app death, the mapping is lost. The next
session generates a new one.

**Rendering.** Every message bubble in the chat view shows
`Mask-XXXXX` as the author label, never a real name or an
emoji-decorated profile picture. Avatars are always the
LunaGlass mask glyph (the same one used for Untrusted contacts —
the metaphor is consistent).

## Wire protocol

Anonymous-group traffic is layered on top of SimpleX
point-to-point messaging. The group itself is **not** a SimpleX
group (no `simplexGroupId`). It exists only at the Aegis layer.

### Envelope

All anonymous-group messages carry the tag prefix
`[aegis:agroup]` followed by a JSON envelope:

```
[aegis:agroup]{
  "g":   "<groupId>",
  "p":   "<sender-pseudonym>",
  "k":   "<base64 sender-public-ephemeral-key for this message>",
  "ct":  "<base64 ciphertext>",
  "ts":  <epoch ms>
}
```

`ct` is the payload encrypted under the group's symmetric key
(see §"Keys"). `p` is the sender's CURRENT pseudonym — never
their SimpleX-layer identity.

### Member → creator (outbound from a non-creator)

A non-creator member sends `[aegis:agroup]{…}` as a normal
SimpleX message to the creator's address. SimpleX delivers it
through its own paired channel; the SimpleX layer reveals
"creator received a message from member-X" to the creator's
device, but the application body is the envelope above and
nothing in it carries member-X's real identity beyond the
pseudonym they chose for this session.

### Creator → other members (relay)

On receipt, the creator's device:

  1. Decrypts `ct` with the group key (to verify integrity, not
     necessarily to read — see §"Centre-of-trust" for the read
     question).
  2. Looks up the sender's address in the group's roster.
  3. For every OTHER member in the roster, repackages the
     envelope without modification (same pseudonym, same
     ciphertext) and sends it as a SimpleX message to that
     member's address.

The creator never rewrites the `p` field or the `ct`. Members
receive byte-identical envelopes from the creator and see the
sender's pseudonym directly.

### Member ← creator (inbound)

A non-creator member receiving an `[aegis:agroup]` envelope from
their paired creator-channel:

  1. Verifies the `g` matches a known anonymous group.
  2. Decrypts `ct` with the group key.
  3. Renders `(pseudonym, body, ts)` into the chat view.

The member knows the message reached them via the creator but
has no way to tell which other member's address sent it
upstream.

## Keys

Each anonymous group has one **group symmetric key** (AES-GCM
256). It is generated by the creator at group creation and
distributed to each new member at the moment they accept the
invite, over their pairwise SimpleX channel with the creator.

Implications:

  - All members can decrypt every message in the group (this is
    the point — they're in the group).
  - The creator can read every message (they hold the key and
    they relay the traffic). The "trust-the-creator" assumption
    above is concretely this: the creator can read, the creator
    can introduce a doppelganger, the creator can refuse to
    forward.
  - When a member leaves or is kicked, the creator regenerates
    the group key and redistributes the new key to remaining
    members over their pairwise channels. The departed member
    can no longer decrypt FUTURE messages — and (see
    §"Forced perfect erasure" immediately below) the messages
    they already received are wiped from their device along with
    the group itself, so there is nothing to decrypt anyway.

## Forced perfect erasure

When a member's tie to the group ends — voluntary leave, kick by
creator, or group dissolution — Aegis wipes every local trace of
the group on that member's device, atomically with the membership
transition:

  - Every message ever received in the group (chat history)
  - The group symmetric key
  - The group's row in the `groups` table
  - The session pseudonym mapping for that group
  - Any per-group attachments cached to disk

This is stronger than the cryptographic PFS guarantee normally
quoted for group chats. Standard PFS only protects FUTURE
messages from a leaked old key; messages already delivered to a
member's device sit in plaintext storage forever. We force the
delivered messages to disappear too. The leaving member's device
ends up indistinguishable, from a forensic angle, from a device
that was never in the group.

Operationally:

  - Member-initiated leave: the local `clearAnonymousGroup(id)`
    runs BEFORE `[aegis:agroup:leave]` ships to the creator —
    the message goes out from a device that has already lost
    every record of the group, so a seizure between the wipe
    and the network round-trip can't recover anything.
  - Creator-kick: the creator's `[aegis:agroup:kicked]` arrives,
    the receiver wipes locally, then acks with
    `[aegis:agroup:kick-ack]`. The creator's roster update
    proceeds whether or not the ack lands; the wipe on the
    kicked side is recipient-driven and idempotent.
  - Group dissolution: every member receives
    `[aegis:agroup:dissolved]` and wipes locally. The creator
    wipes their own copy last, after sending all dissolution
    notices.

What this rule does NOT defend against — the honest caveats:

  - A member who copies the database off the device before
    pressing "leave" keeps a forensic image. Aegis cannot bind
    storage outside its own sandbox; on a rooted device this is
    trivial. We treat the in-app "leave" button as the boundary
    of our guarantee.
  - A modified Aegis build that suppresses the wipe step. The
    user is now the attacker on their own device. Out of scope.
  - Screenshots / off-device recordings / out-of-band copies
    the member made while a member. The system can't claw back
    a photograph of a screen.
  - In-flight messages a member receives in the few seconds
    between the creator sending the kick and the wipe running.
    The current implementation wipes on receipt of the kick
    notice, which is the standard race window.

Within the bounds of "the Aegis app on a stock device is the
ground truth", forced perfect erasure holds.

## Membership lifecycle

### Creation

  1. User → Settings → New Group → picks "Anonymous"
  2. Aegis generates `groupId` (random UUID) + group key (CSPRNG)
  3. Creator's device persists the group with their own role =
     CREATOR, an empty roster (creator is implicitly in it), and
     the group key in the keystore.

### Invite

  1. Creator opens the group, taps "Invite", picks a contact
     from their existing contact list (any tier).
  2. Aegis sends an `[aegis:agroup:invite]` envelope to that
     contact over the pairwise SimpleX channel, carrying:
       - `groupId`
       - Group display name
       - Group symmetric key
       - Creator's SimpleX address (so the invitee can reply)
  3. The invitee's device receives the invite. UI prompts:
     "Join anonymous group '\<name\>'? You will appear as a
     mask to everyone else." Accept or decline.
  4. On accept, the invitee persists the group + the key
     locally, generates their first session pseudonym, and
     sends an `[aegis:agroup:ack]` back to the creator.
  5. Creator adds the invitee to the roster (with their real
     SimpleX address) and is now ready to relay their
     messages.

**Members cannot invite others.** This is structural — the
member's device doesn't know the other members' addresses, so
even if a UI hypothetically allowed invites, the routing layer
would fail. Closing this off explicitly also closes the
transitive-trust attack vector that the Trust Model rejects for
contacts (see SPEC_TRUST_MODEL.md §"Groups carry no data, ever").

### Member departure

  - A member voluntarily leaving: the device runs the forced
    erasure routine (see §"Forced perfect erasure" above), then
    sends `[aegis:agroup:leave]` to the creator. The creator
    removes them from the roster and rotates the group key.
    Order matters: the wipe runs FIRST so the network message
    leaves a device that no longer has the data it just lost.
  - The creator can kick a member: sends `[aegis:agroup:kicked]`
    to the kicked member; on receipt the kicked member's device
    runs the same wipe routine and acks. The creator removes
    them from the roster and rotates the group key regardless
    of whether the ack lands.
  - Either way, the kicked/leaving member's device wipes the
    group key, roster entry, message history, attachments, and
    pseudonym mapping for that group. UI shows "You are no
    longer in <name>" once and removes the chat row.

### Dissolution

The creator dissolves the group: sends
`[aegis:agroup:dissolved]` to every member; each member's
device runs the forced wipe on receipt. The creator wipes
their own copy last, after every dissolution notice is sent.

If the creator's device disappears without dissolving (lost
phone, factory reset), the group becomes effectively dead: no
relay, no key rotation. Members keep their stale local copies
of the chat history until they manually delete the group, since
there is no peer left to issue a dissolution. UI eventually
shows "Group unreachable for 7 days — archive?" and the archive
path runs the forced erasure locally.

## Collision handling

Two members of the same group could generate the same pseudonym
in the same session (probability ≈ 1/2²⁰ per pair per session,
not negligible at moderate group sizes).

Detection and resolution are the creator's responsibility. On
every inbound message, the creator checks `p` against the
roster's pseudonym map. If a collision is detected (two
addresses claiming the same pseudonym this session), the creator:

  1. Sends `[aegis:agroup:rename]` to one of the colliding
     members, instructing them to regenerate.
  2. Holds the message in a one-message-deep queue until the
     rename round-trips.
  3. Forwards the held message under the renamed pseudonym.

Latency cost on a collision: one round-trip with the colliding
member, typically sub-second. Other members are unaware that
this happened — they see the renamed pseudonym from the start.

## Local-storage layout

```
groups
  id              REGULAR or ANONYMOUS
  type            "ANONYMOUS"
  anonRole        "CREATOR" | "MEMBER"
  anonGroupKey    BLOB (only present locally; never transmitted
                  except via §"Invite")
  anonCreatorKey  publicKey of the creator (the only routing
                  target a non-creator member knows about; for
                  the creator's own row this is their own key)

group_members  (creator's device only)
  groupId
  realPublicKey            real SimpleX address of the member
  currentPseudonym         session-current pseudonym, in memory
                           only; never persisted

session_pseudonyms  (every member's device; in-memory only,
                     mirrored to SharedPreferences keyed by a
                     volatile session nonce that resets on cold
                     start)
  groupId          → pseudonym for this session
```

Non-creator devices have **no** `group_members` row for groups
they belong to. They have only their own pseudonym and the
creator's address. This is the structural guarantee that a
seized member device cannot enumerate the group.

## UI

**Chat list**

Anonymous groups appear in the chat list with:

  - LunaGlass mask glyph as the group avatar (the same one
    Untrusted contacts use; semantic match)
  - Display name as the user typed it at creation
  - Subtitle: "Anonymous · \<N\> masks active this session"
    (creator's device shows the real count; non-creator devices
    show the count of distinct pseudonyms they've seen in
    chat history this session)

**Chat view**

  - Every author label is `Mask-XXXXX`. No avatars, no
    initials, no announced names.
  - Message bubbles look the same as a Regular group except for
    the mask label.
  - Permanent banner above the message list:
    "Anonymous group. Everyone is a mask. The creator can route
    your messages — they cannot see who you are to other
    members."
    (Read by every member every time they open the chat — the
    creator-trust limitation is surfaced, not buried.)

**Member-list view**

There isn't one. The member list is the creator's secret.
Non-creator members see only the masks that have spoken in
chat history this session. The "tap header to see member list"
affordance is replaced with the banner above.

## Comparison with Regular groups

| Aspect | Regular | Anonymous |
|--------|---------|-----------|
| Underlying transport | SimpleX native group | Aegis-layer relay through creator |
| Member identity to other members | SimpleX display name | Rotating pseudonym `Mask-XXXXX` |
| Member identity across sessions | Stable | Unlinkable (new pseudonym per session) |
| Routing knowledge on each device | Every member knows every other member's address | Non-creators know only the creator's address |
| Creator centrality | None — any member can post directly | Critical — creator relays every message |
| Member invites other members | Yes (standard SimpleX) | No (structurally impossible) |
| Compromise of member's device | Reveals full roster | Reveals only own pseudonym + creator address |
| Compromise of creator's device | (same as any member) | Reveals full real-name roster |
| Carries Aegis data (location, SOS, etc.) | No | No |

## Edge cases

**Creator goes offline.** Messages from members queue locally on
their devices. When the creator comes back, the queue flushes.
After 24 h offline the queue is dropped and a "Group offline"
notice appears in the chat. No automatic failover to another
member; the creator is the only relay.

**Two-session-message overlap.** A message sent in session N
arrives at a recipient who has already started session N+1. The
sender's pseudonym in `p` is the SENDER's session-N pseudonym;
the recipient sees `Mask-XXXXX` and has no way to link it to
session-N+1's `Mask-YYYYY`. Working as designed — sessions are
the unit of linkability.

**Recipient is themselves a creator of another group.** No
interaction. Group state is per-group; one user can be creator
of group A and member of group B simultaneously, with separate
keys and separate pseudonyms.

**Member device wiped + reinstalled.** Loses the group key,
pseudonym, history. They appear in their own chat list as if
they were never in the group. They cannot rejoin unless the
creator reinvites them — no "anonymous self-recovery" path.
Acceptable; this group type is specifically not for casual use.

**Creator device wiped + reinstalled.** Loses every group's
state, including the roster. Every group they created is
effectively dead from their side; members will see the creator
stop forwarding. Recovery path: creator manually recreates each
group and reinvites members. There is no automatic recovery —
and the spec leaves it that way, because automatic recovery of
the creator role would require a backup mechanism that
contradicts "creator's device is the only place this state
lives".

## Implementation phases

**Phase 1 — Data model + creation flow**
  - `GroupType` enum, schema migration on `groups` table
  - Group key generation + keystore storage on creator's device
  - "Anonymous" picker in the Group create screen
  - Local-only data, no over-the-wire activity yet

**Phase 2 — Invite + accept**
  - `[aegis:agroup:invite]` and `:ack` message types
  - UI: creator's "Invite" picker, invitee's accept/decline
    sheet
  - Roster maintenance on creator side, key persistence on
    member side

**Phase 3 — Routing + chat**
  - Member → creator send path
  - Creator relay logic
  - Member ← creator receive path
  - Chat view with mask labels + permanent banner

**Phase 4 — Lifecycle**
  - Leave, kick, dissolve flows
  - Key rotation on roster changes
  - Collision detection + rename round-trip

**Phase 5 — Hardening (future)**
  - Per-message ephemeral keys (the reserved `k` field)
  - Optional plausible-deniability padding traffic (creator
    sends random envelopes to all members at a low cadence so
    actual traffic isn't observable as bursts)

Phases 1–4 are committable independently. Phase 5 is a research
follow-up.

## Centre-of-trust limitation (the honest paragraph)

The creator of an Anonymous group holds the group key, holds
the roster, and routes every message. A coerced creator with a
duress PIN gets their vault wiped on coerced access (the
existing duress mechanism), which destroys the group key — but
the roster of member SimpleX addresses lives in
`group_members`, NOT in the vault. The roster is a separate
piece of state that survives a duress wipe.

This is a known gap. Phase 5 includes moving the roster into
the vault so duress wipes both. Until then, **creators of
Anonymous groups should treat the roster as compromised on
device seizure** and limit Anonymous-group creation to threat
models that match. Whistleblower coordinators with cold-storage
backup plans: fine. Casual paranoid group chats: do not use
Anonymous groups for that, use Regular.

The other four threats — external observer, peer-to-peer
deanonymisation, single-member compromise, transitive trust via
invite — are all closed structurally.

---

*dε/dt ≤ 0*
