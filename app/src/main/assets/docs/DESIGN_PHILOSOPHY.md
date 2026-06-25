# Aegis — Design Philosophy

## We treat your time as a resource

Most apps don't. They optimize for engagement metrics, time-on-screen, ad impressions, dark-pattern conversions. Aegis is free forever, has no ads, no telemetry, no growth team. We optimize for one thing: **getting you to what you need in the fewest taps and the least cognitive load possible.**

The trade-off most consumer apps make — "add another setting, hide it deep, surface a tooltip if anyone complains" — is invisible to us. We don't have that incentive. We do have a measurable cost: every extra second a user spends finding a feature is a second their phone is in their hand instead of in their pocket, which for the kind of person Aegis is built for can matter.

So we put the cost on a scale and measure it.

---

## The laws we use

Three results from the human factors literature drive most of Aegis's UI decisions.

### Hick's Law — decision time

> The time it takes to make a decision grows logarithmically with the number of equally-likely choices.

Specifically: `time ≈ a + b · log₂(n + 1)`

What this means in plain terms: doubling the number of menu items adds **one full unit** of decision cost — it doesn't matter how clean each item is, more items is slower. A flat list of 20 settings is genuinely harder than a flat list of 10, by a measurable amount.

We use Hick's Law any time we decide whether to add another row, another tab, another menu item.

### Miller's 7±2 — working memory

> The number of items a human can hold in short-term memory at once is 7, plus or minus 2.

Above 9 items, the brain stops processing the list in parallel and falls back to slower serial scanning. A 12-row settings list is more than twice as hard to navigate as you'd guess from the row count alone, because you cross the threshold from "I see all of this at once" to "I'm reading one item, then forgetting it, then reading the next."

We use Miller's law as a hard ceiling. No top-level list in Aegis should have more than ~9 items at once.

### Fitts's Law — target acquisition

> The time to acquire a target with a pointer depends on the distance to the target and its size: `time ≈ a + b · log₂(D/W + 1)`.

Bigger targets and closer targets are faster to hit. For a thumb on a phone, this means **the bottom-right of the screen is the prime real estate** for one-handed right-thumb use — that's where the thumb naturally rests and where the arc of motion is shortest.

We use Fitts's Law for tab placement: the comms tab (the most-used tab for most users) is positioned where the thumb reaches it without stretching.

---

## Putting the laws to work

### Bottom tabs

The five tabs are laid out so that the most-frequently-tapped destinations are in the thumb's natural arc. SOS is centred because in a crisis you don't want to think about reach — you stab the middle of the screen. Comms (chats) sits to the right of SOS because messaging is the most-frequent non-emergency action and right-thumb-right-of-centre is the most reachable zone.

### Settings layout

The settings list is grouped into five clusters under labelled headings (Messaging, Appearance, Data, Network, System). The math:

- Flat 12-row list: decision time ∝ `log₂(13) ≈ 3.70`
- Grouped 5-section list (avg target depth 2.5 sections × 3 items): effective decision time ∝ `~3.2` after chunking

Plus the labelled-cluster approach lets you ignore 4 out of 5 sections at a glance. That's not in the math — it's a discoverability gain on top.

We don't bury the most-used settings under sub-screens. The five Messaging settings are always visible. We don't make you "open a section" to see what's inside.

### Skill tree

The Security tab uses a hexagonal skill tree instead of a flat settings list. The tree has explicit visual structure (root, trunk, branches, sub-branches) that communicates **dependencies**: you can't enable App Duress without first setting an App PIN, so App Duress hangs off the App PIN node visually.

This isn't decoration. A flat list of 11 security toggles would be a Miller-7 violation AND would lose the dependency information. A tree communicates "this depends on that" without any text.

### Info vs Settings

We're strict about what gets a permanent row on the Settings list. The "Capabilities" inventory — a full read-only list of every shipping feature — lives behind one tap, not inline. Permanent informational surfaces eat scroll real-estate forever; a one-tap drill-in costs nothing.

### Conditional surfaces

Things like the queued-messages banner only appear when there's something to act on. They don't waste vertical space the rest of the time. When they do appear, they're always at the top — alerts shouldn't be hunted for.

### Hold-to-execute gestures

SOS Activate is a 3-second hold, not a tap. SOS Stop is the same 3-second hold. The deliberate gesture costs 3 seconds; an accidental tap would cost a SOS broadcast (or a silenced SOS during an active threat). We pay the 3 seconds.

---

## What this means in practice

You can read the entire codebase if you want — it's GPL, on GitHub, openly developed. Every UI decision has a reason, commented inline next to the code.

If something feels wrong, we want to know. We don't track you, so we can't tell from analytics; the only signal we get is when someone tells us. Open a GitHub issue.

---

*Free forever. Open source. Nobody else gets to charge for it.*


## Colour

Cyan is the colour of order — entropy held steady or falling. Red is
its complement: chaos, danger. The blood-stays shader renders this
directly — the ordered world fades to gray while red (chaos) stays
vivid.

Project Aether is order given form. Its colour must be cyan. No gold,
no secondary brand colour. One colour, one identity, one principle.
