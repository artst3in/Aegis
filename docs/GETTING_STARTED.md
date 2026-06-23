# Getting Started

## Install

1. Download the APK for your device:
   - **arm64** — Pixel, Samsung, OnePlus, most phones after 2018
   - **armv7** — Infinix, Tecno, budget phones, older devices

2. On your phone: Settings → Apps → Install unknown apps → Allow your browser

3. Open the downloaded APK → Install

4. Open Aegis

## First Launch

1. **Create your identity** — Aegis generates a cryptographic key pair. No account, no password, no phone number. Your identity IS your key.

2. **Set your App PIN** — This is the trunk of your security skill tree. Everything else builds on it. Choose a PIN you won't forget. This is NOT your phone PIN — it's separate.

3. **Add contacts** — Share your invite link (QR code or text). The other person opens it in their Aegis. Connection established. No server involved.

4. **Assign trust tiers** — Every contact gets one of three tiers:

| Tier | What they see | SOS alerts? |
|------|--------------|---------------|
| **Trusted** | Your location, presence, status, battery | Yes |
| **Emergency** | Nothing routine | Yes |
| **Untrusted** | Nothing | No |

**Trusted** is for your closest circle — spouse, children, parents. The people who actively want to know where you are daily.

**Emergency** is for the doctor, the neighbor with your key, the reliable friend. They get alerted in a crisis but don't see your routine data.

**Untrusted** is everyone else. They can message you, nothing more.

## Shield Tiers

Your security level is visible to your circle as a colored frame on your avatar:

- **No frame** — nothing configured
- **Bronze** — App PIN set (you're already here after step 2)
- **Silver** — 2-9 security features enabled
- **Gold** — all 10 in-app features maxed
- **Cyan** — Gold + Device Owner (factory reset + ADB setup)

Open Settings → Security to see your skill tree and start progressing.

## SOS Setup

1. Go to Settings → SOS
2. Enable hardware trigger (power button ×4)
3. Run the SOS Drill with one paired contact — one real test, done forever
4. The SOS button is always visible on the navigation bar

## Duress PIN (Optional but recommended)

1. Settings → Security → Duress PIN
2. Set your real PIN and your duress PIN
3. Real PIN → normal Aegis
4. Duress PIN → fake clean profile + silent SOS activates
5. The second PIN's existence is cryptographically unprovable

## Connecting Contacts

Each person installs Aegis independently. To connect:

1. Person A opens their profile → shows QR code
2. Person B scans it (or pastes the invite link)
3. SimpleX establishes an encrypted channel
4. Assign the trust tier
5. Done — they can message and see status based on their tier

No server registration. No phone number exchange. The QR code IS the connection.

## Recommended Device Setup

For maximum security:
- **GrapheneOS** on a Pixel phone (hardened Android, no Google)
- **Device Owner** mode (enables Cyan shield tier — the crown)
- Full-disk encryption (enabled by default on modern Android)
- Strong PIN for device unlock (not biometric alone)

Aegis works on any Android 10+ device. GrapheneOS is recommended, not required.

## Troubleshooting

**Messages not sending:** Check internet connection. SimpleX needs internet to reach relays. If all relays are down, messages queue and send when connectivity returns.

**SOS not triggering:** Make sure hardware trigger is enabled in Settings → SOS. Test with the in-app SOS button first (hold 3 seconds).

**App crashing:** Share the crash log via Settings → Debug → Export logs.

---

*dε/dt ≤ 0*
