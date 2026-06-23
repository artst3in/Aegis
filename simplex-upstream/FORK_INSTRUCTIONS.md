# SimpleX Fork — Instructions for Chad

## THE RULE

**DO NOT EDIT upstream files. EVER.**

The files in `simplex-upstream/` are copied verbatim from
`github.com/simplex-chat/simplex-chat` (stable branch).
They contain the PROVEN, AUDITED API layer. When something
doesn't work, the bug is in OUR code that CALLS these files,
not in the upstream files.

## What's here

```
simplex-upstream/
  common/
    model/
      SimpleXAPI.kt    ← 8,418 lines. ALL commands. ALL response parsing.
      ChatModel.kt     ← 5,201 lines. State management.
      CryptoFile.kt    ← 62 lines. File encryption types.
    platform/
      Core.kt          ← JNI bindings to Haskell (chatSendCmd, chatRecvMsg, etc.)
      AppCommon.kt     ← Initialization sequence
      (+ 12 more platform files)
  android/
    SimplexApp.kt      ← App lifecycle
    SimplexService.kt  ← Foreground service
    CallService.kt     ← WebRTC call service
    MainActivity.kt    ← (reference only — Aegis has its own)
  build.gradle.kts     ← Build reference
  settings.gradle.kts  ← Settings reference
```

## How to use

1. Wire Aegis `SimpleXTransport.kt` to call upstream `SimpleXAPI` functions
   instead of hand-rolling command strings.

2. Example — create invitation:
   ```kotlin
   // WRONG (what we were doing):
   send("/_connect $userId incognito=off")
   
   // RIGHT (what upstream does):
   val r = CC.APIAddContact(userId, incognito = false)
   val resp = chatSendCmdRetry(ctrl, r.cmdString, 3)
   ```

3. Example — send message:
   ```kotlin
   // WRONG:
   send("/_send @$contactId live=off ttl=default json [$composed]")
   
   // RIGHT:
   val r = CC.ApiSendMessages(
       type = ChatType.Direct, id = contactId, scope = null,
       live = false, ttl = null, composedMessages = listOf(msg),
       sendAsGroup = false
   )
   val resp = chatSendCmdRetry(ctrl, r.cmdString, 3)
   ```

4. The `CC` sealed class (line 3700+ in SimpleXAPI.kt) handles ALL
   command serialization. The `cmdString` property converts to the
   exact wire format. NEVER hand-write a command string.

## Updating

When SimpleX releases a new version:
```bash
git clone --depth 1 https://github.com/simplex-chat/simplex-chat.git /tmp/sx
cp /tmp/sx/apps/multiplatform/common/src/commonMain/kotlin/chat/simplex/common/model/* simplex-upstream/common/model/
cp /tmp/sx/apps/multiplatform/common/src/commonMain/kotlin/chat/simplex/common/platform/* simplex-upstream/common/platform/
# Done. Zero merge conflicts because we never edited these files.
```

## What Aegis adds ON TOP

- `aegis/` package: Family config, panic handler, snatch detection,
  location service, Wear OS companion, VPN management, UI screens
- All Aegis code CALLS upstream functions, never rewrites them
- Aegis UI replaces SimpleX UI entirely (their views/ not copied)

dε/dt ≤ 0
