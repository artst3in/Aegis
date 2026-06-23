# SPEC: Group Profile — Avatar, Description, Pinned README

**Author:** Chad
**Date:** 2026-06-03
**Status:** APPROVED — all questions resolved, ready for implementation (Part 0
per-member group identity added + signed off 2026-06-03)

> Follow-up explicitly called out in
> `docs/SPEC_GROUPS_HARDENING.md` open question §4: *"a follow-up
> SPEC_GROUP_PROFILE.md that covers avatar + group description +
> pinned-message-as-readme together."* This is that spec. It does
> **not** re-open anything Groups Hardening shipped (roles, system
> messages, member management) — it sits on top of them.

---

## Summary

A group today has exactly one piece of profile: its **name**
(`GroupEntity.name`, editable via the rename flow which fires
`/_group_profile`). A 1:1 contact, by contrast, carries a name, a
bio, and an avatar. The asymmetry is visible everywhere a group is
listed: the chat list draws a generic hex/initial for every group,
the group header is a bare string, and a member who joins a public
group ("Aegis Amsterdam") has no in-app way to learn what the group
is *for* without scrolling its history.

This spec adds four pieces of group profile, each independently
shippable, each riding infrastructure Aegis already has:

0. **Per-member group identity** — separate name and avatar per
   group. Private 1:1 profile never enters the group module.
1. **Group avatar** — the group's shared avatar (set by admin).
2. **Group description** — a short "what this group is" string.
3. **Pinned README** — promote the existing pinned-message
   mechanism into a group "topic / rules" banner at the top of the
   chat.

