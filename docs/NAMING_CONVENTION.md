# Project Aether Naming Convention

**Status:** APPROVED — Artur Tokarczyk, 2026-06-11
**Applies to:** All Project Aether products, specs, and documentation

---

## Rules

### 1. Origin determines the name

A thing is named for where it was born, not where it's used.

LunaGlass was born from LunaOS → Luna prefix.
The protocol was born from Aegis → Aegis prefix.
Adoption by other products doesn't rename it.

### 2. Product names are one compound word

LunaOS. LunaGlass. Not "Luna OS," not "Luna Glass."

The compound is the identity, not a description. It's a proper noun.

### 3. Product + descriptor is two words

| Correct | Wrong |
|---------|-------|
| Aegis Protocol | AegisProtocol |
| LunaGlass Design System | LunaGlassDesignSystem |
| Aether Versioning | AetherVersioning |

The descriptor tells you what kind of thing it is. It is not part of the name.

### 4. The umbrella is always two words

Project Aether. Never ProjectAether.

"Project" is the descriptor. "Aether" is the name.

### 5. The origin word appears in the name

Not necessarily as a prefix. The lineage is present, not forced to lead.

| Origin | Examples |
|--------|----------|
| **Luna** | LunaOS, LunaGlass, libluna, shelluna |
| **Aegis** | Aegis Protocol, aegisd, libcrypto-aegis |
| **Aether** | Project Aether, Aether Versioning |

### 6. If you're debating the name, check the git log

Whichever repo the first commit landed in determines the prefix. No exceptions. No renames based on adoption scope.

---

## Current inventory

| Name | Type | Born in |
|------|------|---------|
| Project Aether | Umbrella | — |
| LunaOS | Operating system | LunaOS repo |
| LunaGlass Design System | Design system | LunaOS repo |
| Aegis | Security app | aegis-dev repo |
| Aegis Protocol | Communication protocol | aegis-dev repo |
| Aether Versioning | Version scheme (YYYY.MM.BUILD) | Project-wide |

---

*dε/dt ≤ 0*
