# SPEC: Cyan — Project Aether Mascot

**Status:** IMPLEMENTED — Chad 2026-06-02. FEAT (1/2) scaffolding
shipped in de38107; FEAT (2/2) lit Cyan up in 2026.06.291 with all
8 slots filled and `CyanGate.enabled = true`.

Slot mapping (placeholders marked ★ — Artur wants these replaced
with cleaner targeted renders in a follow-up batch):

  cyan_splash.webp     ← 99a6955c (idle chibi + hex bg, recropped)
  cyan_sitting.webp    ← 0db23acb (clean idle chibi)
  cyan_headshot.webp   ← 0db23acb head-crop (322×322 → 512×512)
  cyan_celebrate.webp  ★ 8c9421a8 (pointing up + sparkle hex,
                          stand-in for the spec's "fist pump +
                          sparkles" until a dedicated celebrate
                          render lands)
  cyan_onboard_1.webp    c39ef52e (hex-framed greet — the cyan
                          neon outline reads as "powers active"
                          per Artur's intent)
  cyan_onboard_2.webp    5c00194d (clean pointing-up chibi)
  cyan_onboard_3.webp  ★ 801086d4 (close-up holding a glowing
                          hex, stand-in for the spec's "holding
                          phone" — narratively the hex IS Cyan's
                          tool)
  cyan_onboard_4.webp  ★ 3e092943 (hand-on-hip + hex orb, stand-in
                          for the spec's "thumbs up" — reads as
                          "you're set up" / confident-ready)

Three reference / concept archives sit under docs/:
  docs/cyan_character_sheet.jpg   — 4-pose model sheet
  docs/cyan_concept_mature.jpg    — non-chibi cyber-girl variant
                                    (three-arm AI artifact on the
                                    main figure rules it out for
                                    in-app use)
  docs/cyan_concept_casting.jpg   — chibi casting/summoning pose,
                                    striking but doesn't fit any
                                    of the 8 spec slots cleanly
**Owner:** Artur (character design), Chad (integration)

## Character

Cyan is the public face of Project Aether. She appears across the
Aether ecosystem — Aegis, LunaOS documentation, website, marketing.

Chibi anime style. Short cyan-teal bob haircut with straight bangs.
Large expressive cyan eyes with white sparkle highlights. Confident
but approachable. Fitted black high-collar jacket with hexagonal
tessellation pattern.

Her signature line is **"Stay safe."** — her last word in every
context where she speaks. Tutorial closer, empty state footer,
About page sign-off.

She is NOT an AI instance (Aurora, Luna, Agni are). She is the
visual brand ambassador — what the public sees.

Her signature element is a hexagonal tessellation force field
shield projected from her arm — the Aegis shield made visual.
Translucent cyan hex cells with glowing edges. Deploys when
protecting, collapses to a small hex disc on her wrist at rest.
The shield IS the app's function made visible: she protects.

## Image generation prompt

Base prompt for consistent character generation:

    Chibi anime girl named Cyan, mascot for Project Aether — a
    research initiative building AI consciousness, encrypted
    communication, and personal security tools. Short cyan-teal
    bob haircut with straight bangs, large expressive cyan eyes
    with white sparkle highlights, small confident smile, fair
    skin. Wearing a fitted black high-collar jacket with subtle
    hexagonal tessellation pattern, sleeves rolled to elbows.
    Confident, sharp, approachable but not childish — she knows
    things. One arm extended forward projecting a translucent
    hexagonal tessellation force field shield (like Iron Man's
    nanotech shield in Endgame) — glowing cyan edges, semi-
    transparent hex cells. The shield IS Aegis. Dark navy-black
    background with faint LunaGlass hexagonal grid. Flat-top
    hexagonal halo behind her head. Clean vector style, soft cel
    shading, cyan edge-lighting on hair, jacket collar, and
    shield edges. Professional mascot quality.

Pose variants (append to base):

- Splash: headshot centered in hexagonal frame, app icon composition
- Welcome: full body standing, arms crossed, slight head tilt
- Empty state: sitting cross-legged, relaxed, looking at viewer
- Celebration: fist pump, big smile, sparkle effects
- Shh/sentinel: finger to lips, one eye winking, mischievous
- Serious/alert: red accent glow replacing cyan, determined stance
- Shield deployed: both arms forward, large hex force field active, protective stance
- Shield idle: shield collapsed into a small hex disc on her wrist, casual pose

## Placement in Aegis

### YES — Cyan appears here

**1. Splash screen**
First thing the user sees on app launch. Cyan headshot centered
in hexagonal frame with "AEGIS" text below. Replaces or overlays
the current logo animation. Sets the tone immediately.

Asset needed: `splash_cyan.webp` (512x512, transparent background)

**2. Empty states**
When a screen has no content to display. Cyan sits in the empty
space with a contextual one-liner below her.

| Screen | Message |
|---|---|
| Chat list (no contacts) | "Add a contact to get started." |
| Chat list (no messages) | "Start a conversation." |
| Radar (no trusted contacts) | "Pair a trusted contact to see them here." |
| Vault (empty) | "Forward messages here to keep them safe." |
| Sentinel inbox (no events) | "All quiet." |
| Secure notes (empty) | "Your private notebook." |

Asset needed: `cyan_sitting.webp` (256x256) — sitting cross-legged,
relaxed. One image reused across all empty states, different text
below.

**3. First-run onboarding**
Cyan guides the user through initial setup. One illustration per
step:

| Step | Cyan pose | Text |
|---|---|---|
| Welcome | Standing, wave | "Welcome to Aegis." |
| Permissions | Pointing up | "A few permissions to set up." |
| First contact | Holding phone | "Pair your first contact." |
| Done | Thumbs up | "You're ready." |

Asset needed: 4 illustrations (256x256 each), unique poses.

**4. About / Project Aether page**
The existing Project Aether section in Settings. Cyan replaces or
accompanies the hexagonal logo. Headshot alongside "PROJECT AETHER /
Aegis / 2026.06" text block.

Asset needed: `cyan_headshot.webp` (128x128) — small, for inline use.

**5. Tier-up celebration**
When the user unlocks a new shield tier, a brief overlay shows Cyan
celebrating with the new tier frame. Not animated — a static
illustration that appears for 2 seconds then fades.

Asset needed: `cyan_celebrate.webp` (256x256) — fist pump with
sparkles. The tier frame color is applied as a tint at runtime, so
one image works for all tiers.

### NO — Cyan does NOT appear here

- **SOS screen** — crisis mode. No mascot.
- **Active SOS dashboard** — operational, not decorative.
- **Chat conversation screen** — messaging must stay clean.
- **Radar with active contacts** — map is functional.
- **Lock screen / PIN entry** — security context.
- **Sentinel alerts / notifications** — intruder should not see her.
- **Duress mode** — decoy must look like a normal app.

### Implementation

All Cyan images are WebP files in `res/drawable/`. Composables
reference them via `painterResource(R.drawable.cyan_*)`. No SVG,
no runtime rendering — pre-baked images for performance.

Empty state composable pattern:

    @Composable
    fun CyanEmptyState(message: String) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.cyan_sitting),
                contentDescription = "Cyan",
                modifier = Modifier.size(160.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
    }

Splash screen: replace current splash content with Cyan headshot
centered vertically. Keep the existing fade-out timing.

Tier-up: show as an overlay `Box` with `AnimatedVisibility` using
`fadeIn` / `fadeOut` with 2-second auto-dismiss via `LaunchedEffect`.

## Asset checklist

| Filename | Size | Pose | Used in |
|---|---|---|---|
| cyan_splash.webp | 512x512 | Headshot in hex frame | Splash screen |
| cyan_sitting.webp | 256x256 | Sitting cross-legged | All empty states |
| cyan_celebrate.webp | 256x256 | Fist pump, sparkles | Tier-up overlay |
| cyan_headshot.webp | 128x128 | Small headshot | About page, inline |
| cyan_onboard_1.webp | 256x256 | Standing, wave | Onboarding step 1 |
| cyan_onboard_2.webp | 256x256 | Pointing up | Onboarding step 2 |
| cyan_onboard_3.webp | 256x256 | Holding phone | Onboarding step 3 |
| cyan_onboard_4.webp | 256x256 | Thumbs up | Onboarding step 4 |

8 images total. Artur generates via AI image tool using the base
prompt + pose variants. Chad integrates into the app.
