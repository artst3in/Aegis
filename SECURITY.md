# Security Policy

## Reporting vulnerabilities

If you find a security vulnerability in Aegis, **do not open a public issue.**

Email: artst3in@gmail.com

Include:
- Description of the vulnerability
- Steps to reproduce
- Impact assessment (what an attacker could do)
- Suggested fix (if you have one)

You will receive a response within 48 hours. Confirmed vulnerabilities will be patched before public disclosure.

## Scope

The following are in scope:
- Encryption implementation (seal/unseal, key derivation, phrase handling)
- PIN bypass or brute-force paths
- SOS system reliability (false negatives — SOS should fire when triggered)
- Duress profile detection (the real profile should be indistinguishable from decoy)
- Remote access authentication
- Data leakage (messages, contacts, metadata visible without authentication)
- SimpleX transport security

The following are out of scope:
- Physical device access with unlocked app (this is documented in SECURITY_STATUS.md)
- Social engineering attacks
- Denial of service against the SimpleX relay infrastructure
- Issues in the Android OS itself

## Current security status

See [SECURITY_STATUS.md](SECURITY_STATUS.md) in the public repository for an honest assessment of what works, what doesn't, and what you should not trust yet.

## Bug bounty

No formal bug bounty program exists. Significant findings will be credited in the changelog unless you request anonymity.
