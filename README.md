# Aegis

Encrypted communication. Emergency response. Personal security.

Part of [Project Aether](https://github.com/artst3in).

## Status: Alpha

308 Kotlin files. ~87,000 lines. 62 screens. 623 UI strings across 16 languages. 41 specifications.

See [SECURITY_STATUS.md](SECURITY_STATUS.md) for what works and what doesn't.

## Build

See [BUILD.md](BUILD.md).

## Architecture

Single-activity Jetpack Compose app on Android 10+.

```
app/
├── ui/screens/       60 Compose screens
├── ui/components/    LunaGlass design system components
├── simplex/          SimpleX transport (5,000 lines)
├── lock/             Encryption, PIN, seal, recovery phrase
├── sentinel/         Covert intrusion detection (patent filed)
├── backup/           Content-portable backup (Argon2id)
├── remote/           Remote access (locate, lock, wipe, siren)
├── call/             Voice/video calls (WebRTC)
├── core/             SOS pipeline, protocol manager
├── profile/          Multi-profile, ephemeral profiles
└── admin/            Shield tiers, skill tree, achievements
```

Transport: [SimpleX](https://simplex.chat/) — no accounts, no phone numbers, no metadata.

Encryption: 24-word BIP39 phrase → seal keypair. TEE-wrapped storage. Argon2id for PIN and backup.

## Features

Working: E2E messaging, calls, Aether Protocol (anonymous identity), SOS (GPS + audio + camera + siren), hardware trigger, three-PIN duress, trust model, shield tiers, mugshot, SIM swap, canary, geofencing, remote access, Voyager mode, vault, anonymous groups, radar, lock curtain, recovery phrase, phrase-rooted seal + TEE wrap, backup, ephemeral profiles, crash detection, sentinel, snatch detection, transactional delivery (partial).

## Documentation

See [docs/INDEX.md](docs/INDEX.md) for the full documentation map.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

AGPL-3.0. See [ATTRIBUTION-SimpleX.md](ATTRIBUTION-SimpleX.md).
