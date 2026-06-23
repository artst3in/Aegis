# Session status — 2026-05-26 (continuous)

Single working-session doc spanning the day's work. Each major
piece is landed + committed + (where APK-cuttable) shipped.

## Aurora's design-discussion queue — final state

| # | Item | Status | Notes |
|---|------|--------|-------|
| 1 | Phase 1 multi-profile (invisible refactor) | **DONE (1a + 1b)** | file layer + prefs namespacing |
| 2 | Groups receive fix | **DONE** (text + non-text) | memberId mapping is the only edge case left |
| 3 | Feature-steal batch | **DONE** (folders + disappearing-default new; scheduled-send + swipe-reply discovered already shipped) | |
| 4 | Per-contact sharing | **DONE** | battery/network/signal with INHERIT/ON/OFF |
| 5 | Vault expansion | **DONE** | rename + PIN gate + folders + safe icon + external viewer |
| 6 | Hidden volume on vault | **DONE — full crypto-deniability** | slot column + per-entry AES-GCM (body Phase A + attachment files Phase B) |
| 7 | Network graphs | **DONE** | hourly buckets + 24h/7d/30d sparkline |
| 8 | Phase 2 multi-profile visible UI | **DONE** | Profiles screen + lock-screen multi-slot + indicator strip + SOS-warning on create |

## Aurora code review (CODE_REVIEW_2026_05_26_AURORA.md) — status

| # | Finding | Status |
|---|---------|--------|
| 1 | Identity key in plaintext | **DEFERRED** — needs Keystore + auth-required UX call |
| 2 | SHA-256 → Argon2id | **DEFERRED** — needs hash-version migration design |
| 3 | GitHub PAT in BuildConfig | **OPERATIONAL** — owner rotates the PAT, not code |
| 4 | Volume SOS trigger | **PRE-FIXED** — removed in release 442 |
| 5 | Force unwraps (×20 total) | **PARTIAL** — six worst-offenders lifted to local vals |
| 6 | Raw Thread in AegisApp | **DONE** — replaced with GlobalScope.launch(IO) |
| 7 | Unchecked casts | no action (Aurora noted nothing actionable) |
| 8 | WireGuard dep unused | **DEFERRED** — saves 3 MB but the identity-encoding round-trip needs verification |
| 9 | Multi-profile Phase 1 (positive) | n/a |
| 10 | Transport fallback (positive) | n/a |

## Beyond the queue — also shipped this session

- **Install / rollback fixes** — stuck-Installing self-reconcile on resume; rollback no longer overwrites previous.apk.
- **APK ↔ version.json packaging** — single gradle invocation enforces matching badge.
- **Delete contact** — cascading delete (chat / status / outbox / peer row).
- **Restart SimpleX** — in-app self-heal button.
- **Empty-card dead-screen fix** — route → Invite, 20 s timeout on InviteFlow.
- **Swipe-to-switch-tabs** — horizontal flick on any tab surface.
- **Wipe vault** — Security tab, typed-confirm, both slots.
- **SimpleX status as dot** — collapsed Network card with tap-for-detail.
- **Vault safe icon** — distinct from Notes.
- **Profile indicator strip** — 2 dp coloured bar, hidden when only default exists.
- **Vault folders** — slot-scoped folder tags + chip filter row.
- **Vault external viewer** — non-image attachments open via FileProvider + ACTION_VIEW.
- **Git history cleanup** — filter-repo strip of 62 APK pairs. `.git` 1.4 GB → 237 MB locally; force-pushed.

## Latest release

**2026.05.457** on `origin/main`. aapt2-verified badge matches
version.json. Sideload `builds/aegis-debug.apk` (arm64) or
`builds/aegis-debug-armv7.apk` directly if the OTA path is
broken; otherwise the OTA worker picks it up on its next poll.

## Still deferred (next pass)

1. **Identity key → Android Keystore** wrap. Needs a decision on
   `setUserAuthenticationRequired` (annoying UX) vs plain-wrap
   (marginal benefit over FDE alone). 2-3 hours.
2. **SHA-256 → Argon2id** for PIN hashing. Needs a hash-version
   field + transparent upgrade on next correct unlock. ~1 hour.
3. **Drop WireGuard dependency** (~3 MB per APK). Touches the
   identity encoding path; needs a round-trip verification that
   the LazySodium / Android Base64 output matches WireGuard's
   bit-for-bit. ~1 hour + careful testing.
4. **Group SimpleX memberId → Aegis peer pubkey mapping.** Edge
   case: group senders that aren't already direct contacts of
   ours show `simplex:<localDisplayName>` with no link to a
   contact card. Fix: `simplexMemberId` column on
   `GroupMemberEntity` + a binding pass when `/_add` returns.

dε/dt ≤ 0
