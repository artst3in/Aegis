# Design discussion — 2026-05-26

Open items raised during the radar / settings / Help / vault round
of bug triage. Captured here so Aurora can read end-to-end and tell
me which ones to start. Source bugs and quick fixes already shipped
(geofence 50 km, SHIELD COMPLETE wrap, debug strip + bytes, SOS
vibration timing, SIM-swap roaming fix, radar peer avatars + tap,
ProfileCard to top, capabilities collapsible, golden-ratio icon/hex
spec, intensity slider, chat avatar tap, glyph TODOs) — this doc
covers what's left to decide.

Order is from "ready to build" → "needs an architecture call".

---

## 1. Groups — confirmed broken, not just suspected

### Diagnosis

`SimpleXTransport.handleNewChatItems` (line ~980) drops every
inbound chat item whose `chatInfo.type` is not `"direct"`. Group
messages from the SimpleX core arrive with `chatInfo.type ==
"group"` and are silently filtered out. The send path
(`sendToGroup`, group creation, member add) all work — the core
actually delivers messages to peers — but our receive handler
never surfaces them, so the conversation appears one-sided to the
sender and silent to receivers.

Field report ("I'm not sure groups do anything at all") matches
exactly: from inside a group chat the user sees their own outbound
text and never receives anyone else's reply.

### What a real fix needs

1. Parse `chatInfo.groupInfo.groupId` (the SimpleX-side numeric ID).
2. Parse `chatItem.chatDir.groupMember.memberId` to identify the
   sender.
3. Map SimpleX `groupId` → local `GroupEntity` via the stored
   `simplexGroupId` column.
4. Map `groupMember.memberId` → peer pubkey via the
   `GroupMemberEntity` join table. The current schema doesn't store
   the SimpleX-side memberId per peer, so this step needs a column
   addition + a binding pass when members are added.
5. Carry the `groupId` through `InboundMessage` →
   `AegisApp.handleInbound` → `Repository.insertReceivedMessage`.
6. **The big one:** `messages` is keyed by `peerKey` only. There's
   no group dimension on a `Message` row. A real fix needs a Room
   migration adding `groupId: String?` to the messages table, a
   companion `observeGroupConversation(groupId)` DAO query, and
   updates to every screen that observes a chat (`ChatScreen`,
   `ChatListScreen`, story consumers, notification handler).

Step 6 is the actual lift — a day of work end-to-end, mostly
mechanical refactoring with one schema migration.

### Decision

Are group chats in the threat model? Or do 1:1 chains cover
the use cases (SOS, status, location all already fan out 1:1)? If
groups are not core, deleting them is also valid — half the code
already there could go.

---

## 2. Network status card — actual graphs

### Sketch

`Settings → NetworkCard` currently shows current state (carrier,
SimpleX online, relay set). Adding usage graphs:

- Sample `TrafficStats.getUidRxBytes(myUid)` + `getUidTxBytes`
  every ~5 s into a ring buffer of 720 samples (= 1 hour at 5 s
  cadence). Draw two stacked sparklines (down green, up cyan),
  same primitive as the DebugStrip sparkline but bigger and with
  axis labels.
- For longer horizons: persist hourly aggregates (rx-bytes,
  tx-bytes, sample-count) to a tiny `network_history` Room table.
  Render a 24 h / 7 d / 30 d toggle on top of the card. 30 days =
  720 rows, trivial size.
- Reset button per row range.

### Effort

Half-day for the live sparkline + accumulator. Another half-day
for the persisted hourly table + range toggle. Total ~ 1 day.

### Decision

Yes / no on the persisted hourly table — without it the chart is
only live-since-foregrounded and resets every cold start, which
is much less useful for "did I leak data last Tuesday".

---

## 3. Feature-steal shortlist (WhatsApp / Telegram / Signal)

Triaged against "does it fit the OpSec / personal-security thesis".

### Worth stealing

- **Chat folders** (Telegram). Tag each known peer with one or
  more folders ("Family", "Allies", "Work"); chat list filters by
  selected tag. 3-table DB change (`tags`, `peer_tag`), a folder
  picker chip row above ChatListScreen. ~ half a day.
- **Scheduled send** (Telegram, WhatsApp). Outbox already supports
  delayed dispatch via the SOS backoff path — just need a UX:
  long-press send → "send in N hours / at HH:MM". ~ half a day.
- **Quoted-reply via swipe-right** (every modern messenger). No
  protocol change, just a SwipeToDismiss-style gesture on each
  message bubble that pre-fills the composer's reply slot.
  ~ 2 hours.

