# SPEC: Trust-Level Containerization

**Status:** PHASE 2 — TIER GRAPH COMPLETE; one extraction remains
(Chad, 2026-06-05; spec APPROVED — Aurora + Artur, 2026-06-03). Phase 1
runtime gating landed for presence + SOS (groups already
toggle-gated); see "Phase 1 as-built". Phase 2: the full `:core`
skeleton exists (`:core:transport`, `:core:safety:sos`,
`:core:safety:presence`) with its pure leaves relocated, AND the three
tier modules now exist with the spec's dependency graph enforced by the
compiler — `:feature:untrusted` → transport; `:feature:emergency` →
transport + SOS (no presence); `:feature:trusted` → transport + SOS
+ presence (stage 5 done; each seeded with its real tier contract —
`UntrustedPolicy` owns the disposable-TTL, `Emergency`/`TrustedPolicy`
hold the capability matrices). The compile-time trust boundary the spec
is fundamentally about is therefore LIVE.

**The one remaining item** is stage 4's final move: extracting
`SimpleXTransport` (the last transport file in `:app`) down into
`:core:transport`. This is blocked not on effort but on an architectural
fork the transport touches directly: it both parses group events and
calls group repo methods whose parameter/return types
(`GroupEntity`/`GroupMemberEntity`/`GroupRole`/`GroupSystemPayload`/
`GroupModulePrefs`) all live in `:feature:groups`. `:core:transport`
cannot depend on `:feature:groups` (wrong direction), so the move forces
a decision: either relocate the whole group type web *below* transport
(breaks `:feature:groups`' cohesion — it would no longer own its own
types), or lift group-event HANDLING out of the transport into
`:feature:groups` behind a host (the cleaner design, but a real refactor
of the messaging core). Because it is pure structure with zero
functional change and cannot be runtime-verified without a device, it is
deferred to a dedicated, on-device-tested effort rather than bundled
into a release. See "Phase 2 staged migration → stage 4".
**Owner:** Chad (implementation), Aurora (spec)

## Principle

Each trust tier is a separate module. A module with zero
contacts does not run. The attack surface is exactly
proportional to what the user uses.

## Origin

Compartmentalization: ship bulkheads, military need-to-know,
firewalls, FreeBSD jails, Kubernetes pods, Proxmox containers.
Same principle applied at compile time instead of runtime.
A Kubernetes pod can be escaped. A Gradle module boundary
cannot — the code path does not exist.

## Architecture

Three structural boundaries enforced at compile time.
Zero convention boundaries.

### Structural boundary 1: safety vs no-safety

The core splits into three layers:

```
:core:transport          (SimpleX protocol, crypto, database)
:core:safety:sos       (GPS, audio, camera broadcast)
:core:safety:presence    (location sharing, online/offline, battery)
```

Trusted depends on all three. Emergency depends on transport
and SOS — presence is absent from its dependency graph.
Untrusted and groups depend on transport only. Both safety
layers are invisible to them.

### Structural boundary 2: toggle-gated vs auto-gated

Groups require explicit user toggle with warning on every
enable (per SPEC_GROUP_MODULE_ISOLATION.md). All other
modules auto-enable when the first contact at that tier is
added and auto-disable when the last is removed.

### Structural boundary 3: SOS vs presence

Emergency contacts receive SOS broadcasts only — not daily
location, not presence, not battery. The safety layer splits
into :core:safety:sos and :core:safety:presence. Emergency
depends on SOS only. Presence is absent from its dependency
graph — a developer adding a presence import to the emergency
module gets a build error.

There is no such thing as over-engineering in cybersecurity.
Every boundary not enforced by the compiler is a boundary
that can be breached by a bug.

## Modules

```
:core:transport          (SimpleX, crypto, database)
:core:safety:sos       (GPS, audio, camera broadcast)
:core:safety:presence    (location sharing, online/offline, battery)

:feature:trusted     → :core:transport + :core:safety:sos + :core:safety:presence
:feature:emergency   → :core:transport + :core:safety:sos
:feature:untrusted   → :core:transport
:feature:groups      → :core:transport
```

| Module | Enabled when | Data flow |
|---|---|---|
| trusted | ≥1 trusted contact | Full: chat + location + presence + battery + SOS |
| emergency | ≥1 emergency contact | SOS broadcast only (by convention) |
| untrusted | ≥1 untrusted contact | Chat only. Anonymous flag per contact adds pseudonym rotation + forced erasure. |
| groups | User toggle ON | Chat only. Per-group toggles. Auto-disable timer. See SPEC_GROUP_MODULE_ISOLATION. |

