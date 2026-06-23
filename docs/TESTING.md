# Testing Aegis (alpha)

**Aegis is alpha software. It has not been independently audited. Do not
rely on it for your actual safety yet.** Test it, try to break it, and
report what breaks. That is the whole point of this phase.

Aegis is the personal-security messaging side of Project Aether: encrypted
chat over the SimpleX protocol (no phone number, no account, no server
that stores your messages), plus a panic/SOS broadcast to chosen contacts,
a duress decoy profile, an encrypted vault, and remote-access controls.

---

## What's true, and what isn't (read before trusting anything)

We would rather under-promise. Honest scope of the current build:

- ✅ **End-to-end encrypted over SimpleX.** No phone number, no account,
  no server stores your messages.
- ✅ **SOS works from the lock screen** — hold the SOS button (or tap
  power ×4) and your trusted/emergency contacts get an alert with your
  location; audio/camera capture attaches.
- ✅ **Your messages are sealed at rest** under a key derived from your
  24-word recovery phrase.
- ⚠️ **The duress/decoy profile defeats shoulder-surfing and a casual
  phone-grab — NOT a forensic adversary.** Someone who images or roots
  the phone can read the real data regardless of which PIN you type. The
  decoy changes what the app *shows*, not what's on disk. Do **not** rely
  on it against law enforcement or a technical attacker.
- ⚠️ **Contacts and metadata are protected by device encryption, not by
  your phrase.** The phrase seals message *content*; the rest sits under
  the database key.
- ⚠️ **The PIN is a deterrent.** It can be brute-forced offline from a
  phone image; the real cryptographic strength is the phrase-rooted seal.
- ⚠️ **Recovery phrase is shown ONCE and never stored.** Lose it and your
  data is unrecoverable, by design. Write it on paper.

---

## Install

- **Most testers:** install the **release** build. It auto-updates itself
  from then on.
- The **debug** build installs side-by-side (different app id) and needs a
  GitHub token pasted under Settings → Updates to auto-update; it carries
  unfinished/experimental UI. Most testers do not want this.
- Requires **Android 10+ (API 29)**, **arm64** device.
- You'll be sideloading an APK from outside the Play Store — Android will
  warn you. That's expected for an alpha.

---

## Smoke checklist (please run on a real device)

These are the paths most likely to be broken. If any fail, that's a great
bug report.

### A. Fresh install / onboarding
- [ ] Cold launch → tutorial appears, no crash on the splash.
- [ ] You **cannot** skip the recovery-phrase and PIN setup.
- [ ] Phrase page: numbers 1–24 are visible; the **words** are hidden
      until you scratch the panel.
- [ ] The confirm step asks for **random** word positions.
- [ ] Finish onboarding → land in the app, no crash.
- [ ] Force-kill + relaunch → opens to the PIN screen, not back into
      onboarding.

### B. Lock / duress
- [ ] Real PIN unlocks to your real data.
- [ ] The duress PIN opens the decoy; your real chats/contacts are not
      visible in it.
- [ ] Lock + kill + reopen → content isn't readable until you unlock.

### C. Messaging
- [ ] Pair two devices; exchange messages both ways.
- [ ] Send a photo/file; it renders for the recipient.

### D. SOS (test with a second device as the "contact")
- [ ] Hold SOS 3s → the contact receives the alert with location.
- [ ] **From the lock screen**, without unlocking → SOS still fires.
- [ ] Power-tap ×4 trigger works.
- [ ] Triggering the **duress PIN** fires SOS **silently** — no visible
      "SOS ACTIVE" banner or vibration on your screen.
- [ ] "Stop all SOS" (Diagnostics) cancels it and clears the contact's
      alert.

### E. Updates
- [ ] After installing, confirm the app offers + installs a newer build
      cleanly when one ships.

### F. Stability
- [ ] Rotate the device, background/foreground repeatedly.
- [ ] Open the system ringtone picker / a file picker and confirm you are
      **not** bounced to the lock screen on return.
- [ ] Leave it running an hour; confirm it's still alive.

---

## Reporting bugs

Please include, every time:

1. **Device model + Android version** (most bugs are OEM/version-specific).
2. Which **build** (release vs debug) and the **version** (Settings →
   About, or the bottom of Diagnostics).
3. What you did, what you expected, what happened.
4. If it crashed: a `logcat` if you can grab one.

Report to: **<your channel here — GitHub issues / dedicated thread>**.

Thank you for helping test something that's meant to protect people who
actually need it. — Project Aether
