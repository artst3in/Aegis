# Why Aegis Has No Group SOS

## The short answer

Group SOS would help stalkers find you.

## The request

"Why can't I enable SOS for my family group? Everyone in it is someone I trust."

This is the most common feature request. It makes intuitive sense. But it creates attack vectors that cannot be closed.

## Attack 1: The public group

A city group called "Aegis Amsterdam" where local users coordinate safety. Someone triggers SOS. Every member receives GPS, audio, and live camera.

The attacker joins the group. They now receive every SOS broadcast in the city. They are physically close to their target — because they are the threat. The radar shows them as "closest responder." The SOS system guides the victim toward the person hurting them.

This is not a theoretical attack. It is the inevitable one. Any attacker who knows their target uses Aegis will join the local group.

## Attack 2: The expanding group

You create a family group. Five people, all trusted. You imagine this group never changes. Three months later, your cousin adds a friend. Six months later, someone adds a colleague. A year later, the group has twelve people and you have not reviewed your SOS exposure since you toggled it on.

Everyone in that group receives your GPS during your worst moment — including people you never individually decided to trust with your safety.

## Attack 3: The silent promotion

A group admin adds a new member. Your SOS list silently grows. No notification. No confirmation. No moment where you think "do I trust this person with my live location, audio, and camera when I am in danger?"

Per-contact trust forces that question every time. Group membership skips it.

## The asymmetry

There is one scenario Aegis cannot prevent: someone you personally trusted turns out to be dangerous (the Bob problem). If you put Bob on your SOS list yourself, Bob receives your SOS. That is an intrinsic cost of having an SOS list at all. We accept it because the alternative — no SOS system — is worse.

There is a second scenario Aegis absolutely can prevent: strangers receiving your SOS because they share a chat room with you. That is not intrinsic. It is a choice we would make by shipping group-scoped SOS. We do not make that choice.

We cannot fix Bob. We will not manufacture a route for stalkers.

## What to do instead

Add your family members individually as Trusted or Emergency contacts. Trusted contacts receive everything — location, presence, battery, SOS. Emergency contacts receive SOS alerts only — no daily data. Choose the tier that fits each person.

The group is for chatting. Trust is per-person.

This takes sixty seconds and forces you to think about each person on your safety list individually. That friction is the security feature.

## The rule

SOS recipients are individual contacts, classified by tier. Never groups. This is a hard architectural constraint, not a default that can be toggled.

No location inside groups. No presence inside groups. No SOS inside groups. No sensor data of any kind inside groups. Groups are chat-only. This will not change.
