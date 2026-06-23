# Working on Aegis

## Track everything — file it before you fix it (TOP RULE, no exceptions)

Every bug, regression, or idea — the user's OR your own — gets a GitHub
issue in `artst3in/aegis-dev` the MOMENT it appears, BEFORE you start
working it. We routinely juggle several things per message and the un-filed
ones get silently dropped. The session container is ephemeral; the issue is
the only durable memory and the only clean hand-off to another session or to
Aurora.

- **One issue per distinct item**, even when a single message raises three.
  Title = the symptom in the user's words; body = repro / context / current
  hypothesis.
- **Capture is instant; processing is not.** Filing ≠ committing to fix it
  now — it just guarantees nothing is lost and the user can see + prioritise
  the backlog.
- **Close it through the commit:** reference the issue in the fix
  (`Fixes #N`) so it auto-closes and the commit↔issue trail is complete. A
  same-turn one-line fix STILL gets an issue (filed, then closed by the
  commit) — the audit trail beats the tiny overhead.
- **The open-issues list IS the backlog.** Before ending a turn where
  threads are still loose, make sure each is an open issue (or a closed
  one) — never just something you're holding in your head.

## Document code fully and properly

**Override of the global default.** The default Claude rule is "no comments
unless WHY is non-obvious." For this codebase that rule is wrong. Instead:

- KDoc every public class, function, and non-trivial private function.
  State what it does, why it exists, what guarantees it provides, what
  it does NOT do, and any non-obvious invariants the caller has to
  honour.
- Inline `// ...` comments next to anything that wouldn't be obvious in
  isolation: a chosen numeric threshold, a deliberate ordering, a
  workaround for a specific OEM/Android-version quirk, a bug-fix history
  tag tied to a user-reported issue.
