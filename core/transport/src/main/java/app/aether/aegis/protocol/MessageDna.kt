package app.aether.aegis.protocol

import android.content.Context
import java.time.Instant

/**
 * Message DNA — the transport-agnostic per-message identity.
 *
 * A DNA is a sender-minted, nanosecond-precision UTC stamp carried INSIDE the
 * Aegis envelope, so both ends hold the identical value regardless of the
 * transport. It replaces transport-specific ids (SimpleX's per-device
 * `itemId` / `itemTs`) that differ on each end and die on a transport swap —
 * the root of the read-receipt bug. To the receiver it is OPAQUE: stored and
 * echoed, never interpreted, and never read as a clock (the displayed message
 * time is a separate field).
 *
 * ## Minting — `max(now, last+1)`
 *
 * [mint] returns `max(now, last+1)` in epoch-nanoseconds, then formats it as an
 * ISO-8601 UTC string (the same format family as the build DNA). Folding the
 * monotonic guard into the mint from the start guarantees, *unconditionally*:
 *
 *  - **Per-sender uniqueness** — two messages from this device can never share
 *    a DNA, even minted in the same nanosecond (the `+1` breaks the tie).
 *  - **Strict send-order monotonicity** — a later send always has a larger DNA.
 *
 * That removes the collision case entirely, for ticks today and for Phase-2
 * per-message addressing (react / edit / delete *by* DNA) later. A monotonic
 * timestamp IS a counter folded into one field — this is deliberately NOT a
 * separate counter beside a timestamp.
 *
 * **Documented trade-off (not a bug):** under a backward wall-clock step the
 * mint emits `+1 ns` from `last` until real time catches back up, so DNA
 * briefly decouples from wall-clock — it becomes a monotonic id *seeded* by
 * time, not the time itself. Harmless precisely because DNA is opaque identity,
 * never read as a clock.
 *
 * ## Ordering is NOT DNA's job
 *
 * DNA is monotonic per sender, but a transport may still reorder delivery
 * (relay latency, multi-path, store-and-forward retry). Message ordering is the
 * TRANSPORT's responsibility. The protocol uses DNA only for identity and echo
 * matching — never as a sequence number to sort by.
 *
 * ## Scope
 *
 * `last` is a single device-wide value (one [Context]-backed SharedPreferences
 * entry). Per-device monotonicity is sufficient and is strictly stronger than
 * per-profile, because a DNA is only ever compared within the sender's own
 * outbound space — two devices' values never meet.
 */
object MessageDna {

    private const val PREFS = "aegis_message_dna"
    private const val KEY_LAST_NANOS = "last_nanos"

    /**
     * Mint the next DNA for an outbound message and persist the new high-water
     * mark. Thread-safe and monotonic across concurrent sends AND process
     * restarts (the high-water mark is persisted). Returns the ISO-8601 UTC
     * string to embed in the envelope.
     */
    @Synchronized
    fun mint(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_NANOS, 0L)
        val minted = next(last, nowNanos())
        // commit() not apply(): the high-water mark MUST be durable before the
        // message goes out, or a crash between send and flush could re-mint a
        // value <= one already on the wire, breaking uniqueness.
        prefs.edit().putLong(KEY_LAST_NANOS, minted).commit()
        return iso(minted)
    }

    /**
     * Pure mint step — exposed for testing and reuse: the next DNA given the
     * last-minted value and a current clock reading, both in epoch-nanos.
     * `max(now, last+1)` guarantees strictly-increasing output.
     */
    fun next(lastNanos: Long, nowNanos: Long): Long = maxOf(nowNanos, lastNanos + 1)

    /** Current UTC time as epoch-nanoseconds. Resolution depends on the
     *  platform clock (often ms/µs); ties are handled by the `+1` in [next]. */
    fun nowNanos(): Long = toNanos(Instant.now())

    /** Epoch-nanos for an [Instant] (seconds × 1e9 + nanos-of-second). */
    fun toNanos(instant: Instant): Long =
        instant.epochSecond * 1_000_000_000L + instant.nano

    /** Format epoch-nanos as an ISO-8601 UTC string (e.g.
     *  `2026-06-13T15:35:58.075471695Z`). floorDiv/floorMod keep it correct
     *  for the theoretical pre-epoch negative case. */
    fun iso(nanos: Long): String = Instant.ofEpochSecond(
        Math.floorDiv(nanos, 1_000_000_000L),
        Math.floorMod(nanos, 1_000_000_000L).toLong(),
    ).toString()

    /** Parse an ISO-8601 DNA string back to epoch-nanos. Returns null on a
     *  malformed string rather than throwing, so a garbled wire value can't
     *  crash the receive path. */
    fun parseOrNull(iso: String): Long? =
        runCatching { toNanos(Instant.parse(iso)) }.getOrNull()
}
