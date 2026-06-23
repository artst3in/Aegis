# SPEC: Transactional Delivery

**Status:** APPROVED — Artur, 2026-06-06
**Supersedes:** garbage-overwrite approach, most of SPEC_CONTACT_GRAPH_SEALING §4
**Principle:** SimpleX is transport. Aegis is storage. Never both.

## The problem (what we kept patching)

Aegis runs on SimpleX. SimpleX keeps its own database with a
second copy of every message, every contact name, every file
transfer. The SimpleX DB is hardware-keyed (device-only, no
phrase). A seized phone opens the SimpleX DB and reads
everything — even if Aegis's DB is sealed.

Previous approaches:
- Overwrite SimpleX message bodies with garbage on lock
  (hack — SQLite free pages preserve original data)
- Seal the Aegis overlay only + honesty caveat
  (partial — "looks sealed" but isn't)
- Phrase-wrap the SimpleX DB key
  (breaks cold-boot SOS)

All patches. None clean.

## The clean solution

SimpleX stores NOTHING. Messages exist in SimpleX for
milliseconds — received, moved to RAM, deleted from SimpleX
DB immediately. Then sealed into Aegis's DB. Delivery
confirmation sent to the sender ONLY after the seal succeeds.

### Message receive flow

```
1. SimpleX core receives message
   (briefly touches disk — unavoidable, core architecture)

2. Aegis reads message into RAM
   + IMMEDIATELY deletes from SimpleX DB (/_delete item)
   → Message now exists ONLY in RAM. Zero disk trace.

3. Aegis seals message into its own DB
   (phrase-derived public key, no unlock needed)

    (On receipt, before sealing, send the delivered confirmation
     to the sender — one bright tick, "reached your device".)

4a. Seal SUCCEEDS:
    → Send seal confirmation to sender (bright + dim ✓✓)
    → Message is safe. Transport is clean.

4b. Seal FAILS (disk full, crypto error, any reason):
    → Message stays in RAM. Retry seal.
    → NO seal confirmation sent.
    → Sender sees one bright tick (delivered, not sealed).

5. Phone REBOOTS during failed seal:
    → RAM gone. Message lost.
    → Sender still has one tick (no seal). Knows to resend.
    → No plaintext on disk. No leak.
```

### Message send flow

```
1. Aegis composes message, seals into its own DB
   (phrase-derived key)

2. Aegis hands plaintext to SimpleX for transport
   (SimpleX encrypts E2E for the wire)

3. SimpleX stores the sent item in its DB

4. IMMEDIATELY after send confirmation:
   delete the sent item from SimpleX DB

5. Aegis's sealed copy is the only record
```

## What SimpleX keeps

- Connection state (which relays, which queues)
- Contact entries (INCOGNITO HANDLES ONLY — Aegis Protocol)
- Group memberships (for message routing)
- Encryption keys per connection
- Queue management

## What SimpleX does NOT keep

- Message content (deleted immediately after Aegis seals)
- Message history (zero)
- File transfer content (deleted after Aegis seals)
- Real contact names (never existed — Aegis Protocol)
- Attachment data (deleted after Aegis seals)

## Delivery ticks — redefined

Four states above "sending", a monotonic ladder. The two cyan
shades (dim vs bright) plus tick count encode the whole story.
Every rung above "sent" is an **Aegis Protocol receipt** the
recipient sends back — SimpleX's own `sndRcvd` ack drives nothing,
because the transport copy is already destroyed at "sent".

| Ticks | State | What happened | Source |
|---|---|---|---|
| ✓ dim | Sent | Left your device, on the relay | SimpleX `sndSent` |
| ✓ bright | Delivered | Reached the recipient's device | `[aegis:delivered]` |
| ✓✓ bright + dim | Sealed | Sealed at rest with the recipient's 256-bit phrase key; SimpleX transport copy destroyed | `[aegis:sealed]` |
| ✓✓ bright + bright | Read | Recipient opened and decrypted it | `[aegis:read]` |

The first tick flips dim→bright at **delivered** and stays bright;
the second tick appears dim at **sealed** and brightens at **read**.

In every other messenger, two ticks mean "arrived on device."
In Aegis, **delivered** (one bright tick) is that ordinary
network acknowledgment — but the two-tick **sealed** state means
"arrived, encrypted at rest with your friend's recovery phrase,
and the transport copy no longer exists."

Sealed is a cryptographic guarantee, not a network
acknowledgment — which is exactly why it sits above delivered
rather than replacing it.

## Sequencing details

**Delivery tracking:** SimpleX tracks delivery status on
items. Delete AFTER delivery confirmation is received, not
before. The sequence: receive → RAM → delete from SimpleX →
seal in Aegis → confirm delivery.

**File transfers:** In-progress transfers need the SimpleX
item until complete. Delete after transfer completes AND
Aegis seals the file. Large files: seal in chunks if needed.

**Replies/quotes:** SimpleX references parent items by ID.
When the parent is deleted from SimpleX, quotes reference
Aegis's sealed copy instead (by message UUID).

**Read receipts:** Track by item ID. The ID persists in
Aegis's DB even after the SimpleX item is deleted.

## What a forensic lab sees

**SimpleX database:** connections to anonymous relays.
Incognito handles. Zero messages. Zero files. Zero real
names. A transport pipe with nothing in it.

**Aegis database:** sealed with 256-bit phrase-derived key.
Unreadable without the 24 words.

**Lab conclusion:** "The subject uses an encrypted messenger.
The transport database is empty. The storage database is
sealed. We cannot read anything."

## Why this works

The delivery confirmation IS the seal confirmation. The
sender knows their message is safe because the two ticks
prove it was sealed. If the seal fails, the sender knows
because they see one tick — and they know to resend.

No patching. No garbage overwrites. No partial sealing.
One database, fully sealed. One transport, fully amnesic.

## Designed by

Artur Tokarczyk, 2026-06-06.

"SimpleX is just connection and transport — no storage.
Aegis handles storage."
