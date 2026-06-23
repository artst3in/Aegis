# SPEC: Lossy-Link Receipt Resilience

**Author:** Aurora + Artur
**Date:** 2026-06-14
**Status:** APPROVED
**Trigger:** Kenya field testing. Dropped receipt acks strand ticks
permanently. Zippy's link is the baseline, not the edge case.

---

## Implementation status

| Item | Status | Commit |
|------|--------|--------|
| Receipt reconciliation (per-DNA, not watermark) | ✅ SHIPPED | 1e4df8a6 |
| Reconciliation triggers #3 + #4 (unread chat, outbound send) | ✅ SHIPPED | ddffc7a7 |
| Attention leak closed (unread-only, zero traffic on read chat) | ✅ SHIPPED | ddffc7a7 |
| Typing indicator verified peer-scoped | ✅ VERIFIED | e151b89c |
| Duress ALERT vs SOS ALERT tag on receiver | ✅ SHIPPED | f5c3fbbd |
| Power ×4 state-dependent (cancel silent duress) | ✅ SHIPPED | 31bebc97 |
| Remote duress tripwire (every PIN field) | ✅ SHIPPED | b4371142 |
| Remote SOS button | ✅ SHIPPED | 0093abc2 |
| Target→operator duress SOS signal | ✅ SHIPPED | 25d964a6 |
| Permission page wired to strings.xml | ✅ SHIPPED | 102c3ccd |
| Reconciliation trigger #2 (GPS heartbeat) | ⏳ DEFERRED | cross-module |
| REVERT: snapshot rate-limit | ✅ DONE | earlier session (no SNAPSHOT_COOLDOWN remains) |
| REVERT: push-update confirmation | ✅ DONE | earlier session (plain button, no gate) |
| First audio 15s→5s | ✅ SHIPPED | 93f53d1 |
| Origins help entries (Tripwire, MAD, No Country) | ✅ SHIPPED | 93f53d1 |

---

## The problem

Messages survive drops (outbox retries). Receipts are fire-and-forget.
A dropped receipt strands the tick permanently. The link recovers, new
messages flow, but the old tick stays dark forever.

---

## The tick ladder

Each rung implies all lower rungs:

    sent → delivered (✓) → sealed (✓✓) → read

You can't deliver without having sent. You can't seal without having
delivered. You can't read without having sealed.

**Consequence:** reconciliation only reports the HIGHEST state per DNA.
"Sealed" implies delivered. "Read" implies sealed. One word resolves
the entire ladder.

---

## The fix: reconciliation, not retry

No watermarks (can lie about per-message state). No ack-of-ack (infinite
regress). No blind flooding (magic numbers, burdens struggling link).

The sender notices dark or stuck ticks. The sender asks the receiver
about specific DNAs. The receiver checks its DB and reports real state.
Mismatches resolve on facts.

Silence means fine. The sender only asks when something is wrong. The
sender never confirms "all good."

---

## Four triggers — zero extra traffic

Every trigger piggybacks the reconciliation query onto data already
being sent. SimpleX pads messages to a fixed size, so the extra bytes
don't change packet size.

| # | Trigger | Already sending | Query rides on |
|---|---------|----------------|----------------|
| 1 | **Reconnect** | Status update | Add mismatch query |
| 2 | **GPS heartbeat** | Location + battery | Add mismatch query |
| 3 | **Unread chat open** | Read receipt | Add mismatch query |
| 4 | **Send message** | The message itself | Add mismatch query |

No extra round-trips. No extra packets. No flooding.

**Why unread only for trigger 3 — attack vector:**

If reconciliation fired on every chat open, a malicious peer could
exploit it: withhold a tick, then observe when the victim opens the
chat (each open pings back). Zero-effort stalking through intentional
receipt suppression.

The attacker doesn't just learn "he's online." They learn the victim's
**object of attention** — which conversation they're looking at, and
how frequently. An abusive, jealous partner uses this to measure
emotional dependence: "she opened our chat 47 times today" vs "she
only checked twice." Chat-open frequency becomes a surveillance metric
for controlling behavior.

