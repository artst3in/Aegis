import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Bundled GitHub PAT for the private auto-update channel. Read from
// local.properties (key `aether.github.pat`, legacy `aegis.github.pat`)
// so it's never committed to source. Hoisted to script scope because it
// is now bundled into the RELEASE variant ONLY — release ships to end
// users who can't paste a token, so it must self-update unattended. The
// DEBUG variant deliberately gets an EMPTY token and expects the
// developer to paste one in-app (Settings → Updates), so a debug APK
// never carries the credential.
val bundledPat: String = run {
    val prop = Properties()
    val f = File(rootProject.projectDir, "local.properties")
    if (f.exists()) f.inputStream().use { prop.load(it) }
    (
        prop.getProperty("aether.github.pat")
            ?: prop.getProperty("aegis.github.pat")
            ?: ""
    ).trim()
}

android {
    namespace = "app.aether.aegis"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.aether.aegis"
        resValue("string", "app_name", "Aegis")
        minSdk = 29  // Android 10 — minimum for GrapheneOS
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Project Aether versioning scheme.
        //   Official:  YYYY.MM         (e.g. 2026.05)         — public-facing
        //   Cargo:     YYYY.MM.BBB     (e.g. 2026.05.103)     — full ID; BBB ∈ 100..999
        //   Build DNA: ISO-8601 ns timestamp at compile time  — uniquely identifies one build
        // versionCode is a packed integer YYYYMMBBB so Android's monotonic-int comparison
        // matches the Cargo string ordering.
        //
        // BBB is derived: 100 + (commits in HEAD since the first of the current month, UTC).
        // Auto-bumps on every commit, auto-resets to 100 at the start of each month.
        fun sh(vararg cmd: String): String? = runCatching {
            ProcessBuilder(*cmd)
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        }.getOrNull()
        val aetherYear  = sh("date", "-u", "+%Y")?.toIntOrNull() ?: 2026
        val aetherMonth = sh("date", "-u", "+%-m")?.toIntOrNull() ?: 1
        val monthStart  = "%04d-%02d-01T00:00:00Z".format(aetherYear, aetherMonth)
        val commitsThisMonth =
            sh("git", "rev-list", "--count", "HEAD", "--since=$monthStart")?.toIntOrNull() ?: 0
        // Floor BBB against the last shipped versionCode so a history
        // rewrite (rebase / force-push / commit-date reset) can't drop
        // the build number below something Play Store has already seen
        // — versionCode must be strictly increasing for OTA updates to
        // install. We parse builds/version.json (the committed record
        // of the last build); if its month matches the current month,
        // the new BBB must be at least lastBBB + 1.
        val rawBuild = 100 + commitsThisMonth
        val lastShippedBuild = runCatching {
            val txt = rootProject.file("builds/version.json").readText()
            val vcMatch = Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(txt)?.groupValues?.get(1)
            val vc = vcMatch?.toIntOrNull() ?: return@runCatching 0
            val lastYear  = vc / 100_000
            val lastMonth = (vc / 1_000) % 100
            val lastBuild = vc % 1_000
            if (lastYear == aetherYear && lastMonth == aetherMonth) lastBuild else 0
        }.getOrDefault(0)
        val aetherBuild = maxOf(rawBuild, lastShippedBuild + 1)
        val officialVersion = "%d.%02d".format(aetherYear, aetherMonth)
        val cargoVersion    = "%s.%d".format(officialVersion, aetherBuild)
        versionCode = aetherYear * 100_000 + aetherMonth * 1_000 + aetherBuild
        versionName = cargoVersion

        // Nanosecond-precision UTC ISO-8601 — uses `date(1)` so we don't
        // have to wrestle with java.time imports in the Kotlin DSL.
        val buildDna = runCatching {
            ProcessBuilder("date", "-u", "+%Y-%m-%dT%H:%M:%S.%9NZ")
                .redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("unknown")
        buildConfigField("String", "AETHER_OFFICIAL", "\"$officialVersion\"")
        buildConfigField("String", "AETHER_CARGO",    "\"$cargoVersion\"")
        buildConfigField("String", "BUILD_DNA",       "\"$buildDna\"")

        // Stamp the current git HEAD into BuildConfig so the running app
        // can compare itself to the latest pushed commit when polling for
        // updates. Falls back to "dev" if the build isn't inside a git
        // checkout (CI without history, etc.).
        val gitSha = runCatching {
            ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.length == 40 } ?: "dev"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        // Bundled GitHub PAT — default EMPTY. The release buildType
        // overrides this with the real token (see below); the debug
        // variant keeps it empty and pastes a token in-app. Keeping the
        // field declared here means BuildConfig.BUNDLED_GITHUB_PAT always
        // exists for both variants.
        buildConfigField("String", "BUNDLED_GITHUB_PAT", "\"\"")

        // ABI inclusion is set PER BUILD TYPE below (debug.ndk vs
        // release.ndk), not here, because they differ:
        //   - release ships BOTH arm64-v8a + armeabi-v7a so old / budget
        //     32-bit handsets can run Aegis (universal apk + AAB).
        //   - debug stays arm64-only: the universal debug apk is ~117 MB
        //     (debug shrinks less), over GitHub's 100 MB file limit, and
        //     dev testing is on a modern arm64 phone. An old-phone tester
        //     installs the universal RELEASE apk instead.
        // The abi-split block is disabled — AGP rejects abiFilters and an
        // enabled abi-split at once, and abiFilters gives us the single
        // universal apk the OTA channel wants (one file, any CPU).
    }

    splits {
        abi {
            isEnable = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Compress native libs in the APK rather than storing them uncompressed.
    // Doubles install-time decompression but cuts APK size roughly in half
    // for libsimplex.so (which is the bulk).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Basic vs Full is now a runtime toggle (Settings → Advanced).
    // Was previously two product flavors (green/red); collapsed because
    // the only differences were a feature flag + cosmetic theming, which
    // didn't justify two APKs.

    // Rename APK output. With per-ABI splits each variant produces
    // three APKs: aegis-debug-arm64-v8a.apk, aegis-debug-armeabi-v7a.apk,
    // aegis-debug-universal.apk.
    applicationVariants.all {
        outputs.all {
            val out = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abi = out?.filters
                ?.firstOrNull { it.filterType == "ABI" }?.identifier
            val abiPart = if (abi.isNullOrBlank()) "universal" else abi
            out?.outputFileName = "aegis-${buildType.name}-$abiPart.apk"
        }
    }

    // Signing configs — MUST be declared before buildTypes references them.
    //
    // Debug AND
    // release share a single 4096-bit RSA keystore (the Aegis release
    // key). One signature across both build types means cross-channel
    // updates (beta → stable, debug → release on a wiped device) install
    // without uninstall. The keystore + its password live outside the
    // repo at $HOME/.signing/ — three paths come in via local.properties:
    //
    //   aegis.releaseStoreFile          — keystore path (absolute)
    //   aegis.releaseStorePasswordFile  — file containing the password
    //                                     on a single line, no trailing
    //                                     newline. Read at config time;
    //                                     the password never goes into
    //                                     a properties value.
    //   aegis.releaseKeyAlias           — key alias inside the keystore
    //                                     (default: "aegis")
    //
    // Missing keystore → graceful degradation: the release config is
    // left unsigned and assembleRelease produces an unsigned APK rather
    // than failing the build. The unsigned path is for ephemeral CI
    // containers where the keystore hasn't been deposited yet; signing
    // is done out-of-band before publishing.
    signingConfigs {
        val signingProps = Properties().apply {
            val f = File(rootProject.projectDir, "local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val releaseStorePath = signingProps.getProperty("aegis.releaseStoreFile")?.trim()
        val releasePassPath  = signingProps.getProperty("aegis.releaseStorePasswordFile")?.trim()
        val releaseAlias     = signingProps.getProperty("aegis.releaseKeyAlias", "aegis").trim()
        // Resolve keystore + password lazily so an absent keystore on a
        // dev workstation OR ephemeral CI container doesn't kill config.
        // signingConfigured = true when ALL three pieces line up.
        val releaseKeystoreFile = releaseStorePath?.let { File(it) }?.takeIf { it.exists() }
        val releasePassword = releasePassPath?.let { File(it) }
            ?.takeIf { it.exists() && it.length() in 1..256 }
            ?.readText()
            ?.trimEnd('\n', '\r')
        val signingConfigured = releaseKeystoreFile != null && !releasePassword.isNullOrEmpty()

        create("aegis") {
            if (signingConfigured) {
                storeFile = releaseKeystoreFile
                storePassword = releasePassword
                keyAlias = releaseAlias
                keyPassword = releasePassword
            }
            // else: every field stays null; AGP treats this signing
            // config as "do not sign" and the resulting APK is unsigned.
        }
    }

    buildTypes {
        debug {
            // The debug build:
            //   - carries the debug channel (private dev repo)
            //   - installs side-by-side with release via .debug suffix
            //   - shows experimental UI gated on HAS_DEBUG_CHANNEL
            //   - signed with the SAME keystore as release so the
            //     developer can swap between the two without uninstall
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // arm64-only: a universal debug apk is ~117 MB (over the
            // 100 MB git limit) and dev testing is on a modern phone.
            ndk { abiFilters += "arm64-v8a" }

            buildConfigField("boolean", "HAS_DEBUG_CHANNEL", "true")
            buildConfigField("String",  "UPDATE_REPO",       "\"artst3in/Aegis\"")
            buildConfigField("String",  "RELEASE_CHANNEL",   "\"debug\"")

            // NO minify on debug. The only reason it was ever minified was
            // to squeeze the ~84 MB arm64 apk under GitHub's 100 MB git
            // file limit — but APKs ship as Release ASSETS now (not git
            // blobs), so that limit is gone. Running R8 on the debug build
            // bought nothing and actively hurt: R8's optimisation of the
            // huge ChatScreen composable emitted bytecode the ART verifier
            // rejected (VerifyError, crash on opening any chat). A plain
            // unminified debug build skips R8 entirely and is the most
            // faithful test artifact anyway.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("aegis")
        }
        release {
            // BOTH ABIs — old / budget 32-bit handsets included. Yields a
            // single universal apk (~93 MB) for the OTA channel and a
            // both-ABI AAB for Play (which splits per-device on its end).
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            // The release build:
            //   - carries the stable + beta channels (public Aegis repo)
            //   - has no debug code; HAS_DEBUG_CHANNEL=false at compile
            //     time so the debug source set is excluded from this
            //     variant entirely
            //   - is not debuggable (no ADB attach, no logcat firehose)
            buildConfigField("boolean", "HAS_DEBUG_CHANNEL", "false")
            // The public release channel: the release variant self-updates
            // from the public repo (no auth needed), so installed builds
            // can fetch new versions over the air.
            buildConfigField("String",  "UPDATE_REPO",       "\"artst3in/Aegis\"")
            buildConfigField("String",  "RELEASE_CHANNEL",   "\"release\"")
            // Bundle the token into RELEASE only.
            // End users run release and can't paste a token, so it must
            // self-update from the private repo unattended. Empty when
            // local.properties has no PAT — the build still succeeds and
            // the release simply behaves like debug (manual paste).
            buildConfigField("String",  "BUNDLED_GITHUB_PAT", "\"$bundledPat\"")

            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("aegis")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Distribution channel — the ONLY axis on which the Play build and the
    // sideload build differ. Same applicationId (app.aether.aegis), same
    // code, same signing; what changes is the self-update capability:
    //
    //   sideload (default) — the censorship-resistant OTA channel. Keeps
    //     the silent self-update permission cluster (UPDATE_PACKAGES_
    //     WITHOUT_USER_ACTION / INSTALL_PACKAGES / REQUEST_INSTALL_PACKAGES,
    //     declared in the main manifest) and runs AutoUpdateCheck /
    //     UpdateCheckWorker. This is the assembleSideload* output.
    //
    //   play — for the Play Store. Google RESTRICTS those permissions
    //     (Play does the updating), so src/play/AndroidManifest.xml strips
    //     all three with tools:node="remove", and SELF_UPDATE=false makes
    //     the in-app updater dormant. This is the bundlePlayRelease (AAB)
    //     output.
    //
    // SELF_UPDATE is the single compile-time switch the updater reads, so
    // there's no Play build that ships a self-updater it isn't allowed to
    // run.
    flavorDimensions += "channel"
    productFlavors {
        create("sideload") {
            dimension = "channel"
            isDefault = true
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "channel"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // Robolectric needs the merged Android resources + default returns for
    // un-shadowed framework calls. Scoped to unit tests only.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // ProcessLifecycleOwner — drives InAppActivity so the "online"
    // green dot reflects in-app activity, not just process alive.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Room (encrypted message DB)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // SQLCipher (encryption-at-rest for Room)
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // OpenStreetMap tiles
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Coil — image loading for chat attachment thumbnails
    implementation("io.coil-kt:coil-compose:2.7.0")

    // EXIF read/write — used by MugshotCapture to burn GPS + timestamp
    // into the JPEG's EXIF.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // WebView + WebRTC call surface (loads simplex-chat's call.html assets)
    implementation("androidx.webkit:webkit:1.12.1")

    // Home-screen widget (Glance / AppWidget on Compose)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // WorkManager for scheduled update checks
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // QR codes (display + scan) for pubkey pairing
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // NaCl crypto (X25519 + ChaCha20-Poly1305) for LAN transport
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // BiometricPrompt — fingerprint + face unlock for app-lock screen
    implementation("androidx.biometric:biometric:1.1.0")

    // BIP39 recovery phrase — the 24-word
    // phrase is the master key that roots SealCrypto, so this path must
    // never be hand-rolled: an official,
    // audited library only. cash.z.ecc.android / Electric Coin Company
    // (Zcash). Self-contained — bundles the English wordlist + its own
    // PBKDF2-HMAC-SHA512, depends on nothing but kotlin-stdlib, so it
    // adds ~65 KB and zero transitive weight to the ~63 MB budget. The
    // root coordinate resolves to the -jvm variant via Gradle module
    // metadata. Verified against the official Trezor vectors in
    // RecoveryPhraseTest.
    implementation("cash.z.ecc.android:kotlin-bip39:1.0.9")

    // AppCompat — only needed for AppCompatDelegate.setApplicationLocales,
    // which backports per-app locale switching (Android 13+ API) to our
    // minSdk 29 range. We don't extend AppCompatActivity anywhere; the
    // locale override is applied at the Application level.
    implementation("androidx.appcompat:appcompat:1.7.0")

    // CameraX — silent front-camera capture for the mugshot-on-failed-PIN feature
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    // Group-chat module — group module isolation,
    // Phase 2. The dependency arrow points app → :feature:groups
    // (one-way). Groups module classes are visible to app code;
    // app safety code is NOT visible to groups (the absence of a
    // reverse dependency edge is what enforces the boundary).
    implementation(project(":feature:groups"))

    // Trust-container modules — Phase 2.
    // One-way arrow app → module. The presence layer (online/away,
    // and later routine location) lives here so the EMERGENCY tier can
    // be wired WITHOUT it (structural boundary 3: emergency ⊥ presence).
    implementation(project(":core:safety:presence"))
    // Panic layer (siren, alert dedup; broadcast path in a later
    // stage). MUST NOT depend on :core:safety:presence — boundary 3.
    implementation(project(":core:safety:sos"))
    // Transport base layer: connection log, in-flight files, the data
    // layer (entities/models), SimpleXDbKeyStore, and the SimpleXCore JNI
    // binding all live here. Only SimpleXTransport itself remains in :app
    // (deep app integration + a panic-layer reach; see :core:transport).
    implementation(project(":core:transport"))

    // Tier module — trust container, Phase 2 stage 5. app → tier
    // (one-way). The tier's own dependency edges (declared in its
    // build.gradle.kts) are the compile-time trust boundary: untrusted
    // sees only transport.
    implementation(project(":feature:untrusted"))

    // ---- Local unit tests (JVM, no device) --------------------------
    // First unit-test source set in the app module
    // (Stage 1). Drives the BIP39 known-answer tests against the official
    // Trezor vectors so a future dependency bump that silently changes
    // mnemonic/seed output fails CI instead of corrupting users' keys.
    // RecoveryPhrase has no Android dependencies, so these run on the
    // plain JVM via `./gradlew :app:testDebugUnitTest` — no Robolectric.
    testImplementation("junit:junit:4.13.2")
    // Robolectric tier — runs Android-framework-dependent logic
    // (SharedPreferences-backed stores, Base64, Context) on the host JVM
    // so security gates like RemoteAccessGate / LockStore are testable
    // without an emulator. ApplicationProvider comes from androidx.test.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    // Instrumented (on-device) tier — only place the real SQLCipher native
    // engine loads, so the DbRebuild export→wipe→restore round-trip can be
    // proven against a real encrypted DB. Run with a device/emulator:
    //   ./gradlew :app:connectedSideloadDebugAndroidTest
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
}

/**
 * Update-channel manifest. Emitted alongside the APK so the auto-update
 * worker has a canonical "what version is on this channel" source it
 * can read with a 200-byte HTTP GET instead of a 90 MB APK download +
 * blob-SHA round-trip (which was fragile — Android post-install
 * recompression broke the comparison).
 *
 * Captured into a project-extra at configuration time so the task can
 * read the values without re-evaluating android {} closures.
 */
tasks.register("writeVersionJson") {
    doLast {
        val versionCode = android.defaultConfig.versionCode ?: 0
        // Stamp the variant suffix into the shipped versionName so the
        // running app + the GitHub-side version.json agree on what's
        // installed. The base versionName from defaultConfig doesn't
        // include applicationIdSuffix/versionNameSuffix — those are
        // applied per-buildType. The writeVersionJson task is currently
        // wired only to assembleDebug, so we append "-debug" directly.
        // When release builds start shipping their own version.json
        // (to artst3in/Aegis), a sibling writeReleaseVersionJson can
        // omit the suffix.
        val baseVersionName = android.defaultConfig.versionName ?: ""
        val versionName = baseVersionName + "-debug"
        val applicationId = "app.aether.aegis.debug"
        val channel = "debug"
        val gitSha = runCatching {
            ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.length == 40 } ?: "dev"
        val buildDna = runCatching {
            ProcessBuilder("date", "-u", "+%Y-%m-%dT%H:%M:%S.%9NZ")
                .redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("unknown")
        val out = File(rootProject.projectDir, "builds/version.json")
        out.parentFile.mkdirs()
        out.writeText(
            """{"versionCode":$versionCode,"versionName":"$versionName","applicationId":"$applicationId","channel":"$channel","gitSha":"$gitSha","buildDna":"$buildDna"}
"""
        )
    }
}

/**
 * Same shape as writeVersionJson but for the release variant —
 * writes builds/release-version.json so the running release app
 * can poll for updates and the developer can verify what's
 * sitting in builds/aegis-release.apk against the committed
 * manifest. Stamped without the "-debug" versionName suffix.
 */
tasks.register("writeReleaseVersionJson") {
    doLast {
        val versionCode = android.defaultConfig.versionCode ?: 0
        val versionName = android.defaultConfig.versionName ?: ""
        val applicationId = "app.aether.aegis"
        val channel = "release"
        val gitSha = runCatching {
            ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.length == 40 } ?: "dev"
        val buildDna = runCatching {
            ProcessBuilder("date", "-u", "+%Y-%m-%dT%H:%M:%S.%9NZ")
                .redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("unknown")
        val out = File(rootProject.projectDir, "builds/release-version.json")
        out.parentFile.mkdirs()
        out.writeText(
            """{"versionCode":$versionCode,"versionName":"$versionName","applicationId":"$applicationId","channel":"$channel","gitSha":"$gitSha","buildDna":"$buildDna"}
"""
        )
    }
}

afterEvaluate {
    // The OTA version records belong to the SIDELOAD channel (that's the
    // one that self-updates by polling these files). The Play AAB doesn't
    // poll anything, so it writes no version.json.
    tasks.named("assembleSideloadDebug").configure { finalizedBy("writeVersionJson") }
    tasks.named("assembleSideloadRelease").configure { finalizedBy("writeReleaseVersionJson") }
}
