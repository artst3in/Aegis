# Aegis GUI Specification

## Design Language: LunaGlass

Based on the LunaOS LunaGlass visual system. Same language, different platform.

## Core Principles

1. **Hexagons everywhere.** No circles for controls. Flat-top everywhere. No exceptions. Behavior from glow and color, not rotation.
2. **Dark background.** #050508 base. Surfaces at #0a1214, #0d1a1e.
3. **Cyan as primary.** #00d4d4 primary, #007a7a dim, #40f0f0 bright. Glow: rgba(0,212,212,0.15).
4. **Glass panels.** Rounded corners (12px), 1px border (#1a3a40), subtle glow on active elements.
5. **Inter serif font.** Matches the book, the website, the identity.
6. **Glow = alive.** Online contacts, active panels, and the SOS button glow. Offline = no glow.

## Color Palette

| Token        | Hex       | Usage                        |
|-------------|-----------|------------------------------|
| bg          | #050508   | Main background              |
| surface     | #0a1214   | Cards, headers               |
| panel       | #0d1a1e   | Glass panels                 |
| border      | #1a3a40   | All borders                  |
| cyan        | #00d4d4   | Primary accent               |
| cyanDim     | #007a7a   | Inactive hex borders         |
| cyanBright  | #40f0f0   | Highlights, active states    |
| cyanGlow    | rgba(0,212,212,0.15) | Glow backgrounds |
| text        | #e0f0f0   | Primary text                 |
| textDim     | #6a8a8a   | Secondary text, timestamps   |
| red         | #FF0000   | SOS, siren, danger         |
| green       | #00FF00   | Online status                |
| orange      | #ff9800   | Warnings, "away" status, SimpleX indicator |

## Screens

### 1. Chat List (Main Screen)

- Header: "AEGIS" left (cyan, letter-spaced), protocol status right (orange)
- Contact rows: hex avatar (44px, cyan border when online, glow when online) + name + last message + timestamp
- Status dot next to last message (green/orange/grey)
- Hover/tap: subtle cyan background glow

### 2. Chat View

- Top bar: back arrow + hex avatar + name + "online · SimpleX"
- Messages: outgoing = cyan tint background, rounded corners (16px, 4px on sender corner)
- Incoming = surface background, border
- Input: rounded pill input + hex send button
- Timestamps right-aligned, dim

### 3. Contact Status

- Grid of glass panels (2 columns)
- Each panel: hex avatar + name + location + battery bar
- Battery: cyan fill, red below 20%
- Protocol status panel at bottom: "SimpleX · Luna offline" + WireGuard status

### 4. SOS

- Large central hex (120px), cyan border
- Tap → 3-second countdown (orange, numbers count down inside hex)
- After countdown → ACTIVE state: hex turns red, "ACTIVE" label
- Silent by default: GPS broadcasting, circle notified
- Below: glass panel showing GPS coordinates
- Siren button: separate hex, requires confirmation
- "Cannot be stopped by thief" label under siren
- Audio: one-way by default (victim → circle). Circle transmits back only if victim enables speaker or has earbuds connected.
- Brightness: two-finger swipe up = brighter, two-finger swipe down = dimmer. Adjusts without leaving the SOS surface.
- Location pings sent as `[aegis:location]<json>` control messages. Filtered out client-side — never appear in the chat thread.

### 5. Bottom Navigation

- Four hex buttons: Chats (✉), Status (◈), SOS (⚠), Settings (⚙)
- Active tab: full opacity + cyan glow fill. SOS tab: red when active.
- Labels below each hex, 9px, letter-spaced

## Typography

| Element       | Size  | Weight | Font    |
|--------------|-------|--------|---------|
| App title    | 18px  | 700    | Inter |
| Contact name | 15px  | 600    | Inter |
| Message text | 14px  | 400    | Inter |
| Status text  | 13px  | 400    | Inter |
| Timestamps   | 11px  | 400    | Inter |
| Labels       | 9-11px| 400    | Inter |

## Interactive Mockup

See `docs/aegis_gui_mockup.jsx` — runs in Claude artifact or any React environment.

## Implementation Notes

### Hex Avatars
- SVG polygon, 6 vertices, regular hexagon inscribed in circle
- Pointy-top orientation (vertex at 12 o'clock)
- Contact initial letter centered inside, bold, 35% of hex size
- Chat list: 44px. Chat header: 34px. Status cards: 42px. Nav: 28px. SOS: 120px.
- Online: cyan border (#00d4d4) + glow (radial-gradient blur 4px). Offline: dim border (#1a3a40), no glow.

### Status Dots
- 8px circle next to last message preview
- Online: #00FF00 + boxShadow 0 0 6px green. Away: #ff9800. Offline: #6a8a8a.

### Message Bubbles
- Outgoing: background rgba(0,212,212,0.15), border 1px #007a7a
  - Corner radius: 16px 16px 4px 16px (sharp bottom-right = sender corner)
- Incoming: background #0a1214, border 1px #1a3a40
  - Corner radius: 16px 16px 16px 4px (sharp bottom-left = sender corner)
- Padding: 10px 14px
- Max width: 75% of screen
- Timestamps: right-aligned, #6a8a8a, 10px

### Input Field
- Rounded pill: border-radius 20px, padding 10px 16px
- Background: #0d1a1e, border 1px #1a3a40
- Focus state: border changes to #00d4d4
- Font: Inter 14px

### Send Button
- Hex shape 38px, cyan border, cyanGlow fill
- Arrow icon ↑ centered, 16px

### Battery Bars (Status Screen)
- Height: 3px, border-radius 2px
- Track: #1a3a40
- Fill: #00d4d4 when >20%, #FF0000 when ≤20%
- Percentage label: 9px, #6a8a8a, right of bar

### Contact Rows (Chat List)
- Padding: 14px 8px
- Border-bottom: 1px solid #1a3a40
- Hover/tap: background rgba(0,212,212,0.08), transition 200ms

### SOS Animation
- Countdown: numbers 3→2→1 inside hex, orange (#ff9800), 36px bold
- Active state: hex border turns red, fill rgba(229,57,53,0.2)
- Pulse: radial glow oscillates opacity 0.5→1 and scale 1→1.05, 2s ease infinite
- STOP button: hex 36px, red border, redGlow fill
- Siren button: hex 48px, red, speaker icon 🔊

### Scrollbar
- Width: 4px
- Thumb: #1a3a40, border-radius 4px
- Track: transparent

### Transitions
- All interactive state changes: 200ms ease
- Tab switching: instant (no page transition)
- SOS countdown: 1s intervals, no easing

## App Lock Screen

### Trigger
- App opens → lock screen shown first (every time)
- App returns from background (after 30s) → lock screen
- Configurable timeout: 0s (always), 30s, 1min, 5min, never

### Authentication Methods (priority order)
1. **Fingerprint** — Android BiometricPrompt API. Primary method.
2. **PIN** — 4-6 digit numeric PIN. Fallback when biometric unavailable.
3. **Pattern** — optional, not recommended (shoulder surfing risk).

### Lock Screen Layout
- Full dark background (#050508)
- Centered: Aegis hex logo (96px) with subtle glow pulse
- Below logo: "AEGIS" text, cyan, letter-spaced
- Below text: fingerprint icon (hex-shaped border, 64px)
- Tap fingerprint icon → triggers BiometricPrompt
- Below: "Use PIN" text link → switches to PIN pad
- PIN pad: hex-shaped number buttons (0-9), 48px each, 3×4 grid + backspace
- PIN dots: hex-shaped indicators (filled = entered, hollow = remaining)

### Security
- 5 failed PIN attempts → 30 second lockout, escalating (1min, 5min, 15min)
- Fingerprint: follows Android system limits
- Lock screen cannot be bypassed via recent apps or notifications
- In SOS mode: lock screen still requires auth (prevents thief from accessing app)
- SOS button accessible FROM lock screen (dedicated hex at bottom, no auth needed)

### SOS from Lock Screen
- Small red hex (36px) at bottom of lock screen: "SOS"
- Tap → 3-second countdown → silent SOS activates
- No authentication required — speed matters in emergencies
- Lock screen stays active — thief sees lock screen, not the app

### Incoming SOS Alert (Receiver Lock Screen)
- When a contact triggers SOS, the alert surfaces on the receiver's lock screen.
- Layout: panicking peer's name (top), small map preview below. No auth required to view.
- Tap alert + biometric unlock → opens full SOS dashboard (live map, camera feed, audio playback, push-to-talk).
- Fingerprint authenticates directly from the lock-screen alert; no need to open Aegis first.

dε/dt ≤ 0

## Interaction Features (Phase 2)

### Swipe to Reply
- Swipe RIGHT on any message → reply compose opens with quoted message
- Same gesture as Telegram — universally understood, fastest reply method
- Swipe distance: 80dp threshold, spring-back animation

### Edit Sent Messages
- Long press own message → "Edit" option in context menu
- Edited message shows subtle "edited" tag next to timestamp
- Edit history NOT stored (privacy — what you delete is deleted)

### Typing Indicator
- Three-dot animation in chat header when other party is composing
- Dots: three small cyan circles, wave animation (200ms offset each)
- Timeout: disappears after 5s of no input

### Disappearing Messages
- Per-conversation toggle in chat settings
- Options: off / 5 min / 1 hour / 24 hours / 7 days
- Countdown starts when message is READ (not sent)
- Visual: subtle timer icon next to timestamp, thin border that shrinks

### Message Search
- Magnifying glass icon in chat header → search bar slides down
- Real-time filtering as you type
- Matching text highlighted in cyan
- Up/down arrows to jump between results

### Contact Presence Bar
- Thin strip (32dp) below header on chat list screen
- Row of hex dots (16dp each) for each contact
- Green glow = online. Dim = offline. Orange = away.
- Tap a dot → jumps to that person's chat
- Always visible — persistent awareness without opening Status screen

### Cross-Timezone Clocks (Per-Row)
- Each contact's chat-list row surfaces the peer's local time, dim, right of timestamp.
- Status detail panel also surfaces local time prominently.
- Derived from the peer's most recent GPS longitude — no manual timezone setting needed.
- Updates passively as new GPS fixes arrive. Format: 24h, `HH:MM` + timezone abbreviation when known.

### Haptic Feedback
- Subtle vibration (10ms) on every hex button tap
- Stronger pulse (30ms) on send button
- Three-pulse heartbeat pattern on SOS activation
- No haptic on scroll or passive interactions

### Pull-down to Refresh
- Standard pull gesture on chat list → refreshes contacts + messages
- Cyan spinner animation (hex-shaped, not circle)
- Also triggers manual SimpleX reconnect if connection dropped

### Photo Quick-Send
- Camera icon in compose bar → full-screen camera preview
- Tap to shoot → preview → send or retake. One flow, never leaves chat.
- Swipe up on camera preview → gallery picker as alternative

### Voice Message UX
- Hold mic button → recording (waveform visualization)
- Release → send
- Swipe LEFT while holding → cancel (message deleted)
- Slide-to-cancel threshold: 80dp drag left from the mic affordance. Below threshold = snap back. At/above = discard recording.
- Lock icon appears after 1s hold → slide up to lock for hands-free recording

## Phase 3 — Polish Features

### Custom Notification Sound Per Contact
- Assign unique sound per contact in contact settings
- Hear who it is without looking at the screen

### Scheduled Messages
- Long press send button → schedule picker (date + time)
- Shows recipient's local timezone for convenience
- Queued locally, sent at scheduled time. Works offline until then.

### Image Viewer
- Tap image in chat → fullscreen, dark background
- Pinch to zoom, swipe left/right for other images in conversation
- Swipe down to dismiss

### Link Previews
- Paste URL → preview card: title, description, thumbnail
- Fetched once, cached. No tracking.

### Floating Date Headers
- While scrolling chat history: sticky date label fades in at top
- "Today", "Yesterday", "May 20", etc.

### Delivery Status Ticks
- One cyan tick: sent to relay
- Two cyan ticks: delivered to recipient device
- Filled cyan ticks: read
- Configurable: read receipts can be turned off per conversation

### Quick Actions (Chat List)
- Long press contact → mute, pin to top, archive
- Pinned contacts stay at top of list with subtle pin icon

### Pinned Messages
- Long press message → "Pin" → pinned to top of chat
- Tap pinned bar → scroll to pinned message
- Useful for addresses, flight info, meeting times

### Contact Info Screen
- Tap contact name in chat header → profile page
- Sections: shared media grid, shared files, location on map, mute toggle

### Night Auto-Silence
- Settings → Quiet hours (default 23:00–07:00)
- Mutes all notification sounds during quiet hours
- SOS ALWAYS OVERRIDES — quiet hours do not apply to SOS alerts

### Cross-Timezone Clock
- Chat header shows recipient's local time: "Zippy · online · 16:50 EAT"
- Calculated from their last reported GPS timezone

### Unread Badge
- App icon badge (Android launcher)
- Per-tab badge on bottom nav (number in small hex)

## Loki Toolkit

Defensive surfaces that activate when the device is compromised, missing, or under coercion.

### Mugshot on Failed PIN
- 3 consecutive wrong-PIN attempts → silent front-camera capture.
- No shutter sound, no flash, no preview. Capture happens in the background.
- Photo is sent to every paired guardian over SimpleX.
- Counter resets on successful unlock.

### Fake Shutdown
- Triggerable Loki state. Device APPEARS powered off: black screen, no input response, no haptic.
- Background services keep running — GPS, SOS listener, Loki triggers all live.
- Distinct from Voyager dead-battery deception: fake shutdown is user-initiated; Voyager engages at ≤5% battery.
- Exit gesture is set during configuration (e.g. long hold on a specific screen region).

### Canary / Dead-Man's Switch
- Configurable check-in window: 24–48h.
- If the user does not interact with Aegis within the window, an auto-broadcast canary message goes to every paired guardian.
- Any open of the app resets the timer.
- Used for: silent kidnap detection, incapacitation, device loss.

### Decoy Fixtures
- Pre-built fake-contact + fake-message set.
- Surfaces when the user unlocks with the duress PIN.
- Attacker browses what looks like a normal Aegis chat list with believable conversations.
- Fixtures are static, locally generated, and indistinguishable from real chats at a glance.

## Remote Commands

Commands a guardian can send TO a target device (the target being a contact's phone, with consent and pairing established). All require Device Owner on the target.

### Remote Wipe
- Guardian sends factory-reset command to target's phone.
- Target executes a full Device Owner reset on receipt.
- Last-resort. Cannot be reversed.

### Remote Siren
- Guardian triggers an unkillable loud alarm on target's phone.
- Plays at max alarm-stream volume regardless of ringer state.
- Stop requires biometric on the target device — cannot be silenced from notification shade or power button.

### Remote Locate
- Guardian force-enables GPS on target's phone.
- Pulls a one-shot position fix, returned to guardian over SimpleX.
- Useful when target's phone is unattended and location sharing was previously off.

## Voyager Mode

Battery-aware deception layer. Engages automatically as power depletes.

### Fake Dead-Battery Screen at 5%
- At ≤5% battery, Voyager paints a fake Android dead-battery icon on screen.
- All input is refused — taps, buttons, gestures all ignored.
- Device looks bricked. To an attacker or observer, the phone has died.
- Underlying services remain alive in the background.

### Ghost GPS Pings (Once/Hour)
- While in fake-dead state, GPS pings once per hour to the paired guardian set.
- Location is preserved past the apparent shutdown.
- Continues until battery truly dies (on low-draw devices like Pixel/GrapheneOS, this can run for days).

## KILLED Features

- ~~Contact heartbeat notification~~ — feels like stalking. Last-seen on status screen is enough.
- ~~Battery warning push notification~~ — noise. Battery visible on status screen.
- ~~Bots/channels~~ — this is a shield, not a platform.
- ~~Social features~~ — no feed, no discover, no public profiles.

## LunaGlass Visual Effects (Implementation)

Reference: LunaOS/lunaspace/docs/LUNAGLASS_EFFECTS.md
Primary cyan updated to #00FFFF.

### 1. Frosted Glass Panels
```kotlin
Modifier
  .background(Color(0xD90D1A1E))  // 85% opacity
  .blur(12.dp)                     // backdrop blur (requires Android 10+)
  .border(1.dp, Color(0x1F00FFFF), RoundedCornerShape(12.dp))
```
Fallback for Android <12: solid dark background, no blur.

### 2. Breathing Glow on Online Avatars
```kotlin
val alpha by infiniteTransition.animateFloat(
    initialValue = 0.4f, targetValue = 0.8f,
    animationSpec = infiniteRepeatable(tween(3000, easing = EaseInOut), RepeatMode.Reverse)
)
// Apply to glow layer behind hex avatar
Box(Modifier.drawBehind {
    drawCircle(Color(0x00FFFF).copy(alpha = alpha), radius = size * 0.7f,
               style = Fill, blendMode = BlendMode.Screen)
})
```

### 3. Hex Gradient Fill
```kotlin
Brush.radialGradient(
    colors = listOf(Color(0xB300FFFF), Color(0x8000DCDC), Color(0x4D007A7A)),
    center = Offset(size/2, size/2), radius = size/2
)
```
Apply as fill to all hex polygons. Brighter center, darker edges.

### 4. Edge Lighting (top-left light source)
Per-edge stroke: top-left edges +20% brightness, bottom-right -20%.
In Canvas draw: draw each hex edge individually with varying alpha.

### 5. Hex Grid Background
```kotlin
// Subtle repeating hex pattern behind all screens
Canvas(Modifier.fillMaxSize()) {
    // Flat-top hex tessellation, stroke Color(0x0800FFFF), no fill
    // Hex size: 40.dp, very faint
}
```

### 6. Scan Line
```kotlin
val offset by infiniteTransition.animateFloat(
    initialValue = 0f, targetValue = screenHeight,
    animationSpec = infiniteRepeatable(tween(10000, easing = LinearEasing))
)
// Draw 1dp line at offset Y, Color(0x0F00FFFF), blur 2dp
```

### 7. Hex Assembly Transition (tab switch)
```kotlin
// Stagger delay per ring: 30ms
// Scale 0→1 + alpha 0→1, 200ms EaseOut per hex
// AnimatedVisibility with custom enter/exit
```

### 8. Double-Ring Hex Avatars
Inner hex (avatar) + 3dp gap + outer hex (1dp stroke).
Gap glows when online (breathing animation from #2).
Gap pulses red fast (1s) during SOS.

### 9. Counterclockwise Border Light-Up (hold-to-execute)
```kotlin
// Animate a clipping arc from 360° to 0° counterclockwise
// drawArc on hex border path, sweep = holdProgress * 360
// Vibrate at each vertex (every 60° of progress)
```

### 10. Data Rain (optional, low priority)
Tiny 4dp hex particles drifting down at 20px/s.
Color(0x0A00FFFF). ~20 particles. Slow spin.
Disabled on low-end devices. Toggleable in settings.

### Priority Order
1. Breathing glow — makes the app feel alive (easy, high impact)
2. Frosted glass panels — premium feel (Android 10+ only)
3. Hex gradient fill — depth without complexity
4. Double-ring avatars — identity
5. Counterclockwise border — already spec'd for hold-to-execute
6. Hex grid background — texture
7. Everything else — polish phase

## Compose Bar — REDESIGN (replaces current cluttered layout)

Current: +, camera, mic, input, emoji, send — too many icons, no room for text.

New layout — THREE elements only:

```
┌──────────────────────────────────────────┐
│  ⬡+  │  Message...                 │ ⬡↑  │
└──────────────────────────────────────────┘
```

LEFT: hex "+" button — opens action drawer:
  ├── Camera (take photo)
  ├── Gallery (pick photo/video)
  ├── File (attach document)
  └── Location (share position)

CENTER: text input — fills 80% of width. Inter font. Rounded pill.
  Focus state: border → #00FFFF

RIGHT: context-sensitive hex button
  - When input empty: mic icon (hold to record, slide left to cancel)
  - When text entered: send arrow ↑ (hold to execute per edge-heat spec)

Drawer: slides up from bottom. Four hex icons in a row. Flat-top.
Tap outside drawer to dismiss.

Camera and mic icons REMOVED from compose bar.
They live inside the + drawer now.

## COMPLIANCE — FOLLOW THIS DOCUMENT EXACTLY

Every visual spec in this document and in the LunaGlass Design System
(lunaglass_design_system.jsx) is a REQUIREMENT, not a suggestion.

Checklist of specs that MUST be implemented:

- [ ] Inter font everywhere (bundle Inter-Regular.otf + Inter-Bold.otf)
- [ ] Flat-top hexagons everywhere, no pointy-top, no circles
- [ ] Trapezoid edges with radial vertex cuts, no round caps
- [ ] CCW edge-heat animation on ALL hold-to-execute buttons
      (SOS: 6 edges × 0.5s = 3s, vibrate per completed edge)
      (send/call/video: 6 edges × configurable hold time)
- [ ] Hex gradient fill (radial: bright center → dark edge)
- [ ] Breathing glow on online/active elements (3s ease infinite)
- [ ] Compose bar: 3 elements only (+, input, send/mic)
- [ ] SOS dims screen to minimum, does NOT lock device
- [ ] SOS alert overrides silent mode (sound + vibration)
- [ ] Silent mode max 1 hour, then auto-re-enables
- [ ] Lock screen: map visible, dashboard behind fingerprint
- [ ] Duress PIN: fake profile, silent SOS trigger
- [ ] Primary cyan: #00FFFF (pure, not muted)
- [ ] Double-ring hex avatars (inner + gap + outer, gap glows)
- [ ] Hold-to-execute on send/call/video (not single tap)
- [ ] Frosted glass panels (backdrop-blur 12px, Android 10+)

Reference animation: docs/edge_heat_animation.jsx
Reference design system: docs/lunaglass_design_system.jsx

If something in the app doesn't match this spec, it's a bug.