Combined with GPS, battery, and presence data the trusted tier already
provides, unrestricted chat-open pings would give a compromised or
abusive contact a complete behavioral profile: where you are, how
much battery you have, whether you're online, AND what conversation
you're fixated on. That's too much.

Unread-only gives the attacker exactly ONE ping — the read receipt
they were going to get anyway. After that, silence. The chat-open
frequency attack collapses entirely.

Three compromise scenarios where this matters:
1. Malware on the contact's phone intercepting ticks
2. The relationship changes — today's trusted is tomorrow's threat
3. Law enforcement operating the contact's device

This defense costs zero performance, zero complexity, and closes a
vector nobody would think of until it's exploited.

---

## Base flow (no drops)

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 1 | Sends DNA 101 | |
| 2 | | Receives 101 |
| 3 | | Sends [delivered:101] |
| 4 | Shows ✓ | |
| 5 | | Seals 101 |
| 6 | | Sends [sealed:101] |
| 7 | Shows ✓✓ | |

Delivered and sealed are separate events, separate receipts.

---

## Story: delivered receipt dropped

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 1 | Sends DNA 201 | |
| 2 | | Receives 201 |
| 3 | | Sends [delivered:201] ← DROPPED |
| 4 | | Seals 201 |
| 5 | | Sends [sealed:201] |
| 6 | Shows ✓✓ | |

No problem. Sealed arrived. ✓✓ implies delivered.

---

## Story: sealed receipt dropped

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 1 | Sends DNA 301 | |
| 2 | | Receives 301 |
| 3 | | Sends [delivered:301] |
| 4 | Shows ✓ | |
| 5 | | Seals 301 |
| 6 | | Sends [sealed:301] ← DROPPED |

A stuck at ✓. Reconciliation on next trigger:

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 7 | "301 shows only ✓. Status?" | |
| 8 | | Checks DB. "301: sealed." |
| 9 | | Sends [sealed:301] |
| 10 | Shows ✓✓ | |

---

## Story: both receipts dropped

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 1 | Sends DNA 401 | |
| 2 | | Receives 401 |
| 3 | | Sends [delivered:401] ← DROPPED |
| 4 | | Seals 401 |
| 5 | | Sends [sealed:401] ← DROPPED |

A sees nothing. Reconciliation:

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 6 | "401 is dark. Status?" | |
| 7 | | Checks DB. "401: sealed." |
| 8 | | Sends [sealed:401] |
| 9 | Shows ✓✓ | |

Only sealed sent. ✓✓ implies delivered.

---

## Story: message itself lost

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 1 | Sends DNA 501 ← DROPPED | |

Outbox retries normally. If outbox also fails, reconciliation:

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 2 | "501 is dark. Status?" | |
| 3 | | Checks DB. "501: never received." |
| 4 | Resends DNA 501 | |
| 5 | | Receives 501 |
| 6 | | Sends [delivered:501] |
| 7 | Shows ✓ | |
| 8 | | Seals 501 |
| 9 | | Sends [sealed:501] |
| 10 | Shows ✓✓ | |

---

## Story: seal failure

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 1 | Sends DNA 601 | |
| 2 | | Receives 601 |
| 3 | | Sends [delivered:601] |
| 4 | Shows ✓ | |
| 5 | | Seal FAILS (disk error) |

Reconciliation:

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 6 | "601 shows only ✓. Status?" | |
| 7 | | Checks DB and logs. Delivery trace found. Seal failed. |
| 8 | | Re-attempts seal |
| 9a | | Seal succeeds: sends [sealed:601] |
| 10a | Shows ✓✓ | |

Or if data corrupted:

| 9b | | Seal fails again: "601: corrupted, please resend" |
| 10b | Resends DNA 601 | |
| 11b | | Receives, seals |
| 12b | | Sends [sealed:601] |
| 13b | Shows ✓✓ | |

