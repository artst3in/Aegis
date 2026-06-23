# SIGNING — Release & Debug Keystore

**Status:** ACTIVE — keystore generated 2026-06-02 per SPEC_RELEASE_CHANNELS.md.
**Owner:** Artur (custodian), Chad (wiring), Aurora (spec).

---

## Identity

| Field | Value |
|-------|-------|
| Algorithm | RSA |
| Key size | 4096 bits |
| Signature | SHA384withRSA |
| Validity | 100 years (until 2126-05-09) |
| Alias | `aegis` |
| DN | `CN=Aegis, OU=Project Aether, O=Aether Research, L=Antwerp, ST=Antwerpen, C=BE` |
| SHA-1 | `02:C1:D8:0D:D6:6A:0D:2F:F5:92:A6:BB:D7:CD:00:3E:44:18:E3:5E` |
| SHA-256 | `D0:17:93:8B:4E:AD:7B:46:0B:94:A1:74:9D:B8:C0:38:76:CF:A8:13:F3:F3:E5:CC:83:24:45:2F:2D:FB:F5:44` |

The SHA-256 fingerprint above is the canonical identity of any APK
signed by this keystore. Verify a downloaded APK by running:

```
apksigner verify --print-certs aegis-debug.apk
```

The certificate digest in the output must match the SHA-256 above.
If it doesn't, the APK was signed by a different key — do not
install.

---

## Why one keystore

Per docs/SPEC_RELEASE_CHANNELS.md §"Signing":

> One keystore. Both build types signed with the same key. Upload
> this key to Play App Signing so Play Store APKs match GitHub
> APKs. Cross-channel updates work (beta → stable) without
> reinstall. Debug builds also use this key (not Android's default
> debug key) so the developer can update between debug and release
> without uninstall.

The debug variant additionally carries `applicationIdSuffix =
".debug"` so debug + release installs sit side-by-side as
distinct apps — but each variant remains internally update-safe
because the signature is constant.

---

## Where the secret lives

Never in git. Two files held outside the repo:

```
$HOME/.signing/aegis-release.keystore   # 4422 bytes, RSA-4096 store
$HOME/.signing/aegis-release.pass       # 32 chars, ASCII, no trailing newline
```

Both `aegis-release.keystore` and `aegis-release.pass` are
gitignored at the repo root via `.gitignore` (`*.keystore`,
`*.pass`, `/.signing/`). The container the build runs in is
ephemeral, so the keystore must be re-deposited by the developer
at the start of every Aegis-Web session that needs to ship a
signed APK.

For local development on the developer's own machine, the
keystore lives at the same path persistently and there is nothing
to re-deposit.

---

## How the build finds it

`app/build.gradle.kts` reads three values from `local.properties`
(also gitignored):

```properties
aegis.releaseStoreFile=/root/.signing/aegis-release.keystore
aegis.releaseStorePasswordFile=/root/.signing/aegis-release.pass
aegis.releaseKeyAlias=aegis
```

The store password and key password are both read from the same
file (single-line, no trailing newline). The build constructs the
signing config dynamically; if any of the three properties is
missing OR the keystore file at the configured path doesn't
exist, the release signing config is registered as **unsigned**
and `assembleRelease` will produce an unsigned APK that the user
must sign manually before installing.

This degrades gracefully on machines where the keystore hasn't
been deposited yet — the build doesn't fail, it just produces an
unsigned artifact.

---

## Resetting / rotating

Don't, without consulting Artur first. Changing the keystore
breaks cross-update for every installed copy of Aegis:

  - Existing devices fail to install the new APK with
    `INSTALL_FAILED_UPDATE_INCOMPATIBLE` because Android refuses
    to silently accept a re-signed binary.
  - The only recovery is uninstall-then-reinstall, which wipes
    Room DB, identity, vault, and PIN — every user starts from
    scratch.

If a rotation ever DOES have to happen (key compromise), use
Play App Signing's "upload key reset" path documented at
https://developer.android.com/studio/publish/app-signing#reset.
That keeps the app-signing key stable while letting the upload
key change.
