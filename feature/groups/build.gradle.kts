// Group module — the group-module isolation work, Phase 2.
//
// This module owns all multi-recipient code (groups, group rendering,
// group system messages, the on/off gate, the auto-disable timers).
// It exists as a separate Gradle module so the BOUNDARY against
// safety code (panic, location, presence, mugshot, canary, sonar) is
// enforced by the build configuration itself — not by code review,
// not by convention.
//
// What this module IS allowed to depend on:
//   - kotlinx (stdlib, coroutines, serialization)
//   - androidx.core, lifecycle (no app-package symbols)
//   - WorkManager (auto-disable timers)
//   - SharedPreferences (gate state)
//
// What this module is FORBIDDEN to depend on (by absence of the
// imports, enforced by the lack of a Gradle dependency edge from
// here to the app module):
//   - app.aether.aegis.panic.*
//   - app.aether.aegis.presence.*
//   - app.aether.aegis.location.*
//   - app.aether.aegis.canary.*
//   - app.aether.aegis.sentinel.*
//   - app.aether.aegis.sonar.*
//   - app.aether.aegis.mugshot.*
//   - The full Repository class (only the narrow Group* surface)
//   - Any safety-tier or trust-tier symbol
//
// If a developer adds an import from one of the forbidden packages
// into a file under feature/groups/, the build fails because that
// symbol cannot be resolved from this module's classpath. That's
// the compile-time guarantee we want.
//
// Phase 2 migration is iterative. This commit moves the genuinely-
// pure files (no app dependencies); subsequent commits will move
// more as the cross-module API surface (GroupModuleHost interface)
// gets defined and implemented in the app module.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.aether.aegis.groups"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Room runtime only — the @Database (and the KSP compiler)
    // live in :app, but group entities + their DAO carry Room
    // annotations and need the runtime types (Entity, PrimaryKey,
    // Query, Insert). The compiler in :app resolves these via
    // the dependency edge once :app references the entities in
    // its @Database `entities` array.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // Compose — group module's UI surfaces (the disabled-state
    // card today; chat / members screens in subsequent
    // commits) need it.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