Parts 1–3 (the *group's* shared profile) are edited by **OWNER /
ADMIN only**, consistent with the Groups Hardening permission matrix
(rename, TTL, member add are already OWNER/ADMIN), and are
**operationally shared with every member** — like membership and the
group name they are not secret, so (per the Hardening precedent for
system messages) they are **not PIN-sealed**. Part 0 (the *member's
own* per-group identity, Aurora's addition) is each member's own
choice, never derived from their private 1:1 profile. The security
surface that *does* matter is the avatar image itself (EXIF/GPS
leakage), covered in §Security.

Rollback: every change is additive. Existing groups render exactly
as today until someone sets an avatar/description/README.

---

## Problem

### 1. Groups have no avatar

`GroupEntity` (`feature/groups/.../GroupEntities.kt`) stores
`id, name, simplexGroupId, createdAt, joinLink, realIdentity,
enabled, autoDisableMinutes`. There is no image field. The chat
list and group header fall back to a generated glyph. SimpleX's own
`groupProfile` JSON **already carries an `image` field** (we parse
`groupProfile` inbound at `SimpleXTransport.kt:1044` and `:1627`),
and the profile-set path already knows how to encode an avatar as a
`data:image/jpeg;base64,…` URL (`SimpleXTransport.kt:1873-1880`,
used today for the *user's* 1:1 profile via
`setProfile(name, bio, avatarPath)`). We receive group images and
throw them away; we never send one.

### 2. Groups have no description

Same root cause: no field, no UI. A user invited to a group sees
only its name. For the family group this is fine (you know what it
is). For any group with more than a handful of people, or any group
joined via a public link, "what is this / what are the rules /
who runs it" is unanswerable in-app.

### 3. There is no group "README" / topic

`MessageEntity.pinned` exists (`Entities.kt:51`) and the 1:1 chat
already supports multiple pinned messages with a banner that
highlights the most recent. Groups inherit the storage but there is
no notion of a **designated** pinned message that acts as the
group's standing topic — the thing a new member should read first
("City group. No real names. Meetups posted Fridays."). A normal
pinned message scrolls out of the banner as soon as a newer one is
pinned; a README should be sticky and authored deliberately.

---

## Proposal

Four parts (0–3). Ship in order — **Part 0 first** (Aurora's
REQUIRED-FIRST gate); each is useful alone.


### Part 0: Group identity — incognito only

#### Decision (Aurora, 2026-06-03)

Groups are incognito. Always. No "use real identity" option.

The group module has zero access to the user's private 1:1
profile. This is a compile-time boundary (per
SPEC_TRUST_CONTAINERS and SPEC_GROUP_MODULE_ISOLATION) —
the import does not exist. The option to reveal cannot be
built because the code path to read the private profile
cannot be written in the group module.

If someone in a group needs to know who you are, pair with
them 1:1. They see your real profile there. The group stays
incognito.

#### Transport reality (Chad, 2026-06-03)

SimpleX groups support exactly two member identity modes:
- incognito = ON: random handle, auto-generated
- incognito = OFF: your main SimpleX profile

There is no per-group custom name at the protocol level.
The `/_group_profile` command is for the group's shared
profile (Part 1), not a member's. No live member-profile
change — only leave + rejoin.

#### Resolution

Option A from Chad's analysis, hardened: incognito-by-default
is no longer a default — it is the ONLY mode. The toggle to
switch to real identity does not exist. The code path to
access the private profile from the group module does not
exist.

Custom per-group names (Option B: upstream SimpleX
contribution) remain on the roadmap as a future enhancement.
When/if SimpleX adds per-group member profiles, the feature
becomes available without changing the security boundary —
the custom name would be set and stored within the group
module, never reading from the private 1:1 profile.

#### Privacy invariant

The private 1:1 profile NEVER enters the group module.
Not by default. Not by option. Not by toggle. The import
does not compile.

### Part 1: Group avatar

#### Data model

One nullable column on `GroupEntity`:

```
avatarPath TEXT NULL   -- absolute path to the local JPEG, or null
```

Mirrors `KnownPeerEntity.announcedAvatarPath` for 1:1 contacts.
Stored as a file under app-private storage (same root as the
user's own `ProfileRoot.avatarFile`), **not** inline in the DB —
keeps the row small and reuses the existing image-load path
(`AvatarBubble` in `StatusScreen.kt` loads `File(avatarPath)`).

#### Send (OWNER / ADMIN sets it)

Extend the rename path. `renameGroup` already issues
`/_group_profile`; generalise it to a `setGroupProfile(groupId,
name, description, avatarPath)` that fills the SimpleX
`groupProfile` struct's `image` field with the same
`data:image/jpeg;base64,…` encoding `setProfile` uses for 1:1.
`null` avatarPath omits the field (preserves existing image),
matching the 1:1 behaviour at `SimpleXTransport.kt:1878`.

#### Receive (inbound from another admin)

We already parse `groupProfile` on inbound group events. When it
carries an `image`, decode the `data:` URL, write the JPEG to the
group's avatar file, set `GroupEntity.avatarPath`. Refresh on every
`groupUpdated` / profile event so an admin changing the avatar
propagates.

#### Render

- Chat list group row: avatar instead of the generated glyph
  (falls back to the glyph when `avatarPath == null`).
- `GroupMembersScreen` header + `GroupChatScreen` header.
- Edit affordance: in `GroupMembersScreen`, an "Edit group image"
  card (OWNER/ADMIN only), reusing the same image-picker +
  crop the 1:1 `ProfileScreen` uses.

### Part 2: Group description

#### Data model

One nullable column on `GroupEntity`:

```
description TEXT NULL   -- short "what this group is", <= 280 chars
```

#### Transport

Same `setGroupProfile` call (Part 1) carries `description` in the
`groupProfile` struct (SimpleX's group profile has a description
field). Inbound: cache it on the row.

#### Render

- `GroupMembersScreen`: a description card under the identity card,
  with an "Edit" affordance gated to OWNER/ADMIN (reuses the rename
  dialog pattern, multi-line, 280-char cap).
- Optionally surfaced as a one-line subtitle in the chat-list group
  row (truncated). Open question §3.

### Part 3: Pinned README

Reuse `MessageEntity.pinned`; **do not** add a parallel store.
Introduce the idea of *the* README = the single group message
flagged as the standing topic.

#### Data model

One nullable column on `GroupEntity`:

```
readmeMessageId TEXT NULL   -- MessageEntity.id of the README, or null
```

A README is just a pinned message that the group also points at via
`readmeMessageId`. Setting a README pins the message (if not already
pinned) and stores its id; clearing it un-designates (the message
stays pinned-or-not per the user's separate pin state).

Rationale for a pointer column rather than a `isReadme` flag on
`MessageEntity`: `MessageEntity` is shared by 1:1 and groups and
lives in `:app`; the "which message is this group's README" fact is
group metadata and belongs next to the group. One group → at most
one README.

#### Render

- A sticky, collapsible banner at the **top** of `GroupChatScreen`
  (above the message list), distinct from the existing
  "most-recent-pinned" banner: README = "this is the group's
  standing topic", pinned banner = "recently pinned message".
- New members see it expanded on first open; collapsible after.
- OWNER/ADMIN can "Set as group README" from a message's long-press
  menu, and "Clear README" from the banner.

#### History

Setting/clearing the README emits a `GROUP_SYSTEM` row (Hardening
Part 1) — reuse `RENAME`-style kind or add a `README` kind. Open
question §4.

---

## Permission

Consistent with `SPEC_GROUPS_HARDENING.md` Part 2:

| Action                | OWNER | ADMIN | MEMBER |
|-----------------------|-------|-------|--------|
| Set/clear avatar      | ✓     | ✓     |        |
| Set/clear description | ✓     | ✓     |        |
| Set/clear README      | ✓     | ✓     |        |
| View any of the above | ✓     | ✓     | ✓      |

Editing is gated by the same `canManage` check the screen already
computes from the self-row role. MEMBERs see the profile read-only.

---

## Security

This is the part that needs Aurora's eye; the rest is plumbing.

1. **Avatar EXIF / GPS leakage.** An avatar uploaded by an admin is
   broadcast to *every member*, including strangers in a public
   group. A raw camera JPEG can carry GPS coordinates, device model,
   and a capture timestamp. The group-avatar pipeline **must** strip
   EXIF before encoding — Aegis already strips EXIF for mugshots
   (`MugshotSettingsScreen.kt:59` references this) and should reuse
   that path. **Hard requirement, not optional.**

2. **Avatar size cap.** `data:`-URL-encoded base64 rides the message
   channel. Cap the source to a small square (reuse the 1:1
   profile-avatar downscale — confirm its dimension/quality) so a
   malicious admin can't push a multi-MB image to every member.

3. **Not sealed.** Group name, membership, and system messages are
   already unsealed (Hardening §"System messages": they describe the
   group, which is observable to anyone who could seal the chat).
   Avatar/description/README are the same class — shared with all
   members by definition. They are **not** PIN-sealed and **not**
   exported with chat backups, matching the system-message rule.

4. **Duress / decoy.** In duress mode the chat list substitutes
   `DecoyFixtures`. Group avatars must flow through the same decoy
   substitution — a real group avatar must **not** render under a
   duress unlock. The decoy fixtures need group-avatar entries
   (or the renderer must blank avatars in decoy mode). Flag for
   Aurora: which is safer.

5. **Anonymous Groups interaction.** A *group* avatar is the
   group's, not yours, so it doesn't reveal your identity. But the
   image you pick might (a selfie, a photo with you in the
   background). The edit flow should carry a one-line caution when
   the group is one you joined anonymously. The
   `SPEC_ANONYMOUS_GROUPS.md` pseudonym path is otherwise unaffected
   (group profile ≠ member profile).

---

## Alternatives considered

### Store avatar bytes in the DB row

Rejected. Inline BLOBs bloat the row and fight the existing
file-path image-load path (`AvatarBubble`, Coil `File(...)` model).
File-on-disk + path column matches 1:1 contacts exactly.

### A dedicated `group_profile` table

Over-normalised for three nullable fields with a 1:1 relationship to
the group. Columns on `GroupEntity` are the simpler fit, same call
the Hardening spec made for roles. Revisit only if group profile
grows structured/repeating fields.

### `isReadme` boolean on `MessageEntity`

Rejected — see Part 3 rationale. README-ness is group metadata, and
`MessageEntity` is cross-cut between 1:1 and groups in `:app`. A
pointer from `GroupEntity` keeps the group concern in the group
module's data.

### Skip the README, lean on normal pinning

The existing pinned banner already surfaces the most-recent pin. But
a README is *deliberately sticky* — it must not be displaced by the
next operational pin ("meetup moved to 8pm"). Two distinct affordances
(standing topic vs recent pin) is the right model; collapsing them
loses the "read this first" property for new members.

---

## Resolved questions (Aurora, 2026-06-03)

1. **Avatar downscale spec.** 256×256 max, JPEG 70% quality.
   Tighter than 1:1 because it broadcasts to all members.
   The decode → resize → re-encode pipeline is the
   sanitization step — crafted JPEG payloads die in the
   re-encode. Document this: re-encode IS the security
   defense, not just a size optimization.

2. **Decoy group avatars.** Blank all group avatars under
   duress. Simpler, leaks nothing. Nobody notices a generic
   group icon — it’s the default state for most groups.
   Fake avatars risk inconsistency with decoy contact
   fixtures.

3. **Description in the chat-list row.** Yes — show it.
   One-line truncated subtitle. Helps distinguish groups at
   a glance without opening them. The second line is worth
   the vertical space.

4. **README GROUP_SYSTEM kind.** New `README` kind. The enum
   is small enough that one more entry costs nothing, and
   “X set the group README” is much clearer than reusing
   RENAME.

5. **Owner-leaves dissolution.** Confirm `deleteGroup()`
   removes the avatar file, not just the DB row. Add
   explicit `File(avatarPath).delete()` in the deletion
   path if not already present.

6. **Per-group identity prompt UX.** On join (for groups
   joined via link) and on creation (for groups you create).
   Single inline field: “Your name in this group:” with a
   text input and “Join” / “Create” button. Pre-populated
   with the user’s last-used group name (not their private
   1:1 name) for convenience. Cannot be left empty —
   the button stays disabled until a name is entered.
   Avatar is optional at join time (defaults to generated
   glyph); editable later from the group members screen.

---

## Implementation notes (non-binding, for Aurora's review pass)

- Migration: three `ALTER TABLE groups ADD COLUMN` (avatarPath,
  description, readmeMessageId), all nullable, no backfill. Mirrors
  the Hardening migration shape.
- `setGroupProfile(groupId, name, description, avatarPath)`
  generalises the existing `renameGroup` — rename becomes a call
  with description/avatar unchanged. One transport method, not three.
- Inbound parse already reaches `groupProfile`; add `image` +
  `description` extraction next to the existing `groupId` /
  `displayName` reads (`SimpleXTransport.kt:1044`, `:1627`).
- Avatar encode/decode reuses `setProfile`'s `data:` URL path
  verbatim.
- README banner is the largest new UI piece (~120 lines:
  collapsible top banner + long-press "Set as README" + clear).
  Avatar + description reuse `ProfileScreen`'s picker and the rename
  dialog respectively (~150 lines total).
- Estimate: ~1.5 days. No threshold changes. No new permissions.

---

## What this spec does NOT do

- Does not add per-member avatars beyond what 1:1 pairing already
  gives (a group member who is also a KnownPeer already shows their
  1:1 avatar).
- Does not add multiple READMEs or structured group "channels".
- Does not change the rename flow's behaviour (it becomes one call
  into the generalised `setGroupProfile`, same UX).
- Does not seal group profile (it is shared with all members by
  definition — see §Security).
- Does not touch 1:1 chat, the Trust Model, or SOS.
- Does not change Anonymous Groups' member-pseudonym handling
  (group profile ≠ member profile).
