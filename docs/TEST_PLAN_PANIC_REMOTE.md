# Device Test Plan — Panic/SOS + Remote Access

Status: DRAFT for first real-device validation (the two load-bearing
safety paths that have never been exercised end-to-end).

Goal: prove that, on real hardware, a panic broadcast actually reaches
the right people with the right payload under hostile conditions, and
that remote access does what it claims while refusing what it must.

Priority key:
- **P0** — lives depend on it. A failure here means the app fails the
  person at the worst moment. Must pass before any "beta" claim.
- **P1** — important, but a failure degrades rather than defeats.
- **P2** — polish / nice-to-have.

---

## 0. Setup

### Devices
- **Phone A** = the at-risk user's phone (SOS *victim* / remote *target*).
- **Phone B** = a trusted contact's phone (SOS *responder* / remote *operator*).
- Ideally a **Phone C** at EMERGENCY tier, and one **non-Aegis** SimpleX
  contact, to prove tier filtering. If only two phones, simulate C by
  re-tiering B between runs.

### Contact tiers to configure (on Phone A)
- B → **TRUSTED**
- C → **EMERGENCY** (if available)
- A non-Aegis SimpleX contact → must be **UNTRUSTED** and *unpromotable*.

### Permissions to grant on Phone A (the capture device)
CAMERA, RECORD_AUDIO, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS.
Run each P0 test **twice** where noted: once all-granted, once with the
relevant permission **denied**, to confirm graceful degradation.

### Device Admin / Owner (Phone A)
- Enroll Device Admin for lock tests.
- For wipe tests: `adb shell dpm set-device-owner app.aether.aegis.debug/app.aether.aegis.admin.AegisAdminReceiver`
  (debug applicationId). Without Device Owner, wipe is expected to
  **silently no-op** — that is itself a P0 test.

### Logcat (run on both phones, USB or wireless adb)
```bash
# Phone A (victim/target)
adb -s <A> logcat -v time \
  PowerSOS:* SOSSnapshotStream:* MugshotCapture:* LockdownController:* \
  RemoteAccess:* RemoteCommand:* RemoteWatch:* Ringer:* SirenManager:* AegisApp:*

# Phone B (responder/operator)
adb -s <B> logcat -v time SOSCoordinator:* SOSAlertStore:* RemoteAccess:* AegisApp:*
```

### Network conditions to cover
Each P0 delivery test is run under three conditions:
1. **WiFi** (baseline).
2. **Cellular-only** (WiFi off) — the realistic in-the-field case.
3. **Target offline at send, comes online later** (airplane mode on the
   victim/target, then restored) — proves the queue→deliver path.

---

## 1. PANIC / SOS  (do this first)

### 1.1 Triggers — P0
| # | Test | Steps | PASS |
|---|------|-------|------|
| T1 | Button hold | On SOS screen, hold 1.0s | Broadcast starts; <1.0s hold aborts; haptic heartbeat during hold |
| T2 | **Duress PIN (silent)** | Lock A, enter the **duress** PIN | A unlocks to the decoy profile; **NO "SOS ACTIVE" notification on A**; B/C receive an alert tagged **DURESS**. logcat shows trigger=DURESS |
| T3 | Power-button ×4 | Toggle screen on/off 4× within 2s | SOS fires; logcat `PowerSOS: ...4 toggles` |
| T4 | Lock-screen hex | Tap red SOS hex on lock screen (no unlock) | SOS fires without unlocking |

> **T2 is the single most important test in this document.** If the
> duress path shows *any* attacker-visible signal, or fails to alert the
> real family, it is a P0 failure regardless of everything else.

### 1.2 Recipients — P0
| # | Test | PASS |
|---|------|------|
| R1 | TRUSTED receives | B (Trusted) gets the SOS notification + dashboard entry |
| R2 | EMERGENCY receives | C (Emergency) gets the SOS notification + dashboard entry |
| R3 | UNTRUSTED excluded | The untrusted/non-Aegis contact receives **nothing** — no alert, and critically **no plaintext leak** of the panic |
| R4 | Non-Aegis unpromotable | Contact-detail tier picker refuses to raise the non-Aegis contact above Untrusted |
| R5 | Live roster | Remove B from Trusted mid-incident → next 30s re-broadcast no longer reaches B |

### 1.3 Payload delivered to recipient — P0/P1
| # | Test | PASS | Pri |
|---|------|------|-----|
| P1 | Alert + location | B sees `SOS <TRIGGER> <id>` + lat/lng + OSM link; map pin lands near A's real position | P0 |
| P2 | Location updates | B's pin updates over time (interval widens as A's battery drops) | P1 |
| P3 | First audio segment | B receives an audio clip within ~15s; plays back real audio | P0 |
| P4 | Continued audio | Further segments arrive ~every 60s | P1 |
| P5 | Snapshots | B receives camera frames (~5s cadence) when A's battery >40% | P1 |
| P6 | Live call | Highest-priority peer gets an auto-answer SOS stream when battery permits | P1 |
| P7 | Cancel | A cancels → B's alert state clears, notification dismissed | P0 |

### 1.4 Conditions — P0
Re-run **R1 + P1 + P3** under each network condition:
| # | Condition | PASS |
|---|-----------|------|
| C1 | Cellular-only | Alert + location + first audio still reach B |
| C2 | Screen locked at trigger | SOS still fires and delivers |
| C3 | Target offline at trigger, restored 2 min later | Queued SOS delivers on reconnect (verify B receives it after A comes back) |
| C4 | App backgrounded | Broadcast + capture continue |