- **Keep code comments NEUTRAL.** No author names ("Aurora" / "Chad" /
  the owner's real name) and no internal-spec-doc pointers ("per
  `docs/SPEC_X.md` §…") inside `app/`, `core/`, `feature/`. Root is the
  public source minus a trivial subtraction (see "Public source &
  secrets"), so the instant a name or spec-ref lands in shipping code it
  has to be hand-scrubbed back out. Explain the WHY inline; anonymous
  tags ("user report 2026-06-08", plain dates) are fine. If a design
  needs a spec, reference it from `docs/`, never from the code.
- Code is read more often than written, especially across instances
  that don't share memory. Future-me / future-Claude needs the
  rationale sitting next to the line, not two folders away.
- Match the existing density — roughly 20-25% of lines should be
  comments. KDoc + inline together.

## Spec-driven where it matters

Bigger features go through `docs/SPEC_*.md` first — proposal, problem,
proposal, alternatives considered, open questions. Aurora reviews and
adds requirements; the spec lands as `REVIEWED — Aurora approved with
additions`. Then build. Small features and bugfixes can skip the spec.

Authors:
- **Chad** — Claude instance writing code + drafting specs.
- **Aurora** — Claude instance reviewing specs + filing patents.

## Permission ladder

Don't make significant changes without explicit user permission. Things
that count as "significant":
- Touching documented numeric thresholds (PowerBudget Voyager curve,
  sentinel timeouts, sonar calibration values)
- Adding new top-level features
- Removing features

Read freely. Search freely. Looking is not acting.

## No legacy / compatibility / migration code (clean slate)

Pre-launch there are **no existing users and no on-device data to
preserve** — every install is a clean slate. Do NOT write migration
code, backward-compat shims, or "existing install" / downgrade
scaffolding. Concretely, that includes:
- Room destructive-migration *fallbacks for downgrades* and version-bump
  guards. The DB schema version is inert legacy debt pinned at **1**; the
  Room schema identity hash + `DbRebuild` (backup → wipe → recreate →
  restore) are the only change detector. Don't add
  `fallbackToDestructiveMigrationOnDowngrade()` or reason about
  "user_version=N already on disk" — those installs don't exist.
- Compat paths for old wire formats, old build versions, pre-rename
  shims, or "if an older peer…" branches. Hard-deny / clean-break from
  day one; re-pair rather than migrate.

The project has been *deleting* exactly this kind of code (LAN-removal
migration, Argon2id rekey, profile-layout migration, old-Aegis-build
compat, the pre-2026 `Family` shim). Adding it back is a regression. When
in doubt, **delete compat code, don't add it** — and if a change seems to
need migration/downgrade handling, the clean-slate assumption is almost
certainly the answer instead.

## Public source & secrets (READ THIS — Aurora leaked secrets to a
## public repo twice; these rules exist so you don't make it three)

**One source of truth.** ROOT (`app/`, `core/`, `feature/`) is the
single neutral codebase. It already has zero author names, zero physics/
Project-Aether-ecosystem references, and no dead Matrix transport. KEEP
IT THAT WAY (see the comment rule above). Do NOT create a second
hand-maintained "clean" copy — that fork existed once and was killed.

**Public source is DERIVED, never hand-edited.** To publish, run
`scripts/scrub-public.sh <out>`. It git-archives the publishable paths,
drops `src/debug` + `src/release` + the debug keystore, and strips every
`// >>> DEBUG-ONLY … // <<< DEBUG-ONLY` block. Debug-only code therefore
lives in those source-sets OR inside DEBUG-ONLY markers. Internal dev
docs (`docs/`, `CLAUDE.md`, `.claude/`, `playstore/`, `tools/`) are
private and simply never copied.

**Never leak.** These are hard rules — confirm with the owner in the
moment, every time, before any of them:
- Never push to a public repo, never publish, never `gh`-create a repo.
- Never commit secrets. The GitHub PAT, keystore path, and keystore
  password live ONLY in gitignored `local.properties`. Never echo their
  values; never paste them into a commit, comment, or chat.
- The release keystore + its password live OUTSIDE the repo
  (`/root/.signing/` on the build machine). Never put them in the tree.
- A RELEASE build bundles whatever `local.properties` holds into the
  APK. Build public/distributable release APKs with NO PAT in
  `local.properties` (the public release channel polls the public repo,
  no auth needed).
- Before publishing anything: run `scrub-public.sh`, then grep the
  output for names / `github_pat_` / `BEGIN .*PRIVATE` / keystore — zero
  hits or you do not push.

## Release workflow

The OTA channel is **GitHub Releases**, not committed blobs. APKs are
published as release ASSETS so the repos' git history never accumulates
the ~84 MB binary per build. The on-device updater (`UpdateClient`) polls
`<repo>/releases/latest` and reads a machine manifest embedded in the
release body (`<!--aegis-manifest:{…}-->`, carrying versionCode +
per-asset SHA-256). `builds/*.apk` are gitignored local staging
artifacts; only the tiny `builds/version.json` stays tracked (Gradle
reads it as the monotonic versionCode floor).

Build everything from ROOT (debug + release in ONE gradle invocation so
the versionCode floor lines up):

1. `FEAT`/`FIX` commit on the feature branch: one-line summary + a
   multi-line body explaining what changed and why.
2. `./gradlew :app:assembleSideloadRelease :app:assembleSideloadDebug`
   — confirm clean. The assembles finalize by writing
   `builds/release-version.json` / `builds/version.json`.
3. Do NOT copy APKs into `builds/`. `publish-release.sh` reads the APK
   STRAIGHT from the gradle output (`app/build/outputs/apk/sideload/
   <variant>/`) for PRIVATE destinations, so `builds/` only ever holds the
   tracked version json. The sole exception is a PUBLIC release
   (`artst3in/Aegis`): stage it into `builds/aegis-release.apk` via
   `scripts/build-dist.sh` first (strips the PAT, secret-scans, verifies
   the signer + permission split) — publish then sources that staged file.
4. `RELEASE: 2026.MM.XXX — <summary>` commit with the bumped
   `builds/version.json` (debug) / `builds/release-version.json` — NO
   APKs in the commit.
5. Fast-forward `main`, push.
6. Publish the Release(s):
   `scripts/publish-release.sh release`  → `artst3in/Aegis`   (public)
   `scripts/publish-release.sh debug`    → `artst3in/aegis-dev` (private)
   Needs `gh` authed with a **Contents:write** token (`GH_TOKEN`), which
   lives ONLY on the build machine / CI — NEVER bundled in an APK. The
   app downloads with its own read-only PAT (private channel) or
   anonymously (public). `UPDATE_REPO` is `artst3in/Aegis` (release) /
   `artst3in/aegis-dev` (debug), flipped at build time.

To publish the source: `scripts/scrub-public.sh <out>` → review → push
`<out>` to the public repo (confirm first — see the secrets rules).

## Building & publishing from a cloud session

The full build+publish pipeline runs in a Claude Code web container too —
it is NOT build-machine-only. A fresh container ships none of the
prerequisites, so the SessionStart hook (`.claude/hooks/session-start.sh`)
bootstraps them every session:

1. **Android SDK** — installs a minimal SDK (cmdline-tools + platform-35 +
   build-tools;35.0.0 + platform-tools) into `$HOME/android-sdk` when none
   is found, and writes `sdk.dir`. ~450 MB, one-time per fresh container.
2. **SimpleX native libs** — `scripts/fetch-simplex-libs.sh` as before.
3. **Signing + self-update secrets** — materialized FROM ENV into the
   gitignored places the build reads. The hook references only env var
   NAMES; the VALUES live ONLY in the environment's secret config (set in
   the web UI), never in the repo.

Env secrets to set on the environment (Settings → secrets / env):

| Env var | What | Where it goes |
| --- | --- | --- |
| `AEGIS_RELEASE_KEYSTORE_B64` | `base64 -w0 aegisrelease.keystore` | `$HOME/.signing/aegisrelease.keystore` |
| `AEGIS_RELEASE_STORE_PASSWORD` | keystore/key password | `$HOME/.signing/aegis-store-password` |
| `AEGIS_RELEASE_KEY_ALIAS` | alias (optional, default `aegis`) | `local.properties` |
| `AETHER_GITHUB_PAT` | READ-only PAT, bundled into RELEASE self-update | `local.properties` (`aether.github.pat`) |
| `GH_TOKEN` | CONTENTS:WRITE token | env only — `publish-release.sh` reads it directly; NEVER written to a file or bundled |

With those set, a cloud session builds + publishes with the SAME commands
as the build machine — `./gradlew :app:assembleSideloadRelease
:app:assembleSideloadDebug`, then `scripts/publish-release.sh {debug|release}`
(curl+python3, no `gh` needed). To wipe releases first, hit the REST API
with `GH_TOKEN` (`DELETE /repos/<repo>/releases/<id>` + the tag).

Caveat: the keystore is REQUIRED for a build to finish — AGP hard-fails
packaging with `SigningConfig "aegis" is missing required property
"storeFile"` when it is absent (the "degrade to unsigned" note in
`app/build.gradle.kts` does not actually hold for these variants). No
keystore in env → you can compile but not produce an APK.

## Verify delegated work

Sub-agents (and your own multi-file edits) can silently misreport
"already done / no changes needed." Don't trust the summary — re-verify
yourself: compile the affected variant(s), grep for what should/shouldn't
remain, and read the diff before committing. Compilation is the source
of truth, not a report.

## Commit signing — Verified commits

Commits should land **Verified** on GitHub so authorship can't be
spoofed. How to achieve that depends on WHERE the commit is made:

- **Cloud sessions (Claude Code on the web).** The container's local SSH
  signing key (`~/.ssh/commit_signing_key.pub`) is an empty placeholder —
  `git commit -S` produces NO signature, and the ephemeral container shares
  no key with any other session, so nothing carries over. To get Verified
  commits from a cloud session, write them through the GitHub API (the
  `push_files` / `create_or_update_file` MCP tools): GitHub signs API
  commits server-side, so they show Verified. After such a push, resync the
  local checkout with `git fetch && git reset --hard origin/<branch>`.
  Caveat: impractical for large multi-file / native-build commits — a plain
  unsigned push is acceptable for those; it's the build-machine `RELEASE:`
  commits that most need a signature.
- **Build machine.** Configure real signing once — `git config --global
  gpg.format ssh`, `user.signingkey <path-to-real-key.pub>`,
  `commit.gpgsign true` — with that public key registered under the GitHub
  account → Settings → SSH and GPG keys → **Signing keys**. Ordinary local
  commits (including releases) then land Verified with no extra steps.

Verified is about provenance only: it means the signature matches a key
GitHub trusts for this author, NOT that the code is correct or reviewed.

## What this app is

Aegis is the personal-security messaging side of Project Aether —
SimpleX-based encrypted chat, panic broadcast to chosen contacts,
remote-access surface for the owner's other devices, mugshot capture
on duress PIN, and (now) Sentinel mode for unattended-phone monitoring.

The target user is anyone for whom phone security is not optional:
people in unsafe situations — domestic abuse, stalking, coercive
control, trafficking (which hit men too, and men are less likely to
be believed or to ask for help) — journalists, activists, vulnerable
family members. Every design choice trades comfort against survival
for someone who actually needs this.

Free forever. Defensively patented. Nobody else gets to charge for it.
