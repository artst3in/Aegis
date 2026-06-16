# Security Status

**Last updated: 2026-06-15**

Aegis is in active development. This document is an honest assessment of what works, what doesn't, and what you should not trust yet.

---

## Implemented and working

| Feature | Status |
|---------|--------|
| End-to-end encrypted messaging (SimpleX) | ✅ Working |
| Voice and video calls (WebRTC over SimpleX) | ⚠️ Broken — debugging |
| Full panic coordination (GPS + audio + camera + live feed to contacts) | ⚠️ Not yet implemented |
| Aegis Protocol (identity never enters transport) | ✅ Working |
| DNA message envelope (transport-agnostic message identity) | ✅ Working |
| Capability negotiation (safe cross-version interop) | ✅ Working |
| Read receipts by DNA (cross-device, no id-space mismatch) | ✅ Working |
| Receipt reconciliation (lossy-link resilient, per-DNA, 4 triggers) | ✅ Working |
| Attention leak defense (unread-only reconciliation) | ✅ Working |
| SOS with GPS, audio, camera, siren (1-second hold) | ✅ Working |
| SOS is Aegis-only (non-Aegis contacts cannot receive SOS) | ✅ Working |
| Silent SOS / Duress SOS (zero visual footprint, invisible to attacker) | ✅ Working |
| SOS type tagging (sos vs sos:duress — contacts know the difference) | ✅ Working |
| Hardware SOS trigger (power button ×4, state-dependent) | ✅ Working |
| Remote duress trap (every PIN field is a tripwire) | ✅ Working |
| Remote SOS button (trigger target's SOS from distance) | ✅ Working |
| Three-PIN duress (real + two decoy profiles) | ✅ Working |
| Trust model (Trusted / Emergency / Untrusted) | ✅ Working |
| Skill tree (security nodes) | ✅ Working |
| Mugshot on wrong PIN | ✅ Working |
| SIM swap detection | ✅ Working |
| Canary (dead man's switch) | ✅ Working |
| Geofencing | ✅ Working |
| Remote commands (siren, lock) | ✅ Working |
| Remote commands (locate, wipe, video feed) | ⚠️ In testing |
| Voyager mode (fake dead battery, GPS continues) | ✅ Working |
| Vault (separate encrypted storage) | ✅ Working |
| Anonymous groups | ✅ Working |
| Radar (real-time contact positions) | ✅ Working |
| Lock curtain (two-finger drag) | ✅ Working |
| 24-word BIP39 recovery phrase | ✅ Working |
| Messages sealed at rest under phrase-derived key | ✅ Working |
| Content-portable backup (Argon2id, no master key in file) | ✅ Working |
| Backup re-seal (imported backups stay readable) | ⚠️ Currently broken — code fix pushed, not verified on device |
| Ephemeral profiles (wipe on lock, correct re-lock) | ✅ Working |
| Multi-profile creation and switching | ✅ Working |
| OTA updates via GitHub Releases with rollback | ✅ Working |
| Permission tutorial with OEM battery-killer detection | ✅ Working |
| Self-healing handshake (dropped hello auto-recovery) | ✅ Working |
| Glass effects (tilt-reactive sheen, Fresnel edge-light, 3D perspective) | ✅ Working |

| Transactional delivery (messages sealed, transport copy destroyed) | ✅ Working |
| Voyager power management (graduated visual tapering by battery) | ✅ Working |
| First audio transmission at 5s (beats 10s hardware kill window) | ✅ Working |

## Experimental (behind 7-tap gate)

| Feature | Status |
|---------|--------|
| Sentinel (covert intrusion detection cascade) | ⚠️ Experimental — patent filed |
| Sonar (ultrasonic room sensing, part of Sentinel) | ⚠️ Experimental |
| Snatch detection (grab/yank triggers SOS) | ⚠️ Experimental — false-fires on drops |
| Crash detection (vehicle impact → 30s countdown) | ⚠️ Experimental — needs field testing |



## Honest limits of what IS implemented

| Limit | Detail |
|-------|--------|
| Duress profiles are cryptographically indistinguishable | A forensic examiner sees multiple profiles on disk but cannot determine which is real and which is decoy. The profiles are structurally identical. |
| PIN hash is in software, seal key is in hardware | The Argon2id PIN hash lives in SharedPreferences (brute-forceable from a disk image in hours). However, the seal key that encrypts messages is in AndroidKeyStore TEE (StrongBox where available), hardware-bound and unexportable. Brute-forcing the PIN hash gives you the PIN — but without the physical device's TEE, you cannot decrypt any messages. |
| Contacts and metadata are NOT phrase-sealed | The phrase seals message content. Contact list and metadata sit under database encryption. |
| 10-second hardware power kill | The PMIC cuts power on a 10-second hold. No software can prevent this. Camera and GPS fire at 0.5s, first audio at 5s. Evidence transmitted before the thief can power off. |

## Security research

**Attention leak** — a new attack vector discovered and named by Project Aether (June 2026). Chat-open frequency weaponized as a surveillance metric by abusive partners. Defense: unread-only reconciliation. Not found in published security literature.

**Project Aether rule:** if an outbound signal benefits anyone other than the user, it doesn't get sent.

## Testing

18 test files, 1,493 lines of test code. 44 specs. 298 Kotlin files. 87,644 lines of code.

## What this means for you

If you are testing Aegis, every bug you find makes the app safer for someone who needs it.

---

Report bugs: [GitHub Issues](https://github.com/artst3in/Aegis/issues)
