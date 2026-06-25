# Why Aegis Doesn't Use Tor

## The short answer

Your distress call cannot get stuck in a broken Tor circuit.
Reliability beats anonymity when the message is "help me."

## What Tor does

Tor routes traffic through three relays (hops). Each relay
peels one layer of encryption. No single relay knows both
the sender and the destination. This hides your IP address
from the destination.

## Why we don't need it

Aegis solves the identity problem at a different layer.
Your real name, face, and bio never enter the transport.
Every connection — every contact, every group, every relay
— sees an anonymous handle. The identity doesn't exist at
the network level.

Tor hides your IP from the destination. But Aegis already
hides your identity from everything. Hiding the IP of an
anonymous handle is redundant — there is nothing to
correlate it with.

## Why we can't risk it

Tor circuits break. They time out. They fail silently.
A SOS broadcast, a crash detection alert, a dead man's
canary — these are messages that must arrive. A broken Tor
circuit adds seconds of latency or drops the message
entirely.

SimpleX already provides 2-hop private message routing
(enabled by default). Your IP is hidden from the
destination relay. The forwarding relay cannot see which
queue the message goes to. This is stable, fast, and
built into the transport we already use.

## The trade-off

Tor adds metadata resistance — it prevents a network
observer from correlating your traffic patterns. SimpleX's
2-hop routing provides weaker metadata resistance than
Tor's 3-hop routing.

We accept this trade-off because:

1. The identity that metadata could be correlated WITH
   does not exist on the network
2. A safety message that arrives late or not at all is
   worse than a message with slightly less metadata
   resistance
3. SimpleX's 2-hop routing already hides your IP from
   the destination relay

## Summary

Tor solves: "hide my IP from the destination."
Aegis solves: "my identity doesn't exist on the network."

Aegis doesn't need Tor because it solved the problem Tor
would protect against. And it can't risk Tor because a
broken circuit could cost a life.
