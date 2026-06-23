# SPEC: Groups Hardening — Roles, History

**Author:** Chad
**Date:** 2026-06-02
**Status:** IMPLEMENTED — Chad 2026-06-02 (Parts 1 + 2 shipped in releases 2026.06.222 and 2026.06.228; Part 3 group-scoped SOS deliberately omitted, see Alternatives)

---

## Summary

Aegis ships working **regular groups** today: create, send, receive,
mute, rename, leave, set TTL, switch identity. Creation +
messaging are solid and ride the unified message pipeline. The
gaps are in two places — both about membership transparency and
manageability.

1. **Membership is opaque.** Members surfaced after group
   creation appear as truncated 10-char pubkeys unless they happen
   to also be a `KnownPeer`. There is no system-message record
   that "X joined", "Y left", "Z was kicked", "the name changed
   from A to B". A user looking at a group history has no audit
   trail of who has been in the room.
2. **Membership is uneditable.** `Repository.addGroupMember()` /
   `removeGroupMember()` exist, the SimpleX transport supports
   both verbs, but there is **no UI** to invoke them post-creation.
   You can't add a new family member to the family group. You
   can't kick someone after a falling-out. You can only leave the
   group and start a new one.

This spec proposes a tightly-scoped **groups hardening pass** that
closes those two gaps without expanding scope into avatars,
mentions, replies, or threading (those are real but separate
specs).

**Explicitly not in scope: group-scoped SOS.** SOS recipients
remain an explicit per-contact opt-in, not a transitive property
of group membership. See "Alternatives considered" §"Group-scoped
SOS" for the reasoning.

Rollback: every change is additive — existing groups keep working
unchanged. Roles default to a permissive mode that matches today's
"everyone can do anything" behaviour on first migration.

---

## Problem

### 1. Opaque membership

`GroupMemberEntity` stores `{groupId, peerPubkey, joinedAt}` and
nothing else. The members screen tries to resolve names by:

  - matching `peerPubkey` against `KnownPeerEntity.publicKey`, or
  - falling through to a truncated hex prefix.

If a stranger joined the family group via the creator's invite,
their pubkey is never in `KnownPeer` (we only pair contacts on
explicit handshake), so the user sees `member: 7a2c1f8d3e` next
to messages signed by that stranger. The screen comment in
`GroupMembersScreen.kt` calls this out as a TODO.

There is also no in-history record of membership changes:

  - X joined yesterday — invisible
  - Y left this morning — invisible
  - Group was renamed an hour ago — invisible
  - Z was kicked five minutes ago — invisible

