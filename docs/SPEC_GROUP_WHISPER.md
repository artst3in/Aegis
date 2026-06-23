# SPEC: Group Whisper — KILLED

**Status:** KILLED — Artur + Aurora, 2026-06-04
**Original draft:** Aurora + Artur, 2026-06-03
**Transport revision:** Chad, 2026-06-03

## Decision

Group whisper is permanently killed. No private messaging
between group members. No DMs. No whisper. No mechanism to
privately contact an individual group member through Aegis.

Groups are anonymous, always, with zero private contact
capability.

## Why it was killed

### The blast radius

Aegis has GPS, camera, microphone, Device Owner, factory
wipe, lock screen overlay, background services — full
system access. Any message that reaches a user is one
parser exploit away from all of it.

In Signal, a compromised message leaks chat history. In
Aegis, a compromised message could activate the camera,
read GPS, stream audio, or wipe the phone.

Every private contact mechanism (whisper, DM, member
contact) gives a stranger a direct message pipe to a
device with nuclear permissions. The blast radius is
not proportional to the feature — it is proportional
to the permissions behind the wall.

### The research

Discord's group→DM pipeline is the primary attack vector
for online predatory groups. The 764 network used
group→DM to groom and extort minors. Discord deleted
34,000 accounts from one group. The universal safety
recommendation: disable DMs from server members.

Whisper recreates this pipeline. The frictions we designed
(confirm dialog, no reply thread) are weaker than
Discord's message request system, which still fails.

### The three failed approaches

1. **Whisper** (private message in group) — stalker gets
   direct access to target
2. **Public message** (share contact info in group) —
   everyone gets your info, 5 strangers message you
3. **External channel** — doesn't exist, members are
   anonymous

All three fail. The tension between anonymity and
connectivity has no safe in-app resolution.

### The solution

Groups are for information. Contact exchange happens
outside Aegis — Reddit, forums, in-person meetups. The
group points users to external platforms where public
identity exchange is appropriate.

The feature gap IS the security feature. Same principle
as everything else: remove the option, don't manage it.

## What groups ARE

Anonymous community chat. Full conversation capability.
Discuss, warn, share tips, coordinate safety. Active
community under anonymous handles. A town square where
everyone wears masks.

You can shout. You can listen. You can help. You cannot
follow anyone home.

## History

This spec went through three iterations in 24 hours:

1. **Draft** (Aurora): whisper via per-member fan-out.
   Transport was wrong — the core owns fan-out.
2. **Revision** (Chad): rebuilt on SimpleX member contacts.
   Technically correct transport. Three open questions
   for Aurora.
3. **Kill** (Artur + Aurora): even the correct transport
   creates a stalking vector. The blast radius of Aegis
   permissions makes any private contact mechanism
   disproportionately dangerous.

Kept for the record because the investigation was
thorough and the reasoning for the kill matters more
than the kill itself.