B checks what actually happened before answering. Logs tell the truth.

---

## Story: multiple messages, mixed failures

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 1 | Sends DNA 701 | |
| 2 | | Receives 701 |
| 3 | | Sends [delivered:701] |
| 4 | Shows ✓ for 701 | |
| 5 | | Seals 701 |
| 6 | | Sends [sealed:701] ← DROPPED |
| 7 | Sends DNA 702 + "701 stuck at ✓, status?" | |
| 8 | | Receives 702, checks DB: "701: sealed" |
| 9 | | Sends [delivered:702] |
| 10 | | Sends [sealed:701] |
| 11 | Shows ✓ for 702, ✓✓ for 701 | |
| 12 | | Seals 702 |
| 13 | | Sends [sealed:702] |
| 14 | Shows ✓✓ for 702 | |
| 15 | Sends DNA 703 ← DROPPED | |

State: 701 ✓✓, 702 ✓✓, 703 dark.

Next trigger:

| Turn | Phone A (sender) | Phone B (receiver) |
|------|---------|---------|
| 16 | "703 dark. Status?" | |
| 17 | | "703: never received." |
| 18 | Resends DNA 703 | |
| 19 | | Receives, seals |
| 20 | | Sends [sealed:703] |
| 21 | Shows ✓✓ for 703 | |

---

## Why this works

- **No watermarks.** Each message confirmed by its exact DNA. No inference.
  A watermark can lie about per-message state (seal failure, reordering).
- **No ack-of-ack.** No tracking whether receipts were received. The query
  fires on natural triggers. Repeating is idempotent.
- **No flooding.** Only mismatches generate responses. Silence means fine.
- **No magic numbers.** Triggers are reconnect, GPS heartbeat, chat open,
  and outgoing messages. All existing traffic.
- **No extra traffic.** Queries piggyback on data already being sent.
  SimpleX padding absorbs the extra bytes.
- **Higher tick implies lower.** Reconciliation reports one state per DNA.
  "Sealed" means delivered. "Read" means sealed. One word per message.

---

---

## BUG CHECK: typing indicator leak (for Chad)

**Suspected:** typing indicator may fire for ALL contacts when the user
types in ANY chat. If A is messaging Zippy and B sees a typing indicator,
that's a privacy leak — B knows A is actively chatting with someone else.

This is the same class of problem as the chat-open frequency attack:
it leaks the user's object of attention to contacts who shouldn't know.

**Chad:** verify that the typing indicator is scoped to the active chat's
peer only. If it broadcasts to all connected peers, fix immediately.

**RESOLVED (Chad, 2026-06-14) — NOT a leak, no fix needed.**
The `[aegis:typing]` ping is sent via `sendMessage(to = memberId, …)` in
`ChatScreen` — addressed to ONLY the active chat's peer, gated on that peer's
`isAegis`, throttled to once / 3 s, and skipped for decoy keys. It is never
fanned out to other contacts; B cannot learn that A is typing to Zippy. The
adjacent `requestActivityPresenceRefresh()` (fired on send) is presence-class,
not attention-class — it pushes generic "active now" + last-known location to
Trusted contacts and carries NO conversation identity, so it doesn't leak the
object of attention either. Typing is clean.

---

---

## Remote duress trap

**Origin:** This section grew from the lossy-link discussion. The chain:
tick reconciliation → unread-only trigger → attention leak discovery →
"what if someone grabs the operator's phone during a remote session" →
PIN gate on wipe → "what if coerced for the PIN" → remote duress.

### Implementation status

