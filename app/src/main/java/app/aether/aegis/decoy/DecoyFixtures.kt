package app.aether.aegis.decoy

import app.aether.aegis.core.Message
import app.aether.aegis.core.MessageType
import app.aether.aegis.core.Protocol
import app.aether.aegis.data.KnownPeerEntity
import app.aether.aegis.data.MemberStatusEntity
import app.aether.aegis.data.MessageEntity
import app.aether.aegis.data.StoryEntity

/**
 * Hardcoded fake family content surfaced when the user has unlocked
 * with the duress PIN.
 *
 * Crucial design property: this returns the same domain types the real
 * Repository returns (KnownPeerEntity, MemberStatusEntity, etc.) so
 * the existing UI screens (ChatListScreen, StatusScreen, MapScreen…)
 * render decoy data through their normal code paths. The attacker
 * cannot tell which mode they are in because the rendering is
 * identical — only the underlying data differs.
 *
 * v1 of the decoy profile: substitution layer.
 *
 * v2/v3 (deferred): VeraCrypt-style hidden volume — separate SQLCipher
 * DB keyed by the duress PIN, real DB stored in free space and
 * indistinguishable from random. The existence of the real profile
 * cannot then be cryptographically proven.
 *
 * Content notes:
 *   - "Family" members are stock names (Mom / Dad / Sister / Brother /
 *     Grandma) — the attacker sees a plausible family contact list.
 *   - Locations: a believable city cluster (Brussels area used here;
 *     could be made configurable to match the owner's actual city).
 *   - Status: realistic battery levels, recent lastSeen timestamps.
 *   - Conversations: low-information small talk, no operational detail.
 *   - Stories: empty by default — fake stories require fake photos,
 *     which we don't ship in the APK.
 */
object DecoyFixtures {

    private val now: Long get() = System.currentTimeMillis()

    /** Synthetic pubkeys for the fake family. Prefix `decoy-` so we
     *  never collide with real SimpleX-format keys. */
    private const val MOM     = "decoy-fam-mom"
    private const val DAD     = "decoy-fam-dad"
    private const val SISTER  = "decoy-fam-sister"
    private const val BROTHER = "decoy-fam-brother"
    private const val GRANDMA = "decoy-fam-grandma"

    /** All decoy peer pubkeys — caller can check "is this a decoy id?". */
    val allDecoyKeys: Set<String> = setOf(MOM, DAD, SISTER, BROTHER, GRANDMA)

    fun isDecoyKey(key: String): Boolean = key in allDecoyKeys

    /** KnownPeerEntity list — what the chat list iterates over. Pinned
     *  flag mirrors the real-world tendency to pin a parent at the top. */
    fun peers(): List<KnownPeerEntity> = listOf(
        peer(MOM,     "Mom",     pinned = true,  lastSeenAgoMs = 4 * 60_000L),
        peer(DAD,     "Dad",     pinned = false, lastSeenAgoMs = 18 * 60_000L),
        // One Emergency for variety; the rest Trusted (the badge panel
        // in contact detail only shows for Trusted, so the mix reads as
        // a real, differentiated circle rather than uniform).
        peer(SISTER,  "Sis",     pinned = false, lastSeenAgoMs = 2 * 3600_000L,
            trust = app.aether.aegis.data.TrustTier.EMERGENCY.name),
        peer(BROTHER, "Bro",     pinned = false, lastSeenAgoMs = 6 * 3600_000L),
        peer(GRANDMA, "Grandma", pinned = false, lastSeenAgoMs = 18 * 3600_000L),
    )

    private fun peer(
        key: String,
        name: String,
        pinned: Boolean,
        lastSeenAgoMs: Long,
        trust: String = app.aether.aegis.data.TrustTier.TRUSTED.name,
    ) =
        KnownPeerEntity(
            publicKey = key,
            displayName = name,
            addedAt = now - 30L * 86_400_000L,   // 30 days ago — established contact
            lastSeenAt = now - lastSeenAgoMs,
            announcedName = name,
            announcedBio = null,
            announcedAvatarPath = null,
            pinned = pinned,
            muted = false,
            disappearingTtl = null,
            verified = true,                     // decoy contacts are "verified" so no scary prompts
            shareLocation = true,
            // Random-but-stable shield frame + trust tier so decoy
            // contacts show believable avatar rings and (for the
            // Trusted ones) a badge panel, instead of bare/blank — a
            // frameless, badge-less decoy is itself a tell
            // (reversing the earlier "blank under duress").
            peerReportedTier = decoyFrameTier(key),
            trustTier = trust,
        )

    /** Stable random shield-frame tier for a decoy contact, seeded by
     *  its key (Bronze…Cyan — never None, so every decoy has a ring). */
    private fun decoyFrameTier(key: String): String = DECOY_FRAME_TIERS[
        Math.floorMod(key.hashCode(), DECOY_FRAME_TIERS.size),
    ].name

    private val DECOY_FRAME_TIERS = listOf(
        app.aether.aegis.admin.ShieldTier.Bronze,
        app.aether.aegis.admin.ShieldTier.Silver,
        app.aether.aegis.admin.ShieldTier.Gold,
        app.aether.aegis.admin.ShieldTier.Cyan,
    )

    /** MemberStatusEntity list — what the Status grid + Map reads.
     *  Locations cluster around Brussels (50.85, 4.35) but with each
     *  family member at a different believable spot. */
    fun statuses(): List<MemberStatusEntity> = listOf(
        status(MOM,     pct = 73, lat = 50.8503, lng = 4.3517, ageMs = 4 * 60_000L),
        status(DAD,     pct = 41, lat = 50.8333, lng = 4.4000, ageMs = 18 * 60_000L),
        status(SISTER,  pct = 88, lat = 51.2200, lng = 4.4000, ageMs = 2 * 3600_000L),
        status(BROTHER, pct = 12, lat = 50.6326, lng = 5.5797, ageMs = 6 * 3600_000L),
        status(GRANDMA, pct = 67, lat = 50.4674, lng = 4.8720, ageMs = 18 * 3600_000L),
    )