Four parallel containers. All depend on core. None depend
on each other.

## Auto-enable / auto-disable

Adding a contact at a tier enables that tier's module.
Removing the last contact at a tier disables it. No
orphaned attack surface.

Groups are the exception: manual toggle with warning,
because group membership is not individually controlled
(per WHY_NO_GROUP_SOS.md).

## What "disabled" means

A disabled module:
- Registers no SimpleX connections for that tier
- Runs no background services for that tier
- Opens no network endpoints for that tier
- Allocates no database tables for that tier
  (tables created on first contact, not on install)

It does not exist in memory. Not hidden. Absent.

## Example: minimal user

A user with 3 trusted contacts and nothing else:

- trusted: active
- emergency: absent (zero contacts)
- untrusted: absent (zero contacts)
- groups: absent (not toggled)

Attack surface: one module out of four. The other three
do not exist. Adding one emergency contact activates one
more module. Removing it deactivates it. The surface
breathes with the user's actual needs.

## Implementation

### Phase 1: runtime gating (shippable now)

Each module's services and transport are gated behind
`if (contactCount(tier) > 0)`. The code exists in the
binary but never executes for empty tiers.

#### Phase 1 as-built (Chad, 2026-06-03)

Live tier counts added as Room `Flow`s so gates react the instant a
contact is added / removed / retiered:
`KnownPeerDao.trustedCountFlow()` (TRUSTED) and `sosCountFlow()`
(TRUSTED ∪ EMERGENCY), surfaced via `Repository`.

- **Presence module (GPS/location):** `ProtocolService` registers the
  location listener only while `trustedCount > 0`. With zero Trusted
  contacts the GPS listener is never registered and is torn down if
  the last Trusted contact is removed. The status ticker is left
  running — it is shared infra (power budget + snatch reconcile) and
  its location/status broadcasts already no-op on an empty Trusted set.
- **SOS module (power-button ×4):** `AegisApp` registers
  `PowerButtonSOSReceiver` only while `sosCount > 0`, and
  unregisters it when the last sos-eligible contact goes.
  **FAIL-OPEN:** any error reading the count registers the receiver
  anyway — a missing SOS trigger is a safety failure, a redundant
  one is harmless (broadcasts no-op on empty `sosTargets()`).
- **Groups module:** already toggle-gated (`GroupModulePrefs`,
  default OFF) per SPEC_GROUP_MODULE_ISOLATION.md. No change needed.
- **Untrusted module:** no tier-specific service runs for untrusted
  contacts beyond plain chat/transport. Nothing to gate.

- **Snatch detection (accelerometer):** also gated on `sosCount`
  now — `ProtocolService.reconcileSnatchDetection` requires
  `sosTierActive && powerBudget.shouldRunSnatchDetection()`, and
  `startSnatchDetection` early-returns when no SOS-eligible contact
  exists. With nobody to alert, the ~50 Hz sensor never runs; it arms
  the moment the first Trusted/Emergency contact is added. This
  completes Phase 1 runtime gating across both SOS triggers
  (power-button + snatch) and the presence module.

### Phase 2: Gradle module extraction (structural)

Extract into separate Gradle modules:
`:feature:trusted`, `:feature:emergency`,
`:feature:untrusted`, `:feature:groups`.
Split core into `:core:transport`, `:core:safety:sos`,
and `:core:safety:presence`.

Untrusted and groups depend on `:core:transport` only.
Emergency depends on `:core:transport` + `:core:safety:sos`.
A developer adding a presence import to emergency or a
safety import to untrusted gets a build error. Every
boundary is the compiler, not the reviewer.

#### Phase 2 staged migration (Chad, 2026-06-03)

Done as a sequence of small, build-green commits — the same iterative
approach `:feature:groups` used. The hard part isn't moving files;
it's that the safety/feature code calls back into `:app` services
(Repository, ProtocolManager, SOSHandler). We invert that with the
**host-interface pattern already proven by `:feature:groups`**: each
module declares a narrow `*Host` interface for what it needs from the
app, a `*HostHolder.current` is set by `AegisApp.onCreate`, and the
module never gains a compile-time edge back to `:app`. Moving a file
keeps its Kotlin package, so `:app` call sites are unchanged — only
the Gradle dependency edge is added.

Stages (each ends green and is committed independently):

