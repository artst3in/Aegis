# Shield Tier System — Avatar Frames

**Status:** IMPLEMENTED — Chad (ShieldTier enum + ShieldTierEngine in
app/src/main/java/app/aether/aegis/admin/ShieldTier.kt, surfaces in
SecurityScreen + the avatar-frame renderer; 11 nodes counted today
with SOS Drill the only one not wired — see SPEC_SOS_DRILL.md).
**Owner:** Chad (implementation), Aurora (spec)

## Five Tiers — Medals to Brand Crown

| # | Frame | Nodes | Requirement |
|---|-------|-------|-------------|
| 1 | **None** | 0 | Nothing configured |
| 2 | **Bronze** | 1 | PIN set (trunk node) |
| 3 | **Silver** | 2-9 | Working through features |
| 4 | **Gold** | 10 | All in-app nodes complete |
| 5 | **Cyan** | 11 | Gold + Device Owner |

## Why Cyan Is the Top

Cyan is the Aegis brand color. It's not a default — it's the crown. The person glowing cyan wiped their phone, rebuilt around Aegis, and maxed every node. Standard medal progression (bronze → silver → gold) leads to the brand identity as the ultimate achievement.

## Design

One axis. Node count. No parallel tracks. DO is node 11. Cyan requires ALL 11.

## Device Admin and Device Owner — Two-For-One on Owner

Device Admin is its own node, distinct from Device Owner.

- A standard Play Store install can enrol Device Admin via the
  in-app one-tap intent (`ACTION_ADD_DEVICE_ADMIN`). That's it —
  Admin is achievable for every user.
- Device Owner can only be set via `dpm set-device-owner` after
  a factory reset. ADB is required.
- Setting Device Owner automatically activates the admin receiver
  (Android requires the admin to exist for the DO promotion to
  succeed). So a user who reaches DO has **both** Admin and Owner
  active at once — Owner counts as two node activations.

This is by design. Owner is the harder achievement; the reward
is two ladder rungs of progress instead of one. A user who only
ever enrols Admin tops out at Gold (10 nodes); reaching Cyan
requires actually achieving DO.

## Difficulty Curve

| Transition | Effort |
|------------|--------|
| None → Bronze | 30 seconds (set PIN) |
| Bronze → Silver | 10 minutes (toggle a second feature) |
| Silver → Gold | One session (max all 10 in-app nodes including Admin) |
| Gold → Cyan | Factory reset + ADB (NG+) — one tier shift, two nodes earned |

## Rules

- Frame visible to all contacts on dashboard
- Frame updates in real-time as nodes are enabled/disabled
- Disabling a node drops the tier immediately
- No timers, no decay, no drills gating tiers
- Cyan = Aegis brand color = highest honor

---

*dε/dt ≤ 0*
