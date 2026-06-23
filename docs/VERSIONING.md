# Project Aether Versioning Scheme

> **This versioning scheme applies to ALL Project Aether repositories:**
> LunaOS · Aegis · LCPFS · Null Bootloader · all future components.
>
> One scheme. Universe-wide. Zero entropy.


> **TL;DR**: `2025.12.100` — Year.Month.Build. One number to increment. Zero entropy.

## The Scheme

```
┌─────────────────────────────────────────────────────────────┐
│  OFFICIAL (public):        2025.12                          │
├─────────────────────────────────────────────────────────────┤
│  CARGO VERSION (all):      2025.12.100                      │
│  └── YYYY.MM.BBB - bump BBB (100→101→102) per release       │
├─────────────────────────────────────────────────────────────┤
│  BUILD DNA:                2025-12-27T14:32:15.123456789Z   │
│  └── Unique per compile, nanosecond precision               │
└─────────────────────────────────────────────────────────────┘
```

### Components

| Component | Format | Example | Purpose |
|-----------|--------|---------|---------|
| **Official** | `YYYY.MM` | `2025.12` | Public-facing, marketing, docs |
| **Cargo** | `YYYY.MM.BBB` | `2025.12.100` | All Cargo.toml, crates.io compatible |
| **Build DNA** | ISO-8601 ns | `2025-12-27T14:32:15.123456789Z` | Unique build identification |

### Release Workflow

```bash
# First release of December 2025
version = "2025.12.100"

# Second release same month
version = "2025.12.101"

# January 2026 arrives
version = "2026.01.100"
```

> **Why start at 100?** Cargo/semver rejects leading zeros (`2025.12.000` is invalid).
> Starting at 100 preserves the 3-digit aesthetic while staying semver-compliant.
> We "lose" 100 slots per month — but 900 releases/month is still plenty.

---

## Entropy Analysis: Why This Scheme?

### Information-Theoretic Entropy (bits of decision per release)

| Scheme | Decisions | Bits/Release | Sync Overhead | Total Entropy |
|--------|-----------|--------------|---------------|---------------|
| **SemVer (x.y.z)** | major/minor/patch? | ~1.58 bits | mental model | ██████████░░░░░░ **62%** |
| **CalVer YYYY.MM.DD** | none (date-locked) | 0 bits | calendar sync | ████████░░░░░░░░ **50%** |
| **Dual (DD + BBB)** | which scheme? + maintain 2 | ~2.0 bits | dual tracking | █████████████░░░ **85%** |
| **Unified YYYY.MM.BBB** | just increment BBB | 0 bits | none | ███░░░░░░░░░░░░░ **18%** |

### Detailed Breakdown

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                           ENTROPY COMPONENTS                                  ║
╠══════════════════════════════════════════════════════════════════════════════╣
║                                                                              ║
║  SEMVER (1.2.3)                                                              ║
║  ├─ Semantic judgment: "breaking? feature? fix?"     log₂(3) = 1.58 bits    ║
║  ├─ Cross-package sync: "all at same version?"       +0.5 bits              ║
║  ├─ No temporal meaning: "when was 1.2.3?"           +0.3 bits (lookup)     ║
║  └─ TOTAL: ████████████░░░░░░░░ 62%                                         ║
║                                                                              ║
║  CALVER YYYY.MM.DD                                                           ║
║  ├─ Date lookup: "what day is it?"                   0 bits (automatic)     ║
║  ├─ Locked to calendar: can't do 2nd build/day      +1.0 bit (constraint)  ║
║  ├─ Day implies staleness: "27th? it's the 28th!"   +0.5 bits (perception) ║
║  └─ TOTAL: ████████░░░░░░░░░░░░ 50%                                         ║
║                                                                              ║
║  DUAL SCHEME (DD internal, BBB for crates.io)                                ║
║  ├─ Two mental models running parallel              +1.0 bit               ║
║  ├─ "Which scheme for this package?"                 log₂(2) = 1.0 bit     ║
║  ├─ Sync between schemes                            +0.5 bits              ║
║  ├─ Explain to contributors                         +0.7 bits              ║
║  └─ TOTAL: █████████████████░░░ 85%                                         ║
║                                                                              ║
║  UNIFIED YYYY.MM.BBB (current)                                               ║
║  ├─ Increment BBB on release                         0 bits (mechanical)    ║
║  ├─ All packages same scheme                         0 bits (no choice)     ║
║  ├─ Build DNA handles uniqueness                     0 bits (automatic)     ║
║  ├─ Month boundary: reset to 100                    +0.18 bits (rare)      ║
║  └─ TOTAL: ███░░░░░░░░░░░░░░░░░ 18%                                         ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

