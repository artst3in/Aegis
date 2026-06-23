# Trust Model — Three Tiers

**Status:** IMPLEMENTED — Chad (TrustTier enum on
KnownPeerEntity.trustTier; the recipient lists at every safety
emission site — SOSHandler fan-out, location patches, presence
beacons, story fan-out — filter their recipients on the tier
before sending, and ProtocolManager.gateAegisControl gates
`[aegis:…]` control envelopes on per-peer capability). Anonymous
Groups (the architectural exception to "regular groups are
chat-only") remains the subject of SPEC_ANONYMOUS_GROUPS.md and
is not implemented — Regular groups via SimpleX native are what
ships today.
**Owner:** Chad (implementation), Aurora (spec)

## The Core Design

Every contact in Aegis belongs to **one** of three tiers. The tier IS the relationship. No per-feature toggles, no granular controls, no advanced mode.

| Tier | Routine data sharing | SOS alerts | Visibility |
|------|----------------------|--------------|------------|
| **Trusted** | All (location, presence, status, sensors) | Yes | Full profile |
| **Emergency Contact** | Nothing | Yes | Red "!" placeholder |
| **Untrusted** | Nothing | No | Mask icon |

One label per contact. The label determines everything.

## Why Three Tiers, Not Two

Pure binary (trusted/untrusted) fails real use cases. A doctor, a neighbor with your spare key, a distant cousin, or a boss — these are people you want alerted in a true emergency but who shouldn't see your daily location, status, or routine data.

Forcing the user to choose between "trust them with everything" or "they miss your SOS" is a bad choice. The middle tier solves it.

This matches the convergent industry pattern. Instagram's "Close Friends" introduced one extra tier for intimate content; WhatsApp is copying it; personal safety apps maintain dedicated "emergency contacts" lists separate from regular contacts. Signal's single-trust-level model is actively criticized by privacy-aware users for being too coarse.

Granular per-feature toggles have been tried and rejected industry-wide. They are foot-guns under stress — too many decisions, too many ways to misconfigure. Tier-based models win because the tier IS the decision.

## Tier Definitions

### Trusted

The people who actively want your routine data and you want them to have it. This is a narrower category than "people I trust." A doctor, lawyer, or executor may be deeply trusted but does not belong here — they don't want or need your daily location and battery level. Trust without active interest in routine data is Emergency, not Trusted.

Typical Trusted membership: spouse or partner, parents of young children, children of elderly parents, AI family, the small set of close co-monitoring friends who share a mutual interest in each other's daily presence. Usually under a dozen people. Often half that.

The tier is for active mutual presence-sharing only. It is NOT a measure of how much you trust someone in general — for that, Emergency covers a much broader population.

**Receives:**
- All routine sharing (location updates, presence, status, sensor data when enabled)
- SOS alerts immediately
- Sees your full profile: avatar, display name, security tier badge (Bronze / Silver / Gold / Cyan)
- Can DM you, voice/video call you, participate in groups with you as a known identity

**Visual treatment in UI:**
- Full color avatar
- Display name
- Security tier badge visible next to name
- Standard chat row, no special outline

### Emergency Contact

People who matter in a crisis but aren't part of daily life. Doctor, neighbor with spare key, distant family, employer, lawyer, executor.

**Practical heuristic for membership:** roughly the size of your Facebook friends list. Dozens to hundreds. The wider social network — anyone you would reach for in a crisis. The breadth is intentional and is the feature: SOS effectiveness scales with eyes on the alert.

**Why broad is right:** the recipient's preference does not enter the equation. They are on your social graph; they have already opted into receiving things from you by being your contact. A SOS notification arrives and they tap it out of curiosity — what is this from Artur — and the recording plays. They are now a witness to whatever is happening. Even if they don't act, the audio is captured on dozens of devices and constitutes evidence. The design treats every contact as a potential witness, not as a person whose comfort must be considered before an alert.

Because Emergency contacts receive nothing routinely, the breadth costs no privacy day-to-day. Only in the crisis moment does the wide net light up.

For contrast: Trusted is the people you message daily, usually a single-digit count. Emergency is everyone else you'd reach for in a crisis, usually a double- or triple-digit count.

**Receives:**
- Nothing routinely. No location, no presence, no status, no sensor data, ever.
- SOS alerts when fired. The alert includes location, audio, photo capture — the same payload Trusted gets — but only at the moment of SOS.

**Visual treatment in UI:**
- Avatar replaced with red "!" icon
- Chat row has red outline
- Display name still visible (you marked them, you know who they are)
- No security tier badge shown
- When you tap into the chat, a permanent banner reads: "Emergency contact. They receive SOS alerts only. No routine sharing."

The red marking serves a dual purpose: it reminds you of their role at a glance, and it raises the threshold for routine messaging — you're less likely to send casual chatter to someone whose chat row is outlined in red.

### Untrusted

Everyone else you've added or who has added you. Acquaintances, group members from public communities, contacts from a one-time interaction.

**Receives:**
- Nothing. No routine sharing. No SOS alerts. No presence. No status. No sensor data of any kind.
- They can message you and you can message them. That's the entire interaction surface.

**Visual treatment in UI:**
- Avatar replaced with a mask icon
- Display name shown (the SimpleX display name they chose)
- No real identity inferred
- No security tier badge
- They see you as a mask too — symmetric anonymity

This tier is the default for any new contact unless you explicitly promote them. Friction is the feature — you should never accidentally promote someone you barely know to Trusted.

## Anonymous Groups (Separate System)

Aegis supports **two group types**, distinguished only by identity visibility within the group. Neither type ever carries data — the "Groups carry no data" rule is universal.

| Group type | Identity within group | Data sharing | Visual cue |
|------------|----------------------|--------------|------------|
| **Anonymous** | Members hidden from each other, rotating session tokens, ephemeral | Zero (chat only) | Mask icon for every member, mask icon on the group itself in the chat list |
| **Regular** | Members visible by their chosen SimpleX display name, standard SimpleX admin controls | Zero (chat only) | Standard group icon, normal chat list appearance |

The user selects the group type at creation. Once set, the type is fixed (a regular group cannot be silently converted to anonymous or vice versa).

**Anonymous groups** serve high-stakes use cases where members must not be able to identify each other: whistleblower networks, support groups, activist cells, investigative journalism collaborations. The structural hiding survives even a compromised member device — the device simply has no record of who else is in the group, only its own session token.

**Regular groups** serve everyday family and friend use: a circle chat, a hobby club, a work team. Members see each other's display names and can DM each other directly. This is the standard SimpleX group behavior — no phone numbers exposed, but identities are open within the group.

In both cases, Aegis itself adds no data layer on top. The group is a chat surface. Period. See SPEC_ANONYMOUS_GROUPS.md (to be written) for the full architecture of the anonymous variant.

The relationship between the contact tier and group identity: a person in your contacts as "Untrusted" tier means *you* hide *their* identity from your view (and they hide yours). A person inside an anonymous group means *the group structure* hides everyone's identity from everyone else.

## SOS Recipient Resolution

When SOS fires, the recipient list is computed as:

```
recipients = (Trusted members) ∪ (Emergency Contacts)
```

That's it. One union, no flags to check, no per-contact SOS toggle to inspect. The tier determines membership.

**Recipients are individual contacts only. Never groups.** If Mom is Trusted and we are both members of a "Family" group, SOS fires in my DM to Mom — not in the group chat. The group is a chat-only construct; data flows only through individually-classified contacts.

Untrusted tier contacts are NEVER recipients. Anonymous groups are NEVER recipients. The Untrusted tier exists precisely to exclude people from the SOS broadcast.

## Groups Carry No Data — Ever

Groups in Aegis are **chat-only**. They never carry routine sharing or SOS broadcasts. This is a hard architectural rule, not a default that can be flipped.

**Why this rule exists:** without it, groups become a transitive trust attack vector. If group membership inherited trust, then any member with permission to add people could promote a stranger into a position where they receive my data automatically. The user never approved the new person, but the group context did it for them. This is unacceptable.

**The rule:**
- No location sharing inside groups
- No presence indicators inside groups
- No status updates inside groups (beyond the chat messages themselves)
- No SOS broadcasts inside groups
- No sensor data of any kind inside groups

Groups are pure messaging channels. The only thing they transport is the chat content their members deliberately post into them.

**Consequence:** if you want someone to receive your data, you must classify them as Trusted or Emergency as an *individual contact*. Group membership confers nothing. This is the single guarantee that keeps the trust model auditable: I can answer "who sees my data?" by reading my own contact list. No group rosters to chase, no transitive paths to trace.

## Promoting and Demoting

Moving a contact between tiers requires deliberate action. Each transition has a distinct confirmation:

- **Untrusted → Emergency Contact:** "This person will receive SOS alerts including your location, audio, and camera capture. They still won't see your routine activity. Confirm."
- **Untrusted → Trusted:** "This person will receive your location, presence, status, and SOS alerts continuously. Confirm." (Two-step confirmation — most consequential transition.)
- **Emergency → Trusted:** "This person will now also receive your routine activity, not just emergencies. Confirm."
- **Any tier → Untrusted:** Single-tap with brief warning: "They will no longer receive any data from you, including SOS alerts. They can still message you." Reversible without friction.

Demotion is one tap. Promotion is deliberate. Asymmetric friction protects against accidental over-sharing.

## What This Replaces

This trust model replaces and supersedes:

- Any per-feature "share X with Y" toggle anywhere in the app
- Any "advanced mode" or "power user mode" toggle
- Any global feature enable/disable that affects sharing (camera, location, mic permissions are still OS-level)
- Any separate "emergency contacts" sub-list (Emergency Contact tier replaces it)

The Trust Model is the single source of truth for who sees what. No other layer modifies it.

## Default State

- A newly added contact starts as Untrusted.
- A newly created contact list is empty. The user adds people deliberately, one at a time.
- Aegis does not pre-populate the contact list from the device address book. Users must invite each contact via SimpleX QR or link.

## Implementation Notes

- The tier is a single `TrustTier` enum on each contact: `TRUSTED`, `EMERGENCY`, `UNTRUSTED`.
- SOS broadcast code iterates `contacts.filter { it.tier == TRUSTED || it.tier == EMERGENCY }`.
- Routine sharing code iterates `contacts.filter { it.tier == TRUSTED }`.
- Untrusted tier contacts are excluded from all background data flows by construction. No checks needed at the broadcast site — they were never in the candidate set.
- The tier is stored locally on each device. It is not synchronized to any server. Your tier assignments are your private knowledge.

## Blocked State (Outside the Trust Model)

The trust model governs what data a contact receives. Blocking is a separate orthogonal state: it controls whether the contact can communicate with you at all.

A blocked contact:
- Cannot send messages to you (your app discards their messages silently)
- Cannot call you
- Cannot invite you to groups
- Receives no data of any kind (the trust tier becomes moot)
- Their existing chat history with you remains accessible to you but no new messages can land

Blocked is implemented as a separate boolean flag on the contact, parallel to the tier. A contact can be (Trusted, blocked = true), though that combination is unusual and probably indicates the user should demote-and-block instead. The UI should suggest demoting to Untrusted before blocking, but does not enforce it.

Unblocking restores their ability to communicate with you. It does not restore their previous tier — that decision is independent.

## Revocation Immediacy

When a contact's tier is changed, the effect is immediate. There is no grace period, no last-message-included, no five-minute lag for "in-flight" data.

- Demote Trusted → Emergency: next location ping (which happens on a schedule) skips them. Any in-flight ping already dispatched will be received; this is acceptable since the demotion was just made and the user accepts that one final update may land.
- Demote Trusted → Untrusted: same. The next scheduled update excludes them.
- Demote Emergency → Untrusted: if an SOS is in progress, the SOS broadcast that was dispatched moments before the demotion is honored. Subsequent SOS alerts exclude them.
- Promote Untrusted → Emergency: they receive the next SOS, not retroactive ones.
- Promote Emergency → Trusted: routine sharing starts on the next scheduled update tick.

The implementation reads the tier at the moment of dispatch, so a tier change immediately affects all not-yet-dispatched messages.

## First-SOS Onboarding for Emergency Contacts

The first SOS alert any recipient receives from a particular user includes a one-time onboarding banner:

> [Name] is signaling distress and has reached out to you because you are part of their safety plan. They have sent their current location, audio recording, and a photo from their device.
> 
> What you can do: contact them directly, share this information with emergency services, or pass it to someone who can respond.

Subsequent SOS alerts from the same sender to the same recipient show the standard SOS alert without the onboarding banner — the recipient now understands their role.

The onboarding payload is one extra notification on first contact only. Local flag on the recipient device suppresses it for subsequent SOS alerts from that sender.

**A note on framing:** the onboarding banner does double duty. Practically, it tells the recipient what is happening and what they can do. Socially, it tells them they were chosen — they are part of someone's safety plan and didn't know. The phrase "you are part of their safety plan" is intentionally tier-neutral: it conveys the social honor without revealing whether the recipient is Trusted or Emergency. A Trusted contact (who already receives routine data) infers they're Trusted from that context. An Emergency contact learns only that they are in the safety net, not which specific tier.

This preserves the tier-privacy rule (see below): the recipient never learns their exact classification from the app, only that they were chosen.

## Tier Changes Are Private

A contact never receives a notification when their tier is changed, and the app never reveals a contact's exact tier classification as a transmitted payload. The tier is the user's private classification within their own app.

This is structural: the tier is stored only on the user's device, never transmitted to the contact, never synchronized to any server. There is no API call that says "tier changed from X to Y" — the tier change is a local-only event. The first-SOS onboarding banner is intentionally tier-neutral ("you are part of their safety plan") — it does not name the specific tier.

**What the rule protects:**
- The app never sends a tier name as a payload.
- Contacts never receive notifications saying "your tier changed."
- The user is shielded from social pressure: a contact cannot lobby for promotion because they don't learn what tier they hold from the app.
- Untrusted contacts are shielded from social hurt: they have no way to learn the user classifies them as Untrusted.

**What the rule does not (and cannot) prevent:**
- A Trusted contact will know they are Trusted because they see the user's routine data — location, presence, status. The data flow itself is the signal. This is part of the relationship working — they can see you on their radar, that's the feature.
- An Emergency contact can infer they are Emergency at the first SOS: they received the alert (so they're in the safety net), but they receive no routine data (so they're not Trusted). The inference is straightforward and unavoidable. The first-SOS banner does not name the tier, but the recipient can deduce it from what they see and what they don't.

The classification is private as a transmitted fact. It is not private as an inferable fact for those already inside the safety net. Untrusted contacts — the population that most benefits from privacy of classification — see nothing and can deduce nothing.

---

*dε/dt ≤ 0*
