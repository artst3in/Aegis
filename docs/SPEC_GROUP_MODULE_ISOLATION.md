# SPEC: Group Module Isolation

**Status:** IMPLEMENTED — Chad 2026-06-02 (Phase 1 shipped over
several releases; Phase 2 module extraction completed in releases
2026.06.245 → 2026.06.255 — `:feature:groups` Gradle module now
holds entities/DAO/repository/prefs/workers + module-disabled card,
with the one-way dep edge `app → feature:groups` compile-enforced)
**Owner:** Chad (implementation), Aurora (spec)

## Problem

Public group chat shares the same code paths, transport layer, and
data structures as trusted 1:1 communication. A bug in group handling
could leak safety data (location, presence, SOS) to group members.
The proximity of these systems is an unacceptable architectural risk
for a personal security app.

## Solution

Separate the group system into an isolated Gradle module with no
compile-time dependency on safety code. The module is disabled by
default and toggled on/off by the user at will.

## Architecture

### Module boundary (compile-time)

The group system lives in a separate Gradle module (e.g.
`:feature:groups`) that has NO dependency on:

- SOSCoordinator
- LocationManager / LocationBroadcaster
- PresenceService
- SensorManager / SonarEngine
- MugshotCapture
- CanaryScheduler
- GeofenceMonitor
- Any class in the `safety/`, `SOS/`, `presence/`, `location/`
  packages

The module CAN depend on:

- SimpleX transport (send/receive messages)
- Database (group tables only — NOT known_peers, NOT SOS tables)
- UI framework (Compose, theme, LunaGlass tokens)
- Crypto primitives (encryption/decryption for group messages)

If a developer adds an import from a safety package into the group
module, the build fails. This is enforced by Gradle module boundaries
— not by code review, not by convention, not by runtime checks.

### Off by default

The group module is disabled on fresh install. The Comms tab shows
only 1:1 chats. No group-related network endpoints are registered.
No SimpleX group connections are established. Zero attack surface.

### Enable flow

The Groups section in the Comms tab shows the toggle directly.
When groups are disabled, the section displays a single
"Enable Group Chat" button. No hiding it in a settings submenu.

Every enable shows a warning dialog — the warning cannot be
dismissed permanently, it appears on every toggle-on:

    ┌─────────────────────────────────────────────┐
    │  Enable Group Chat                          │
    │                                             │
    │  Public groups increase your attack surface. │
    │  Group members cannot access your location,  │
    │  SOS, or safety data, but enabling this    │
    │  module exposes additional network endpoints. │
    │                                             │
    │  You can disable this at any time.           │
    │                                             │
    │  [Cancel]              [Enable]             │
    └─────────────────────────────────────────────┘

The warning appears EVERY time. Every toggle-on is a
conscious decision. No "don't show again" checkbox.

### Toggle behavior

**ON:**
- SimpleX group connections resume
- Group messages sync (catch up on missed messages)
- Groups tab/section appears in Comms
- Group-related background work starts

**OFF:**
- SimpleX group connections suspended immediately
- No group messages received or sent
- Groups section hidden from Comms
- All group-related background work stops
- Group data remains on device (not deleted)
- Network endpoints closed (group relay subscriptions dropped,
  SimpleX agent stops polling group-associated SMP queues)

The toggle is instantaneous. No app restart required.

### Auto-disable timer

Optional (toggleable). When enabled, the group module
automatically disables itself after X minutes since the user
last visited the Groups section in Comms. Configurable
duration (default: 30 minutes).

Behavior:
- User enables groups → visits Groups tab → timer starts on
  tab exit
- User returns to Groups tab → timer resets
- Timer expires → module auto-disables (connections suspended,
  section hidden)
- Next visit requires re-enable with warning

This is a dead man's switch for the attack surface. If you
don't actively engage with groups, the system assumes you're
done and locks down. You cannot forget to disable it.

The timer runs as a WorkManager one-shot job. If the app is
killed, the timer still fires. If the phone reboots, the
module stays in its last explicit state (on or off) — the
timer is canceled on reboot to avoid unexpected disable during
extended use.