### Cognitive Load Comparison

| Action | SemVer | CalVer DD | Dual | **Unified** |
|--------|:------:|:---------:|:----:|:-----------:|
| "What version next?" | 🤔🤔🤔 | 📅 | 🤔🤔 | ➕1 |
| "Multiple builds today?" | ✅ | ❌ | ⚠️ | ✅ |
| "Same scheme everywhere?" | ✅ | ✅ | ❌❌ | ✅ |
| "Feels fresh tomorrow?" | ✅ | ❌ | ⚠️ | ✅ |
| "crates.io compatible?" | ✅ | ✅ | ✅ | ✅ |

### Final Score

```
SemVer:        ██████████████████░░░░░░░░░░░░  62%  (judgment overhead)
CalVer DD:     ███████████████░░░░░░░░░░░░░░░  50%  (calendar-locked)
Dual Scheme:   █████████████████████████░░░░░  85%  (complexity explosion)
Unified BBB:   █████░░░░░░░░░░░░░░░░░░░░░░░░░  18%  ← OPTIMAL
```

**Winner: Unified YYYY.MM.BBB** — mechanical increment, zero semantic judgment, one scheme everywhere, automatic freshness via build DNA.

---

## Implementation Details

### Compile-Time Generation (build.rs)

```rust
// luna_core/build.rs generates at compile time:
println!("cargo:rustc-env=LUNA_BUILD_DNA={}", iso8601_timestamp);
println!("cargo:rustc-env=LUNA_VERSION={}.{:02}", year, month);
```

### Runtime Access (version.rs)

```rust
pub const VERSION: &str = env!("CARGO_PKG_VERSION");     // "2025.12.100"
pub const VERSION_OFFICIAL: &str = "2025.12";            // Public-facing
pub const BUILD_DNA: &str = env!("LUNA_BUILD_DNA");      // Nanosecond timestamp
pub const VERSION_BUILD: u32 = 100;                      // Build counter (100-999)
```

### Information Channels

```
User sees:     "Aegis 2026.05"           (clean, fresh)
Cargo uses:    "2025.12.100"              (semver-compatible)
Debug shows:   "2025-12-27T14:32:15.123456789Z"  (exact build moment)
```

---

## FAQ

**Q: Build numbers go 100→999. What if I run out?**

A: The number simply grows: `2025.12.1000`, `2025.12.1001`, etc. Cargo handles this fine.

That said, if you're shipping 900+ releases in a single month (30/day, more than one per waking hour), perhaps the versioning scheme isn't the problem you should be solving. Consider:
- 🔬 Are these releases being tested before shipping?
- 🎯 Is there a clear definition of "done" for each change?
- 🧘 When was your last day off?

The 3-digit `BBB` is a *gentle reminder* that releases should be deliberate, not a hard limit.

---

**Q: Why not just use git commit hashes?**

A: Commit hashes are excellent for *identification* but terrible for *comparison*. You can't look at `a3f7b2c` and `e9d1f4a` and know which is newer. `2025.12.142` > `2025.12.141` — instant, obvious, human-readable.

---

**Q: Why reset to 100 each month instead of continuing from the previous month?**

A: The month boundary reset serves as a natural "fresh start" signal. It also keeps numbers small and memorable. Nobody wants to debug `2025.12.47293`.

