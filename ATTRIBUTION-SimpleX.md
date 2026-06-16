# SimpleX attribution

Aegis bundles the SimpleX Haskell core (`libsimplex.so`, `libsupport.so`,
`libapp-lib.so`) from [simplex-chat/simplex-chat](https://github.com/simplex-chat/simplex-chat),
along with the minimal JNI binding declarations at
`chat.simplex.common.platform.Core` (the symbol path baked into the .so).

SimpleX Chat is licensed AGPL-3.0-only. Copy of the licence:
https://github.com/simplex-chat/simplex-chat/blob/stable/LICENSE

By distributing this binary to family members, the AGPL source-availability
obligation is satisfied by:
  - This file (link to upstream source)
  - The Aegis repository itself, which contains the integration code

Native libraries were extracted from the official SimpleX Chat Android
release APK (v6.5.1) and are redistributed unchanged. They are NOT
rebuilt from source as part of Aegis's gradle build — that would require
a GHC cross-compile toolchain. To rebuild from source, see
https://github.com/simplex-chat/simplexmq#build.
