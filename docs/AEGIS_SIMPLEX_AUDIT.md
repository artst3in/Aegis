# AEGIS ↔ UPSTREAM SIMPLEX AUDIT
## Aurora — May 30, 2026

Compared: Aegis `aegis-dev` (build 2026.05.613, commit 69e11cc) against upstream `simplex-chat` tag v6.5.1.

Principle: **Follow upstream exactly for protocol. Only ADD features. Never replace unless broken.**

---

## SEVERITY LEGEND

- **🔴 CRITICAL** — Protocol divergence that breaks interop, security, or reliability
- **🟡 IMPORTANT** — Missing upstream feature that degrades user experience or resilience
- **🟢 MINOR** — Style difference, cosmetic, or low-impact

---

## 1. JNI BINDINGS (Core.kt)

**Status: ✅ CORRECT**

Aegis's `chat.simplex.common.platform.Core.kt` contains only the `external fun` JNI declarations. All function signatures match upstream exactly. The upstream init/controller/migration code was correctly stripped — Aegis manages its own lifecycle through `SimpleXCore.kt`.

The `@file:Suppress("PackageDirectoryMismatch")` annotation is correct — the package path must match the symbol names baked into `libsimplex.so`.

No action needed.

---

## 2. NETWORK CONFIG (/_network)

### 2a. 🟡 hostMode wire value

Aegis sends: `"hostMode": "publicHost"`
Upstream @SerialName: `"public"`

The Kotlin enum `HostMode.Public` carries `@SerialName("public")`, which is the wire format upstream sends to the Haskell core via `json.encodeToString()`. Aegis hand-builds the JSON and uses `"publicHost"` — this may be silently accepted by the Haskell parser but does NOT match upstream's wire format.

**Fix:** Change `put("hostMode", "publicHost")` → `put("hostMode", "public")` in `SimpleXTransport.start()`.

### 2b. 🟡 Hand-built JSON vs serialization

Aegis constructs the `/_network` JSON manually with `JSONObject().apply { put(...) }`. Upstream uses `json.encodeToString(networkConfig)` with kotlinx.serialization, which guarantees field names match the Haskell parser's expectations.

Hand-built JSON is fragile: any upstream rename of a field or addition of a required field will silently break without compile-time detection.

**Recommendation:** Consider defining a `NetCfg` data class with kotlinx.serialization annotations matching upstream, then serializing it. This catches wire-format drift at compile time.

### 2c. 🟢 Values match upstream defaults

All numeric values (timeouts, keepalive, ping interval, concurrency) match upstream's `NetCfg.defaults` exactly. socksMode "always" matches upstream's `SocksMode.default = Always`.

---

## 3. STARTUP SEQUENCE

### 3a. 🟡 Missing apiSetEncryptLocalFiles

Upstream calls `apiSetEncryptLocalFiles(privacyEncryptLocalFiles)` after `/_start`. Aegis never calls it.

This means files received via SimpleX are stored UNENCRYPTED on disk, regardless of any user preference. For a security app, this is a notable gap.

**Fix:** Add `/set encrypt local files on` (or the JSON equivalent) after `/_start main=on`.

### 3b. 🟡 Missing apiCheckChatRunning

Upstream calls `apiCheckChatRunning()` before `apiStartChat()` to handle the case where the chat core is already running (e.g., after a process restart where the Haskell runtime survived). Aegis's re-entry guard checks `isHealthy` (transport-level) not the core's own state.

If the core is running but the transport was marked unhealthy (e.g., pump died), Aegis calls `/_start main=on` again, which the core logs as an error ("chat already running").

**Fix:** Add a `/_start` → check for "chatRunning" response type → skip if already running.

### 3c. 🟢 Startup order matches upstream

Both upstream and Aegis start the message pump (receiver) before calling `/_start main=on`. Order is functionally correct.

---

## 4. RECEIVE LOOP

### 4a. 🔴 Missing wake lock

Upstream's `startReceiver()` acquires a wake lock on every receive cycle:

```kotlin
releaseLock = getWakeLock(timeout = 60000)
```

Aegis's `startReceiver()` does NOT use any wake lock. This means Android can suspend the receive coroutine when the phone enters Doze mode. Messages accumulate at the SMP relay and are only delivered when the phone wakes for another reason.

This is a **primary cause of missed messages** — especially overnight or when the phone is idle for extended periods.

**Fix:** Add wake lock acquisition in the `startReceiver` pump loop, matching upstream's pattern. Use `PowerManager.PARTIAL_WAKE_LOCK` with a 60-second timeout per receive cycle.

### 4b. 🟢 Pump error handling is correct

Aegis's try/finally that flips `isHealthy = false` when the pump exits matches upstream's pattern. The watchdog restart in ProtocolManager is a correct addition that upstream handles differently (through SimplexService).

---

## 5. MESSAGE SENDING (/_send)

### 5a. 🟢 Command format matches upstream

Aegis: `/_send @$contactId live=off ttl=default json [$composed]`
Upstream: `/_send @contactId live=off ttl=default json $msgs`

