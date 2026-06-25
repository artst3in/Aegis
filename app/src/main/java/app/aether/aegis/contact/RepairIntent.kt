package app.aether.aegis.contact

import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory hand-off between the "Re-pair" UI button on
 * ContactDetailScreen and the SimpleX pairing-completion hook in
 * [app.aether.aegis.simplex.SimpleXTransport.bindContact]. When the
 * user taps Re-pair, the existing contact's peer key is stashed
 * here and the user is sent through the normal Accept-invite flow.
 * When pairing lands and bindContact is about to insert a new
 * known_peers row, it consults this holder — if a target is set,
 * it routes through [app.aether.aegis.data.Repository.repairKnownPeer]
 * instead, merging the new SimpleX identity into the existing row
 * (history preserved, name preserved, trust tier preserved).
 *
 * Process-scoped only — no persistence. If the process dies mid-
 * Re-pair, the user just gets a normal new contact instead, which
 * they can manually merge later. No silent failures.
 *
 * Single-slot: only one Re-pair can be in flight at a time. Set
 * overwrites whatever was there before; consume() reads + clears
 * atomically so the next pairing doesn't accidentally inherit a
 * stale target.
 */
object RepairIntent {

    private val slot = AtomicReference<String?>(null)

    /** Mark the next completed pairing as a Re-pair targeting
     *  [targetPeerKey]. Returns the previous target if one was
     *  pending (caller can decide whether to warn). */
    fun arm(targetPeerKey: String): String? = slot.getAndSet(targetPeerKey)

    /** Read + clear. Returns null when no Re-pair is pending. */
    fun consume(): String? = slot.getAndSet(null)

    /** Non-mutating peek for diagnostics. */
    fun peek(): String? = slot.get()

    /** Drop any pending Re-pair without consuming through bindContact —
     *  used when the user backs out of the Accept flow. */
    fun clear() { slot.set(null) }
}
