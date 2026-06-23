# Call-screen UI improvements — TODO

**Status as of 2026.06.259:** Tier 1 + Tier 2 + the CallStyle
notification from Tier 3 are all shipping. The remaining gaps
are mid-call voice ↔ video upgrade (Tier 3) and emoji reactions
during call (Tier 3) — both low priority for the security-app
thesis. This doc is kept for historical reference.

Notes from the WhatsApp 2026 redesign, Telegram 10.5 call redesign,
Signal, and Google Phone, narrowed to what's high-leverage for Aegis
given the current `CallScreen.kt`.

Sources:
- WhatsApp call-screen redesign — Android Police
- WhatsApp PiP for video calls — Macworld
- Telegram 10.5 call redesign — 9to5Google
- Signal calling options — Signal Support
- Google Phone incoming-call UI — Android Authority
- Compose Picture-in-Picture — Android Developers

## Current baseline

- `TopAppBar` with peer name + status subtitle + back arrow
- WebView body (`file:///android_asset/www/android/call.html`)
- `CallConnectingOverlay` pulsing avatar while `!Connected`
- Cyan state-badge pill (top-right) showing raw `CallState` enum
- `AlertDialog` for timeout errors / failures
- Bottom `Surface` (black 55 % alpha) with circular Mute / Camera off /
  Flip / End buttons
- No PiP, no audio-device picker, no self-view PiP for video, no
  proximity-sensor wake-lock, no encryption affordance, no
  speaker/earpiece toggle, no quality indicator

## Tier 1 — biggest wins, smallest builds

- **Audio device picker** (speaker / earpiece / Bluetooth). API 31+
  `AudioManager.setCommunicationDevice`. Mirror upstream
  simplex-chat's `CallAudioDeviceManager`. None of WhatsApp / Telegram
  / Signal ships without this; we have nothing.
- **Self-view PiP for video calls.** Small bottom-right floating
  preview of your own camera, draggable. Tap to swap focus with
  remote. Local stream already exists in the WebView; just surface a
  thumb.
- **Proximity-sensor screen-off during voice calls.**
  `PROXIMITY_SCREEN_OFF_WAKE_LOCK`. Upstream simplex-chat does this in
  `ActiveCallState`. We don't.
- **Encryption indicator on the call screen.** Aegis is privacy-first
  — show it. Padlock + small "verified" pill linking to
  `VerifyContactScreen`. Telegram's 4-emoji checksum is the gold
  standard.
- **WhatsApp 2026 floating-island look** with LunaGlass hex skin.
  Each control its own outlined pill, primary bar floats over
  content, secondary controls (camera flip, share) in a 3-dot
  overflow.

## Tier 2 — useful, medium build

- **Picture-in-Picture (minimize).** Replace back arrow with a
  minimize affordance per WhatsApp 2026. Android 10+
  `setAutoEnterEnabled(true)` makes it auto-PiP on swipe-up. Call
  keeps running in a corner bubble; user can navigate the rest of the
  app. Returns via a persistent top banner.
- **Tap-anywhere to toggle controls in video mode.** Auto-hide after
  3 s of inactivity. Tap the remote video to bring them back.
- **Dynamic background by state.** Telegram pattern: cyan glow when
  Connected, red when from an SOSking peer, neutral while ringing.
  Plays well with LunaGlass.
- **Connection quality indicator.** Small bars near peer name driven
  by the `connection` JS events we already handle (good / connecting
  / degraded / failed).

## Tier 3 — bigger lifts, may not need yet

- Pill-shaped accept/decline on incoming (Google Phone 2026
  direction; accessibility win vs swipe).
- `CallStyle` notification (Android 10+) for full-screen-intent
  ringing.
- Mid-call voice ↔ video upgrade with pill-shaped accept dialog.
- Reactions during call (Telegram).

## Recommended first slice when we come back to this

A + B + C + D in one ship — audio picker, self-view PiP, proximity
wake-lock, encryption indicator. Concrete, mostly new code, no risk
to the call-protocol layer that just started working.
