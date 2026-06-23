# SPEC_PROTECTED_MODE — guard-rails for a child's own device

Status: APPROVED (Aurora review 2026-06-09) + PROTOTYPED (Chad, behind the
Experimental gate). Aurora's three additions merged below (§5b).

## Implementation status (prototype, 2026-06-09)

Built behind the Experimental unlock (7-tap cargo version), entered from
Security (Opsec) → Protected Mode.

- **Mechanism:** `protectedmode/ProtectedMode.kt` — process-wide
  controller + per-profile `ProtectedModeStore` (Argon2id PIN, mirrors
  LockStore). `isGated(gate)` is the single predicate; inert under duress
  (invariant 3); off by default (invariant 4).
- **Config screen:** `ui/screens/ProtectedModeScreen.kt` — set PIN,
  Child/Lockdown/Custom presets, à-la-carte toggles, arm/disarm. Disarm
  prompts for the protected-mode PIN OR the app PIN (master override,
  invariant 2).
- **Tabs lock in place:** `AegisBottomNav` dims System/Opsec, adds a lock
  glyph, never moves them; tapping a locked tab opens the disarm prompt
  (invariant 1: SOS, the centre slot, is never lockable).
- **Wired gates** (`ProtectedMode.WIRED` — all nine):
  - *System tab* / *Opsec tab* — lock in place (above).
  - *Contact management* — delete + **mute** (Aurora §5b.1), in ContactDetail.
  - *Trust-tier* — the promote/demote picker in ContactDetail.
  - *Groups* — create (GroupCreate), join-via-link (AddContact, group links
    only), leave (GroupMembers) all disable.
  - *Hide Groups* — removes the group **management** surface in the chat
    list (Join / New / Disable header, auto-disable config, the "make a
    group" hint) while KEEPING the Groups tab and existing-conversation
    list, exactly per Aurora §5b.3.
  - *Danger Zone* — the wipe-all button in Diagnostics.
  - *Profiles* — switch (row tap) + delete in ProfilesSettings.
  - *Updates* — the update action button.
- **Aurora review follow-up still open:** contact *block* (§5b.2) —
  `Repository.setPeerBlocked` exists but has no UI surface yet, so there is
  no control to gate; wire it when the block UI lands.

Prototype-only literals (not yet in `strings.xml`) pending the translation
pass; no documented numeric thresholds touched.

---

Original proposal follows.

## 0. Problem

A child runs Aegis on **their own phone** — so they stay reachable by
family, share location, and can fire SOS if something goes wrong. The
guardian sets it up; the child is the everyday user. And a child on their
own phone can — by accident — sever their own safety net:

- wipe the whole app (Diagnostics → DANGER ZONE),
- delete a Trusted contact — including the parent, the child's lifeline,
- demote a Trusted contact, which silently stops the child's OWN SOS +
  location updates from reaching that person,
- flip security settings (PIN, duress, device admin) into a broken state,
- switch or delete a profile.

None of that is malicious. It's a kid with a touchscreen. Protected Mode
makes those destructive surfaces inert so a child can't accidentally cut
the cord between themselves and the people watching over them.

**Two flavours, one mechanism.** The child's-phone case is the headline,
but Protected Mode is really a configurable **commitment device** — a
separate PIN guarding whatever destructive-or-escalating actions the
arming user chooses:

- *Guardian → child:* lock down a kid's own phone (above).
- *User → self:* arm it on yourself to put a Ulysses pact between you and
  an impulse — e.g. lock trust-tier changes so a 2 a.m. urge to promote
  your whole contact list to Trusted has to survive fetching the PIN.

Same machinery, same PIN ladder; only the persona and the chosen gates
differ. The name is deliberately goal-shaped ("the app is protected"),
not user-shaped, precisely so it covers both.

## 1. Threat model — read this first

**Protected Mode is NOT a security feature. It is accident-prevention
(or impulse-prevention).**

Every other lock in Aegis (app PIN, duress PIN, vault PIN) defends
against an *adversary* — a thief, a coercer. Protected Mode defends
against the *everyday user's own fingers* — a child's, or your own.
Consequences:

- It can be simple. Whoever holds the protected-mode PIN can disable it;
  the user defeating their own gate is **not** a failure — there's no
  hostile party to keep it from.
- The friction *is* the feature: enter PIN → do the thing → re-arm. Enough
  to break an accident or an impulse, not meant to defeat intent.
- It must be strictly *weaker* than the real lock — never a new way to
  strand the account owner.

Conflating it with the security PINs would be a design error.

## 2. Hard invariants (non-negotiable)

1. **SOS is ALWAYS reachable.** Protected mode never gates, hides, or
   delays the panic button — not for any reason. A child in danger must
   be able to fire it. This is the entire purpose of the app; a
   restricted mode that could touch SOS would be a contradiction.
2. **The owner is never locked out.** The app PIN (and the recovery
   phrase) always override / disarm protected mode. Protected mode sits
   *below* the real lock in the ladder and can never block it.
3. **Real-mode only.** Under a duress unlock the decoy world is shown and
   protected mode is inert — security precedence beats convenience. The
   two never interact.
4. **Off by default, optional.** A user who never wants it never sees it.

## 3. Proposed design

A 4th, optional **protected-mode PIN**, distinct from the app/duress/vault
PINs. The everyday user (a child, or yourself) holds the app PIN to use
the phone daily, so the protected-mode gate needs its own secret kept by
whoever armed it.

### 3.1 À la carte gates — every one is its own toggle

Protected Mode is **not** a fixed bundle. The person arming it ticks
exactly which surfaces to lock; the rest stay live. The same machinery
serves a guardian locking down a child's phone AND a user arming it on
themselves as a commitment device (e.g. "stop me impulsively promoting
everyone to Trusted"). The available gates:

| Gate | What it blocks while armed |
|---|---|
| **Lock System tab** | the Settings surface |
| **Lock Opsec tab** | the Security surface (PIN/duress/admin config) |
| **Lock contact management** | add / delete / block contacts, mute / unmute notifications |
| **Lock trust-tier changes** | promote / demote (the silent-SOS-break) |
| **Lock group membership** | create / join (incl. invite link) / leave |
| **Hide Groups entirely** | removes the Groups sub-tab outright |
| **Lock DANGER ZONE** | wipe-all, core reset, the destructive ops |
| **Lock profiles** | switch / delete profile |
| **Lock updates** | the self-update controls |

SOS is never in the list (invariant #1).

**Presets for quick setup, custom for control** — two doors in, so the
non-technical guardian and the power user are both served:

- **Presets (default, one tap):**
  - **Child** *(the default when you first enable it)* — pre-ticks the
    curated full set for a kid's phone (both tabs, contacts, trust,
    group membership, danger zone, profiles, updates). Set a PIN, done.
  - **Lockdown** — everything *plus* Hide Groups: the "nothing but chats,
    map, and SOS" configuration.
- **Custom (power users):** the à la carte table above — tick exactly
  what you want and nothing else (the self-restraint case usually lives
  here: just **Lock trust-tier changes**, say).

Presets are only *starting points*: pick **Child**, flip one toggle, and
you're in "Custom (from Child)". A preset is never a cage.

### 3.2 How a locked surface renders

- **Locked TABS (System / Opsec) stay IN PLACE** — *not* hidden. They
  keep their nav slots, dimmed with a small lock glyph. Deliberate: the
  nav is `[System · Opsec · SOS · Comms · Radar]` with panic dead-centre.
  Hiding the two left tabs would either strand SOS off-centre with a gap,
  or re-centre the survivors and destroy the muscle memory for the one
  control that matters most under stress. The layout — and SOS's centre
  position — must never move. So the tabs stay; they just stop working.
- **Locked ACTIONS** (contact/group/tier management, danger zone, etc.)
  are disabled wherever they appear (contact detail, chat-list
  long-press, deep links), greyed with the same lock affordance.
- **"Hide Groups entirely"** is the one that removes rather than dims —
  the Groups *sub-tab* (inside Comms, not a nav slot, so no layout
  impact) disappears. Existing group conversations remain visible in
  Comms — only the management surface (create / join / leave) is hidden.

**What stays fully open:** Chats (read + send to existing contacts),
the groups the child is already in, Map/Radar, and **SOS** (centre slot,
untouched).

**Arming / disarming:**
- Arm from Opsec → Protected Mode (enter the protected-mode PIN to set it).
- Disarm: **tapping a locked tab is the entry point** — it prompts for
  the protected-mode PIN instead of navigating. Discoverable for the
  owner, useless to a child (they can't enter it), and it needs no
  obscure secret gesture. The app PIN is always a master override
  (invariant 2).
- Stays armed across restarts (it's the child's everyday state, not a
  one-off hand-over), until the guardian disarms it.

## 4. Alternatives considered

- **Android screen pinning** — pins the *whole* app, no per-tab
  granularity, and any swipe-up + back combo escapes it. Too coarse.
- **Reuse the vault PIN** — wrong semantics; the vault is a hidden notes
  store, and a kid knowing it would expose the vault. New PIN is cleaner.
- **Globally hide destructive actions** (no mode) — then the *owner* has
  to dig for their own wipe/settings constantly. A toggle is better.
- **A separate Android user / restricted profile** — heavy, loses the
  child's SimpleX identity, and is OS-level overkill for "stop the kid
  breaking their own Aegis."

## 5. Open questions

1. ~~Hide vs. PIN-gate the tabs.~~ **Decided: lock in place, never hide.**
   Hiding shifts the nav and would move SOS off its centre slot —
   unacceptable. Locked-but-present tabs preserve the layout + muscle
   memory, and the lock-tap doubles as the disarm entry.
2. ~~Name.~~ **Decided: "Protected Mode"** — names the goal (the app is
   protected) rather than the user.
3. ~~Trust-tier changes.~~ **Decided: gate promote/demote.** The nightmare
   case is silent and only bites when it matters: the child demotes the
   parent, and the child's OWN SOS + location quietly stop reaching them.
   The kid's in trouble and their lifeline goes dark. Exactly the
   invisible damage Protected Mode exists to stop.
4. ~~Chat scope.~~ **Decided: all contacts, fully open.** It's the child's
   own phone — every contact is family (the parent, grandma). There's no
   sensitive-contact list to seal, so messaging stays completely usable
   and no allowlist is needed.

## 5b. Aurora review additions (2026-06-09)

Three gaps identified and approved:

1. **Notification muting gated.** A child can mute a Trusted contact's
   notifications without demoting them. The parent's SOS, location, and
   status updates arrive but silently — functionally invisible. "Lock
   contact management" now covers mute/unmute.

2. **Contact blocking gated.** Blocking is distinct from deleting but has
   the same effect — communication severed. "Lock contact management"
   now covers block/unblock.

3. **"Hide Groups" scope clarified.** Hide removes the management
   sub-tab (create / join / leave), NOT existing group conversations.
   Conversations the child is already in remain visible in Comms.

## 6. Not in scope (v1)

- Time limits / screen-time (that's the OS's job).
- Per-contact message allowlists — unnecessary on a child's own
  family-only device (see §5 Q4); revisit only if a non-family use case
  ever appears.
- Any change to SOS, calls, or the transport layer.