1. **`:core:safety:presence` skeleton + pure leaf — DONE.** Module
   created; `InAppActivity` (online/away heartbeat, zero `:app` deps)
   moved into it; one-way `app → :core:safety:presence` edge added.
2. **Presence behaviour out of `ProtocolService`.** Move the location
   stream + status broadcasting into `:core:safety:presence` behind a
   `PresenceHost` (trusted targets, send-message, last-known fix). The
   monolithic service shrinks to transport + the host wiring.
3. **`:core:safety:sos`.** Module created; build wires
   `app → :core:safety:sos` with NO edge to `:core:safety:presence`
   (boundary 3 holds). Moved in so far:
   - `SirenManager` — pure tone-generator leaf, zero `:app` deps.
   - `SOSAlertStore` — receiver-side SOS + coordination state.
     Self-contained (Compose snapshot state only); moves with no
     content change. The module takes the Compose **runtime** artifact
     (no compiler plugin — there are no `@Composable` functions).
   - `SOSAudioPlayer` — auto-plays inbound SOS audio. Its single
     `AegisApp.applicationContext` reach (in `restoreAudioMode`, a
     Context-less code path) is now inverted through the new
     **`SOSModuleHost`** interface + `SOSModuleHostHolder`,
     installed by `AegisApp.onCreate` — the GroupModuleHost/
     PresenceModuleHost pattern.
   - `SOSEvidenceLog` — victim-only append-only evidence trail. Its
     `applicationContext` reach uses the host's `appContext`, and its
     own-victim gate (`SOSHandlerBridge.isMyOwnSOSActive()`, which
     stays in `:app`) is now asked through the host's
     `isMyOwnSOSActive()`. Both fail closed when the host is unset.
   `SOSModuleHost` now also exposes the Stage-3 widening (Chad,
   2026-06-04): `selfKey`, `sendStatus(peer, body)` (STATUS-only, hides
   the transport's MessageType), `sosTargetKeys()`, `displayNameFor()`,
   `isAegis()`, `victimLocation()` (reduces the Room status entity to a
   lat/lng pair), and `unlockSOSDrillAchievement()`. With those,
   `SOSCoordinator` moved in (its in-file `SOSHandlerBridge` +
   `SelfLocation` helpers route through the host too — no `:app` edge;
   added `androidx.core:core-ktx` to the module for `ContextCompat`).
   Still in `:app`: `SOSSnapshotStream` (→ CameraX + the mugshot
   `HeadlessLifecycleOwner` + a direct `SimpleXTransport` reach — the
   genuinely heavy one, moves once transport is extracted), and
   `SOSTrigger` (entangled in `core/Models.kt` alongside
   `Message`/`Protocol`/`MessageType`; moves with that data split, not
   alone).
4. **`:core:transport`.** Module created; `ConnectionLog` +
   `InFlightFiles` (trivially-pure leaves) moved in. `SimpleXDbKeyStore`
   moved in (Chad, 2026-06-04): despite the "→ BackupManager" note it
   turned out to be a pure leaf — its only imports are Android Keystore +
   `javax.crypto`, and the BackupManager / SimpleXCore mentions were
   KDoc references, not code edges. One adjustment: `MigrationCandidate.
   wipe()` went `internal → public` because the `:app` caller
   (SimpleXTransport) sits across the new module boundary; the
   constructor + backing `raw` stay internal. `app → :core:transport`
   edge wired. Still in `:app` pending a verified stage: `SimpleXCore`
   (JNI binding to `:simplex-upstream` + `System.loadLibrary` — native
   linkage wants an on-device test), `SimpleXTransport` (→ AegisApp/
   Repository/many tiers, behind a transport host), and the Room
   DAOs/entities (the `@Database` + KSP stay in `:app`, referencing
   entities across the edge — the `:feature:groups` trick).
5. **`:feature:{trusted,emergency,untrusted}`.** Wire the dependency
   graph from the Modules table: trusted → transport+SOS+presence,
   emergency → transport+SOS, untrusted → transport. At this point
   the compiler enforces every boundary in the spec.

## Relationship to other specs

- **SPEC_GROUP_MODULE_ISOLATION.md** — the groups
  module spec. This spec generalizes that pattern to
  all trust tiers.
- **WHY_NO_GROUP_SOS.md** — why groups never access
  safety data. This spec enforces it structurally.
- **SPEC_TRUST_MODEL.md** — defines the tiers. This
  spec containerizes them.
