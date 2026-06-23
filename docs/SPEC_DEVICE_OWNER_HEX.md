# SPEC: Device Owner as a Skill Tree Hex

## The idea

Device Owner is a hex petal on the security skill tree. It cannot be enabled from inside the app — the user must run an ADB command on a computer to grant it. This makes it the hardest node to unlock and the final gate before Diamond (max shield).

## Why it belongs on the tree

Device Owner gives Aegis:
- Uninstall prevention (attacker can't remove the app)
- Factory reset protection
- Enforce lock-screen PIN policy
- Silent app installation (auto-updates without prompt)
- Disable USB debugging (attacker can't ADB in)
- Kiosk mode option (lock device to Aegis during SOS)

Without Device Owner, an attacker who gets past the lock screen can just uninstall Aegis. With it, Aegis is permanent.

## Skill tree placement

```
Level 1:  App PIN    Vault PIN
Level 2:  App Duress  Vault Duress  Mugshot
Level 3:  DEVICE OWNER  ← requires ADB, cannot be set in-app
Independent: Canary  Geofence  SIM Watch

Diamond: all nodes lit including Device Owner
```

Device Owner is the only node that can't be tapped to enable. The hex shows instructions instead:

```
🔒 Device Owner

Connect your phone to a computer and run:

  adb shell dpm set-device-owner app.aether.aegis/app.aether.aegis.admin.AegisAdminReceiver

Then return here. This tile will light up automatically.
```

## Detection

On every SecurityScreen mount, check:
```kotlin
val dpm = context.getSystemService(DevicePolicyManager::class.java)
val isOwner = dpm.isDeviceOwnerApp(context.packageName)
```

If true → hex is lit, Diamond counter increments.
If false → hex shows ADB instructions, stays locked appearance.

## UX

- Tapping the hex opens a bottom sheet with the ADB command (copyable)
- QR code encoding the command (for scanning from a computer terminal)
- "Why?" section explaining what Device Owner enables
- Once detected: hex lights up, confetti/glow animation, Diamond check

## Diamond gate

Diamond requires ALL hexes lit including Device Owner. This means you cannot achieve max shield without a computer. Deliberate. The kind of person who connects ADB is the kind of person who takes security seriously.

---

*dε/dt ≤ 0*