    private fun status(key: String, pct: Int, lat: Double, lng: Double, ageMs: Long) =
        MemberStatusEntity(
            peerKey = key,
            batteryLevel = pct,
            isCharging = pct == 41,              // one charging, the rest not — looks realistic
            networkType = "cellular",
            signalStrength = -70,
            latitude = lat,
            longitude = lng,
            lastActive = now - ageMs,
            heartRate = null,
            hrv = null,
            spo2 = null,
        )

    /** Map<peerKey, Message> — matches Repository.observeLastMessagePerPeer
     *  shape. Returned as domain `Message` so ChatListScreen renders it
     *  the same way it would a real last-message preview. */
    fun lastMessages(): Map<String, Message> = mapOf(
        MOM     to lastMsg(MOM,     "call me when you can",  4 * 60_000L,   outgoing = false),
        DAD     to lastMsg(DAD,     "ok 👍",                  20 * 60_000L,  outgoing = true),
        SISTER  to lastMsg(SISTER,  "lol",                   2 * 3600_000L, outgoing = false),
        BROTHER to lastMsg(BROTHER, "see you sunday",        6 * 3600_000L, outgoing = false),
        GRANDMA to lastMsg(GRANDMA, "love you 💕",            18 * 3600_000L, outgoing = false),
    )

    private fun lastMsg(peer: String, body: String, ageMs: Long, outgoing: Boolean) =
        Message(
            id = "decoy-last-$peer",
            from = if (outgoing) "self" else peer,
            to = if (outgoing) peer else "self",
            content = body,
            timestamp = now - ageMs,
            protocol = Protocol.SIMPLEX,
            type = MessageType.TEXT,
            status = if (outgoing) "read" else "sent",
        )

    /** Full conversations — used by ChatScreen as a substitute for
     *  the repository.conversation() flow. Returns domain `Message`
     *  so the chat UI can render straight from this. */
    fun conversation(peerKey: String): List<Message> = when (peerKey) {
        MOM -> listOf(
            convMsg(MOM, "m-mom-1", false, "are you eating properly",    5 * 3600_000L),
            convMsg(MOM, "m-mom-2", true,  "yes mom",                    4 * 3600_000L),
            convMsg(MOM, "m-mom-3", false, "send a picture",             3 * 3600_000L + 30 * 60_000L),
            convMsg(MOM, "m-mom-4", false, "call me when you can",       4 * 60_000L),
        )
        DAD -> listOf(
            convMsg(DAD, "m-dad-1", false, "fixed the bike",             3 * 3600_000L),
            convMsg(DAD, "m-dad-2", true,  "thanks dad",                 2 * 3600_000L),
            convMsg(DAD, "m-dad-3", false, "no problem",                 90 * 60_000L),
            convMsg(DAD, "m-dad-4", true,  "ok 👍",                       20 * 60_000L),
        )
        SISTER -> listOf(
            convMsg(SISTER, "m-sis-1", true,  "did you see that thing",  4 * 3600_000L),
            convMsg(SISTER, "m-sis-2", false, "what thing",              3 * 3600_000L + 50 * 60_000L),
            convMsg(SISTER, "m-sis-3", true,  "nvm haha",                3 * 3600_000L),
            convMsg(SISTER, "m-sis-4", false, "lol",                     2 * 3600_000L),
        )
        BROTHER -> listOf(
            convMsg(BROTHER, "m-bro-1", true,  "you home sunday?",       8 * 3600_000L),
            convMsg(BROTHER, "m-bro-2", false, "yeah",                   7 * 3600_000L),
            convMsg(BROTHER, "m-bro-3", true,  "good, bringing kids",    6 * 3600_000L + 30 * 60_000L),
            convMsg(BROTHER, "m-bro-4", false, "see you sunday",         6 * 3600_000L),
        )
        GRANDMA -> listOf(
            convMsg(GRANDMA, "m-gma-1", false, "thank you for calling",  20 * 3600_000L),
            convMsg(GRANDMA, "m-gma-2", true,  "of course ❤",            19 * 3600_000L),
            convMsg(GRANDMA, "m-gma-3", false, "love you 💕",             18 * 3600_000L),
        )
        else -> emptyList()
    }

    private fun convMsg(peer: String, id: String, outgoing: Boolean, body: String, ageMs: Long) =
        Message(
            id = id,
            from = if (outgoing) "self" else peer,
            to = if (outgoing) peer else "self",
            content = body,
            timestamp = now - ageMs,
            protocol = Protocol.SIMPLEX,
            type = MessageType.TEXT,
            status = if (outgoing) "read" else "sent",
        )

    /** Build a faux outgoing Message the attacker just typed in the
     *  decoy composer. Marked "delivered" then flipped "read" so it
     *  looks like the fake family member received it. */
    fun outgoingDecoyMessage(peer: String, body: String): Message =
        Message(
            id = "decoy-out-" + java.util.UUID.randomUUID().toString().take(8),
            from = "self",
            to = peer,
            content = body,
            timestamp = System.currentTimeMillis(),
            protocol = Protocol.SIMPLEX,
            type = MessageType.TEXT,
            status = "read",
        )

    /** Stories list — empty for now. Fake stories would need fake
     *  photo assets shipped in the APK, which we don't want to commit. */
    fun stories(): List<StoryEntity> = emptyList()
}
