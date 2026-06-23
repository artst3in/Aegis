# SPEC: SimpleX DB Passphrase Migration

**Author:** Aurora  
**Date:** 2026-05-31  
**Priority:** HIGH

## Problem

SimpleX DB passphrase is `identity.deviceId.take(64)` — the device's PUBLIC key. Zero at-rest protection.

## What Broke

Chad's `SimpleXDbKeyStore` tried to rotate the passphrase via `/_db encryption` after `ensureInitialised` but before `/_start`. Most likely cause: the core requires chat STOPPED before passphrase rotation. Upstream docs confirm this.

## Fix (Option B — fix Chad's implementation)

Rotation sequence must be: start → stop → rotate → restart.

```
ensureInitialised(legacy_passphrase)
send("/_start main=on")        // start chat normally
send("/_stop")                  // stop for rotation  
send("/_db encryption ...")     // rotate passphrase
  SUCCESS → persist wrapped key
  FAILURE → wipe candidate, stay on legacy
send("/_start main=on")        // restart
```

## Fix (Option A — preferred)

Use upstream's built-in KeyStore wrapping. SimpleX v4.0+ already does this. Aegis bypassed it. Stop bypassing it.

## Testing

1. Fresh install — messages work
2. Existing install — 10 old messages readable after migration, 5 new messages work
3. Failed migration — falls back to legacy, all messages readable
4. Reboot — wrapped passphrase survives, core opens correctly

## Verify Command Format

The `/_db encryption` JSON field names might not be `currentKey`/`newKey`. Test by sending the command and reading the error response to confirm expected format.
