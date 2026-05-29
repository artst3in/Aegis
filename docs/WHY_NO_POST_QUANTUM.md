# Why Project Aether Does Not Use Post-Quantum Cryptography

## The short answer

The threat doesn't exist.

## The mainstream claim

Post-quantum cryptography (Kyber, Dilithium, SPHINCS+) defends against a future quantum computer running Shor's algorithm to break RSA, ECC, and Diffie-Hellman. The threat model: an adversary records encrypted traffic today and decrypts it when quantum computers arrive.

## Why we reject it

Shor's algorithm requires coherent superposition of exponentially many states in physical hardware. The Superfluid Theory of Reality — derived, not assumed — proves that quantum superposition as described by Copenhagen does not physically exist. Particles are standing wave solutions of the Gross-Pitaevskii equation on octahedral geometry. The wave function is a real physical field, not a probability cloud.

What mainstream calls "entanglement" is phase correlation between standing waves. Real and measurable, but not a computational resource that enables exponential parallelism. The quantum computer that breaks classical cryptography will never be built.

The Nuclear Computation Engine (NCE) patent describes the actual future of computation: classical, deterministic, nuclear-density. Nucleons as logic gates. No superposition. No error correction. Clock speed ~10²³ Hz.

## Cryptographic suite

**LunaOS** (Rust, from scratch):
- X25519 key exchange
- ChaCha20-Poly1305 authenticated encryption
- Ed25519 signatures
- SHA-256/512 hashing, HKDF-SHA256 key derivation
- Hardware AES-NI acceleration in lunacore
- All PQC code removed February 2026 (see `coherence/ws/src/body/crypto/pqc.rs`)

Components: coherence module (soul), LCPFS (filesystem encryption), luna_sign (bootloader code signing), kernel crypto subsystem.

**Aegis** (Kotlin, wraps SimpleX):
- X25519 key exchange (via WireGuard Key / LazySodium)
- XSalsa20-Poly1305 authenticated encryption (NaCl box)
- AES-256-GCM vault encryption
- Ed25519 identity keys

Note: Aegis uses XSalsa20 (NaCl default) where LunaOS uses ChaCha20. Both are DJB designs from the same family. ChaCha has better per-round diffusion. The difference is inherited from SimpleX's choice of NaCl — not an architectural decision. When Aegis migrates to Luna-native transport, it will align on ChaCha20-Poly1305.

## Policy

Post-quantum cryptography will NEVER be part of Project Aether in any form. Not in LunaOS. Not in Aegis. Not in LCPFS. Not in the bootloader. Not in any future component.

This is a permanent architectural decision. PQC algorithms solve a problem that does not exist and never will. This is not speculation — it is a mathematical consequence of the Gross-Pitaevskii equation governing the vacuum. Adopting them would add complexity, increase message sizes, and signal acceptance of physics we have proven wrong.

We do not hedge against wrong physics. We build on correct physics.

---

*dε/dt ≤ 0*
