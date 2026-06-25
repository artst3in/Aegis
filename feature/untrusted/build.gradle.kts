// :feature:untrusted — part of the trust-container Phase 2 split,
// "structural boundary 1/2".
//
// The lowest trust tier: anonymous, chat-only, disposable. An UNTRUSTED
// contact is a fresh random Aether handle with no revealed identity, no
// presence sharing, no panic eligibility, and a disposable message
// history (48h auto-delete). This module owns that retention policy.
//
// Dependency edge (the whole point of the split): untrusted depends on
// :core:transport ONLY. It has NO edge to :core:safety:sos or
// :core:safety:presence — a developer who adds a panic or presence
// import here gets an unresolved-symbol build error, not a review
// comment. The compiler enforces "an untrusted contact can never reach
// safety machinery."
//
// Phase 2 seed: UntrustedPolicy (the disposable-history TTL), relocated
// out of Repository so the tier that defines the rule also owns the
// number. :app references it as app -> :feature:untrusted (allowed
// direction).

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.aether.aegis.feature.untrusted"
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
    // The floor every tier sits on. Untrusted gets transport and
    // nothing else — no safety layers.
    implementation(project(":core:transport"))
}