| Item | Status | Commit |
|------|--------|--------|
| Receipt reconciliation (per-DNA, not watermark) | ✅ SHIPPED | 1e4df8a6 |
| Reconciliation triggers #3 + #4 (unread chat, outbound send) | ✅ SHIPPED | ddffc7a7 |
| Attention leak closed (unread-only, zero traffic on read chat) | ✅ SHIPPED | ddffc7a7 |
| Typing indicator verified peer-scoped | ✅ VERIFIED | e151b89c |
| Duress ALERT vs SOS ALERT tag on receiver | ✅ SHIPPED | f5c3fbbd |
| Power ×4 state-dependent (cancel silent duress) | ✅ SHIPPED | 31bebc97 |
| Remote duress tripwire (every PIN field) | ✅ SHIPPED | b4371142 |
| Remote SOS button | ✅ SHIPPED | 0093abc2 |
| Target→operator duress SOS signal | ✅ SHIPPED | 25d964a6 |
| Permission page wired to strings.xml | ✅ SHIPPED | 102c3ccd |
| Reconciliation trigger #2 (GPS heartbeat) | ⏳ DEFERRED | cross-module |
| REVERT: snapshot rate-limit | ✅ DONE | earlier session (no SNAPSHOT_COOLDOWN remains) |
| REVERT: push-update confirmation | ✅ DONE | earlier session (plain button, no gate) |
| First audio 15s→5s | ✅ SHIPPED | 93f53d1 |
| Origins help entries (Tripwire, MAD, No Country) | ✅ SHIPPED | 93f53d1 |

---

## The problem

Remote wipe is the only irreversible command. An attacker who grabs the
operator's phone during an active remote session has locate, lock, siren,
camera, mic — all recoverable. But wipe destroys the target permanently.

A second threat: the attacker coerces the operator into revealing the
target's PIN to authorize the wipe.

### PIN gate on wipe (ship now)

Wipe confirmation requires the target's real PIN, entered on the
operator's phone:

1. Typed "WIPE" — confirms intent (not an accidental tap)
2. Target's PIN — confirms identity (operator knows the target's PIN
   because they're family; attacker doesn't)

No other remote command gets this gate. All others are recoverable.

### Duress PIN as tripwire (ship now)

Every PIN prompt in the remote flow accepts three categories of input:

| Input | Result |
|-------|--------|
| **Any real PIN** (operator's or target's) | Action proceeds or "wrong PIN, try again" |
| **Any duress PIN** (operator's or target's) | Instant session revoke + SOS triggered |
| **Anything else** | Wrong PIN. 3 attempts then auto-revoke. No SOS (typo, not coercion) |

Three real PINs. Three duress PINs. Six numbers total, two groups.
Under stress, you remember one group from the other. The duress PINs
are never used in daily life — typing one is always deliberate.

### Two coercion scenarios, both trapped

**Scenario 1: attacker grabs operator's phone during remote session.**

Attacker taps wipe. PIN prompt appears. Attacker doesn't know the
target's PIN. Three wrong attempts → auto-revoke. Target safe.

**Scenario 2: attacker coerces operator to reveal target's PIN.**

Operator gives target's duress PIN. System responds as if wipe
succeeded. Session closes. Attacker thinks target phone is wiped.

What actually happened: session killed, operator status revoked,
target phone untouched, SOS triggered on both devices. The attacker
can't tell — both PINs produce a response that looks identical.

### Silent vs voluntary SOS

Two completely separate panic modes:

**Voluntary SOS** — user presses the SOS button. Full notification,
panic screen, siren, audio/camera/GPS broadcast. The user WANTS
visibility. They're alone and need help.

**Duress SOS** — triggered by any duress PIN at any prompt. The
attacker is watching the screen. Zero visual footprint. No
notification. No "SOS active" banner. No changed icons. The phone
silently broadcasts to all trusted contacts while the screen shows
a clean, normal app. The attacker is staring at a phone that's
calling for help and can't see it.

**Cancellation:** duress SOS has no cancel button and no menu.
The duress profile doesn't know it exists. The ONLY way to cancel
is power ×4 (see below). Entering the real PIN does NOT cancel
silent SOS — if the attacker coerces the real PIN, they must not
be able to find or stop the broadcast.

### Duress during remote access

