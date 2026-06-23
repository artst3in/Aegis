# SPEC: Profile onboarding + new-profile flow

**Status:** RESOLVED — Aurora + Artur answered all 8 open questions
(`docs/AURORA_DECISIONS_ONBOARDING_2026_06_06.md`, 2026-06-06). Ready to
build; the decisions doc is authoritative where it and this draft differ.
**Author:** Chad, 2026-06-06
**Designed by:** Artur Tokarczyk; reviewed by Aurora.

## Resolved decisions (summary — see the decisions doc for detail)

1. **One "New profile" button**; permanent/ephemeral chosen in-flow.
2. **A stays alive during B's onboarding** — A locks, seal key wiped,
   but background SAFETY services keep running on A's *cleartext* safety
   data (routing keys, trust tiers — intentionally unsealed). A's CHAT
   notifications are SUPPRESSED; only **SOS** comes through, and SOS
   overrides onboarding (handle emergency, restart onboarding later).
3. **Never two profiles in parallel** — exactly one active profile;
   A runs safety-only, never chat, during B's onboarding.
4. **Completion:** permanent B is created-but-not-active with an explicit
   "opening this shuts down [A], no help until you set up contacts"
   warning (open now or postpone). Ephemeral B is **now-or-never** (not
   saved; if not opened immediately it dies).
5. **Switching:** ephemeral B = visitor (lock → dies → back to A);
   permanent B = the new home (A reached explicitly via profile picker).
6. **SOS on ephemeral B:** destroy B → load A locked → SOS fires on
   A's cleartext safety data (same as panicking from the lock screen).
7. **Import into ephemeral:** allowed (inspect a backup in RAM; lock → gone).
8. **Dual-runtime is sound:** A's safety services need only cleartext
   data, so A can be fully locked while its safety runs — same data
   layer as a normal locked profile. One seal key in memory at a time.

The original draft (with the open questions) follows for the record.

---

## The redesign

Today, "new profile" is a small dialog (id + PIN), and switching
profiles **kills the process** and reloads — only one profile is ever
live. Artur's redesign:

> New-profile creation should be **ONE button**. Tap it (+ accept a
> warning) and it opens a brand-new profile **exactly like a fresh
> install** — the full "Welcome to Aegis" onboarding. The **current
> profile must stay alive in the background, just locked** — so SOS
> and notifications keep working. From the onboarding you choose
> **permanent or ephemeral**, and **create new OR import a backup**.
> You CAN import a backup into an **ephemeral** profile — a valid way to
> work on a permanent profile's data without touching the permanent one.

## Flow

1. Profiles screen: a single **"New profile"** button (replaces the
   current "Create new profile" + "Create ephemeral profile" pair and
   the id/PIN dialog).
2. Tap → warning ("Your current profile stays protected in the
   background — SOS and alerts keep running — while you set up a new
   one"). Accept.
3. The current profile **locks** (UI gated) but its background services
   keep running (SOS, notifications, SimpleX receive, sentinel).
4. Foreground shows the **"Welcome to Aegis" onboarding** — same flow as
   a fresh install:
   - **Account type:** Permanent or Ephemeral.
   - **Start:** Create new (recovery phrase → PIN → unlock method) OR
     Import backup (file + backup password → restore).
5. On finish, the new profile is the foreground/active session.

## Two important properties

**(a) Current profile stays alive + locked.** It is NOT killed. Its
foreground service keeps SOS, remote-access, sentinel, and inbound
notifications running for the original profile while the user works in
the new one. This is the core of the request and the hard part — see
Open Questions.

**(b) Import-into-ephemeral is allowed and useful.** Restoring a backup
into an EPHEMERAL profile lets the owner read/work with a permanent
profile's data in a session that is **wiped on lock** and never written
durably — i.e. inspect or use a backup without committing it to a
permanent on-disk profile. The restore re-seals into the ephemeral
profile's RAM-only DB; on lock it's gone.

## The hard part — dual-profile runtime (Open Questions)

The current architecture binds ONE active profile to the process:
`profileRoot`, the Room DB, `identity`, and the transports are all the
active profile's, set at `AegisApp.onCreate`, and a profile switch
kill-restarts. Keeping profile A's **services** alive while profile B is
the **foreground** session is a genuine departure. Questions for review:

1. **What exactly must keep running for A while B is foreground?** SOS
   broadcast + remote-access (A's trusted contacts), inbound
   notifications, sentinel. These need A's identity + A's contacts (A's
   DB) live. Does B's foreground need A's DB closed, or can both be open?
2. **SimpleX layer:** already multi-user (we built dedicated ephemeral
   users). A and B can be distinct SimpleX users coexisting in the one
   core. So the transport can carry both. The blocker is the **Aegis
   layer** (single active Room DB + identity), not SimpleX.
3. **SOS while in B:** if the owner triggers SOS from B, does it use
   A's safety network (B, especially ephemeral, has none)? The existing
   SOS-while-ephemeral path already destroys-ephemeral → switches to
   primary → broadcasts. Reconcile that with "A is already alive in the
   background."
4. **Lifecycle:** when the user finishes with B, how do they get back to
   A? Is B's existence persistent (a second permanent profile) or is A
   always the "home" it returns to on lock? Does locking B return to A?
5. **Memory/safety:** running two profiles' data layers raises the live
   plaintext surface. Acceptable? (A is locked, so A's seal key is
   wiped — A's services run but A's at-rest content isn't decrypted.)

## Buildable in pieces (once the runtime question is settled)

- **One button + warning** → launch onboarding. (UI, easy.)
- **Onboarding offers permanent/ephemeral + create/import.** Reuse
  `FirstRunScreen`'s restore + the tutorial enrolment. (Moderate.)
- **Import into a (new) profile, incl. ephemeral.** Needs the
  master-key-independent backup (separate spec) so a restore re-seals
  under the new profile's key. (Moderate–large.)
- **Dual runtime — A alive+locked while B foreground.** The architectural
  piece. Needs the decisions above before any code. (Large.)

## Discipline note

Written as a spec first, deliberately. The last architectural change that
auto-shipped without device-tested design (the existing-install phrase
migration) corrupted a real user's data. This one touches the profile
runtime — the thing every other feature sits on — so it gets designed and
reviewed before it gets built, and it ships opt-in and device-tested.