---

**Q: What about release candidates and pre-releases?**

A: Use the build DNA. A pre-release is just a build that hasn't been tagged yet. When you're ready to release, bump BBB and tag. The DNA timestamp tells you exactly which build became the release.

---

**Q: How do I know which build is deployed in production?**

A: Check `BUILD_DNA`. Every binary contains its exact birth certificate down to the nanosecond:
```
2025-12-27T14:32:15.123456789Z
```
No ambiguity. No "which 2025.12.103 is this?" — there's only one build with that DNA.

---

**Q: Why nanosecond precision? Why not microseconds or picoseconds?**

A: Nanoseconds hit the hardware sweet spot:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  PRECISION        │  DURATION           │  REALITY                     │
├─────────────────────────────────────────────────────────────────────────┤
│  Millisecond (ms) │  10⁻³ sec           │  Network latency scale       │
│  Microsecond (μs) │  10⁻⁶ sec           │  SSD I/O, context switches   │
│  Nanosecond (ns)  │  10⁻⁹ sec           │  ← CPU clock cycle (~0.3ns)  │
│  Picosecond (ps)  │  10⁻¹² sec          │  Light travels 0.3mm         │
└─────────────────────────────────────────────────────────────────────────┘
```

Modern CPUs run at 3-5 GHz — that's 3-5 billion cycles per second, or **one cycle every ~0.2-0.3 nanoseconds**. The OS timer (`SystemTime`) bottoms out at nanosecond resolution because that's what the hardware can actually measure.

Going finer would just add fake zeros:
- `2025-12-27T14:32:15.123456789Z` ← real precision
- `2025-12-27T14:32:15.123456789000Z` ← picoseconds = fiction

Nanoseconds give us **10⁹ unique timestamps per second** — enough to distinguish builds even if you somehow triggered two in the same clock cycle (you can't).

*"But light travels 0.3mm in a picosecond — couldn't we go finer?"*

Theoretically, yes. A transistor is ~5nm; light crosses it in ~17 attoseconds (10⁻¹⁸ sec). The limit isn't physics — it's that no *current hardware clock* can measure finer than nanoseconds. When quantum timing circuits arrive, we'll happily add more digits. Until then, nanoseconds are the honest limit.

---

**Q: Can two DNA timestamps collide?**

A: The send path is serialized — one operation must complete before the next
begins. Minimum time per send: ~50ms. Clock resolution: 1ms (Android). The
clock advances at least 50 ticks between operations. Under normal conditions,
collision probability is exactly 0.

The only scenario: a device with a dead RTC battery boots to epoch, sends a
message before NTP sync, reboots, repeats. The vulnerable window is seconds.
Probability of two such events producing the same millisecond:

```
P(broken RTC) × P(send before NTP) × P(same ms in ~17min epoch window)
= 10⁻² × 10⁻² × 10⁻⁶
= 10⁻¹⁰ (one in ten billion per message)
```

Worst case if it happens: an adjacent message shows a read tick early. The
two messages share a nanosecond, so they are next to each other in the timeline.
The user almost certainly read both.

**No disambiguator needed.** The `Z` suffix (UTC) eliminates daylight saving
time jumps. The serialized send path eliminates same-session collisions. The
remaining risk is a wrong tick on an adjacent message at one-in-ten-billion
odds on a phone with a dead battery. Twice.

---

**Q: Wait — does this mean my PC is running real nanotech? Is nanotech no longer sci-fi?**

A: Yes. Your CPU has billions of transistors at 5nm scale. Light crosses one in 17 attoseconds. You're running sci-fi hardware every time you compile.

And now it finally has matching software. 🌙

---

## Philosophy

> **Minimum entropy**: The best versioning scheme is the one that requires zero thought.
>
> Every decision point is a potential bug. Every judgment call is cognitive load.
> The unified BBB scheme reduces versioning to a mechanical increment —
> leaving brain cycles for actual engineering.

---

*Document created: 2025.12 | Project Aether — Universe-wide versioning*
