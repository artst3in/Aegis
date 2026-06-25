package app.aether.aegis.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * Process-wide singleton libsodium binding.
 *
 * WHY THIS EXISTS — a hard native crash fix. Every `SodiumAndroid()`
 * construction runs JNA's `Native.register`, which pins a JNI **global
 * reference** for every native method in the binding and never releases
 * it. ART caps the JNI global-reference table at 51200 entries. Crypto
 * holders across the app (LockStore, VaultCrypto, PeerCrypto, …) each
 * built their OWN `LazySodiumAndroid(SodiumAndroid())`, and several are
 * freely-constructed factories — e.g. `LockStore(context)` is rebuilt on
 * demand, and `ShieldTierEngine.currentTier` constructs one on every
 * Settings recomposition. Each construction leaked hundreds–thousands of
 * Method global refs, so the table climbed until it overflowed and the
 * runtime aborted the process (observed in the field: 50704
 * `java.lang.reflect.Method` global refs, SIGABRT in
 * `com.sun.jna.Native.register` ← `SodiumAndroid.<init>` ←
 * `LockStore.<init>`).
 *
 * The binding is a STATELESS wrapper over the native library — it holds
 * no per-call or per-profile state — so one shared instance is correct
 * and safe to use from any thread. Every crypto holder must reuse
 * [shared] instead of constructing its own; constructing a new
 * `SodiumAndroid()` anywhere reintroduces the leak.
 */
object Sodium {
    /** The one and only libsodium binding for the whole process. Built
     *  lazily on first crypto use so app startup doesn't pay for the
     *  native registration until something actually needs it. */
    val shared: LazySodiumAndroid by lazy { LazySodiumAndroid(SodiumAndroid()) }
}
