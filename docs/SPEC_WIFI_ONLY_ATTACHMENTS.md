# SPEC: Wi-Fi-only attachment downloads

Status: REVIEWED — Aurora approved with additions (trust gate; cold-restart concern)
Author: Chad

## Problem

Attachments auto-download the instant they arrive (`/freceive` fired on the
file invitation, `SimpleXTransport.kt:4335`). For the target user that's an
attack surface: a hostile contact can **flood videos to burn through the
victim's mobile-data plan** (or rack up overage charges) with no consent.

We want a "download attachments over Wi-Fi only" control: on a metered
(cellular) connection, don't auto-pull — show the file as a tap-to-download
placeholder, and let the user pull it deliberately.

## Decisions (locked with user)

- **Wi-Fi gate:** on mobile (metered), defer **ALL** attachments — even a
  small image needs a tap. No size threshold on the *mobile* path.
- **Type + size gates (ALL networks, incl. Wi-Fi):** auto-download only
  applies to user-selected *types*, and only below a user-set *size cap* —
  so a 1 GB video doesn't auto-pull even on Wi-Fi.
- **Default:** the Wi-Fi-only toggle isn't hardcoded — **asked during the
  onboarding tutorial**. Until answered, default ON (protect). Type/size
  defaults ship sensible (below) and live in Settings.
- **Metered test:** transport-based (Wi-Fi/Ethernet = unmetered), the SAME
  rule the update gates now use — factor it into one shared helper.

## Auto-download decision

A file auto-pulls (fires `/freceive` on arrival) only if ALL hold; else it
becomes a tap-to-download placeholder:

```
autoDownload(file) =
    contact.trustTier != UNTRUSTED       // trust gate — untrusted = always tap
    && !(metered && wifiOnly)            // network gate — defer all on mobile
    && file.mcType in autoTypes          // type gate  (applies on every network)
    && file.size <= maxAutoBytes         // size gate  (applies on every network)
```

- **Trust gate** — an Untrusted contact's attachments NEVER auto-download,
  regardless of network, type, or size. Always tap-to-download. Emergency
  and Trusted contacts auto-download normally. The placeholder bubble for
  Untrusted files should show the sender's trust tier so the user knows
  why it didn't auto-pull.

- **autoTypes** — a set over SimpleX `mcType`: Images / Videos / Voice notes
  / Files. Default ON: **Images + Voice**. Default OFF (tap): **Videos +
  Files** (the big stuff).
- **maxAutoBytes** — a **logarithmic** size slider, because file sizes span
  KB→GB. Position `p∈[0,1]` → `bytes = round(MIN * (MAX/MIN)^p)`, with
  `MIN = 256 KB`, `MAX = 2 GB`, and the far-right stop = **Unlimited** (no
  cap). Default **~25 MB**. Label renders human-readable (KB/MB/GB / "∞").


## The structural catch

A file message **skips** the normal message-emit path
(`SimpleXTransport.kt:4381 continue`). The chat row for an attachment is
created only when the download COMPLETES (`handleRcvFileComplete`, 4892).
So deferral can't be "just skip `/freceive`" — there'd be no bubble at all,
and the user wouldn't even know a file was sent.

## Design

1. **Prefs** — `AttachmentPrefs` (SharedPreferences):
   - `wifiOnly: Boolean` + `tutorialAsked: Boolean` (default `wifiOnly =
     true` until the tutorial sets it);
   - `autoTypes: Set<MediaType>` (default {Image, Voice});
   - `maxAutoBytes: Long` (default ~25 MB; `Long.MAX_VALUE` = Unlimited).
   Each exposed as a `StateFlow` so Settings + the gate observe live.

2. **Shared metered helper** — `NetworkMetering.isMetered(context)` (the
   transport-based check). Migrate the three update gates
   (AutoUpdateCheck / UpdateCheckWorker / SettingsScreen) onto it too, so
   there's ONE definition.

