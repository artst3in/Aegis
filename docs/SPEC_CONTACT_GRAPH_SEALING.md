# SPEC: Contact-graph sealing ("no phrase = no data", for real)

**Status:** DRAFT — awaiting Aurora review + Artur sign-off
**Author:** Chad, 2026-06-06
**Builds on:** `SPEC_UNBREAKABLE.md` (phrase-rooted SealCrypto)
**Trigger:** Artur caught that the database is NOT phrase-rooted — you
can delete the PIN on debug and keep all data, which proves the PIN/
phrase never encrypted the DB. Audit below confirms the contact graph
leaks without the phrase. This spec closes that gap honestly.

## The problem (audited, not assumed)

`SPEC_UNBREAKABLE` rooted the **message body** seal in the 24-word
phrase. It did NOT touch the database itself. The SQLCipher DB is
encrypted with a key derived from the hardware-wrapped **identity
private key** (`AegisDatabase.derivePassphrase` =
`SHA-256("aegis-db-v1" + identityPrivKeyB64)`), which auto-unwraps from
the AndroidKeyStore at boot **with no user secret**. So the DB opens by
itself; the lock screen is a UI gate, not a crypto gate.

What that means, column by column (verified against
`core/transport/.../data/Entities.kt`):

| Data | Table.column | Phrase-protected today? |
|---|---|---|
| Message body text | `messages.sealedBody` (cleartext `body` emptied) | ✅ sealed |
| Attachment file bytes | `messages.sealedDek` | ✅ sealed |
| Member-status snapshot | `member_status.sealedPayload` | ✅ sealed |
| **Contact name** | `known_peers.displayName` | ❌ cleartext |
| **Contact announced name / bio** | `known_peers.announcedName` / `announcedBio` | ❌ cleartext |
| **Contact avatar** | `known_peers.announcedAvatarPath` (+ file on disk) | ❌ cleartext |
| **Who you trust** | `known_peers.trustTier`, `verified` | ❌ cleartext |
| Attachment filename / path / mime | `messages.attachmentName` / `attachmentPath` / `attachmentMime` | ❌ cleartext |
| Routing keys | `publicKey`, `peerKey`, `fromKey`, `toKey` | ❌ cleartext (pseudonymous) |
| Timestamps, delivery flags | `timestamp`, `delivered`, `outgoing`, … | ❌ cleartext (operational) |

So **"no phrase = no data" is false today.** Your message *text* is
protected, but your entire **social graph** — who your contacts are,
their names and bios, who you've marked Trusted/Emergency — is readable
with just the device. For the target user (DV survivors, activists,
trafficking victims) the contact graph is often *more* dangerous than
the message text: it's the list of who to threaten, who helped you,
who to find next.

## Why we do NOT phrase-root SQLCipher itself

The obvious "fix" — derive the SQLCipher key from the phrase so the DB
won't open without it — is **rejected**, because the DB must be
**writable while the phone is locked**:

