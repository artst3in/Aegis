package app.aether.aegis.data

/**
 * Trust tier. Every contact carries
 * exactly one of these. The tier is the relationship — there are
 * no per-feature share toggles or advanced modes layered on top.
 *
 *   TRUSTED    — receives routine data (location, presence, status,
 *                sensors) AND sos alerts. Single-digit count
 *                typically: spouse, parents, children, AI family.
 *   EMERGENCY  — receives NOTHING routinely; receives sos alerts.
 *                Wider net: doctor, neighbour with spare key,
 *                cousin, boss. Dozens to hundreds is fine — eyes on
 *                the alert scales witness coverage.
 *   UNTRUSTED  — receives nothing, ever. Chat surface only.
 *                Default for any new contact.
 *
 * The enum is stored ON DEVICE ONLY. Never transmitted, never
 * synchronised to a server, never surfaced to the contact themself.
 * That is structural privacy (the rule is "the app never sends it");
 * note that observable behaviour still lets
 * recipients infer their broad class (Trusted sees radar updates,
 * Emergency sees only sos alerts).
 *
 * Recipient resolution at broadcast time:
 *   routine    = peers WHERE trustTier = TRUSTED
 *   sos      = peers WHERE trustTier IN (TRUSTED, EMERGENCY)
 *   untrusted  = excluded from every broadcast by construction.
 *
 * Blocked is orthogonal — a separate boolean on the same entity.
 * A blocked contact's messages are dropped before the trust layer
 * even runs; conceptually the tier is moot while blocked.
 */
enum class TrustTier {
    TRUSTED,
    EMERGENCY,
    UNTRUSTED,
    ;

    /** Friendly label for confirmation dialogs and the tier picker.
     *  Spec uses "Emergency Contact" two words but we shorten it on
     *  controls; the longer phrasing is kept for confirmation copy. */
    val label: String get() = when (this) {
        TRUSTED   -> "Trusted"
        EMERGENCY -> "Emergency"
        UNTRUSTED -> "Untrusted"
    }
}