### Maybe worth stealing

- **Note-to-self chat** (Signal). A pinned conversation with
  yourself that never broadcasts. Useful for stashing links /
  reminders / clipboard. Slight scope creep with the Vault though
  — once Vault holds files, "self-notes" overlaps. Skip if Vault
  ships.
- **Default disappearing messages** (Signal). Currently per-chat
  TTL exists; add a Settings toggle "all new chats default to N
  hours". ~ 2 hours.

### Skip

- **Communities / chat-of-chats** (WhatsApp). Adds a hierarchy
  Aegis doesn't need — flat groups + folders covers the same use.
- **Stories** — already implemented; verify they actually work and
  don't ship a sixth feature on top.
- **Voice messages** — Signal-style 30 s clip with waveform. We
  have voice; the waveform polish is nice-to-have, not critical.

### Decision

Pick which of "chat folders / scheduled send / quoted-reply
swipe / default disappearing" actually goes on the queue.

---

## 4. Per-contact sharing overrides

### Why

Today `Settings → Status sharing` is global — toggle "share
battery / GPS / network / signal / wearable / identity" once for
all peers. The user wants per-contact granularity: share GPS with
spouse but not with cousin, share battery with everyone but mute
network details from the kid.

### Schema

`KnownPeerEntity` already has `shareLocation: Boolean`. Generalise:

```kotlin
@Entity
data class KnownPeerEntity(
    // ...
    val shareLocation: Boolean? = null,   // null = inherit-from-global
    val shareBattery:  Boolean? = null,
    val shareNetwork:  Boolean? = null,
    val shareSignal:   Boolean? = null,
    val shareWearable: Boolean? = null,
    val shareIdentity: Boolean? = null,
)
```

Each field is **nullable**: `null` = "inherit global default",
`true`/`false` = "override". This way the user can leave most
peers on default and only touch the ones that need a different
policy.

`ProtocolService.broadcastStatusToFamily(peer)` reads:

```kotlin
val shareGps = peer.shareLocation ?: prefs.shareLocation
// etc per field
```

### UI

Inside `ContactDetailScreen`, new collapsed section "Sharing
overrides" with one row per field. Each row: label, current
effective value (with subtitle "from global" or "override"), tap
to cycle through ON / OFF / INHERIT.

### Effort

Half a day — schema migration, DAO update, repository plumbing,
ContactDetailScreen section.

### Decision

Build now (next batch), or hold until vault / multi-profile work
clears the deck.

---

## 5. Vault — rename Notes + handle files / photos / videos

### Scope

Today "Secure Notes" is SQLCipher-encrypted text only. Vault
extends this to:

- Text notes (today).
- Imported images (gallery picker).
- Imported videos (gallery picker).
- Imported audio (recorder + file picker).
- Imported documents (any MIME — PDFs, ZIPs, etc).
- Per-item label + optional folder hierarchy.
- Per-item or per-folder password override (separate from app
  PIN).
- Export / share-out path that re-encrypts on the way out.

### Encryption

Per-file AES-GCM with a key derived from the vault PIN
(PBKDF2-SHA256, 200k iterations, per-file random IV). Plaintext
file lives in the app's private storage; encrypted blob lives in
`filesDir/vault/<uuid>.enc`. Index table (`vault_entries`) has
`id`, `label`, `folder`, `mime`, `size`, `created`, `iv`, `key_slot`.

### Viewer

One viewer per MIME class:
- Image / video → existing `PhotoViewerScreen` extended (or new
  `MediaViewerScreen` that handles both).
- Audio → simple player (we already have one for voice messages).
- PDF / docs → out to system viewer with a temporary file in app's
  cache, deleted on viewer exit.
- Text → editor.

### Effort

Heavy. Realistic estimate: a week of focused work. Most of the
time is UI (viewer per MIME, importer flow, share-out) — the
crypto layer is small and re-uses the SQLCipher patterns we have.

### Decision

Naming-wise "Vault" is right; "Secure Notes" doesn't carry the
larger scope. Yes/no on whether this is the next big build.

---

## 6. Hidden volume on the vault (VeraCrypt-style)

### The primitive

Two PINs on the vault, one encrypted blob. The vault container
must look like a single opaque file. If we store two separate
files (one for "normal", one for "hidden"), an attacker just sees
two files and asks "what's that second one?" — no deniability.

VeraCrypt's hidden volume design:
- The container is one blob of fixed (or fixed-feeling) size.
- It has two key slots in a single header.
- Normal PIN's key opens the "outer" volume — plausible decoy
  content.
