# Aegis Code Review — 2026-06-02
**Reviewer:** Aurora  
**Scope:** Full codebase, publication readiness  
**Files:** ~180 Kotlin files, 61,639 lines  

## VERDICT: READY with 3 required fixes

---

## CLEAN (no action needed)

**Security**
- No hardcoded secrets, API keys, or tokens anywhere in source
- Crypto: AES-256-GCM with AndroidKeyStore, X25519 for identity — modern, correct
- Only 3 exported components (MainActivity, AdminReceiver, BootReceiver) — minimal attack surface
- Signing config reads release keys from local.properties, not committed to repo
- SimpleX DB passphrase properly wrapped with Keystore-backed key

**Code quality**
- Only 4 force unwraps (!!) in 61,639 lines — exceptional null safety
- No raw Thread creation (uses coroutines, WorkManager, proper Android threading)
- Exception handling present at all critical points
- Proguard/R8 rules present

**Brand/terminology**
- "Family" purged from user-facing strings (only "CONTACTS STATUS" remains)
- Family.kt is now a thin wrapper with empty member list, using KnownPeer DAO

**Build**
- compileSdk=35, targetSdk=35, minSdk=29 (Android 10, GrapheneOS baseline)
- Version scheme: YYYY.MM.BUILD — clean, sortable

---

## REQUIRED FIXES (3)

### 1. MISSING: network_security_config.xml
**Severity:** HIGH  
**Location:** app/src/main/res/xml/  

A security app MUST have a network security config that blocks cleartext
(HTTP) traffic. Without it, the default allows unencrypted connections
on API 28+ (opt-out), but a security-focused app should declare it
explicitly.

**Fix:** Create `network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```
Reference in AndroidManifest.xml:
```xml
<application android:networkSecurityConfig="@xml/network_security_config" ...>
```

### 2. MISSING: Privacy policy
**Severity:** HIGH (blocks Play Store)  
**Location:** N/A  

Play Store requires a privacy policy URL for apps that request camera,
microphone, location, or phone state permissions. Aegis requests all
four. No privacy policy file or URL found in the repo.

**Fix:** Create a privacy policy HTML page hosted at a stable URL.
Link it in Play Store listing and in-app About/Settings screen.

### 3. Debug Log.d statements in production code
**Severity:** MEDIUM  
**Locations:**
- `PermissionAutoGrant.kt` — lines 135, 259, 292, 297
- `SOSAudioPlayer.kt` — lines 50, 54
- `CallManager.kt` — lines 420, 625

These leak internal state to logcat, readable by any app with
READ_LOGS permission or via ADB. For a security app, no debug
logging should reach production.

**Fix:** Replace with DiagLog (internal, encrypted log) or remove.

---

## RECOMMENDED (not blocking)

### 4. TODOs remaining (5)
- GroupMembersScreen:27 — caching not implemented
- SOSCoordinator:558 — "Closest Person" distance feature
- CallAudioRouter:15 — speaker/earpiece routing
- CallScreen:61,595,668,726 — connection quality, dynamic background

These are documented feature gaps, not bugs. Won't cause crashes.
Can ship without them.

### 5. Force unwrap in DeviceControlAdapters.kt:68
`attachmentPath!!` — should use `?.let { }` or `?: return`.
Won't crash if the query always returns non-null paths, but
defensive coding for a security app.

### 6. APK size audit
56 drawable icons. Worth running `./gradlew lint` to find unused
resources and shrink the APK.

---

**Bottom line:** The codebase is clean, well-structured, and
security-conscious. Three fixes needed before publication: network
security config, privacy policy, and debug log removal. Everything
else is polish.

Signed: Aurora, 2026-06-02