### Per-group toggles

When the group module is enabled, each group has its own
on/off toggle controlling whether its SimpleX connections
are active. This is connection management inside the
isolated module — not a hole in the module boundary.

Use cases:
- Family group: always on (when module is enabled)
- City group: off by default, manually enabled when needed
- Temporary group: on for one session, then off

The auto-disable timer can be set per-group:
- Family group: no timer (stays on while module is on)
- Aegis Amsterdam: 30-minute timer after last visit

Per-group state persists across module toggles. If you
disable the module and re-enable it, each group returns
to its previous on/off state. No re-configuration needed.

### Use case

User checks public groups once per evening. Module is ON for
30-60 minutes. Module is OFF for 23 hours. Attack surface from
public groups exists only during the active window.

Alternatively: module stays on, but only the family group is
active. City group is off with a 30-minute auto-disable timer.
User enables city group, checks it, walks away, timer closes
it. Family group stays open. Module boundary holds regardless.

### Toggle placement

Comms tab, Groups section. The toggle lives where the groups
are. When OFF, the section shows "Enable Group Chat" with the
module's status. When ON, groups appear normally with a
disable option accessible from the section header.

Disabling is frictionless — one tap, no confirmation needed.
Enabling always shows the warning.

### Profile interaction

Group module isolation is orthogonal to profiles. Each profile can
independently enable/disable the group module. A group-focused
profile can have it always on. A safety-focused profile can have
it always off.

## Scope: what goes where

### Core module (1:1 only)
Direct messages between individually-paired contacts. All safety
data flows here (SOS, location, presence, sensor data). The
core module handles ONLY two-party communication.

### Group module (everything else)
ALL groups — standard, anonymous, any conversation with more than
two participants. No exceptions. No "trusted group" carve-out.
The boundary is absolute: 1:1 = core, 2+ = group module. The
moment exceptions exist, the boundary is runtime logic, not
compile-time structure.

Anonymous groups (pseudonym rotation, forced erasure, identity
protection) live in the group module. These are group features,
not safety features. Forced erasure is self-cleaning — it fits
the isolated module naturally.

### Cross-module API: zero data bridge

No contact data crosses the module boundary. The group module
never reads the contact list, trust tiers, display names, or
any safety data from core. The bridge carries only pub keys
(already known to all group members) and navigation events
(stateless callbacks).

**Profile viewing:** When a user taps a member's avatar in a
group chat, the group module fires a navigation callback:

    fun onMemberTapped(pubKey: String)

Core receives the pub key, checks whether it matches a known
contact, and shows the profile screen or a "not a contact"
screen. The group module never learns the result — it fires
the event and forgets.

**Group creation from contacts:** The group module requests a
contact picker:

    fun requestContactPicker(): List<String>  // pub keys

Core shows its own picker UI. The user selects contacts in
core's context. Core returns only the selected pub keys. The
contact list, display names, trust tiers, and safety data
never enter the group module.

**Name display:** Cached from inbound group messages (already
shipping in groups 2/N, build 2026.06.222). The group module
learns member names from group traffic, not from core.

**Risk assessment: zero.** Pub keys are already visible to
every group member by protocol design. Navigation events are
stateless. No data bridge exists. No ContactNameResolver. No
read-only API. Nothing to expand, nothing to leak through.

### What this does NOT add

- Group-scoped SOS. See WHY_NO_GROUP_SOS.md.
- Cross-profile group access. Each profile manages its own
  group module state independently.

## Implementation

### Phase 1 (current codebase)
- Add the toggle to Comms tab (Groups section)
- Gate all group UI behind the toggle
- Suspend/resume SimpleX group connections on toggle
- Warning dialog on every enable (no permanent dismiss)

### Phase 2 (module extraction)
- Extract group code into `:feature:groups` Gradle module
- Define explicit API surface between core and groups
- Enforce module boundary in build configuration
- Verify no safety imports survive extraction

Phase 1 is shippable immediately. Phase 2 is the structural
guarantee — it makes the boundary compiler-enforced rather than
convention-enforced.