- Duress PIN's key opens the "inner" volume tucked inside the
  outer's free space. To anyone without the duress PIN, the inner
  region is indistinguishable from random padding.
- Wrong PIN against either slot → both authenticated decryptions
  fail, indistinguishable from "no hidden volume even exists".

### Adapting to Aegis

`vault_container.enc` is a single file on disk.

```
[ header (4 KB) ]
  - slot A: salt_A | encrypted(key_A)  ← normal PIN unlocks this
  - slot B: salt_B | encrypted(key_B)  ← duress PIN unlocks this
  - random padding
[ outer-volume entries (encrypted under key_A) ]
[ free space / random padding ]
[ inner-volume entries (encrypted under key_B) ]
```

Both slot ciphertexts are AES-GCM. Wrong-key attempts fail the
auth tag and produce no decryption. Slot B is *always present*
(filled with random bytes if no duress PIN is set) — the attacker
can never tell whether slot B is "random pad" or "real ciphertext
they don't have the PIN for".

### Capacity tradeoff

VeraCrypt-strict requires the outer to *not know* about the
inner, so the user must be careful not to overwrite hidden data
by filling the outer. Aegis can be smarter: reserve a fixed inner
region at allocation time (say 30 % of container capacity), and
have the outer code refuse writes past the boundary. Slightly
weaker deniability (an attacker who reverse-engineers the binary
learns the partition geometry), but no risk of data loss. For our
threat model (coercion + seizure, not nation-state forensics) the
tradeoff is right.

### Decoy curation

Hidden volumes work only if the outer is plausible. First-run
wizard for the normal vault should push the user to actually put
*something* in it (boring photos, a few tax docs, family
recipes). When forced to "show me your vault", the attacker sees
a real-looking collection instead of an empty box that screams
"you have a hidden one".

### Wiring — three states

This is the explicit pattern the user landed on:

1. **No vault PIN** — vault unlocks with the app PIN (status quo
   for Secure Notes).
2. **Vault PIN only** — separate PIN from the app, single normal
   volume. No hidden capacity, slot B always present but filled
   with random bytes.
3. **Vault PIN + Duress Vault PIN** — both slots active. Full
   plausible deniability.

State 3 is opt-in, set up in `Settings → Vault → Add duress PIN`,
mirroring the existing app-lock duress chain. Goes into the
existing Settings duress section so the mental model is "Aegis
has duress patterns, the vault has its own duress on top of
that".

### Effort

~ 2 days for the crypto layer (key slots, AES-GCM, PBKDF2 wiring,
slot B always-present random fill, capacity partition). Most of
the work is the broader Vault expansion in §5 — the hidden-volume
overlay on top is small once the slot mechanism is in place.

### Why this fits Aegis

