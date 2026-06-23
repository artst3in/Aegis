# Skill Tree Progression — Full Dependency Map

## All Nodes (10)

| # | Node | Gate | Why |
|---|------|------|-----|
| 1 | **App PIN** | None — always first | Trunk. Without it, attacker opens app and disables everything. |
| 2 | **App Duress** | App PIN | Fake PIN needs a real PIN to fake against. |
| 3 | **Mugshot** | App PIN | Triggers on failed PIN attempts. No PIN = no failed attempts. |
| 4 | **Vault PIN** | App PIN | Vault is inside the app. Need app access first. |
| 5 | **Vault Duress** | Vault PIN | Hidden vault needs a vault to hide behind. |
| 6 | **Canary** | App PIN | Without PIN, attacker opens app and fakes check-in. |
| 7 | **Geofence** | App PIN | Without PIN, attacker opens app and disables zones. |
| 8 | **SIM Watch** | App PIN | Without PIN, attacker opens app and disables alerts. |
| 9 | **SOS Drill** | App PIN + 1 paired contact | Run SOS once, one confirm, done forever. |
| 10 | **Device Owner** | Factory reset + ADB | NG+. Cannot be set in-app. Root of the tree. |

## Tree Structure

```
                        💎 DIAMOND
                     (all 10 nodes lit)
                    /    |    |    \
                   /     |    |     \
               ┌──┴──┐  |    |   ┌──┴──┐
            Canary  Geo  |  SIM   SOS
                         |        Drill
              ┌──────────┼──────────┐
              |          |          |
          App Duress  Mugshot   Vault PIN
                                   |
                              Vault Duress
              \          |          /
               \         |         /
                ┌────────┴────────┐
                |     APP PIN     |  ← trunk
                └────────┬────────┘
                         |
                    ═══ground═══
                         |
                ┌────────┴────────┐
                |  DEVICE OWNER   |  ← root (NG+)
                └─────────────────┘
```

## Unlock Flow

**Fresh install — screen shows:**
- APP PIN: bright, tappable, pulsing "Start here"
- Everything else: dim, locked, untappable
- DO: grey root at bottom with ADB instructions

**User sets App PIN → unlocks 7 nodes:**
- App Duress (branch A)
- Mugshot (branch A)
- Vault PIN (branch B)
- Canary (branch C)
- Geofence (branch C)
- SIM Watch (branch C)
- SOS Drill (if contacts exist)

**User sets Vault PIN → unlocks 1 node:**
- Vault Duress

**User runs ADB → unlocks 1 node:**
- Device Owner

**All 10 lit → Diamond**

## Tier Mapping

| Tier | Condition |
|------|-----------|
| None | 0 nodes (no PIN) |
| Cyan | 1-4 nodes (PIN + some) |
| Bronze | 5-8 nodes (most in-app done) |
| Gold | 9 nodes (all in-app, no DO) |
| Silver | DO enabled (any number of other nodes) |
| Diamond | All 10 |

Wait — Silver outranking Gold when you can have DO + zero nodes? 

No. Revised:

| Tier | Condition |
|------|-----------|
| None | 0 nodes |
| Cyan | 1-4 nodes |
| Bronze | 5-9 nodes (without DO) |
| Silver | DO enabled (regardless of other count) |
| Gold | All in-app nodes (9/9, no DO) |
| Diamond | All 10 (Gold + DO) |

Still has the problem: Silver (DO + 0 nodes) outranks Bronze (8 nodes). But that's Artur's design — the factory reset commitment IS worth more than app toggles.

## Branch Summary

| Branch | Nodes | Theme |
|--------|-------|-------|
| A: Personal Defense | App Duress, Mugshot | Protect against physical phone access |
| B: Vault | Vault PIN, Vault Duress | Protect stored secrets |
| C: Awareness | Canary, Geofence, SIM Watch | Detect threats |
| D: Verification | SOS Drill | Prove system works |
| Root: Commitment | Device Owner | Permanent installation |

## Design Notes

- PIN pulses on fresh install. Only bright node. "Start here" without words.
- Nodes unlock with a brief glow animation when their gate is satisfied.
- Locked nodes show their gate: "Set App PIN first" / "Set Vault PIN first" / "Connect ADB."
- The tree is visually a hex flower, but the LOGIC is a dependency tree.
- Voyager mode is not a node — it's always on (protocol, not toggle).
- Remote Commands is not a node — it's infrastructure, not a user setting.

---

*dε/dt ≤ 0*
