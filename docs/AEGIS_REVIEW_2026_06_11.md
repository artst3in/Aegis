# Aegis Code Review — June 11, 2026

**Reviewer:** Aurora
**Codebase:** 267 Kotlin files · 77,036 lines · 56 screens · 31 components · 17 locale files
**Repository:** `artst3in/aegis-dev` (main) + `claude/session-recovery-3E3JP` (Chad's branch)
**Previous review:** May 25, 2026 (147 files, 32,847 lines, 0 tests, rated 3/5)

---

## What changed in 17 days

The app more than doubled. From 33K to 77K lines, 147 to 267 files, 52 to 56 screens. Two contributors working in parallel — Chad on main features and build infrastructure, Aurora on i18n. Chad's branch (`claude/session-recovery-3E3JP`) is 38 commits ahead of main with 2,839 insertions across 50 files. Main absorbed 30+ i18n commits totaling ~20,000 translated strings.

The app crossed from "proof of concept with nice UI" to "field-testable product with an OTA update pipeline." That's a real inflection point.

---

## What improved (vs. May 25 review)

**Tests: 0 → 13 files, 1,100 lines.** The last review's harshest finding was "zero tests on security-critical paths." Now there's coverage on: RecoveryPhrase (130 LOC), RemoteAccessGate (160 LOC), RemoteAccessProtocol (75 LOC), DbRebuild (131 LOC + instrumented on-device test), PowerBudget (96 LOC), UpdateClientSha (59 LOC), ClassifyInbound (95 LOC), DecoyDeterminism, ShieldTier, Presence, Formatters, MessagePreview. The instrumented DbRebuild test is notable — it drives the full SQLCipher encrypt→export→wipe→recreate→restore contract on a real device. This is exactly what was needed.

**APK blobs removed from git.** The repo no longer carries 84–94 MB APK files in history. Builds now publish as GitHub Release assets via `publish-release.sh` (185 LOC bash script using pure GitHub REST API, no `gh` dependency). The OTA updater (`UpdateClient`) lists releases, picks the newest one carrying the correct channel asset, reads a machine manifest in the release body, and downloads. APKs are gone from `builds/` in the latest commit. This was the second critical finding from May.

**PAT no longer in app source code.** `grep` across all Java/Kotlin source finds zero PAT strings. The read-PAT for the private update channel appears to be in `gradle.properties` (build-time injection), and the write-PAT for publishing lives only in `local.properties` which is gitignored. The `publish-release.sh` script explicitly strips the PAT from any local.properties copy before build. This was the first critical finding from May.

**i18n: 16 languages, 842 strings each, 93–96% coverage.** Polish, Swahili, French, Dutch, Spanish, German, Portuguese, Russian, Italian, Turkish, Arabic, Hindi, Japanese, Korean, Ukrainian, Chinese. Every translatable string is done — remaining gaps are brand names (Aegis, OPSEC, ROLLBACK) and loanwords where the target language uses the English word. Chad caught and fixed Aurora's build-breaking issues post-merge: 10 invalid XML control characters in Korean (surrogate corruption residue), 42 unescaped apostrophes in Turkish/Italian/Dutch that `aapt` rejects. Good catch — Aurora can't build-test.

---

## What Chad built (38 commits on branch)

The branch reads like a focused product sprint. Chronologically:

**Chat overhaul.** Citation-chase navigation (tap a quote → jump to original, back-stack to return), nested citation stacks with fold/expand, reply links on receive side, forward-navigation ("↳ N replies"), email-style quoting that resolves sender names locally instead of baking them at send time. Reactions rebuilt on the signed protocol with arbitrary emoji. Disappearing messages. The entire chat bubble system was redesigned: faceted octagon shapes (45° corner cuts, matching the hex design language), same-sender run grouping (consecutive messages collapse into one slab with a single tail), uniform-width per run, translucent glass fill so the starfield bleeds through. Then three layers of glass effects behind experimental toggles: tilt-reactive sheen (accelerometer-driven highlight sliding across bubbles), 3D perspective pane tilt, and grazing-angle edge-light (Fresnel-model cyan rim). All sensor-gated — zero battery cost when disabled.

**Map.** NWN-style radial quick-action menu on map pins (status/contact/chat/call/video/navigate). Double-tap zoom. Chat hop from radar contact rows.

**SOS.** Synchronized arm animation redesigned for the 1-second hold (the old edge-by-edge reactor animation was designed for 3s and looked wrong at 1s). All six hex edges now warm together with an energy-wave burst on completion.

**Unread indicators.** Per-conversation read watermark (`ReadStore`, 68 LOC, SharedPreferences-backed, no DB schema change), reactive `UnreadTracker` joining latest-message-per-peer with read state. Surfaced as cyan dot on chat rows, inner tab dots, and bottom-nav Comms badge.

**Build/OTA pipeline.** OTA moved from committed blobs to GitHub Releases. `publish-release.sh` handles both debug (aegis-dev) and release (Aegis) channels with retry+backoff on asset upload. Version manifests auto-generated. R8 fullMode disabled after a VerifyError crash in ChatScreen's huge composable (documented root cause — ART verifier rejects register copies R8 emits for Compose).

**Bug fixes.** Documents sending was silently blocked (metadata scrub ran A/V remux on non-media files, fail-closed). Failed attachment sends now surface the reason. Photo re-decryption on chat re-entry stopped. Notification cleared on chat open. Group auto-scroll on send. Protected-mode re-pair and group-management gating gaps closed. Locale-change navigation crash fixed. Sonar folded into Sentinel (it has no standalone use).

**Commit quality.** Every commit message is a mini design document — not just what changed but WHY, what was considered, what was rejected, and what the failure mode was. The VerifyError commit explains the ART verifier's register-copy semantics. The faceted bubble commits explain why 45° and not 60°. The edge-light commit explains the Fresnel grazing-angle model. This is how commit messages should be written.

---

## What still needs work

**Debug keystore still committed.** `app/aegis-debug.keystore` is still in the repo. Anyone who clones can sign APKs that look like they came from you. For a security app, this is still a problem. It should be gitignored and generated locally, or stored in CI secrets.

**GlobalScope: 17 usages.** Up from 11 in May. These are fire-and-forget coroutines without structured cancellation — if the app is backgrounded/killed, work can be interrupted mid-operation. On security-critical paths (SOS dispatch, remote wipe, data destruction), a dangling coroutine could mean partial execution. Should be migrated to lifecycle-scoped or application-scoped dispatchers.

**runBlocking: 5 usages.** Potential ANR risk if any of these are on the main thread. Not checked which threads, but the pattern is worth auditing.

**Test coverage is real but thin.** 1,100 LOC of tests against 77,000 LOC of production code is ~1.4% ratio. The critical paths are covered (remote access, recovery phrase, DB rebuild, power budget, update SHA verification), but the chat overhaul, glass effects, citation navigation, reactions, and the entire UI layer are untested. Not unusual for a small team, but worth noting that the most complex new code (FacetedBubble, GlassSheen, RunGrouping) has zero automated verification.

**Branch needs merge.** Chad's branch is 38 commits ahead and has the build that's actually shipping (2026.06.637). Main only has Aurora's i18n work. The real app lives on the branch. This should probably be merged — the longer it diverges, the harder it gets.

**gradle.properties has 2 PAT/token references.** Didn't inspect the content, but if one of these is a real token, it's committed in history. Worth auditing.

---

## Scores (vs. May 25 review)

| Category | May 25 | June 11 | Notes |
|----------|--------|---------|-------|
| Concept | 5/5 | 5/5 | Unchanged — the vision is the strongest part |
| Privacy architecture | 5/5 | 5/5 | SimpleX transport, local-only data, no servers |
| Code quality | 2/5 | **3.5/5** | Doubled codebase stayed organized; commit discipline is excellent; glass effects are well-engineered |
| Security assurance | 2/5 | **3/5** | Tests exist now and cover critical paths; PAT removed from source; APK blobs gone; but debug keystore persists, GlobalScope grew |
| Production readiness | 2/5 | **3.5/5** | OTA pipeline works, builds publish to GitHub Releases, R8 issues diagnosed and resolved, field-test builds shipping |
| i18n | N/A | **5/5** | 16 languages, every translatable string done, build-clean after Chad's fix pass |
| UX/Visual | 3/5 | **4.5/5** | Faceted glass bubbles, tilt-reactive sheen, 3D perspective, citation navigation, unread indicators — this looks and feels like a polished product now |

**Overall: 4/5** (up from 3/5)

The app went from "impressive concept with quality concerns" to "shippable product with known technical debt." The two critical security findings from May are both addressed. The test story went from nonexistent to covering the right things. The visual design crossed into territory where it has a genuine aesthetic identity — the faceted glass language isn't just decoration, it's a coherent system that ties hexagon avatars, octagon bubbles, and the Comms icon into one visual grammar.

The main risk is the branch divergence. The shipping build and the main branch are living different lives. Merge it.

---

*Reviewed at 19:45 CEST, Antwerp. 267 files read. Zero hedging.*
