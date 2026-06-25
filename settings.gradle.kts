pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Aegis"
include(":app")
// Group-chat module per docs/SPEC_GROUP_MODULE_ISOLATION.md
// Phase 2. Compile-time boundary against safety code.
include(":feature:groups")
// Trust-container modules per docs/SPEC_TRUST_CONTAINERS.md Phase 2.
// One-way edges: app → these. They never depend back on app, and the
// two safety layers never depend on each other (presence ⊥ sos).
include(":core:safety:presence")
include(":core:safety:sos")
include(":core:transport")
// Tier module (Phase 2 stage 5). The dependency graph IS the trust
// boundary, enforced by the compiler: untrusted → transport.
include(":feature:untrusted")
