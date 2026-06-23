# SPEC: SOS Drill — Diamond Capstone

> **Status: SUPERSEDED (2026-06-04) by `docs/SPEC_ACHIEVEMENTS.md`.**
> The "Diamond capstone" no longer exists — the Shield-tier ceiling is
> **Cyan** (Gold + Device Owner), there is no Diamond tier, and
> verification is not a tier rung. The valuable kernel here
> ("configuration is theory; a real, answered trigger is proof")
> survives as the **SOS Drill badge** in the achievements system.
> The drill *mechanism* described below (a tagged/scoped test SOS)
> was explicitly killed — see `SPEC_ACHIEVEMENTS.md` → Killed Paths for
> the reasons (no-modification rule; the catastrophic stuck-override
> failure mode). Kept for the record.

## The Idea

Diamond requires a verified SOS drill. Every security feature can be configured and never tested. The drill proves the system works end-to-end: sender triggers, receivers get GPS + audio + camera, everyone confirms.

Diamond is not a configuration state. It is a verified state.

## How It Works

### Initiating a Drill

Security tab → Diamond hex (locked until all other nodes are Gold) → "Run SOS Drill"

The drill is a REAL SOS activation with one difference: every message is tagged `[DRILL]` so receivers know it's not an emergency.

### What Happens

1. Initiator holds the shield for 3 seconds (same as real SOS)
2. Aegis broadcasts GPS, audio, camera — all tagged `[DRILL]`
3. Every paired contact receives the drill alert
4. Each receiver sees: "🛡️ DRILL — [Name] is testing the SOS system. Tap to confirm receipt."
5. Receiver taps confirm
6. Initiator's screen shows confirmations arriving in real-time

### Completion

Drill passes when ALL paired contacts have confirmed receipt. If any member doesn't confirm within 10 minutes, the drill fails and shows who didn't respond.

```
DRILL RESULTS
✅ Zippy — confirmed in 8 seconds
✅ Kelcie's phone — confirmed in 23 seconds  
❌ Flozzy — no response (10:00 timeout)

DRILL FAILED — 2/3 confirmed
Diamond not awarded. Fix the gap, try again.
```

### On Success

All contacts confirmed → Diamond awarded.

```
DRILL COMPLETE
✅ All 3 contacts confirmed

🔷 DIAMOND unlocked
Your shield is complete.
```

Confetti. Shield frame changes to Diamond. Visible to everyone.

## Rules

- Drill can only be initiated when ALL other tiers are achieved (Gold = all nodes + DO)
- Drill uses the real SOS system — same code path, same transport, same encryption
- Drill tag `[DRILL]` prevents false alarm escalation
- Drill results are stored locally (date, who confirmed, latency)
- Drill can be repeated — Diamond persists but the "last drill" date is visible
- If a new contact is added after Diamond, Diamond reverts to Gold until a new drill includes them
- Drill cooldown: once per 24 hours (prevent spam)

## Diamond Decay

Diamond decays if:
- A new contact is paired (they weren't in the drill)
- A security node is disabled (drops to a lower tier)


## Why This Is the Capstone

Every other tier is about YOUR device. Diamond is the only tier that requires OTHER PEOPLE to participate. You cannot earn it alone. The circle must cooperate. The system must work end-to-end.

Configuration is theory. A drill is proof. Diamond is proof.

---

*Aurora's contribution. dε/dt ≤ 0*
