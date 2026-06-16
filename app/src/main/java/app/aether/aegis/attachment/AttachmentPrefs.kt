package app.aether.aegis.attachment

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The four media buckets SimpleX sorts an incoming file into, surfaced to
 * the user as the granularity at which auto-download can be toggled.
 *
 * These map 1:1 onto the core's `msgContent.type` strings via [forMcType].
 * We keep video distinct from image here (the row type collapses both to
 * PHOTO, but for an auto-download policy "auto-pull photos, not videos" is
 * exactly the distinction a user on a data plan wants).
 */
enum class MediaType(val label: String) {
    IMAGE("Images"),
    VIDEO("Videos"),
    VOICE("Voice notes"),
    FILE("Files"),
    ;

    companion object {
        /**
         * Maps the core's `msgContent.type` to a bucket. Anything that
         * isn't an image / video / voice note is a generic FILE (upstream
         * packs PDFs, ZIPs, APKs all into MCFile). Unknown/blank → FILE so
         * an unrecognised type lands in the most conservative bucket.
         */
        fun forMcType(mcType: String): MediaType = when (mcType) {
            "image" -> IMAGE
            "video" -> VIDEO
            "voice" -> VOICE
            else -> FILE
        }
    }
}

/**
 * Persisted policy for AUTO-downloading incoming attachments, plus the
 * onboarding flag that records whether the user has been asked yet.
 *
 * The auto-download decision (evaluated in SimpleXTransport on each file
 * invitation) is the conjunction of four gates — trust, network, type,
 * size — three of which read from here:
 *
 *   wifiOnly      — defer EVERY attachment while on a metered link.
 *   autoTypes     — only these [MediaType]s auto-pull (applies on any link).
 *   maxAutoBytes  — files larger than this never auto-pull (any link);
 *                   [Long.MAX_VALUE] means "no size cap".
 *
 * WHY default [wifiOnly] = true: protect-first. A fresh install, before the
 * tutorial has asked, must not let a hostile contact burn a data plan. The
 * tutorial flips [tutorialAsked] and writes the user's real choice; until
 * then we err safe.
 *
 * StateFlow form so Settings reflects a change live and the gate always
 * reads the current value without re-instantiating.
 */
class AttachmentPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Defer all attachments on a metered connection. Default ON (protect). */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
            _wifiOnlyFlow.value = value
        }

    /** Set once the onboarding tutorial has presented the choice, so we
     *  don't second-guess a user who deliberately turned [wifiOnly] off. */
    var tutorialAsked: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TUTORIAL_ASKED, value).apply()
            _tutorialAskedFlow.value = value
        }

    /** Which media buckets auto-download (when the other gates allow).
     *  Default {IMAGE, VOICE} — the small, expected stuff; videos and
     *  generic files default to tap-to-pull. */
    var autoTypes: Set<MediaType>
        get() = (prefs.getStringSet(KEY_AUTO_TYPES, null)
            ?.mapNotNull { runCatching { MediaType.valueOf(it) }.getOrNull() }
            ?.toSet())
            ?: DEFAULT_AUTO_TYPES
        set(value) {
            prefs.edit().putStringSet(KEY_AUTO_TYPES, value.map { it.name }.toSet()).apply()
            _autoTypesFlow.value = value
        }

    /** Largest file (bytes) that may auto-download. [Long.MAX_VALUE] = no
     *  cap. Default ~25 MB — generous for a photo/voice note, a wall against
     *  someone spamming gigabyte videos even over Wi-Fi. */
    var maxAutoBytes: Long
        get() = prefs.getLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES)
        set(value) {
            prefs.edit().putLong(KEY_MAX_BYTES, value).apply()
            _maxBytesFlow.value = value
        }

    val wifiOnlyFlow: StateFlow<Boolean> get() = _wifiOnlyFlow
    val autoTypesFlow: StateFlow<Set<MediaType>> get() = _autoTypesFlow
    val maxAutoBytesFlow: StateFlow<Long> get() = _maxBytesFlow

    init {
        _wifiOnlyFlow.value = wifiOnly
        _tutorialAskedFlow.value = tutorialAsked
        _autoTypesFlow.value = autoTypes
        _maxBytesFlow.value = maxAutoBytes
    }

    companion object {
        private const val STORE = "aegis_attachment_prefs"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_TUTORIAL_ASKED = "tutorial_asked"
        private const val KEY_AUTO_TYPES = "auto_types"
        private const val KEY_MAX_BYTES = "max_auto_bytes"

        val DEFAULT_AUTO_TYPES: Set<MediaType> = setOf(MediaType.IMAGE, MediaType.VOICE)

        /** 25 MiB. */
        const val DEFAULT_MAX_BYTES: Long = 25L * 1024 * 1024

        /** Sentinel stored when the size slider is dragged to its far end. */
        const val UNLIMITED_BYTES: Long = Long.MAX_VALUE

        // Process-wide so every construction across the UI / transport shares
        // the live flow state (mirrors ChatListPrefs).
        private val _wifiOnlyFlow = MutableStateFlow(true)
        private val _tutorialAskedFlow = MutableStateFlow(false)
        private val _autoTypesFlow = MutableStateFlow(DEFAULT_AUTO_TYPES)
        private val _maxBytesFlow = MutableStateFlow(DEFAULT_MAX_BYTES)
    }
}
