# Contributing to Aegis

## Architecture

Aegis is a single-activity Jetpack Compose app with these major modules:

| Module | Purpose |
|--------|---------|
| `app/` | Main application, UI screens, navigation |
| `core/` | Core business logic, data layer |
| `feature/` | Feature modules (groups, etc.) |
| `simplex-upstream/` | SimpleX Chat protocol integration |

Key packages inside `app/`:
- `ui/screens/` — 62 Compose screens
- `ui/components/` — Reusable UI components (LunaGlass design system)
- `simplex/` — SimpleX transport layer (~5,000 lines)
- `lock/` — Encryption, PIN, seal/unseal, recovery phrase
- `sentinel/` — Covert intrusion detection
- `backup/` — Content-portable backup (Argon2id)
- `remote/` — Remote access handler
- `call/` — Voice/video calls (WebRTC)
- `core/` — SOS pipeline, protocol manager

## Building

See [BUILD.md](BUILD.md).

## Submitting changes

1. Fork the repository
2. Create a feature branch (`feature/your-change`)
3. Make your changes
4. Test on a physical device (emulators miss sensor-dependent features)
5. Submit a pull request with a clear description

## Code style

- Kotlin with Jetpack Compose
- No `!!` (non-null assertions) — use safe calls or explicit checks
- UI strings go in `strings.xml`, not hardcoded
- Comments explain WHY, not WHAT
- Security-critical code gets a doc comment explaining the threat model

## What needs help

See the issues tab. Priority areas:
- Automated tests (19 test files for ~87,000 lines — coverage is thin)
- Translation — 16 languages need native speaker review
- Accessibility — screen reader support, content descriptions
- Performance profiling on low-end devices

## Design system

Aegis uses the [LunaGlass](https://github.com/artst3in/LunaGlass) design system. Cyan (#00E5FF) is the brand color. UI components follow hex geometry. See the design tokens in the LunaGlass repository.

## Specifications

Every major feature has a specification in `docs/SPEC_*.md`. Read the relevant spec before modifying a feature. If your change conflicts with the spec, discuss in the PR.
