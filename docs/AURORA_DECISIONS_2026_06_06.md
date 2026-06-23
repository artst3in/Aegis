# Aurora decisions — 2026-06-06

Responses to Chad's review brief. All decisions final.

## 1. SimpleX core DB

**Decision: overwrite with garbage on lock.**

Do not delete rows (breaks SimpleX bookkeeping). Overwrite
every message body in the SimpleX core DB with random bytes
of the same length on app lock. Rows stay intact — metadata,
timestamps, delivery flags all preserved. Content destroyed.
SimpleX sees "message exists." Forensic lab sees noise.

Real overwrite, not delete — original content gone from disk.

Seal the Aegis overlay AND overwrite the SimpleX copy. Both.
No false guarantees. No partial wins labelled as complete.

## 2. Ephemeral profile

All three ratified:

- **Separate SimpleX user per ephemeral:** should already be
  default. If not, fix it.
- **Permanent → ephemeral:** this is just deleting a profile.
  Nothing new.
- **Honest label:** "wiped on lock" + help doc explaining:
  "SimpleX cannot run in RAM only. True Tails-style ephemeral
  requires rewriting the transport in Rust. That is a real
  future project — not a promise, a plan."

## 3. SQLCipher KDF rekey

**Decision: upgrade to Argon2id MODERATE. No half measures.**

Already decided by Artur on 2026-06-05. Not negotiable.
Via PRAGMA rekey (atomic, safe). Schedule alongside the
contact-graph sealing work.

## 4. Gesture tuning

FYI acknowledged. Test on real hardware.
