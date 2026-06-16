// :core:safety:sos — part of the trust-container Phase 2 split.
//
// The SOS layer: siren, SOS-alert dedup/state, and the GPS/audio/camera
// broadcast path. Trusted + Emergency tiers depend on this; Untrusted and
// Groups must NOT — and crucially this module must NEVER depend on
// :core:safety:presence ("structural boundary 3": sos ⊥ presence, so the
// Emergency tier can be wired with SOS but without any presence/location
// code).
//
// Allowed: kotlinx (coroutines), Compose runtime (snapshot state for
// SOSAlertStore — runtime artifact only, no compiler plugin), android
// framework (Context, AudioManager, ToneGenerator, MediaPlayer).
// Nothing from :app, nothing from :core:safety:presence.
//
// Moved so far:
//   - SirenManager     (siren tone generator; zero :app deps)
//   - SOSAlertStore    (receiver-side SOS/coord state; Compose
//                       snapshot state only, zero :app deps)
//   - SOSAudioPlayer   (auto-plays inbound SOS audio; its lone
//                       AegisApp.applicationContext reach is inverted
//                       through SOSModuleHost)
//   - SOSEvidenceLog   (victim-only evidence trail; appContext +
//                       own-victim gate both routed through the host)
//   - SOSCoordinator   (the SOSModuleHost interface — the GroupModuleHost
//                       pattern — carries selfKey, sendStatus,
//                       sosTargetKeys, displayNameFor, isAegis,
//                       victimLocation, etc. so it reaches transport/
//                       Repository/mugshot through the host, not a :app
//                       edge).
// Still in :app: SOSSnapshotStream (CameraX + a direct SimpleXTransport
// reach — moves once transport is extracted).

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.aether.aegis.core.safety.sos"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // ContextCompat.checkSelfPermission in SOSCoordinator.SelfLocation
    // (last-known-fix reader for responder distance pings). Same version
    // as :app.
    implementation("androidx.core:core-ktx:1.15.0")
    // Compose RUNTIME only (no compiler plugin / buildFeatures.compose):
    // SOSAlertStore holds its receiver-side SOS state in snapshot state
    // (mutableStateOf / mutableStateMapOf / SnapshotStateMap) so the :app
    // dashboards recompose when a roster / coord digest lands. Those are
    // plain library calls — there are no @Composable functions in this
    // module — so we need the runtime artifact but NOT the Compose
    // compiler. Same BOM as :app / :feature:groups.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.runtime:runtime")
}
