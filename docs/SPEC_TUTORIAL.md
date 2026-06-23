# SPEC: Onboarding Tutorial

**Status:** IMPLEMENTED — Chad 2026-06-03. `TutorialScreen.kt` (9-page
HorizontalPager), gated via `firstRunNextDestination`, replay entry
in HelpScreen. Compiles clean; release pending the signing keystore
(absent on the build container).
**Owner:** Chad (implementation), Aurora (spec)

## Implementation notes (as-built)

- **Tab tour added — page 6 (Artur, 2026-06-03):** the original 8
  pages explained *what Aegis is* but never showed *how to use it*,
  so it read as an intro rather than a tutorial. New page 6 "Find
  your way around" lists the five bottom-nav tabs with their REAL nav
  icons (pulled straight from `R.drawable.ic_aegis_*` + the `nav_*`
  string labels, so it can't drift from `AegisBottomNav`) and a
  one-line description of each. Page count is now 9; PIN/Ready/Thank
  you shifted to pages 7/8/9.
- **"Get Started" no longer skips Thank you (Artur, 2026-06-03):**
  page 8's "Get Started" button used to exit the tutorial directly,
  jumping past page 9 — so Cyan's "Stay safe." closer was skipped on
  the most common path. It now ADVANCES to the Thank-you page; the
  hand-off into the app happens from that page's "Enter Aegis" button.
- **No payment button in onboarding (Artur, 2026-06-03):** asking a
  brand-new user to pay before they've used the app reads as rude.
  Page 9's donation button + "Maybe later" were removed; the page now
  just thanks the user and *points* to support ("…from System →
  About"). The actual ask moved to the About card — see below.
- **Pose-palette trim (Artur, 2026-06-03):** the flow used six
  distinct Cyan variants, which was too busy. Trimmed to THREE,
  biased toward finger-up. The per-page pose lines below are
  superseded by the asset table at the foot of this spec.
- **Full-body Cyan + asset cleanup (Artur, 2026-06-03):** Cyan was
  being hex-cropped, which clipped her gestures and looked different
  on every page; several source arts also had distracting background
  hexagons. Fix:
    1. The navy background + decorative hexagons were stripped to
       transparency on the three clean sticker assets (cyan_onboard_2,
       cyan_sitting, cyan_celebrate) via a one-off Pillow flood-fill.
    2. `CyanPose` now renders the WHOLE figure (`ContentScale.Fit`) on
       that transparency — no hex frame, no crop — so the same clean
       sticker reads identically across the tutorial, About, and the
       empty state. The hex frame is kept only for the tier-up badge.
    3. Onboard1/3/4 are NOT used here — they have hex frames + glowing
       panels baked into the artwork (the root of the inconsistency).
- **Splash reverted to the Aegis logo (Artur, 2026-06-03):** the
  splash showed Cyan; it now shows `ic_aegis_foreground` again. The
  brand mark belongs on the splash, not the mascot.
- **Proper speech bubble (Artur, 2026-06-03):** `CyanSpeechBubble`
  grew a triangular tail (`BubbleTail.Up`/`Down`) that points at Cyan
  — up in the tutorial/empty state (she's above the bubble), down on
  the About card (she's below it). It now reads as a real 💬. Also
  given a soft cyan border (body + the tail's slanted edges): the
  near-black fill was invisible on the dark background, so the border
  is what defines the bubble.
- **Presence principle (Artur, 2026-06-03):** full-body Cyan is for
  the TUTORIAL only (she's fully present, teaching). In-app surfaces
  show a compact waist-up (or face) Cyan — she's a reminder, "not
  fully present." So the About card's support spot and the contacts
  empty-state ("add a contact here") use the waist-up finger-up
  sticker (Onboard2); the tutorial keeps the full-body poses.
- **Cyan recolour + cube removal (Artur, 2026-06-03):** her hair and
  eyes were blue; recoloured to a uniform LunaGlass cyan (#00FFFF)
  across ALL eight character assets (reprocessed from the original
  blobs in one consistent Pillow pass: hue→180°, vivid saturation
  floor, value lifted+compressed so darker source shades land in the
  same bright-cyan family rather than reading teal). The stray
  floating cube next to the finger-up pose (Celebrate) was deleted
  (keep-largest-opaque-blob flood fill).
- **Support ask is gentle, on About (Artur, 2026-06-03):** the About
  card's support affordance is Cyan at the very bottom (smaller,
  full-body) under a "Support Project Aether" bubble; tapping the
  block opens the Revolut link. Onboarding only points here.

- **Page 1 reframed (Artur, 2026-06-03):** Cyan now opens with
  "Welcome to **Project Aether**. I'm Cyan — I'll be your guide."
  instead of "Welcome to Aegis." — establishing the umbrella project
  and introducing the mascot before any feature talk. The Aegis
  wordmark + "Part of Project Aether" sits below the bubble.
- **Pose → asset mapping:** the spec's pose names map onto the 8
  existing `cyan_*` WebPs (no shh/wink asset exists, so the PIN page
  uses the headshot as a focused close-up). Shield pages (3, 7) turn
  the hex halo ON.
- **Signature:** Cyan's "— Stay safe." sign-off renders ONLY on the
  final page (page 8), not under all eight bubbles — per
  SPEC_CYAN_MASCOT.md it's "her last word".
- **PIN page (6):** embeds an inline first-time PIN set against the
  live `LockStore` (same path as Settings → App Lock); shows "PIN
  already set ✓" on replay. There was no standalone `PinSetupFlow`
  composable to reuse, so the minimal first-time flow is inlined.
- **First-launch gating:** only auto-shows for a genuinely fresh
  install (not yet onboarded AND `tutorial_completed` unset), so
  existing users updating into the build never get it shoved in
  front of them. Replay from Help always works.

## Requirements

- **Skippable** — "Skip" button visible on every page. One tap exits.
- **Replayable** — accessible from Help screen at any time.
- **First launch only** — auto-shows on first app open. Never again
  unless replayed from Help.
- **Cyan-guided** — Cyan mascot at the bottom of each page with a
  speech bubble delivering the message. She's the narrator.

## Structure

Horizontal pager (swipe left/right). 9 pages. Progress dots at bottom.
Skip button top-right. No forced flow — user can swipe freely
or skip at any point.

## Pages

### Page 1: Welcome

Cyan pose: **wave** (casual, no halo)
Background: dark with faint hex grid
Speech bubble: "Welcome to Project Aether. I'm Cyan — I'll be your
guide." (Artur 2026-06-03: lead with the umbrella project + introduce
the mascot, not "Welcome to Aegis.")
Below bubble: Aegis wordmark + "Part of Project Aether" +
"Encrypted communication + personal security"

No action required. Swipe to continue.

### Page 2: What makes this different

Cyan pose: **pointing up** (no halo)
Speech bubble: "No phone number. No account. No server stores your messages. Just cryptography."

Simple text below:
- SimpleX protocol
- Zero metadata
- End-to-end encrypted

### Page 3: Your safety net

Cyan pose: **shield deployed** (hex halo ON)
Speech bubble: "If you're ever in danger, one button alerts everyone you trust."

Simple text below:
- Hold SOS button 3 seconds — or power button ×4
- GPS, audio, camera broadcast to your contacts
- Works from lock screen

### Page 4: Three tiers of trust

Cyan pose: **sitting** (no halo, relaxed)
Speech bubble: "You decide who sees what."

Visual: three cards stacked vertically:

| Tier | What they see |
|---|---|
| Trusted | Everything — location, presence, SOS |
| Emergency | SOS alerts only — no daily data |
| Untrusted | Messages only — nothing else |

### Page 5: The skill tree

Cyan pose: **thumbs up** (no halo)
Speech bubble: "Harden your phone one step at a time."

Visual: simplified skill tree graphic (just 3-4 nodes shown):
App PIN → Duress PIN → Vault → Canary
"Each node unlocks a new layer of protection."

### Page 6: Find your way around (tab tour)

Cyan pose: **pointing** (no halo) — she's pointing the tabs out
Speech bubble: "Five tabs run along the bottom. Here's what each one does."

Visual: the five bottom-nav tabs, in shipped left→right order, each
row = the REAL nav icon + label + one-liner. Labels/icons come from
the same `nav_*` / `ic_aegis_*` resources `AegisBottomNav` uses, so
the tour can't drift from the bar:

| Tab | What's there |
|---|---|
| System | Settings, your identity, app lock, and updates. |
| Opsec | Your security skill tree — harden the phone step by step. |
| SOS (red, centre) | The big one. Hold to alert everyone you trust. |
| Comms | Your encrypted chats. Message your contacts here. |
| Radar | Live map — where your trusted contacts are, and who's online. |

Footnote: "SOS stays locked in the centre. You can reorder the
rest in System → Nav."

No action required. Swipe to continue.

### Page 7: Set your PIN

Cyan pose: **shh/wink** (no halo)
Speech bubble: "First things first. Set your app PIN."

Action: PIN setup flow launches inline. This is the only
page with a required action — user must set the PIN to
continue (or skip the entire tutorial).

After PIN is set, the page shows a checkmark and auto-
advances.

### Page 8: Ready

Cyan pose: **shield deployed** (hex halo ON)
Speech bubble: "You're ready. Add a contact to get started."

Button: "Get Started" → ADVANCES to the Thank-you page (page 9). It
must not exit here, or it skips Cyan's closer + the support ask. The
actual hand-off into the app happens from page 9's buttons.

### Page 9: Thank you

Cyan pose: **wave** (no halo, warm)
Speech bubble: "Thank you for choosing Aegis." + "— Stay safe." closer

Below bubble:
"Aegis is free forever. No ads. No tracking. No data sold.
If it ever helps you, you can support Project Aether any time from
System → About."

Button: "Enter Aegis" → dismisses into the app.

Below: "You can replay this tutorial from Help at any time."

**No donation button here** (Artur 2026-06-03) — the support ask
lives on the About card, not in onboarding. This page only points to
it.

## Implementation

### Composable

`TutorialScreen` — a `HorizontalPager` with 9 pages.

Each page is a `TutorialPage` composable:

    @Composable
    fun TutorialPage(
        cyanImage: Int,         // R.drawable.cyan_*
        speechText: String,
        content: @Composable () -> Unit = {},
    )

Layout per page:
- Top 60%: content area (text, visuals, or PIN flow)
- Bottom 40%: Cyan image + speech bubble
- Speech bubble: rounded rect with cyan border, small
  triangle pointing down toward Cyan

### Navigation

- First launch: `TutorialScreen` shown before main content.
  After completion or skip, `SharedPreferences` flag
  `tutorial_completed` set to true.
- Replay: Help screen entry "Replay Tutorial" → resets the
  pager to page 1. Does NOT reset the SharedPreferences flag
  (tutorial still counts as completed for first-launch logic).
- Skip: sets `tutorial_completed = true` and navigates to
  main screen immediately.

### PIN page integration

Page 7 embeds the inline first-time PIN set against the live
`LockStore`. If the user already has a PIN (replaying the tutorial),
page 7 shows "PIN already set ✓" with Cyan's speech bubble saying
"Already done. Nice."

### Assets needed

Uses TWO clean full-body sticker assets from SPEC_CYAN_MASCOT.md —
standing (Sitting) and finger-up (Celebrate) — rendered full-body on
a transparent background (no hex frame) for one consistent look
everywhere. The waist-up finger-up (Onboard2) was dropped: mixing a
waist-up crop with full-body poses looked off (Artur). See the
"Full-body Cyan" note above.
The other onboarding arts (Onboard1/3/4) were avoided here: they have
cyan hex frames + glowing panels baked into the artwork, which is the
inconsistency this pass removed. No additional assets; the tab tour
(page 6) reuses the live `R.drawable.ic_aegis_*` nav glyphs.

| Page | Cyan pose (asset) |
|---|---|
| 1 Welcome | standing (Sitting) |
| 2 Different | finger-up (Celebrate) |
| 3 Safety | finger-up (Celebrate) |
| 4 Tiers | standing (Sitting) |
| 5 Skill tree | finger-up (Celebrate) |
| 6 Tab tour | finger-up (Celebrate) |
| 7 PIN | finger-up (Celebrate) |
| 8 Ready | finger-up (Celebrate) |
| 9 Thank you | standing (Sitting) |

### Replay entry

Add to HELP_ENTRIES in HelpScreen.kt:

    HelpEntry(
        title = "Replay Tutorial",
        subtitle = "Walk through Aegis basics with Cyan.",
        route = "tutorial",
        iconRes = AegisIcons.Star,
    )
