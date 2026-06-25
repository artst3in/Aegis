// :core:transport — part of the trust-container Phase 2 split.
//
// The base layer every tier sits on: SimpleX protocol, crypto,
// database, and the connection plumbing. In the finished graph all
// four feature tiers (trusted / emergency / untrusted / groups)
// depend on this and nothing lower; the two safety layers
// (:core:safety:sos, :core:safety:presence) build on it too.
//
// Allowed: kotlinx (coroutines), android framework, and (in later
// stages) the :simplex-upstream binding. It must NOT depend on any
// :feature:* or :core:safety:* module — transport is the floor.
//
// Phase 2 migration is iterative (mirrors :feature:groups and the
// :core:safety:* modules). Now living here: ConnectionLog +
// InFlightFiles (pure leaves), the data layer (Entities, Models,
// Transport, the :simplex-upstream platform shim Core.kt),
// SimpleXDbKeyStore (Android Keystore + javax.crypto only — a pure
// leaf despite the historical "→ BackupManager" note, which was a KDoc
// reference, not a code edge), and SimpleXCore (the JNI binding to
// :simplex-upstream + the native lib; external-fun declarations compile
// here, the .so loads at runtime from :app's jniLibs).
//
// Still in :app: SimpleXTransport — it integrates with half the app
// (AegisApp / Repository / panic / call / remote / services / groups),
// including a reach UP into :core:safety:sos that would invert the
// layer order, so its extraction needs a host-interface pass + an
// on-device messaging test, not a blind move
// (Phase 2 staged migration → stage 4).
//
// INTERIM NOTE: package `app.aether.aegis.simplex` is split across
// :app and this module during the migration (pure relocation, zero
// call-site churn). It collapses if SimpleXTransport ever moves here.
// No `internal` cross-references between the halves.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.aether.aegis.core.transport"
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
    // Room runtime types only (@Entity / @PrimaryKey / @ColumnInfo) for the
    // entity classes that live here. The @Database + KSP processor stay in
    // :app and reference these entities across the app -> :core:transport
    // edge — the same trick :feature:groups uses for its group entities.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
}