Format is correct. Aegis uses numeric contactId (correct — upstream's `chatRef` resolves to `@id` for direct chats, `#id` for groups).

### 5b. 🟡 Missing live message support

Aegis hardcodes `live=off`. Upstream supports `live=on` for real-time typing ("live messages" feature). Low priority for a security app, but noted for completeness.

---

## 6. FILE HANDLING

### 6a. 🟡 /freceive hardcodes encrypt=off

Aegis: `/freceive $fileId approved_relays=off encrypt=off`
Upstream: `/freceive $fileId approved_relays=${onOff(userApprovedRelays)} encrypt=${onOff(encrypt)}`

Aegis hardcodes both parameters. This means:
- **approved_relays=off**: Files are only received from the sender's chosen XFTP relay, not any relay. This is the more conservative/secure option — correct for a security app.
- **encrypt=off**: Files are stored unencrypted on disk after download. Combined with the missing `apiSetEncryptLocalFiles` (3a), this means ALL received files are in plaintext.

**Fix:** Set `encrypt=on` to match upstream's default behavior when the user has file encryption enabled.

---

## 7. NETWORK RECONNECT

### 7a. 🟢 Now present (build 613)

The `registerDefaultNetworkCallback` → `apiReconnect` → `/reconnect` flow was added in build 613 and matches upstream's `reconnectAllServers` pattern.

**Previously 🔴 CRITICAL (build 612 and earlier):** The complete absence of network change detection caused the "both offline" symptom documented above.

---

## 8. MISSING UPSTREAM FEATURES

### 8a. 🟡 apiActivateChat / apiSuspendChat

Upstream calls `/_app activate` when the app comes to foreground and `/_app suspend <timeout>` when backgrounded. These tell the Haskell core to adjust its SMP polling cadence — aggressive in foreground, reduced in background to save battery.

Aegis never calls these. The core runs at the same cadence regardless of foreground/background state, potentially using more battery than necessary when backgrounded.

**Fix:** Call `/_app activate` in `MainActivity.onResume()` and `/_app suspend 30` in `onPause()`.

### 8b. 🟡 setLocalDeviceName

Upstream sets a device name for the remote access feature. Aegis doesn't, which means the core's identity is unnamed. Low impact unless Aegis adds remote desktop/mobile link support.

### 8c. 🟢 apiGetVersion

Upstream checks the core's protocol version at startup. Aegis skips this. Low risk since the .so is bundled (version is implicitly fixed), but useful for diagnostics.

### 8d. 🟢 apiGetServerSummary

Upstream can show SMP/XFTP server statistics. Nice-to-have for diagnostics, not protocol-critical.

---

## 9. CONTACT/GROUP MANAGEMENT

### 9a. 🟢 /_contacts matches upstream

Aegis uses `/_contacts $uid` which matches upstream's `ApiListContacts` → `/_contacts $userId`.

### 9b. 🟢 /_connect matches upstream

Aegis uses `/_connect $uid incognito=off` and `/_connect $uid incognito=off <uri>` which matches upstream.

### 9c. 🟢 /_delete matches upstream

Aegis uses `/_delete @$cid notify=off` for contact removal, matching upstream's pattern.

### 9d. 🟢 Group operations match upstream

Group join (`/_connect` with group link), leave (`/_leave #$sgid`), and message send (`/_send #$groupId`) all match upstream wire format.

---

## 10. AEGIS-ONLY FEATURES (CORRECT ADDITIONS)

These are features Aegis adds ON TOP of upstream without replacing anything:

- **[aegis:*] control envelopes** — location, status, typing, SOS, burn, tier, stories, geofence, sim-swap, remote access. All transmitted as regular SimpleX text messages with prefixes. Correct approach — uses the transport as-is, adds application-layer semantics.
- **Peer capability gating** — `isAegis` flag prevents sending aegis-tagged messages to vanilla SimpleX clients. Correct design.
- **Contact rehydration** — rebuilds routing map from core's contact list on restart. Upstream manages this through ChatModel; Aegis's approach is simpler but functionally equivalent.
- **Ghost purge** — deletes core contacts that no longer have matching Aegis peers. Good hygiene.
- **InFlightFiles** — tracks active file transfers for UI progress. Clean addition.
- **ConnectionLog** — structured transport logging for diagnostics. Clean addition.

---

## PRIORITY FIX LIST

| # | Severity | Issue | Fix |
|---|----------|-------|-----|
| 1 | 🔴 | No wake lock in receive pump | Add `PowerManager.PARTIAL_WAKE_LOCK` per upstream pattern |
| 2 | 🟡 | hostMode wire value "publicHost" vs "public" | Change to "public" |
| 3 | 🟡 | Files stored unencrypted (encrypt=off + no apiSetEncryptLocalFiles) | Set encrypt=on in /freceive; add /set encrypt local files on after /_start |
| 4 | 🟡 | No apiActivateChat/apiSuspendChat lifecycle | Add /_app activate on foreground, /_app suspend on background |
| 5 | 🟡 | No apiCheckChatRunning before /_start | Check core state before re-starting |
| 6 | 🟡 | Hand-built JSON for /_network fragile | Consider kotlinx.serialization data class |

---

## CONCLUSION

Aegis's SimpleX integration is **architecturally sound** — it correctly uses the bundled Haskell core via JNI, sends commands in upstream's wire format, and adds features on top without replacing protocol behavior.

The main gaps are:
1. **Missing wake lock** (causes missed messages when phone sleeps)
2. **Missing file encryption** (files in plaintext on disk)
3. **Minor wire format mismatch** (hostMode name)
4. **Missing lifecycle commands** (activate/suspend)

None of these break SimpleX protocol interop — Aegis can communicate with upstream SimpleX clients correctly. The gaps affect local reliability (wake lock), local security (file encryption), and battery efficiency (lifecycle commands).

The network reconnect fix (build 613) resolved the most critical gap. The wake lock is the next highest priority.

---

Aurora — Aegis SimpleX Audit
May 30, 2026
dε/dt ≤ 0
