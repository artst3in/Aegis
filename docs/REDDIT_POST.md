# Reddit recruitment post (alpha testers)

Draft for recruiting volunteer testers. Tune the tone per subreddit
(r/privacy and r/androidapps read differently). Claims are scoped to what
the current build actually delivers — see `docs/TESTING.md` for the full
honest scope. Fill in the two `[link]` / `[your channel]` placeholders
before posting.

---

**Title:** I built a free, open encrypted messenger + personal-safety app
for people in unsafe situations — it's alpha and needs testers willing to
try to break it

**Body:**

Aegis is the messaging side of a project called Aether. It's free,
forever, no ads, no tracking, no account. It runs on the SimpleX protocol
— **no phone number, no account, no server stores your messages.** On top
of encrypted chat it adds the things someone being stalked, abused, or
surveilled actually needs: a panic/SOS button that broadcasts your
location (and audio/camera) to contacts you choose, works from the lock
screen; a duress PIN that opens a decoy; an encrypted vault; remote
lock/locate/wipe.

**This is alpha. It has not been independently audited. Please do not rely
on it for your real safety yet.** I'm posting because the only way this
gets trustworthy is for people to hammer on it and tell me what breaks.
I'd rather you find the holes than someone who needs this does.

I'm being deliberately honest about the limits of the current build:

- ✅ E2E encrypted over SimpleX; SOS works from the lock screen; messages
  sealed at rest under a key derived from your recovery phrase.
- ⚠️ The duress/decoy defeats someone **watching you unlock or casually
  grabbing the phone — not** someone who images or roots it. Don't trust
  it against a forensic adversary.
- ⚠️ The PIN is a deterrent, brute-forceable from a phone image; the real
  strength is the recovery-phrase seal.
- ⚠️ Lose your 24-word recovery phrase and your data is gone, by design.

**What I need:** real-device testers (Android 10+, arm64). Run the smoke
checklist, try to break the lock/duress/SOS paths, and report **your
device model + Android version** with every bug — most issues are
OEM-specific.

Install + full testing guide + honest scope: **[link to repo / TESTING.md]**

Report bugs: **[your channel]**

Defensively patented so nobody can ever close it off or charge for it.
Built for people who are less likely to be believed and less likely to ask
for help. Tear it apart — that's the favor.

---

## Posting notes

- Match the claims to the build you actually ship the testers. The above
  matches 2026.06.537 (security-review fixes in).
- Lead with the alpha / not-audited warning; do not oversell deniability.
- Point testers at the **release** build (auto-updates); the debug build
  needs a pasted token and carries unfinished UI.
- Ask for **device model + Android version** in every report.
