# Documentation Index

## For new contributors

| Document | Description |
|----------|-------------|
| [BUILD.md](../BUILD.md) | How to build Aegis from source |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Architecture, code style, what needs help |
| [SECURITY.md](../SECURITY.md) | How to report vulnerabilities |
| [TESTING.md](TESTING.md) | Smoke test checklist |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Technical architecture overview |
| [VERSIONING.md](VERSIONING.md) | YYYY.MM.BUILD version scheme |

## Specifications

Core features — every spec describes the design before implementation.

| Spec | Feature |
|------|---------|
| [SPEC_UNBREAKABLE](SPEC_UNBREAKABLE.md) | Encryption architecture: phrase, PIN, TEE, seal |
| [SPEC_AEGIS_PROTOCOL](SPEC_AEGIS_PROTOCOL.md) | Anonymous identity — how identity never enters the transport |
| [SPEC_TRANSACTIONAL_DELIVERY](SPEC_TRANSACTIONAL_DELIVERY.md) | Seal-then-purge message pipeline |
| [SPEC_TRUST_MODEL](SPEC_TRUST_MODEL.md) | Trusted / Emergency / Untrusted tiers |
| [SPEC_TRUST_CONTAINERS](SPEC_TRUST_CONTAINERS.md) | Trust container isolation |
| [SPEC_SOS_COORDINATION](SPEC_SOS_COORDINATION.md) | SOS closest-person notification |
| [SPEC_SOS_DRILL](SPEC_SOS_DRILL.md) | SOS drill system |
| [SPEC_SENTINEL_MODE](SPEC_SENTINEL_MODE.md) | Covert intrusion detection cascade |
| [SPEC_PORTABLE_BACKUP](SPEC_PORTABLE_BACKUP.md) | Content-portable backup (Argon2id) |
| [SPEC_PROFILE_ONBOARDING](SPEC_PROFILE_ONBOARDING.md) | Multi-profile creation and switching |
| [SPEC_ANONYMOUS_GROUPS](SPEC_ANONYMOUS_GROUPS.md) | Anonymous group chat |
| [SPEC_SHIELD_TIERS](SPEC_SHIELD_TIERS.md) | Bronze → Cyan security progression |
| [SPEC_SKILL_TREE_PROGRESSION](SPEC_SKILL_TREE_PROGRESSION.md) | Skill tree node progression |
| [SPEC_SKILL_TREE_VISUAL](SPEC_SKILL_TREE_VISUAL.md) | Skill tree visual design |
| [SPEC_ACHIEVEMENTS](SPEC_ACHIEVEMENTS.md) | Achievement system |
| [SPEC_CRASH_DETECTION](SPEC_CRASH_DETECTION.md) | Vehicle crash detection |
| [SPEC_DEVICE_CONTROL_PANEL](SPEC_DEVICE_CONTROL_PANEL.md) | Remote device control |
| [SPEC_DEVICE_OWNER_HEX](SPEC_DEVICE_OWNER_HEX.md) | Device Owner hex badge |
| [SPEC_PRESENCE_STATES](SPEC_PRESENCE_STATES.md) | Online/offline/away states |
| [SPEC_RELEASE_CHANNELS](SPEC_RELEASE_CHANNELS.md) | Release channel management |
| [SPEC_TUTORIAL](SPEC_TUTORIAL.md) | First-run tutorial with Cyan |
| [SPEC_CYAN_MASCOT](SPEC_CYAN_MASCOT.md) | Cyan mascot design |
| [SPEC_CONTACT_GRAPH_SEALING](SPEC_CONTACT_GRAPH_SEALING.md) | Contact graph encryption |
| [SPEC_GROUPS_HARDENING](SPEC_GROUPS_HARDENING.md) | Group security hardening |
| [SPEC_GROUP_MODULE_ISOLATION](SPEC_GROUP_MODULE_ISOLATION.md) | Group module isolation |
| [SPEC_GROUP_PROFILE](SPEC_GROUP_PROFILE.md) | Group profile management |
| [SPEC_GROUP_WHISPER](SPEC_GROUP_WHISPER.md) | Private messaging within groups |
| [SPEC_PENDING_INVITATIONS](SPEC_PENDING_INVITATIONS.md) | Invitation expiry and management |
| [SPEC_SETTINGS_REORG](SPEC_SETTINGS_REORG.md) | Settings screen organization |
| [SPEC_SIMPLEX_DB_MIGRATION](SPEC_SIMPLEX_DB_MIGRATION.md) | SimpleX database migration |
| [SPEC_SUPPORT_BUTTON](SPEC_SUPPORT_BUTTON.md) | Support button design |

## User-facing documentation

Shipped inside the APK under `app/src/main/assets/docs/`.

| Document | Section |
|----------|---------|
| GETTING_STARTED.md | Basics |
| AEGIS_USER_MANUAL.md | Basics |
| FAMILY_SPEC.md | Security — The Shield |
| PRIVACY.md | Security |
| PROTECTION_LIMITS.md | Security |
| EPHEMERAL_PROFILES.md | Security |
| WHY_YOUR_PASSWORD_IS_UNCRACKABLE.md | Security |
| WHY_NO_GROUP_SOS.md | Design decisions |
| WHY_NO_GROUP_DM.md | Design decisions |
| WHY_NO_TOR.md | Design decisions |
| DESIGN_PHILOSOPHY.md | Design decisions |
| ARCHITECTURE.md | Technical |
| VERSIONING.md | Technical |
| ABOUT_PROJECT_AETHER.md | Technical |
| SENTINEL.md | Experimental |
| SNATCH_DETECTION.md | Experimental |
| CRASH_DETECTION.md | Experimental |

## Design and analysis

| Document | Description |
|----------|-------------|
| [FAMILY_SPEC.md](FAMILY_SPEC.md) | Complete feature overview |
| [GUI_SPEC.md](GUI_SPEC.md) | UI specification |
| [AEGIS_SIMPLEX_AUDIT.md](AEGIS_SIMPLEX_AUDIT.md) | SimpleX integration audit |
| [LUNAGLASS_AUDIT.md](LUNAGLASS_AUDIT.md) | LunaGlass design system audit |
| [THE_BOB_PROBLEM.md](THE_BOB_PROBLEM.md) | Trust model edge cases |
| [WHY_NO_POST_QUANTUM.md](WHY_NO_POST_QUANTUM.md) | Post-quantum cryptography analysis |
| [PLAY_STORE_LISTING.md](PLAY_STORE_LISTING.md) | Play Store listing draft |
