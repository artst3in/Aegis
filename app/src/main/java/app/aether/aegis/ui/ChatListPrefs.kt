package app.aether.aegis.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Visual density / layout mode for the Contacts tab. Orthogonal to
 *  sort + grouping — pick the order independently of the shape. */
enum class ChatViewMode(val label: String) {
    /** Default: avatar + name + last-message preview + status. */
    NORMAL("Normal"),
    /** Same row, no preview text. Triples contacts-per-screen for
     *  users with many contacts. */
    COMPACT("Compact"),
    /** Badoo-style grid of portrait tiles — big square avatar + name
     *  underneath. Optimised for visual scan. */
    GRID("Grid"),
    ;
}

/** Sort modes for the Contacts tab. The "natural direction" for each
 *  is what most users intuitively want when first choosing the mode;
 *  the [ChatListPrefs.ascending] flag reverses it. */
enum class ChatSortMode(val label: String) {
    /** Last message timestamp. Natural: newest first (the WhatsApp default). */
    TIME("Last message"),
    /** displayName, locale-insensitive. Natural: A → Z. */
    NAME("Name"),
    /** Trust tier the OWNER assigned to the peer (EMERGENCY > TRUSTED >
     *  UNTRUSTED). Natural: highest-trust first. */
    TRUST("Trust tier"),
    /** Shield tier the PEER has achieved (Cyan > Gold > Silver > Bronze >
     *  None). Natural: best shield first. */
    TIER_ACHIEVED("Security tier"),
    /** Verified peers (safety code matched) before unverified.
     *  Natural: verified first. */
    VERIFIED("Verified first"),
    ;
}

/**
 * Persisted chat-list sort + grouping preferences. Three orthogonal
 * knobs:
 *
 *   sortMode          — which field decides the order
 *   ascending         — reverses the natural direction of [sortMode]
 *   groupByTrust      — render TrustTier section headers between
 *                       Emergency / Trusted / Untrusted groups;
 *                       [sortMode] applies WITHIN each group
 *
 * StateFlow form so the Compose list re-sorts the instant the user
 * picks a new mode from the sheet — no screen rebuild.
 */
class ChatListPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    var sortMode: ChatSortMode
        get() = ChatSortMode.values().getOrElse(
            prefs.getInt(KEY_SORT, ChatSortMode.TIME.ordinal),
        ) { ChatSortMode.TIME }
        set(value) {
            prefs.edit().putInt(KEY_SORT, value.ordinal).apply()
            _sortFlow.value = value
        }

    var ascending: Boolean
        get() = prefs.getBoolean(KEY_ASC, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ASC, value).apply()
            _ascFlow.value = value
        }

    var groupByTrust: Boolean
        get() = prefs.getBoolean(KEY_GROUP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GROUP, value).apply()
            _groupFlow.value = value
        }

    var viewMode: ChatViewMode
        get() = ChatViewMode.values().getOrElse(
            prefs.getInt(KEY_VIEW, ChatViewMode.NORMAL.ordinal),
        ) { ChatViewMode.NORMAL }
        set(value) {
            prefs.edit().putInt(KEY_VIEW, value.ordinal).apply()
            _viewFlow.value = value
        }

    /**
     * Whether the stories strip at the top of the chat list is
     * collapsed (just a tiny header bar visible) or expanded (full
     * horizontal LazyRow of hex tiles). Persists so a user who
     * doesn't use stories doesn't have to re-collapse on every
     * launch. Default OFF (expanded) so a fresh install sees
     * stories exist; users who never touch them flip to ON once
     * and stay collapsed.
     */
    var storiesCollapsed: Boolean
        get() = prefs.getBoolean(KEY_STORIES_COLLAPSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_STORIES_COLLAPSED, value).apply()
            _storiesCollapsedFlow.value = value
        }

    val sortFlow: StateFlow<ChatSortMode> get() = _sortFlow
    val ascendingFlow: StateFlow<Boolean> get() = _ascFlow
    val groupByTrustFlow: StateFlow<Boolean> get() = _groupFlow
    val viewModeFlow: StateFlow<ChatViewMode> get() = _viewFlow
    val storiesCollapsedFlow: StateFlow<Boolean> get() = _storiesCollapsedFlow

    init {
        // Hydrate flows from stored values on first construction.
        _sortFlow.value = sortMode
        _ascFlow.value = ascending
        _groupFlow.value = groupByTrust
        _viewFlow.value = viewMode
        _storiesCollapsedFlow.value = storiesCollapsed
    }

    companion object {
        private const val STORE = "aegis_chat_list"
        private const val KEY_SORT = "sort_mode"
        private const val KEY_ASC = "ascending"
        private const val KEY_GROUP = "group_by_trust"
        private const val KEY_VIEW = "view_mode"
        private const val KEY_STORIES_COLLAPSED = "stories_collapsed"

        // Process-wide so multiple ChatListPrefs constructions across
        // the UI hierarchy share the live flow state.
        private val _sortFlow = MutableStateFlow(ChatSortMode.TIME)
        private val _ascFlow = MutableStateFlow(false)
        private val _groupFlow = MutableStateFlow(false)
        private val _viewFlow = MutableStateFlow(ChatViewMode.NORMAL)
        private val _storiesCollapsedFlow = MutableStateFlow(false)
    }
}
