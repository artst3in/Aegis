# Attention Leak

**A new attack vector discovered and named by Project Aether, June 2026.**

## What it is

An attention leak reveals which conversation you're focused on and how often you focus on it. It's distinct from known privacy leak categories:

- **Presence leak** tells someone you're online. Every messaging app has this.
- **Metadata leak** tells someone who you talk to and when. Most apps expose this.
- **Attention leak** tells someone *which specific conversation you're looking at right now* and *how many times you've looked at it today*.

No published security research uses this term. We identified and named it during Aegis Protocol development.

## How it works

A malicious contact withholds a delivery receipt. Your tick stays dark. You keep opening the chat to check whether your message was delivered. Every time you open it, the app sends a signal back to the contact.

The attacker does nothing. You do all the work. They sit back and watch: "She opened our chat 12 times in the last hour." "She hasn't checked since yesterday."

## Why it's dangerous

For an abusive or controlling partner, chat-open frequency is a measure of emotional dependence. Combined with GPS, battery level, and online status — which a Trusted contact already receives — attention data completes a full behavioral profile:

- Where you are (GPS)
- Whether your phone is alive (battery)
- Whether you're using it (presence)
- **What you're thinking about** (attention)

That last one crosses a line no other data point crosses.

## How Aegis prevents it

Aegis only sends receipt reconciliation on **unread** chat opens — when a read receipt is already being sent. Opening a fully-read chat generates zero outbound traffic. No signal. No ping. No data.

The attacker gets exactly ONE notification: the read receipt for the message they sent. After that, silence. Open the chat a hundred times — the attacker sees nothing after the first.

## The broader principle

A security app must assume that any data it transmits can be weaponized. "How often the user opens a conversation" sounds harmless. In the hands of a jealous partner, it becomes a surveillance tool.

Project Aether rule: if an outbound signal benefits anyone other than the user, it doesn't get sent.