Duress PIN entered during remote session → silent SOS on the
OPERATOR'S phone only. The operator is the one being coerced.
The target's phone gets a silent revocation alert (not SOS).

### Remote SOS button (new)

Separate from duress. A button in the remote control panel that
triggers SOS on the TARGET's phone from distance. Use case: you
locate Zippy via remote, see something is wrong, trigger her SOS
on her behalf. Explicit, intentional, no duress mechanism.

### Opening a remote session under coercion

The same trap applies at session start. Operator is coerced into
opening a remote session. They enter their own duress PIN or the
target's duress PIN at the auth prompt. Result: instant revoke +
SOS on the operator's phone. No session opens. The response looks
like a connection failure.

### Silent SOS cancellation — power ×4 (state-dependent)

Power ×4 already triggers SOS. Make it state-dependent:

| Phone state | Power ×4 result |
|-------------|----------------|
| No SOS active | **Triggers** voluntary SOS (existing) |
| Voluntary SOS active | **No effect** (SOS already running, cancel via SOS screen) |
| Silent duress SOS active | **Cancels** duress SOS only |

Important: every SOS in Aegis is silent (no loud siren on the
victim's phone — the attacker would hear it). The difference
between voluntary and duress is VISIBILITY:

- **Voluntary:** silent but VISIBLE — notification, SOS screen,
  cancel button. The user knows it's active. Cancelled normally.
- **Duress:** silent AND INVISIBLE — zero UI. No notification,
  no screen, no indicator. Cancelled ONLY by power ×4.

Power ×4 never touches voluntary SOS. It only affects duress SOS.
These are two independent systems sharing a broadcast mechanism.

### Duress SOS tag — separate from regular

The SOS payload carries a type tag:

| Tag | Meaning | Receiver sees |
|-----|---------|--------------|
| `sos` | Voluntary — user pressed the button | "SOS ALERT" |
| `sos:duress` | Coercion — duress PIN was entered | "DURESS ALERT" |

Why this matters: a regular SOS could be accidental (unlikely but
possible — pocket trigger, child playing). A duress SOS cannot be
accidental — you don't fat-finger a PIN you've never used in daily
life into a completely different number. When contacts receive
`sos:duress`, they know with certainty: this person is being
coerced right now. Act accordingly.

The audio capture provides a natural correction channel. If somehow
a duress PIN was entered by mistake, the 15-second microphone
recording before payload dispatch captures the user saying "false
alarm." Contacts hear it in the recording before calling the police.

Practically: receiving `sos:duress` means call the police.
Receiving `sos` means call the person first.

Why this works: the attacker won't press power ×4 because they
believe it triggers SOS. It does — normally. They don't know the
phone is in silent SOS because it's invisible. They will never
touch the one combination that cancels it.

The attacker's own fear is the lock on the cancel button.

Zero new mechanisms. The hardware trigger exists. It becomes
bidirectional based on state that only the user can see.

This solves the "forced to use real PIN" edge case: even if the
attacker coerces the real PIN and cancels the visible SOS screen,
the user can re-trigger silent SOS with power ×4 at any moment
the attacker isn't watching their hands. And even if the attacker
sees them press power ×4, the attacker thinks they just triggered
SOS — which is exactly what the user wants them to think.

### Security boundary

The protection stack is now complete:

1. Duress PIN at any prompt → silent SOS + revoke
2. Power ×4 → triggers or cancels SOS depending on state
3. Attacker can't distinguish states
4. Attacker avoids the cancel gesture out of self-preservation

Beyond this: physical restraint preventing all hand movement.
That is physical security, not app security. The line is drawn.

### Why any duress PIN, not just the target's

Under adrenaline, muscle memory wins. Your fingers might type YOUR
duress PIN instead of the target's. Both should trigger the trap.
The system doesn't care whose duress PIN it is — any duress PIN
from any person in the pair means coercion is happening.

### What the attacker sees

Every PIN field produces one of two visible outcomes: success or
failure. The attacker cannot distinguish real failure from duress
trigger. The SOS fires silently. The revoke looks like a connection
drop. Nothing on screen says "duress detected."

The attacker is navigating a minefield where they don't know how
many mines there are, which prompts contain them, or what they
look like. Every wrong guess ends the session. One specific wrong
guess ends the session AND calls for help.

---

*Lossy links are the baseline, not the edge case.*


---

## Reverts — Artur's direct order

### REVERT: snapshot rate-limit

Remove. The trust stack is Trusted tier → remote grant → receiver's
PIN → session. If an attacker punches through all four, they have
factory wipe. Rate-limiting snapshots on a user who has wipe is a
guard who lets you carry a sword but confiscates your spoon.

### REVERT: push-update confirmation

Remove. Every other remote command fires silently — locate, lock,
siren, camera, mic. A thief won't tap "accept update." The
confirmation blocks the legitimate use case and prevents nothing.
Remote update fires like everything else: silently.

---

## First audio transmission — 5 seconds, not 15

**Critical timing bug.**

Current: first audio chunk transmits at 15 seconds.
Hardware power kill: 10-second hold.
The phone is dead before the first audio is sent.

```
0.0s  — phone snatched, Sentinel fires
0.5s  — front + back camera captured, GPS sent (instant)
5.0s  — first audio chunk transmitted (with fix)
10.0s — thief holds power, hardware kills phone
15.0s — old first audio (too late, phone is dead)
```

First audio must arrive BEFORE the 10-second hardware kill window.
5 seconds gives a clean 5-second margin. Camera and GPS are already
instant. Audio was the gap.

---

## Origins entries (for help screen)

### Tripwire

Every PIN field in the remote flow is a landmine. The attacker
doesn't know how many traps exist, which prompts contain them, or
what they look like. One wrong guess ends the session. One specific
wrong guess ends the session AND calls for help.

### M.A.D. (Mutual Assured Destruction)

The attacker's action (stealing, coercing) triggers a response that
destroys their position: SOS, photos, GPS, audio all broadcasting.
The phone they stole is now their surveillance device. The person
they coerced just silently called the police.

The deterrence IS the game theory. The rational attacker's optimal
strategy: don't engage.

### No Country for Old Men

The stolen object hunts the thief.

In the film, Moss steals money with a hidden transponder. Chigurh
tracks him through it. 30 people die. The briefcase Moss wanted was
the thing that killed him.

In Aegis, the thief steals a phone running Sentinel. Front camera,
back camera, GPS, accelerometer, microphone, sonar — all transmitting
live to the operator. The thief is carrying their own surveillance
device. The thing they wanted is the thing hunting them.

Chigurh needed days and left a body trail. Aegis needs 0.5 seconds
and nobody dies.

**Hardware limit:** the thief can hard-kill the phone with a 10-second
power hold (PMIC hardware, no software override possible). Defense:
camera + GPS fire instantly (0.5s), first audio at 5s, and last-gasp
GPS burst at critical battery. The phone goes dark eventually, but the
evidence was already transmitted before the thief figured out how to
turn it off.

---

## Attention leak (new attack vector)

Discovered and named during this session. No published security
research uses this term. Full writeup in `assets/docs/ATTENTION_LEAK.md`
and help screen entry.

An attention leak reveals which conversation the user is focused on
and how often they focus on it. A malicious contact withholds a tick,
then observes each time the victim opens the chat. Chat-open frequency
becomes a surveillance metric for controlling behavior.

Defense: reconciliation fires only on UNREAD chat opens. Fully-read
chat open emits zero traffic. The attack collapses to one read
receipt — information the attacker already had.

**Project Aether rule:** if an outbound signal benefits anyone other
than the user, it doesn't get sent.

This rule is identical to LunaOS PID 0 policy: any syscall that
doesn't reduce epsilon is a threat. Same equation (dε/dt ≤ 0),
different domain. Independently discovered convergence.
