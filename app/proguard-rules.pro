# Aegis ProGuard / R8 rules

# SimpleX core — the JNI binding lives at this exact package path
# (the .so resolves Java_chat_simplex_common_platform_CoreKt_* symbols).
# Renaming the package or stripping the symbols breaks the native call.
-keep class chat.simplex.common.platform.** { *; }
-keepclassmembers class chat.simplex.common.platform.** { *; }

# lazysodium uses reflection on libsodium's interfaces; keep them
# resolvable so JNA bindings don't fail at runtime.
-keep class com.goterl.lazysodium.** { *; }
-keep interface com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Room — generated DAOs use reflective hooks
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-dontwarn androidx.room.**

# Coil — async image loading
-dontwarn coil.**
-dontwarn coil3.**

# SQLCipher — native lib loader
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.**

# Glance widget — receivers + serialized state
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver
-keep class * extends androidx.glance.appwidget.action.ActionCallback

# Compose stays
-dontwarn androidx.compose.**

# Aegis-side: every package we ship has reflective hooks somewhere
# (Room entities, Compose previews, kotlinx serialization). Keep the
# class metadata — R8 still shrinks unused methods inside them.
-keep class app.aether.aegis.core.** { *; }
-keep class app.aether.aegis.data.** { *; }
-keep class app.aether.aegis.simplex.** { *; }
-keep class app.aether.aegis.transport.** { *; }
-keep class app.aether.aegis.peer.** { *; }
-keep class app.aether.aegis.profile.** { *; }
-keep class app.aether.aegis.update.** { *; }
-keep class app.aether.aegis.identity.** { *; }
-keep class app.aether.aegis.services.** { *; }
-keep class app.aether.aegis.widget.** { *; }
-keep class app.aether.aegis.admin.** { *; }
-keep class app.aether.aegis.receivers.** { *; }
-keep class app.aether.aegis.groups.** { *; }
-keep class app.aether.aegis.prefs.** { *; }
-keep class app.aether.aegis.lock.** { *; }
-keep class app.aether.aegis.panic.** { *; }
-keep class app.aether.aegis.canary.** { *; }
-keep class app.aether.aegis.geofence.** { *; }
-keep class app.aether.aegis.simswap.** { *; }
-keep class app.aether.aegis.mugshot.** { *; }
-keep class app.aether.aegis.sentinel.** { *; }
-keep class app.aether.aegis.sonar.** { *; }
-keep class app.aether.aegis.remote.** { *; }
-keep class app.aether.aegis.vault.** { *; }
-keep class app.aether.aegis.call.** { *; }
-keep class app.aether.aegis.contact.** { *; }
-keep class app.aether.aegis.i18n.** { *; }
-keep class app.aether.aegis.decoy.** { *; }
-keep class app.aether.aegis.story.** { *; }
-keep class app.aether.aegis.power.** { *; }
-keep class app.aether.aegis.util.** { *; }
-keep class app.aether.aegis.schedule.** { *; }
-keep class app.aether.aegis.backup.** { *; }
-keep class app.aether.aegis.diag.** { *; }

# Kotlin metadata — needed for reflection-using libs above
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions

