// :core:safety:presence — part of the trust-container Phase 2 split.
//
// The presence layer: who is online/away, last-active, and (in later
// stages) routine location + battery sharing to TRUSTED contacts.
//
// Why a separate Gradle module: "structural boundary 3"
// requires that the EMERGENCY tier — which receives panic alerts only
// — cannot touch presence data. That guarantee is the build graph:
// :feature:emergency will depend on :core:safety:sos but NOT on
// :core:safety:presence, so a developer who adds a presence import to
// the emergency module gets an unresolved-symbol build error, not a
// review comment.
//
// Allowed dependencies: kotlinx (coroutines), androidx.lifecycle
// (ProcessLifecycleOwner drives foreground/online detection). Nothing
// from :app, nothing from :core:safety:sos.
//
// Phase 2 migration is iterative (mirrors :feature:groups). Now living
// here: InAppActivity (online/away heartbeat, zero :app deps) and
// PresenceBroadcaster (status/location fan-out), behind PresenceModuleHost
// for what they still need from :app. The one-way app →
// :core:safety:presence edge is established. Remaining in ProtocolService:
// the raw location-stream wiring, which moves once the transport split
// settles.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.aether.aegis.core.safety.presence"
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
    // ProcessLifecycleOwner + DefaultLifecycleObserver drive the
    // foreground/online heartbeat in InAppActivity.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