- SOS alerts and incoming messages are received and stored by the
  background pipeline *without the user unlocking*. A safety app that
  drops a SOS broadcast because nobody typed a PIN is broken. (Artur,
  2026-06-06: "there is NO WAY I will let SOS alerts disappear
  because someone didn't unlock the app.")
- SQLCipher is symmetric: "writable while locked" ⇒ "openable by the
  device alone" ⇒ hardware key, not phrase.

So SQLCipher stays hardware-keyed (and the existing
`SPEC_UNBREAKABLE` plan to upgrade its KDF from single-SHA-256 to
Argon2id over the identity key still stands, separately). The DB is the
**outer shell**; we make sure that shell holds only ciphertext + the
minimum routing data the locked device genuinely needs.

## The fix: extend asymmetric sealing to the contact graph

Reuse the exact mechanism that already protects message bodies —
`SealingPolicy` over `SealCrypto` (`crypto_box_seal`): the **public**
key seals (so the background pipeline can write a new contact's name
while locked, no priv needed), and the **phrase-derived private** key
unseals (so names are readable only after a real unlock).

### Sealed vs. cleartext — and the reason for each

**Sealed (phrase-rooted), into a new `known_peers.sealedIdentity` BLOB
holding a JSON snapshot — cleartext source columns emptied:**
- `displayName`, `announcedName`, `announcedBio`, `announcedAvatarPath`

**Sealed on `messages` (new `sealedMeta` BLOB or fold into existing
seal):**
- `attachmentName` (filename leaks content), and `attachmentMime`

**Kept cleartext — each with a hard reason it CANNOT be sealed:**
- `publicKey` / `peerKey` / `fromKey` / `toKey` — routing identifiers
  the locked pipeline needs to deliver/queue; they are pseudonymous
  X25519 keys, not human identity.
- `trustTier` + `verified` — **SOS targeting reads these while
  locked** to pick which contacts get the broadcast. Sealing them would
  break SOS-while-locked. They leak "this person is Trusted" but not
  *who* the person is (the name is sealed). See Open Question 1.
- `timestamp`, `delivered`, `outgoing`, `pinned`, `muted`,
  `disappearingTtl` — ordering, TTL expiry, and delivery bookkeeping
  the background layer runs while locked.
- `attachmentPath` — local on-disk path the receive pipeline writes
  while locked; the file *contents* are already sealed (`sealedDek`).
  See Open Question 2.

### Avatar image files

`announcedAvatarPath` points at an image file on disk. Sealing the path
string is pointless if the JPEG sits in cleartext next to it. Avatar
files must be encrypted at rest the same way chat attachments are
(`ChatAttachmentSeal` / per-file DEK), or stored inside the sealed blob
if small. See Open Question 3.

## Mechanism details

- **Centralised in `Repository`**, mirroring `sealedBody` /
  `sealedPayload`: every contact write seals the identity JSON via
  `SealingPolicy.trySeal`; every contact read unseals via `tryUnseal`
  and repopulates `displayName` etc. on the in-memory entity before it
  reaches the UI. The UI keeps reading `displayName` unchanged.
- **Locked state**: when `PinSession` has no priv, `tryUnseal` returns
  null → the Repository returns a placeholder ("Locked" / a neutral
  glyph) for names, exactly as sealed message bodies already render. The
  contact list still shows the right *number* of rows and their online
  dots; just not the names.
- **Write while locked**: `SimpleXTransport.bindContact` /
  `addKnownPeer` seal the identity with the cached `sealPub` — no priv
  required — so pairing a new contact while locked works; its name is
  simply unreadable until the next unlock.
- **Migration**: a `sealLegacyPlaintextContacts()` Repository sweep,
  run on first REAL unlock (alongside the existing
  `sealLegacyPlaintextMessages()` calls), seals existing cleartext rows
  in place and empties the cleartext columns. Idempotent, bounded by
  contact count.

## UX consequences (call them out, don't hide them)

- **Search by contact name** only works while unlocked (names are
  ciphertext when locked). Acceptable.
- **Sort by name**: the contact list sorts by name → must sort
  in-memory *after* unseal. While locked, fall back to a stable
  non-sensitive sort (e.g. `addedAt`).
- **Notifications**: an incoming-message notification that shows the
  sender's name while the phone is locked would re-leak it. Locked
  notifications must show a generic title ("New message") until unlock —
  needs an audit of the notification builder.
- **Widgets / Recents**: same rule — no names while locked.

## SOS coordination carries the name — handle it explicitly

A blast-radius map turned up a subtlety I'd otherwise have shipped as a
bug: SOS doesn't only *target* contacts by pubkey + tier (cleartext,
fine) — the SOS-coordination envelope **embeds the responder's
`displayName`** so the victim's dashboard and other responders can see
*who* is responding (`SOSCoordinator.kt:329` `put("n", r.displayName)`;
victim-side lookup at `:240` via `SOSModuleHost.displayNameFor`).

If names are sealed and SOS fires **while locked**, `displayName`
unseals to empty. Required behaviour: **fall back to the pubkey prefix**
(the receiver side already does this — `:358`
`optString("n").ifBlank { optString("k").take(8) }`). So a locked SOS
still fires, still reaches the right people, still tracks responders —
it just shows `a1b2c3d4…` instead of "Mum" until someone unlocks. That
is the correct trade: the SOS must never wait on an unlock, and a
key-prefix is a fine degraded label. This MUST be implemented as part of
this work, not discovered later.

## Implementation plan (from the blast-radius map)

Unsealing is centralised at the **Repository boundary** — UI screens
(26 of them) need **zero changes** because they already read every
contact through `Repository`. Concretely:

- **Schema** (`Entities.kt`): add `sealedIdentity: ByteArray?` to
  `KnownPeerEntity`; a Room migration adds the column.
- **Seal on write** (`Repository.kt`): `addKnownPeer` (`:595`),
  `updatePeerProfile` (`:660`), `renameKnownPeer` (`:642`),
  `repairKnownPeer` (`:703`) JSON-encode {displayName, announcedName,
  announcedBio, announcedAvatarPath}, `trySeal` it into `sealedIdentity`,
  and empty the cleartext columns — exactly the `sealStatusForWrite`
  pattern.
