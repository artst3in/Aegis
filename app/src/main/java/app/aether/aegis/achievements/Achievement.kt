package app.aether.aegis.achievements

/**
 * The verified-security badge catalog.
 *
 * One entry per security capability that has a real, end-to-end
 * success signal to hook. A badge is earned the FIRST time that
 * capability fires for real and succeeds (for the two-party ones, when
 * a human on the other end acts) — never via a test/drill mode.
 * There is deliberately no "test" path.
 *
 * [id] is the stable wire/storage key — never renamed, so old stores
 * and inbound peer-badge envelopes stay readable across version bumps.
 * [title]/[howToEarn] are display strings (keep the playful
 * tone). [shareable] gates whether earning it is announced to trusted
 * contacts as a verification signal.
 */
enum class Achievement(
    val id: String,
    val title: String,
    val howToEarn: String,
) {
    /** A contact tapped "I'M RESPONDING" to your real sos
     *  (SOSCoordinator.handleAcceptOnVictim). Delivery doesn't count
     *  — a human has to answer. */
    SOS_DRILL(
        "sos_drill",
        "SOS Drill",
        "A contact answered your sos with “I'm responding.”",
    ),

    /** A remote command from the owner actually executed on this
     *  device (RemoteAccessHandler success path). */
    REMOTE_OPERATOR(
        "remote_operator",
        "Remote Operator",
        "A remote command ran on your device.",
    ),

    /** A geofence you configured actually crossed and fired its
     *  action. */
    PRISON_BREAK(
        "prison_break",
        "Prison Break",
        "A geofence you set crossed and fired for real.",
    ),

    /** Your dead-man's-switch canary fired to your contacts. */
    DEAD_MAN(
        "dead_man",
        "Dead Man",
        "Your dead-man's-switch fired because you didn't check in.",
    ),

    /** A duress unlock captured a mugshot and shipped it to a
     *  contact. */
    CAUGHT_YOU(
        "caught_you",
        "Caught You",
        "A duress unlock caught a mugshot and sent it.",
    ),

    /** Sentinel caught movement / tampering while the phone was left
     *  unattended, and the alert landed. */
    WATCHTOWER(
        "watchtower",
        "Watchtower",
        "Sentinel caught something while you were away.",
    ),

    /** A real SIM swap was detected and alerted. */
    NUMBERS_UP(
        "numbers_up",
        "Number's Up",
        "A SIM swap was detected and you were alerted.",
    ),
    ;

    companion object {
        /** Resolve a wire/storage id back to its enum, or null for an
         *  id this build doesn't know (forward-compat with a peer on a
         *  newer version that earned a badge we haven't shipped). */
        fun byId(id: String): Achievement? = entries.firstOrNull { it.id == id }
    }
}
