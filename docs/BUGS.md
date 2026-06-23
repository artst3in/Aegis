# Aegis — open bug & task backlog

Running list of known bugs and unfinished work. Newest reports at the top
of each section. When something lands, move it to "Recently fixed" with
the commit. Items that are code-complete but not yet confirmed on two real
phones live under "Needs on-device verification" — that is the active
device-test queue, not "done".

_Last updated: 2026-06-12_

## ⚠️ Needs on-device verification (landed + compiling, NOT confirmed on device)

These all compile and pass unit tests, but every one is a device-observable
behaviour the tests can't exercise. Verify on two real phones, then move to
"Recently fixed".

1. **Delivery/read ticks — four-state ladder + read-receipt reliability.**
   Commits `da5e999` (ladder) + `4d56450` (read-receipt freeze). The tick
   model is now: `sent` ✓ dim → `delivered` ✓ bright → `sealed` ✓✓ bright+dim
   → `read` ✓✓ bright+bright (see `SPEC_TRANSACTIONAL_DELIVERY.md`).
   _Verify:_ (a) all four states render in order with the right brightness;
   (b) a message you read reaches bright ✓✓ — including the foreground
   catch-up (receive while backgrounded, then reopen the chat) and after an
   app restart (watermark is now persisted).

2. **Attachment filename / MIME — received file showed `<uuid>.vnd.andr` /
   octet-stream + duplicate filename line.** Commit `9a690e4`. Files now stage
   under their original name; send is caption-only; receive uses `file.fileName`
   for display + MIME and the core path only to locate bytes.
   _Verify:_ send an APK between two **new-build** phones — real name, opens
   (correct MIME), no duplicate name line. (Old→new sends can't recover: an
   old sender never transmitted the real name.)

3. **Signed control channel — clean-upgrade self-heal (presence + GPS).**
   Commit `9357916`: `HelloBroadcaster.broadcastNow` now fires from
   `SimpleXTransport` once the transport is healthy + after `apiReconnect`
   (was a racy cold-start launch); a hello with a new/changed key resets
   `controlCtrRecv` and greets back. Presence + GPS were CONFIRMED restored by
   a manual re-pair (2026-06-11). _Verify:_ that a clean upgrade self-heals an
   already-paired contact WITHOUT a re-pair (the case the threat-model user
   can't perform, and that an absent contact can't get at all).

4. **Bottom-nav glass sheen — grey box.** Commit `50b6bc4`. `maskToContent`
   (offscreen layer + `BlendMode.SrcAtop`) paints the glint only over the
   glyph's pixels; nav icons opt in. _Verify:_ sheen toggle on → metallic
   shine on the glyph, no opaque rounded square on the dark bar.

## P0 — Replay-counter desync can re-break a bootstrapped contact

The one part of the 06-10 signed-channel fallout that CAN recur (the rest was
a one-time migration miss, now self-healing — see item 3). A partial bootstrap
or counter desync drops an already-working 1:1 contact back to `dropped
unverified x.aegis.v1`, and presence/ticks/GPS die again with no automatic
recovery. Needs its own guard — e.g. tolerate/resync `controlCtrRecv` on a
freshly verified hello rather than hard-rejecting on counter mismatch.
Sites: `SimpleXTransport.verifySignedControl` (~834), `HelloBroadcaster`.

**Background (06-10 signed-channel mechanism, kept for reference).** In 1:1
chats presence (`[aegis:status]`), ticks (`[aegis:read]`/`[aegis:sealed]`/
`[aegis:delivered]`) and location (`[aegis:location]`) all ride the Ed25519
signed control channel; group + plaintext chat are unaffected. Shipped in
`c1c9c1a`. The pubkey bootstraps via `[aegis:hello]`. Decisive ConnectionLog
lines on a broken pair: `not bootstrapped` (we lack peer key) / `dropped
unverified x.aegis.v1` (sig or counter) / `dropped unsigned plaintext` (peer on
old build) / `broadcast aegis:hello … to N peer(s)` + `aegis:hello → … ok=?`.

## Carried over (prior-session handoff)

- **Chat bubble rim — DECISION pending.** Flat 1 dp hairline may read too
  flat; option is a faint inner bevel ~1.5 dp inward (still seamless).
  Needs an owner pick. Site: `ui/components/GlassSheen` (`glassEdgeLight`).
- **Wi-Fi-only attachments — finish WIP.** Remaining: Settings "Attachment
  downloads" section (toggle + per-type checkboxes + log-size slider),
  tutorial step that writes `AttachmentPrefs`, migrate the other two update
  gates (`UpdateCheckWorker`, `UpdateSettingsScreen`) onto `NetworkMetering`,
  and verify `/freceive` survives a cold restart. Spec:
  `docs/SPEC_WIFI_ONLY_ATTACHMENTS.md`.
- **Power Voyager — not started.** Spec approved, implementation pending.
  Spec: `docs/SPEC_POWER_VOYAGER.md`.

## Build / infra

- **`build.gradle.kts` "degrade to unsigned" does not hold.** With no release
  keystore, AGP HARD-FAILS packaging (`SigningConfig "aegis" is missing
  required property "storeFile"`) instead of emitting an unsigned APK as the
  comment claims. Either leave the signingConfig unset when unconfigured (so
  debug falls back to the default debug keystore and release goes unsigned for
  out-of-band signing) or fix the comment. Site: `app/build.gradle.kts`
  signingConfigs block.

## Tech debt (from `docs/AEGIS_REVIEW_2026_06_11.md`)

- Debug keystore still committed in-repo (`app/aegis-debug.keystore`).
- `GlobalScope` usage has grown — audit for lifecycle leaks.
- Test coverage thin (~1.4%): chat overhaul, glass effects, citation
  navigation, reactions, and the UI layer are untested.
- `gradle.properties` had 2 PAT/token references — audit git history.

## Recently fixed

- 2026-06-12 — Radar avatar flicker fixed: a sticky per-peer avatar cache in
  `recomputePeerMarkers` (MapScreen) treats a transient null `announcedAvatarPath`
  on a status-only rebuild as "no data this frame," keeping the last-known
  avatar instead of dropping the pin to its first-letter monogram. Verify on
  device (two phones sharing location).
- 2026-06-12 — Naming convention applied: "Aether Protocol" → "Aegis
  Protocol" across comments + docs; `SPEC_AETHER_PROTOCOL.md` →
  `SPEC_AEGIS_PROTOCOL.md` (`dd9163c`). Per `docs/NAMING_CONVENTION.md`.
- 2026-06-12 — Delivery/read ticks root cause closed out (pending device
  verification, see above). ConnectionLog from the broken pair showed native
  `sndRcvd` deliberately skipped + an `UnreadDiag` "stuck" line: the read
  receipt was foreground-gated with an in-memory-only watermark and no retry,
  so a message read from the background — or whose single `[aegis:read]`
  packet was dropped — froze the sender at `sealed`. Fixed by persisting the
  watermark + a forced foreground catch-up resend (`4d56450`), and the new
  `delivered` rung (`da5e999`). The two earlier suspects were investigated and
  RULED OUT: `recordReceived` stores the inbound `simplexItemId` = the sender's
  echoed `sourceItemId` (id-spaces match), and the `sent`-time purge deletes
  only the SimpleX core copy, never nulling the row's `simplexItemId`.
- 2026-06-11 — Republished both channels at `2026.06.692` after wiping 20
  stale releases (debug + release on `aegis-dev`).