3. **Gate at the invitation** (`SimpleXTransport.kt:4335`): evaluate
   `autoDownload(file)` (network ∧ type ∧ size, above). If it returns
   false →
   - do NOT `/freceive`;
   - synthesize a **placeholder row** now, reusing the routing/attribution
     already in scope (fileId, fileName, fileSize, mcType, groupKey,
     contactName): record a received attachment with `attachmentPath = null`
     and the known name/size/mime;
   - persist `sourceItemId → fileId` in a `DeferredDownloads` store
     (SharedPreferences) so a tap can pull it even after a restart, until
     the invitation expires.

4. **Completion updates the placeholder** — `handleRcvFileComplete` must
   match an existing placeholder (by `sourceItemId`) and UPDATE its path
   rather than insert a duplicate; then drop the `DeferredDownloads` entry.
   (Confirm whether `recordReceivedAttachment` already upserts by
   sourceItemId; if not, add that.)

5. **Bubble affordance** — for a file message with `attachmentPath == null`,
   not in `InFlightFiles`, and present in `DeferredDownloads`: render
   "⬇ Download · <size>" instead of a thumbnail. Tap →
   `transport.receiveDeferredFile(fileId)` (a thin wrapper over `/freceive`)
   → normal completion flow → bubble swaps to the media.

6. **Settings — "Attachment downloads" section**:
   - "Download over Wi-Fi only" toggle;
   - "Auto-download types" — Images / Videos / Voice / Files checkboxes;
   - "Max auto-download size" — logarithmic slider (256 KB … 2 GB …
     Unlimited), live human-readable label.
   A deferred file ALWAYS overrides these on explicit tap (user intent
   wins).

7. **Tutorial step** — onboarding asks the user; writes
   `AttachmentPrefs.wifiOnly` + `tutorialAsked = true`.

## Edge cases

- Invitation expired before the tap → `/freceive` fails → bubble shows
  "Unavailable (expired)"; clear the deferred entry.
- Toggle flipped OFF while deferred files exist → leave them as tap-to-pull
  (don't auto-flood retroactively); new arrivals auto-download.
- Manual "save to Downloads" path must no-op on a not-yet-downloaded file.
- Group + 1:1 both covered (the placeholder reuses both routing branches).

## Test plan

- Pure: `NetworkMetering.isMetered` for WIFI / CELLULAR / ETHERNET / null.
- `AttachmentPrefs` default + tutorial write.
- Deferred-store round-trip (set on invite, cleared on complete).
- Instrumented (or manual): metered + ON → placeholder, no bytes pulled;
  tap → completes → bubble updates, no duplicate row; un-metered → auto.

## Risk

Touches the file-receive path, which carries a long history of subtle
fixes (relay approval, encrypt=off at-rest sealing, group attribution).
Implement behind the pref, default-safe, with the placeholder/upsert logic
covered by tests before wiring the gate.


## Aurora review — June 11, 2026

**Status: APPROVED** (with trust gate added above + one concern below)

The three-gate design (network + type + size) is correct — no single gate
covers all cases. Trust gate added as the fourth and first check.

The structural catch is the spec's strongest insight: file messages skip
the normal message-emit path, so blocking the download would make files
invisible. The placeholder-then-update pattern solves this without touching
the happy path.

Good decisions: default ON (protect first), logarithmic size slider (linear
is useless across KB to GB), shared metered helper consolidating the three
update gates into one (debt repayment baked into the feature), tutorial
asks instead of assuming. Edge cases covered: expired invitations, toggle
changes mid-flight, groups.

**One concern — cold restart survival:** The DeferredDownloads store is
SharedPreferences, so the fileId survives process death. But does the
SimpleX-side invitation survive? If SimpleX's internal state is lost on
cold restart, the stored fileId might point to nothing and the freceive
command would fail silently. Worth confirming whether freceive works after
a cold restart for an invitation received in a previous process. If not,
the placeholder should degrade to "Unavailable" on failed freceive, same
as the expired-invitation edge case.
