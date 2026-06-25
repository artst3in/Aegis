# What Aegis can and can't protect

We will never lie to you about what's safe. Your life may depend on
knowing exactly where the line is — so here it is, in plain terms:
everything Aegis does to protect you, and the one thing no software on
any phone can do.

## Everything we do

**On the wire — end-to-end encrypted.**
Your messages travel through the SimpleX network as ciphertext. The
relays that pass them along, and anyone watching the network, see
scrambled bytes — never your words. There is no server with a copy.

**At rest — sealed with your recovery phrase.**
On your phone, your messages are encrypted with a key derived from your
24-word recovery phrase (256-bit — uncrackable by brute force). A phone
that is **locked, seized, or powered off** is just sealed noise. No PIN
guess, no lab, no court order opens it without the phrase.

**The transport keeps nothing.**
Aegis is built on SimpleX, but SimpleX is only the pipe — not storage.
The moment a message arrives, Aegis pulls it into memory, **deletes it
from the transport**, and seals it into its own encrypted store. The
same happens when you send: your transport copy is destroyed the instant
the message is on its way. A forensic pull of the transport finds an
**empty pipe** — no messages, no history, no names.

**The ticks tell you the truth.**
One dark tick: it left your phone. **Two dark ticks: it is sealed at
rest on your friend's phone, and neither transport holds a copy** — a
cryptographic fact, not a "delivered" guess. Two bright ticks: they read
it.

**When you lock, the key disappears.**
The instant you lock Aegis, the key that reads your messages is **wiped
from memory**. A locked phone holds only sealed noise and nothing to
open it with. Lock with the two-finger pull-down from anywhere, or let
it auto-lock.

**Ephemeral profiles leave (almost) nothing.**
An ephemeral profile lives only in memory and is destroyed on lock.

## The one thing physics won't allow

To **show** you a message, your phone has to **decrypt** it. To decrypt
it, the key has to be in memory. So for the seconds a message is on your
screen — or being typed, or being sealed — the plain words and the key
to read them both exist in your phone's live memory.

**This is not a flaw in Aegis. It is true of every app, every
messenger, every device ever made.** You cannot read something and keep
it unreadable at the same instant. Signal, your bank, your email — all
the same. There is no way around it, because it isn't a software choice;
it's how computers work.

What it means in practice: if someone takes your phone **while it is
unlocked and open**, and has specialist equipment to copy live memory,
they may recover what is on the screen at that moment. Not your history
(that's sealed) — just what is open, right then.

And the defence is simple and total: **lock it.** The instant you lock,
the key is gone from memory and there is nothing left to extract. A
locked Aegis, even in a forensic lab, gives up nothing.

## So, honestly

We built every layer it is possible to build:
- encrypted on the wire,
- sealed at rest under your phrase,
- transport that stores nothing,
- key wiped the moment you lock,
- memory-only ephemeral profiles.

The last inch — a powered-on, unlocked, *open* phone connected to
specialist memory-extraction gear — is a **law of physics, not a gap we
left**. The closest thing to crossing even that is rebuilding the
transport to run entirely in memory, which is on our roadmap; but no
software anywhere defeats a live memory grab of an unlocked screen.

We tell you this because your safety depends on the truth, not on us
pretending the line isn't there. Keep your phone locked when it leaves
your hand. That single habit closes the only door we cannot close for
you.