The chat reads as a continuous stream with no transitions, which
is fine for a 1:1 chat and badly wrong for a multi-party
conversation where context matters ("who added that person?",
"when did this happen?", "did Y see the message before they
left?").

### 2. No post-creation member management

`GroupCreateScreen` lets the creator pick initial members. After
that, `GroupMembersScreen` is read-only. Result: groups are
frozen at creation. The data layer (`Repository.addGroupMember`,
`removeGroupMember`) and transport layer (`SimpleXTransport
.addMemberToGroup`, `removeMember`) both already work — only the
UI is missing.

---

## Proposal

Two additions, each independently shippable. Each closes one of
the two gaps above.

### Part 1: Member identity + system messages

#### Member identity caching

Add two nullable columns to `GroupMemberEntity`:

```
displayName        TEXT NULL
displayNameSeenAt  INTEGER NULL  -- millis epoch
```

Populate `displayName` from inbound traffic:

  - When SimpleX delivers a group message, capture the sender's
    announced profile name and cache it on the member row.
  - When SimpleX delivers a `MEMBER_JOIN` / `MEMBER_PROFILE_UPDATE`
    event, refresh the row.

`displayNameSeenAt` is the last time the cache was refreshed; the
UI shows a small "·" dim suffix when the name is older than 30
days (signals the cache may be stale).

Fallback rendering order:

  1. `KnownPeerEntity.displayName` if the pubkey is also a known
     contact (existing path)
  2. `GroupMemberEntity.displayName` (new)
  3. `"member: <8-char hex>"` (existing fallback)

No new transport: this rides on traffic Aegis already receives.

#### System messages

Add a new `MessageType` variant:

```kotlin
enum class MessageType {
    TEXT, IMAGE, VOICE, STATUS, LOCATION, GROUP_SYSTEM
}
```

`GROUP_SYSTEM` messages are written to the same `messages` table
with `peerKey = "group:<id>"`. Body is a structured JSON payload:

```json
{ "kind": "JOIN",   "actor": "<pubkey>", "subject": "<pubkey>" }
{ "kind": "LEAVE",  "actor": "<pubkey>" }
{ "kind": "KICK",   "actor": "<pubkey>", "subject": "<pubkey>" }
{ "kind": "RENAME", "actor": "<pubkey>", "from": "...", "to": "..." }
{ "kind": "ROLE",   "actor": "<pubkey>", "subject": "<pubkey>",
                    "to": "ADMIN" }
{ "kind": "TTL",    "actor": "<pubkey>", "seconds": 86400 }
```

Renderer: a compact center-aligned chip in `GroupChatScreen` —
small text, `onSurfaceVariant` colour, no bubble. Tap the chip to
expand the actor/subject identity (handy when the actor is a hex
pubkey instead of a name).

These rows are sender-locally generated (the device that performs
the action writes the row directly) AND captured from SimpleX
inbound events when other members act. We dedupe by
`(groupId, kind, actorPubkey, subjectPubkey, timestampMinute)` so
the local-write and the SimpleX-echo don't both render.

System messages are **not** sealed (they describe the group's
membership, which is operationally observable to anyone who could
seal a chat). They are not exported with chat backups.

### Part 2: Member management UI + roles

#### Roles

Add a `role` column to `GroupMemberEntity`:

```
role TEXT NOT NULL DEFAULT 'MEMBER'  -- MEMBER, ADMIN, OWNER
```

Mapping to SimpleX:

  - `OWNER` = SimpleX `owner` (creator)
  - `ADMIN` = SimpleX `admin`
  - `MEMBER` = SimpleX `member`

Permission matrix:

| Action            | OWNER | ADMIN | MEMBER |
|-------------------|-------|-------|--------|
| Send message      | ✓     | ✓     | ✓      |
| Leave group       | ✓¹    | ✓     | ✓      |
| Mute (local)      | ✓     | ✓     | ✓      |
| Rename group      | ✓     | ✓     |        |
| Set group TTL     | ✓     | ✓     |        |
| Add member        | ✓     | ✓     |        |
| Remove member     | ✓     | ✓²    |        |
| Promote to admin  | ✓     |       |        |
| Demote admin      | ✓     |       |        |
| Delete group      | ✓     |       |        |

¹ Owner leaving = group dissolves (matches SimpleX behaviour).
² Admin can remove members but not owners or other admins.

Default migration: existing groups assign `OWNER` to whoever
holds the `joinLink` (the creator); everyone else becomes
`MEMBER`. No promotions happen automatically.

#### Member management screen

`GroupMembersScreen` gets:

  - **Long-press a member** — bottom sheet with role-gated
    actions (remove, promote/demote). Buttons grey out by
    permission.
  - **"+" button in the top bar** — opens the same member-picker
    `GroupCreateScreen` uses, pre-filtered to peers NOT already
    in the group. Calls `addGroupMember` per selection.
  - **Role badges** — small "OWNER" / "ADMIN" chip next to the
    name. Members get no chip (default).

All actions emit the corresponding `GROUP_SYSTEM` message (Part 1)
so the history reflects the change.

---

## Alternatives considered

### Group-scoped SOS (NOT in this spec)

An earlier draft of this spec proposed a `sosReach` column on
`GroupEntity` (NONE / MEMBERS / EMERGENCY_ONLY, default NONE)
plus a fan-out path in `SOSHandler.broadcast()` that emitted
one SimpleX group message per qualifying group. Framing:
"convenience for the SOSked user — the family group should just
work."

Rejected on Artur's call. The reasoning that matters:

**The intrinsic Bob case.** Aegis cannot ever prevent a trusted
abuser from being on the SOS list. If Alice has Bob on her
trust tier, Bob receives the SOS broadcast. Bob being "the
closest person" who gets routed Alice's distress call is
unfixable without making SOS useless for everyone else who
relies on it. The trade-off — Alice signalling for help in a way
Bob can't silence, even if it pisses him off — is probably the
right one. Silence isn't safer.

**The avoidable stranger case.** What we DO control is whether
the recipient pool ever includes someone Alice never met. A
random stalker following her on Reddit and joining her city's
public Aegis group to "help" can be in SOS range. That's not
intrinsic. That's a choice we'd make by shipping group-scoped
SOS at all. We don't make that choice.

The asymmetry is the point: we can't fix Bob (acceptable cost),
but we absolutely will not manufacture a route for stalkers
(unacceptable cost we don't have to pay).

Per-group toggles, defaults, opt-in friction — none of them
change this. The moment the SOS pipeline can reach someone
the user didn't deliberately put on a per-contact list, the
property is broken. Convenience cuts both ways, and the
asymmetric harm here lands hard on exactly the user Aegis was
built for.

SOS recipients stay an explicit per-contact opt-in. Users who
want all family members reached individually promote each one to
Trusted or Emergency. The friction is the safety feature.

### Roles as a separate `GroupRole` table

More normalised. Rejected as overkill: roles are bounded
(MEMBER/ADMIN/OWNER) and per-membership, so a column on
`GroupMemberEntity` is the simpler fit. If we ever add custom
roles ("treasurer", "moderator"), revisit.

### Generate system messages purely from SimpleX events

Cleanest separation — only the server's event stream is canonical.
Rejected because local actions need immediate UI feedback (kick
someone, see the row appear instantly), and SimpleX may take
seconds to echo back. We generate locally AND dedupe against the
echo.

---

## Open questions

1. **System message visibility on prior history.** New
   `GROUP_SYSTEM` rows only exist from the migration date forward.
   Do we backfill from `joinedAt` timestamps on existing
   `GroupMemberEntity` rows? Probably yes for JOIN, not for
   anything else (we don't have the data).
2. **System messages and sealing on Anonymous Groups.** Anonymous
   Groups (separate spec) deliberately rotate pseudonyms and hide
   real identity. `GROUP_SYSTEM` rows would need to reference the
   actor's current pseudonym, not their real pubkey. The
   pseudonym-rotation interaction needs Aurora's eye.
3. **Role-change race.** If the owner demotes admin A while A is
   in the middle of removing member B, what wins? SimpleX
   serialises group operations server-side, so probably whichever
   reaches the server first. Need a test to confirm.
4. **Group avatars** — out of scope for this spec but called out
   in the audit. Suggest a follow-up `SPEC_GROUP_PROFILE.md` that
   covers avatar + group description + pinned-message-as-readme
   together.
5. **Mentions / replies** — also out of scope. Add to backlog,
   not blocking this spec.

---

## Implementation notes (non-binding, for Aurora's review pass)

  - Migration is a single `ALTER TABLE` per added column. Three
    columns total: `displayName`, `displayNameSeenAt`, `role` on
    `GroupMemberEntity`. No new columns on `GroupEntity`. No
    backfill required (defaults are correct).
  - `MessageType.GROUP_SYSTEM` adds a new variant to an existing
    enum; existing Room TypeConverter is `valueOf`-based, so the
    migration is "add the case to the renderer, do nothing in
    the DAO".
  - The member-management UI is the largest piece of new code
    (~300 lines for the picker + role bottom sheet + history
    integration), but reuses `GroupCreateScreen`'s member picker
    composable.
  - Total estimate: 1.5-2 day implementation, no spec-required
    threshold changes. Smaller than the original three-part scope
    once SOS-reach was pulled.

---

## What this spec does NOT do

  - Does not add group-scoped SOS (see Alternatives §
    "Group-scoped SOS")
  - Does not change Anonymous Groups (separate spec)
  - Does not add avatars (separate spec)
  - Does not add mentions, replies, or threading
  - Does not change 1:1 chat behaviour
  - Does not change the Trust Model's per-contact tier semantics
  - Does not introduce server-side state — every piece of state
    above lives in the local Room DB; SimpleX is used only for
    transport