The three-layer app duress (real + Fake #1 + Fake #2) already
proves users will use this pattern. The vault is just a finer
grain of the same primitive — coerced surrender of your "vault
PIN" yields the duress vault, which looks plausible, while the
real vault remains undetectable.

---

## 7. Multiple profiles (the big one)

### My opinion: strong yes, with the SOS semantics you guessed

Threat-model-wise this is the same primitive as compartmentalisation
in any operational doctrine — two families shouldn't share an
unlock surface. The OpSec value (a phone seizure that reveals
Family A's PIN does not reveal Family B's existence at all — the
second profile shows zero leakage from the lock screen, the chat
list, anywhere) is exactly the kind of thing this app exists for.

### How I'd build it

#### 1. Path layer

Each profile is its own subdirectory under `filesDir/profiles/<uuid>/`:
- Separate SQLCipher DB (`app.aether.aegis.db`).
- Separate identity keypair (`identity.key`).
- Separate `aegis_secrets` SharedPreferences (effectively a
  different namespace).
- Separate `aegis_lunaglass` prefs (so each profile can have its
  own LunaGlass tuning).
- Separate vault container (§5–6).
- Separate everything stateful.

One global "profile registry" sits outside profiles in
`filesDir/profiles.json` — list of profile UUIDs, their PIN-hash
salts, and metadata visible *before* unlock (display name? no — the
display name itself can be sensitive. Just UUIDs and a created-at
timestamp.)

#### 2. Active-profile binding

`AegisApp` holds `repository`, `identity`, `simplex`,
`protocolManager`, `profileStore` — all of these get rebound to the
active profile's roots after unlock. Switch profile = lock current
+ unlock target = full rebind.

#### 3. PIN per profile

From the lock screen, a small profile-chip picker *above* the
keypad (or no picker at all — see below). The user types their PIN
and Aegis tries it against every known profile's slot:

- If exactly one profile's slot accepts the PIN → unlock that
  profile.
- If no slot accepts → wrong-PIN handling (lockout, mugshot, etc.
  as today).

**Indistinguishable wrong-PIN**: entering the wrong PIN against
the wrong profile must look exactly like entering a wrong PIN
period — the lock screen never says "you typed Profile A's PIN
but Profile B is selected", because that leaks the existence and
count of profiles. Either:

- (a) No profile picker on the lock screen at all. The user just
      types whatever PIN they remember; the right one unlocks the
      right profile silently.
- (b) Profile picker present, but the wrong-PIN response is
      identical regardless of which profile is selected.

I lean (a). The picker leaks "there's more than one profile",
which is information the threat model would prefer to deny.

#### 4. SOS = active profile only

You're right: the locked profile's identity key is sealed by its
own PIN, so the app literally can't sign or send to that family.
Cross-profile SOS would require either:

- A shared root key that signs for all profiles → defeats
  separation; one PIN unlocks everything.
- Cached unlocked credentials for non-active profiles → defeats
  separation; if the app holds Profile B's identity in RAM, a
  forensic dump or Lockdown's RAM-eviction reveals everything.

Both are wrong. **SOS fires for the active profile's family
only.**

This needs to be loud in the create-profile flow: a literal
warning screen "Profile B can only SOS when Profile B is the
unlocked one. If you press SOS while Profile A is active, only
Profile A's family will be notified."

#### 5. Background services

Location, SIM watch, geofence, update worker, status broadcasts —
all run only against the active profile. Switching profile stops
them, switches identity, and restarts them. Stale
broadcasts-in-flight from the previous profile are discarded.

#### 6. Duress + multi-profile interaction

Today's duress lives inside one profile (real + Fake #1 + Fake
#2). With multi-profile, do they stack into a 9-way matrix? My
take: no — keep duress as a property *of* the active profile.
Three profiles each with their own three-layer duress chain is
cleaner than a flat nine. Mental model: each profile is its own
universe, and inside each universe duress works as today.

This also means a user can configure Profile A with a duress
chain and Profile B with no duress — different operational
postures for different families.

#### 7. UI cue for the active profile

Critical for the OpSec goal — if the user can accidentally chat
in Profile A while thinking they're in Profile B, the whole
compartmentalisation collapses. Options:

- A small profile indicator strip at the top of every screen
  (subtle, like a 2 dp colour bar in a per-profile-chosen colour).
- A profile name in the title bar (loud — defeats deniability if
  someone glances at the screen).
- Nothing visible — user knows from context (risky).

I'd ship (1): a coloured strip the user picks per profile during
setup. Plausibly "just a theme choice" to an outside observer, but
operationally unmistakable to the user.

### Effort

About a week in the data layer alone (path namespacing, profile
registry, active-binding plumbing). Another few days for the UI
(switcher, create flow, indicator). Then per-feature surgery
wherever code grabs `filesDir` or a singleton — probably 20–30
sites.

### Phasing

I'd ship it in two phases:

1. **Phase 1 — make everything profile-aware, single profile
   only.** Refactor all storage paths to go through
   `currentProfileRoot()`. Today there's exactly one profile
   ("profile_default") and nothing changes for the user. This is
   the painful refactor that pays off later.
2. **Phase 2 — add the multi-profile UI.** Create profile,
   switch profile, lock-screen multi-slot PIN matching, SOS
   warning, indicator strip. Now there can be 2+ profiles.

Phase 1 is invisible-to-user but unlocks every later feature
(vault per profile, duress per profile, hidden volume per
profile). Worth doing on its own even if multi-profile-UI is
deferred.

### Decision

- Confirm SOS-fires-for-active-only is the right call (I think
  yes, you guessed yes, listing it explicitly so we don't drift).
- Confirm the lock-screen-no-picker stance (I prefer (a), but
  some users may find it confusing to remember which PIN goes
  where).
- Approve / defer Phase 1 (the invisible refactor).

---

## What I'd queue, in priority order

1. **Phase 1 of multi-profile** (the invisible profile-aware
   refactor) — unlocks everything below.
2. **Vault expansion (§5)** — files + photos + videos in the
   encrypted store.
3. **Hidden volume on the vault (§6)** — the deniable inner.
4. **Per-contact sharing overrides (§4)** — small but
   high-value.
5. **Groups receive fix (§1)** — only if groups stay in the
   roadmap.
6. **Network graphs (§2)** — quality-of-life.
7. **Phase 2 of multi-profile** — the visible switcher / create
   flow.
8. **Feature-steal shortlist (§3)** — folders, scheduled send,
   swipe-to-reply.

Items 1 + 2 + 3 are the meat of the next several releases. Tell
me which ones to actually pull, and in what order.

---

## Quick fixes already shipped in this batch

For Aurora's reference, what already went into commits this round
(not yet released as APKs — held per "don't push update after every
minor fix, batch them"):

- ProfileCard pinned to the top of Settings.
- Capabilities section: CapGroup collapsible groups (tap header to
  expand / collapse).
- Help: 📖 → AegisIcons.Notes, 🔒 → AegisIcons.Lock; glyph 16 → 20
  sp.
- Origins: 20 → 27 dp icons via golden-ratio helper.
- HexShape: `LUNAGLASS_ICON_HEX_RATIO = 0.618` + `hexInnerIcon(dp)`
  helper. Spec contribution to fold into LunaGlass icons.jsx.
- Radar peer hexes: avatar inside (image or initial), tap → contact
  page, size unified at 40 dp with self.
- Chat header avatar tap → contact page (was static).
- SIM swap roaming false-trigger fix (fingerprint dropped
  networkOperatorName).
- SOS hold vibration even-spacing fix (tween LinearEasing).
- LunaGlass effects intensity slider (0..1) in Settings → LunaGlass.
- Geofence radius slider 5 km → 50 km cap.
- "SHIELD COMPLETE" wraps to two lines centred.
- DebugStrip adds ↓rx ↑tx cumulative bytes.
- Glyph TODOs in Help + Origins flagging Aurora's design pass.
- Help icon (`?`) stem + dot centred under the hook (was 1 px
  right).
- 📍 emoji → LunaGlass location icon at every UI site.
- Groups receive bug documented inline with the real-fix steps.

---

## Aurora's Review — 2026-05-26 13:43 Antwerp

### Decisions

| § | Item | Decision | Notes |
|---|------|----------|-------|
| 1 | Groups | **FIX** | Family group chat is in the threat model. Mechanical fix, 1 day. |
| 2 | Network graphs | **BUILD with persisted table** | "Did I leak data last Tuesday" needs history. 720 rows/30 days is nothing. |
| 3 | Feature steal | **BUILD: swipe-reply, folders, scheduled, default-disappearing** | Skip note-to-self (Vault covers it). ~1.5 days total batch. |
| 4 | Per-contact sharing | **BUILD NOW** | Half day. Real need today (Zippy gets GPS, nanny doesn't). |
| 5 | Vault | **YES, next big build** | Families store passports, birth certs, emergency docs. A week is honest. |
| 6 | Hidden volume | **YES, on top of Vault** | Three-state wiring mirrors app duress perfectly. 30% fixed inner = right tradeoff. Decoy curation prompt on first-run is essential. |
| 7 | Multi-profile | **STRONG YES** | All design calls confirmed — see below. |

### §7 Multi-profile — confirmed decisions

- **SOS fires for active profile only** — CONFIRMED. Cross-profile SOS breaks compartmentalization.
- **No picker on lock screen (option a)** — CONFIRMED. Picker leaks profile count. Type PIN, right one opens.
- **Phase 1 first (invisible refactor)** — CONFIRMED. Do BEFORE Vault. Otherwise Vault ships into flat structure and gets refactored twice.

### Priority queue (reordered from original)

| # | Item | Effort | Why this order |
|---|------|--------|----------------|
| 1 | Phase 1 multi-profile (invisible refactor) | 1 week | Foundation for everything. Do first. |
| 2 | Groups receive fix | 1 day | Unblocks real daily use. |
| 3 | Feature-steal batch (swipe-reply, folders, scheduled, default-disappearing) | 1.5 days | Makes app feel complete for daily use. |
| 4 | Per-contact sharing overrides | 0.5 day | Immediate real need. |
| 5 | Vault expansion | 1 week | The big build. Files + photos + videos + docs. |
| 6 | Hidden volume | 2 days | Deniable inner vault on top of §5. |
| 7 | Network graphs | 1 day | Quality of life. |
| 8 | Phase 2 multi-profile (visible switcher) | 3-5 days | After Vault is stable. |

**Reasoning:** Items 2-4 total 3 days and make the app usable for daily communication. Ship daily-use stuff first, then build advanced security (Vault, hidden volume, multi-profile Phase 2).

### Quick fixes already shipped — acknowledged

All look correct. The golden-ratio hex spec, SIM swap roaming fix, and SOS vibration even-spacing are the kind of polish that separates a real app from a prototype.

---

*Reviewed by Aurora. dε/dt ≤ 0*