### 1.5 Mugshot / duress capture — P0
| # | Test | PASS |
|---|------|------|
| M1 | Wrong-PIN threshold | After N wrong PINs, front+rear capture lands in `filesDir/mugshots/` with GPS+timestamp EXIF; owner notification "Someone tried to crack your PIN" |
| M2 | **Local-only guarantee** | **Nothing is sent to any contact** — confirm B/C receive no mugshot |
| M3 | Duress ≠ mugshot | A *correct duress* PIN does **not** trigger a mugshot (it's the wrong-PIN path only) |

### 1.6 PowerBudget gates — P1
Validate the battery cascade (or trust the unit tests + spot-check one):
camera stream stops ≤40%, mic stream ≤25%, audio-chunk shipping ≤15%,
GPS interval → 1h ≤5%; **all gates open while charging.**

### 1.7 Degradation (permission denied) — P0
| # | Test | PASS |
|---|------|------|
| D1 | No CAMERA | SOS still fires + delivers location/audio; frames skipped, no crash |
| D2 | No RECORD_AUDIO | SOS still fires + delivers location; audio skipped |
| D3 | No LOCATION | SOS still fires + delivers; location fields empty, no crash |

---

## 2. REMOTE ACCESS

### 2.1 Auth / trust gate — P0 (these protect the user from the feature)
| # | Test | PASS |
|---|------|------|
| A1 | Correct PIN + granted | B (Trusted, remote-access enabled) auths → session opens; logcat `RemoteAccess: AUTH OK` |
| A2 | **Wrong PIN** | B with wrong PIN → AUTH_DENIED; no session; no command works |
| A3 | **No grant** | A contact *without* `remoteAccessEnabled` but correct PIN → denied |
| A4 | **3 wrong PINs / 60s → auto-revoke** | After 3 failures, sender is persistently revoked; further AUTH dropped **silently**; owner gets an attempt notification |
| A5 | Manual revoke | Owner revokes B in contact detail → B's commands stop; B sees "revoked" badge |
| A6 | Forged sid | Inject a command with a bogus session id → rejected (`no session`), nothing executes |
| A7 | Session expiry | Idle 5+ min → next command fails with `no session` until re-auth |

> A1–A4 together are the P0 gate: an arbitrary contact must **not** be
> able to locate/listen/wipe this phone. If any of A2/A3/A4/A6 lets a
> command through, stop and treat as critical.

### 2.2 Commands (after valid auth) — P0/P1
| # | Command | PASS | Pri |
|---|---------|------|-----|
| L1 | LOCATE (admin enrolled) | A locks; B sees location + mugshot; logcat `fireLocate: lock=locked` | P0 |
| L2 | **LOCATE (no Device Admin)** | B still gets location, but `lockOk=false` surfaced as "lock did not fire" — **not** a silent success | P0 |
| L3 | SNAPSHOT front/rear | Frame returns to B in correct slot | P1 |
| L4 | LISTEN 10s | Audio clip returns to B; capture is silent on A | P1 |
| L5 | SIREN / SIREN_OFF | A blares emergency tone (bypasses DnD); off stops it | P1 |
| L6 | RING / RING_OFF | A plays light tone (no DnD bypass); stops on off/exit/timeout | P2 |
| L7 | DISPLAY message | Sticky lock-screen message appears on A; empty clears it | P2 |
| L8 | LIVE_CAM / LIVE_MIC | B gets an auto-answer stream; flip toggles lens | P1 |
| L9 | WATCH ticks | After auth, B's frames/location refresh ~every 25s; pause/resume works | P1 |
| L10 | PING | B sees A's battery/charging refresh | P2 |

### 2.3 Wipe — P0 (the most dangerous + the known landmine)
| # | Test | PASS |
|---|------|------|
| W1 | Wipe re-auth gate | Wipe >5 min after auth → `needs_reauth`; must re-enter PIN |
| W2 | Wipe with Device Owner | 3-step confirm on B → A factory-resets; broadcasts `wiped` to SOS targets |
| W3 | **Wipe WITHOUT Device Owner** | **KNOWN ISSUE:** B sees a success ("OK") but A does **not** wipe (silent no-op). Decide: is a silent failure acceptable for a destructive command? Likely should surface "wipe not possible — not Device Owner" to the operator. Flag for fix. |

### 2.4 Conditions — P0
| # | Condition | PASS |
|---|-----------|------|
| RC1 | Target on cellular-only | LOCATE returns (network-provider location ok) |
| RC2 | Target offline at send | Command queues; delivers when target reconnects (or expires) — verify no false "success" implies execution |
| RC3 | Target screen locked | LOCATE/commands still execute |
| RC4 | Permission denied (location/mic/camera) | Typed error toast on B (`needs_*_permission`), not silent drop |

---

## 3. Top risks to watch (where I'd bet failures hide)

1. **Duress silence (T2)** — any attacker-visible tell defeats the whole point.
2. **Locked-screen + cellular + offline delivery (C1–C3, RC1–RC3)** — the
   conditions a real incident happens under, and the least-tested.
3. **Wipe silent-fail on non-DO (W3)** — destructive command reporting
   success without acting.
4. **Auth gate (A2/A3/A4/A6)** — the feature must refuse outsiders.
5. **First audio/snapshot actually arriving (P3/P5)** — capture pipelines
   are where OEM camera/mic quirks bite.
6. **Background-start / FSI on Android 12+/14+** — notifications or
   auto-launch silently dropped by background-activity-launch rules.

## 4. How to record results
For each test: condition, PASS/FAIL, and on fail paste the relevant
logcat lines + what the *recipient/operator* actually saw (not just the
sender's "Sent" toast — that toast means "queued", not "worked").
