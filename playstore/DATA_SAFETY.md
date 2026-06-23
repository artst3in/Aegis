# Google Play Data Safety Form — Aegis

## Does your app collect or share any of the required user data types?
Yes

## Data collected

### Location
- **Approximate location:** Collected. Shared with other users (paired contacts only).
  - Purpose: App functionality (panic system, status dashboard)
  - Required: Yes (core feature)
  - Processing: On-device only. Not sent to developer or third party.
  
- **Precise location:** Collected. Shared with other users (paired contacts only).
  - Purpose: App functionality (panic GPS broadcast)
  - Required: Yes (core feature)
  - Processing: On-device and peer-to-peer via SimpleX. Not sent to developer.

### Personal info
- **Name:** Optional. User can set a display name.
  - Purpose: App functionality (contact display)
  - Processing: Stored on-device only. Shared only with paired contacts via SimpleX.

### Photos and videos
- **Photos:** Collected during panic events or sent as message attachments.
  - Purpose: App functionality (panic camera, photo messages)
  - Processing: On-device + peer-to-peer. Not sent to developer.

### Audio
- **Voice or sound recordings:** Collected during panic events or voice messages.
  - Purpose: App functionality (panic audio stream, voice messages)
  - Processing: On-device + peer-to-peer. Not sent to developer.

### Messages
- **Other in-app messages:** Encrypted messages between paired contacts.
  - Purpose: App functionality (messaging)
  - Processing: On-device + peer-to-peer via SimpleX relays (encrypted, no metadata).

### App activity
- **App interactions:** Canary system tracks last foreground timestamp.
  - Purpose: App functionality (dead man's switch)
  - Processing: On-device only.

## Data NOT collected
- Email address
- Phone number
- Payment info
- Health info
- Browsing history
- Search history
- Contacts list (system contacts)
- Device or other IDs (no advertising ID, no device fingerprint)
- Crash logs (no analytics, no crashlytics)

## Data sharing
- No data is shared with third parties. Ever.
- No data is sent to the developer. Ever.
- All data transmission is peer-to-peer via SimpleX protocol.
- SimpleX relays see only encrypted blobs with disposable addresses.

## Data security
- All data encrypted in transit (SimpleX double-ratchet + NaCl)
- All data encrypted at rest (Android keystore + app-level encryption)
- No server-side storage of any kind

## Data deletion
- User can delete all data by uninstalling the app
- No server-side data exists to delete
- Burn-after-reading messages are deleted after viewing
- Disappearing messages are deleted after timer expires

## Encryption
- Yes, all data in transit is encrypted
- Yes, user data is transferred over a secure connection
- Encryption protocol: SimpleX (double ratchet, NaCl/libsodium)
