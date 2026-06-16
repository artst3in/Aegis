# Ephemeral profiles

An **ephemeral profile** is a throwaway identity that is **wiped when you
lock the app**. Use it for a one-time conversation, meeting someone you
don't trust yet, or a contact you want gone after the session.

## What it does

- Created from **Settings → Profiles → Create ephemeral profile**.
- Runs as its own isolated messaging identity (its own contacts, its own
  message history) — separate from your main profile.
- When you **lock** Aegis — manual lock, the auto-lock timeout, or a
  reboot — the ephemeral profile is **destroyed**: its messages,
  contacts, keys, and its messaging-engine user are all deleted, and you
  land back on your main profile.
- **SOS while on an ephemeral profile** destroys it instantly, switches
  to your main profile, and raises the alarm from there — one button:
  clean the evidence, then call for help.
- It is **not** in your recovery phrase and **not** in backups. Once
  wiped, it is gone — there is no recovery.

## Being honest about "wiped"

Ephemeral means **wiped on lock**, *not* "never written to disk."

Aegis is built on the SimpleX messaging engine, and that engine **cannot
run in memory only** — it must write its database to storage to work at
all. So while an ephemeral profile is active, its data is on disk like
any other. On lock we **delete that data and overwrite the message
contents**, which removes it from normal access. But deletion on a phone
is not a guaranteed forensic wipe: fragments can survive in unused
storage until the system reuses that space. A determined forensic lab
with the unlocked device might still recover traces.

If you need true, Tails-style, **never-touches-disk** ephemerality, that
requires rewriting the messaging transport so it can run entirely in RAM.
That is a real project on the Aether roadmap — **a plan, not a promise.**
Until it lands, treat an ephemeral profile as "destroyed on lock," and
for the highest-risk situations assume traces *may* remain.

## When to use it

- A single conversation that shouldn't outlive the session.
- A contact you don't yet trust.
- A one-time channel you'll never need again.

For anything you need to keep, use your normal profile — and remember
that turning an ephemeral profile **permanent** (the "Make permanent"
button) deliberately keeps its data on disk, which is the *dangerous*
direction: you are creating evidence that can be recovered.
