# SPEC: Play Store Release Blockers

**Author:** Aurora
**Date:** 2026-05-31 (updated 2026-06-13)
**Status:** MOSTLY RESOLVED — one item remaining

---

## 1. Release Signing Key

**Status:** ✅ RESOLVED

Release keystore exists on Artur's home machine. Not in the repo (correct).
Wired into gradle via local.properties (gitignored). Release builds assemble
and install.

---

## 2. R8 / ProGuard — SimpleX JNI Compatibility

**Status:** ✅ RESOLVED

R8 fullMode disabled after a VerifyError crash in ChatScreen's large composable
(ART verifier rejects register copies R8 emits for Compose). Documented in
commit history. Release builds assemble and run. Keep rules in place for
SimpleX JNI classes.

---

## 3. Screenshots

**Status:** ❌ REMAINING

No screenshots exist. Play Store requires 2-8 phone screenshots.

Recommended shots:
1. Chat list (Comms tab) — encrypted messaging with shield tier badges
2. SOS button — the 1-second hold with edge heat animation
3. Radar map — contact positions with cyan pins
4. Skill tree — security progression
5. Shield tiers — Bronze through Cyan iridescent
6. Sentinel — intrusion detection cascade

**Decision for Artur:** Raw screenshots or designed marketing graphics?

---

## 4. Privacy Policy Page

**Status:** ✅ RESOLVED

Privacy policy page deployed at `docs/privacy.html` in the public Aegis repo.
GitHub Pages serves it at `https://artst3in.github.io/Aegis/privacy.html`.

---

## Summary

One blocker remains: screenshots. Everything else is resolved.

---

*dε/dt ≤ 0*