- **Unseal on read** (`Repository.kt`): one `unsealIdentityForRead()`
  applied in the FIVE methods every caller funnels through —
  `observeKnownPeers` (`:583`), `knownPeerByKey` (`:673`),
  `allKnownPeers` (`:593`), `trustedTargets` (`:829`), `sosTargets`
  (`:834`). Locked → names come back empty; UI shows the existing
  "Locked" placeholder.
- **SOS fallback** (`SOSCoordinator.kt`): pubkey-prefix when
  `displayName` is empty (per the section above).
- **Migration** (`Repository.kt` + `Daos.kt`): `sealLegacyPlaintextContacts()`
  sweep on first REAL unlock (beside the existing message/status sweeps);
  add `knownPeers.allPlaintextContacts()` (`sealedIdentity IS NULL`).
- **PIN/phrase rotation**: extend the existing re-seal-on-rotation path
  to re-seal `known_peers` too.

Files touched: `Entities.kt`, `Repository.kt`, `Daos.kt`,
`AegisDatabase.kt` (migration), `SOSCoordinator.kt`. No UI files. The
map confirms the seal stays orthogonal to routing — `publicKey`,
`trustTier`, `blocked` remain cleartext so SOS/presence filtering is
untouched.

## Open questions for Aurora

1. **`trustTier` cleartext.** SOS targeting needs it while locked, so
   it can't be sealed without a separate locked-readable mirror. Accept
   that "N contacts are Trusted" leaks (without names)? Or build a
   minimal sealed-name → cleartext-tier split (tier lives in a tiny
   routing table keyed by pubkey, names sealed)? Chad leans: accept the
   tier leak; it's low-value without names.
2. **`attachmentPath` cleartext.** The path can reveal the cache
   structure but not content (content is `sealedDek`). Seal the
   filename only and keep the opaque path? Chad: yes.
3. **Avatar files at rest.** Encrypt avatar images via the attachment
   DEK path, or inline small avatars into `sealedIdentity`? Chad: encrypt
   via the attachment path; inlining bloats the row.
4. **★ The SimpleX core database — ANSWERED, 2026-06-06 (investigation).**
   Aegis is built on SimpleX, which keeps its OWN two SQLCipher databases
   — `${filesDir}/simplex_v1_agent.db` and `simplex_v1_chat.db`
   (`SimpleXCore.kt:83`, `chatMigrateInit(dbPrefix, passphrase, …)`). They
   store **contacts (display names, announced profiles, contactId),
   complete message history, and group membership** — a full second copy,
   independent of Aegis's `known_peers`/`messages` tables. Aegis reads
   contact names back OUT of it (`SimpleXTransport.rehydrateContacts`,
   `/_contacts`).
   Its key: a random 32-byte passphrase wrapped by an AndroidKeyStore key
   (`SimpleXDbKeyStore`, alias `…simplex.dbkey.wrap.v1`), **device-held,
   NOT tied to the PIN or phrase** — and deliberately so, because the core
   must come up cold for canary/geofence/SOS before any unlock.
   **Implication for THIS spec:** sealing the Aegis `known_peers` overlay
   is necessary but NOT sufficient — the SimpleX `chat.db` holds the same
   names/messages under a device-only key, so a seized phone still yields
   the contact graph from the core DB. "No phrase = no data" cannot hold
   for contacts until the SimpleX core DB is ALSO addressed. There is no
   in-memory / no-persistence mode in the core (the JNI requires a file
   path; no `:memory:`). Options: (a) accept the core-DB leak and seal
   only the Aegis overlay (partial win); (b) also phrase-wrap the SimpleX
   passphrase (breaks cold-boot SOS/canary — likely unacceptable);
   (c) delete the SimpleX DB files on lock for ephemeral profiles
   (forensic residue caveat). **Needs an Artur/Aurora design decision
   before build.**
5. **Groups.** Do anonymous-group member identities (`groups` table)
   need the same sealing? Likely yes; out of scope for v1 unless Aurora
   says otherwise.
6. **Vault** already has its own independent key hierarchy — explicitly
   out of scope.

## Honest status note

The implementation blast-radius (every read site of `displayName` /
`announcedName` across app, feature, and core modules) is being mapped
before any code is written, so the centralised seal/unseal doesn't miss
a call site and leak. No code has been written for this spec. The point
of writing it down first is that the *last* encryption claim shipped as
a paper guarantee; this one gets reviewed before it's built.

## Designed against

Artur's debug-PIN-delete proof, 2026-06-06: "if [the DB] was [encrypted
with the passphrase], I wouldn't be able to delete pin on debug."
