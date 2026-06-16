package app.aether.aegis.ui

import app.aether.aegis.admin.ShieldTier
import app.aether.aegis.core.FamilyMember
import app.aether.aegis.data.KnownPeerEntity
import app.aether.aegis.core.Message
import app.aether.aegis.data.TrustTier

/**
 * Pure sorting / grouping helper for the Contacts tab. Takes the raw
 * peer list + lookup maps and returns a flat list of [ChatListRow] —
 * either a contact row or a section header (when grouping is on).
 *
 * Kept here as a free function (not in ChatListScreen.kt) so the
 * sort logic is unit-testable in isolation and doesn't pull a Compose
 * recomposition just to re-sort.
 */
sealed class ChatListRow {
    /** Section header inserted by groupByTrust mode. */
    data class GroupHeader(val tier: TrustTier, val count: Int) : ChatListRow()
    /** Actual contact row — peer's publicKey is the stable key. */
    data class Contact(val member: FamilyMember) : ChatListRow()
}

/** Apply sort + group settings to a member list. Returns a flat
 *  `ChatListRow` sequence ready for LazyColumn items(). */
fun buildSortedChatList(
    members: List<FamilyMember>,
    peers: List<KnownPeerEntity>,
    lastMsgByPeer: Map<String, Message>,
    selfKey: String,
    sortMode: ChatSortMode,
    ascending: Boolean,
    groupByTrust: Boolean,
): List<ChatListRow> {
    val peerByKey = peers.associateBy { it.publicKey }

    // Sort comparator: each mode produces a key the sort can use.
    // The `ascending` flag flips the resulting order; the comparator
    // here always returns the NATURAL order for the mode.
    val cmp = Comparator<FamilyMember> { a, b ->
        // Self stays pinned to the top regardless of sort — it's the
        // note-to-self row and the user expects it always reachable.
        when {
            a.publicKey == selfKey && b.publicKey != selfKey -> return@Comparator -1
            b.publicKey == selfKey && a.publicKey != selfKey -> return@Comparator 1
        }
        when (sortMode) {
            ChatSortMode.TIME -> {
                val ta = lastMsgByPeer[a.publicKey]?.timestamp ?: 0L
                val tb = lastMsgByPeer[b.publicKey]?.timestamp ?: 0L
                // Natural: newest first (descending timestamp).
                tb.compareTo(ta)
            }
            ChatSortMode.NAME -> {
                a.name.compareTo(b.name, ignoreCase = true)
            }
            ChatSortMode.TRUST -> {
                val ra = trustRank(peerByKey[a.publicKey])
                val rb = trustRank(peerByKey[b.publicKey])
                // Natural: highest-trust first (descending rank).
                rb.compareTo(ra).orElseBy { a.name.compareTo(b.name, true) }
            }
            ChatSortMode.TIER_ACHIEVED -> {
                val ra = shieldRank(peerByKey[a.publicKey])
                val rb = shieldRank(peerByKey[b.publicKey])
                // Natural: best shield first.
                rb.compareTo(ra).orElseBy { a.name.compareTo(b.name, true) }
            }
            ChatSortMode.VERIFIED -> {
                val va = peerByKey[a.publicKey]?.verified == true
                val vb = peerByKey[b.publicKey]?.verified == true
                // Natural: verified first.
                vb.compareTo(va).orElseBy { a.name.compareTo(b.name, true) }
            }
        }
    }
    val sorted = members.sortedWith(if (ascending) cmp.reversed() else cmp)

    if (!groupByTrust) return sorted.map { ChatListRow.Contact(it) }

    // Group by TrustTier with the natural Trusted → Emergency →
    // Untrusted order. Trusted is the inner circle (routine data + sos),
    // Emergency the wider sos-only net, Untrusted the outside — so
    // "best trust first" leads with Trusted, matching the direction
    // label ("Trusted first" / "Untrusted first") and TrustTier's own
    // hierarchy. Self stays in its assigned tier (typically none, so it
    // falls into UNTRUSTED unless the owner has set it).
    val groupOrder = listOf(TrustTier.TRUSTED, TrustTier.EMERGENCY, TrustTier.UNTRUSTED)
    val groups = sorted.groupBy { m ->
        peerByKey[m.publicKey]?.trustTier
            ?.let { runCatching { TrustTier.valueOf(it) }.getOrNull() }
            ?: TrustTier.UNTRUSTED
    }
    val orderedGroups = if (ascending) groupOrder.reversed() else groupOrder
    return orderedGroups.flatMap { tier ->
        val list = groups[tier].orEmpty()
        if (list.isEmpty()) emptyList()
        else listOf(ChatListRow.GroupHeader(tier, list.size)) +
            list.map { ChatListRow.Contact(it) }
    }
}

/** TrustTier rank — higher = more trusted / closer to user.
 *  TRUSTED > EMERGENCY > UNTRUSTED. Trusted is the inner circle
 *  (routine data + sos); Emergency the wider sos-only net; Untrusted
 *  the outside. This matches TrustTier's documented hierarchy and the
 *  direction label, so "best trust first" actually leads with Trusted. */
private fun trustRank(peer: KnownPeerEntity?): Int {
    val tier = peer?.trustTier?.let { runCatching { TrustTier.valueOf(it) }.getOrNull() }
        ?: TrustTier.UNTRUSTED
    return when (tier) {
        TrustTier.TRUSTED   -> 2
        TrustTier.EMERGENCY -> 1
        TrustTier.UNTRUSTED -> 0
    }
}

/** ShieldTier rank — higher = better shield earned.
 *  Cyan > Gold > Silver > Bronze > None. */
private fun shieldRank(peer: KnownPeerEntity?): Int {
    val tier = peer?.peerReportedTier
        ?.let { runCatching { ShieldTier.valueOf(it) }.getOrNull() }
        ?: ShieldTier.None
    return tier.ordinal  // ordinal already maps None=0..Cyan=4
}

/** Comparator chaining helper. `a.compareTo(b).orElseBy { secondary }`
 *  reads more cleanly than nested `thenBy { ... }` chains. */
private inline fun Int.orElseBy(fallback: () -> Int): Int =
    if (this != 0) this else fallback()
