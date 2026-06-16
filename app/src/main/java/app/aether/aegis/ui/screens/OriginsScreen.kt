package app.aether.aegis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.components.HexShape
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisPanel
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * One row in the Technology Origins list: a single Aegis feature credited
 * to its real-world source (NASA, Enigma, ARPANET, railway dead-man
 * handles, bank-teller silent alarms). Tone: "this tech protects soldiers
 * and astronauts — now it protects your family". Pure presentation data —
 * no protocol, no DB, no behaviour hangs off these fields.
 *
 * The card's glyph is the existing LunaGlass icon for that feature when
 * one exists ([iconRes]); the rest fall back to a small hex with a single
 * letter/symbol so the column scans cleanly without invented art.
 */
private data class Origin(
    val feature: String,
    val source: String,
    val tagline: String,
    val body: String,
    val glyph: String,
    /** Optional LunaGlass vector — when set, the hex renders this
     *  instead of [glyph]. Existing entries that already had a
     *  single-letter or unicode-symbol glyph stay as-is. */
    val iconRes: Int? = null,
)

/**
 * The full credits list, rendered top-to-bottom in declaration order.
 * This IS the content of the screen — adding a feature here adds a card,
 * no other wiring needed. Bodies are intentionally prose (not strings
 * resources) because this is a single-locale narrative section; if it
 * ever needs translation it moves to `strings.xml` like the headers do.
 */
private val ORIGINS = listOf(
    Origin(
        feature = "Voyager Protocol",
        source = "NASA deep-space power management",
        tagline = "Voyager 1 still transmits from 24 billion km on 470 W.",
        body = "Voyager 1 launched in 1977 and still transmits from " +
            "interstellar space on 470 watts. When the signal delay " +
            "exceeds 22 hours, the spacecraft runs on the rules it " +
            "left with. Aegis follows the same model: when you are " +
            "out of reach, your phone holds its protocol and acts on " +
            "your behalf.",
        glyph = "V",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_voyager,
    ),
    Origin(
        feature = "Dead Man's Switch (Canary)",
        source = "Railway · mining safety",
        tagline = "Operator must actively hold. Release = alarm.",
        body = "Locomotive engineers in the 1880s stood on a " +
            "spring-loaded pedal the entire shift. Release it and " +
            "the brakes locked. Coal miners carried canaries " +
            "underground: when the bird stopped singing, the air was " +
            "no longer safe. Aegis combines both. Stop checking in " +
            "and the system assumes you cannot, and alerts the " +
            "people who can help.",
        glyph = "C",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_canary,
    ),
    Origin(
        feature = "Sonar",
        source = "Military submarine detection (WWI / WWII)",
        tagline = "SONAR = Sound Navigation And Ranging. US Navy.",
        body = "Developed during World War I to hunt submarines, " +
            "active sonar sends a pulse and listens for the echo. " +
            "The US Navy refined it for decades. Aegis uses the same " +
            "principle at ultrasonic frequencies, above human " +
            "hearing, to detect motion near your phone without " +
            "disturbing anyone in the room.",
        glyph = "≈",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_sonar,
    ),
    Origin(
        feature = "Duress PIN",
        source = "Banking · law enforcement",
        tagline = "Silent alarm codes used in bank vaults since the 1920s.",
        body = "Since the 1920s, bank tellers have had a second code. " +
            "Type it under the gun and the vault opens normally, but " +
            "dispatch already knows. The robber sees cooperation. " +
            "The police see an alarm. Aegis gives you three PINs: " +
            "one real, two decoy. Enter a decoy and the attacker " +
            "sees a harmless phone while your real data stays " +
            "hidden.",
        glyph = "★",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_duress,
    ),
    Origin(
        feature = "Remote Wipe",
        source = "Military intelligence",
        tagline = "Destroy classified data before enemy capture.",
        body = "Military communications gear has carried a destroy " +
            "protocol since World War II: if capture is imminent, " +
            "erase the keys. The hardware survives but the data is " +
            "gone. A remote command from a trusted contact erases " +
            "the encryption keys and factory-resets the device. By " +
            "the time anyone examines it, there is nothing to find.",
        glyph = "✕",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_wipe,
    ),
    Origin(
        feature = "Geofencing",
        source = "Military · law enforcement",
        tagline = "GPS perimeter monitoring. Restricted zones, ankle monitors.",
        body = "GPS perimeter monitoring began with military " +
            "restricted zones and later moved to law enforcement " +
            "ankle monitors. Cross the boundary and the system " +
            "responds instantly. Aegis lets you draw a safe zone on " +
            "the map. Leave it, and your trusted contacts know, " +
            "whether you meant to or not.",
        glyph = "◉",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_geofence,
    ),
    Origin(
        feature = "Mugshot",
        source = "Law enforcement booking (1888 — Bertillon)",
        tagline = "Standardised suspect photography for 137 years.",
        body = "In 1888, Alphonse Bertillon standardised suspect " +
            "photography for the Paris police, creating the first " +
            "biometric identification system. If someone is where " +
            "they should not be, photograph them. Aegis fires the " +
            "front camera silently when too many wrong PINs are " +
            "entered. The photo goes straight to your trusted " +
            "contacts.",
        glyph = "◐",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_mugshot,
    ),
    Origin(
        feature = "SOS",
        source = "Emergency services (911 · 112)",
        tagline = "Three dots. Three dashes. Three dots. Universal.",
        body = "The bank-teller panic button dates to the 1920s: a " +
            "foot switch under the counter that silently lit a board " +
            "at the police station. No words needed. Just one press. " +
            "Aegis puts the same switch on your phone. Hold the " +
            "button and your GPS, audio, and camera stream to every " +
            "trusted contact.",
        glyph = "!",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_sos,
    ),
    Origin(
        feature = "Press-and-Hold Arming Sequence",
        source = "Industrial · nuclear safety",
        tagline = "Nuclear launch sequence. Two-key interlock. Progressive arming.",
        body = "Nuclear launch requires two officers turning two keys " +
            "simultaneously. Heavy machinery requires a held switch " +
            "that kills the motor the instant you let go. Dangerous " +
            "actions must be deliberate and progressive. Aegis uses " +
            "hold-to-execute for SOS and slide-to-confirm for " +
            "locking. You see how committed you are before the " +
            "action fires.",
        glyph = "▲",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_edge_heat,
    ),
    Origin(
        feature = "End-to-end encryption · double ratchet",
        source = "Enigma · Signal Protocol",
        tagline = "Decades of military cryptography → forward-secret IM.",
        body = "The Enigma rotors advanced with every keystroke so no " +
            "two letters were encrypted the same way. Decades of " +
            "military cryptography refined this into the Signal " +
            "double ratchet: every message gets a fresh key and " +
            "compromising one reveals nothing about the others. The " +
            "math that protects state secrets now protects your " +
            "family, with no government holding a spare key.",
        glyph = "🔒",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_lock,
    ),
    Origin(
        feature = "Decentralisation",
        source = "DARPA · ARPANET",
        tagline = "Designed to survive nuclear attack.",
        body = "ARPANET was designed in the 1960s to survive a nuclear " +
            "strike. No single point of failure. Destroy any node " +
            "and the network routes around it. Aegis uses relays " +
            "that know nothing about you: no accounts, no phone " +
            "numbers, no metadata. There is no server to subpoena " +
            "because no server has anything to give.",
        glyph = "◈",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_mesh,
    ),
    Origin(
        feature = "Remote Commands",
        source = "NASA · military drones",
        tagline = "Commanding craft millions of km away.",
        body = "NASA commands spacecraft millions of kilometres away " +
            "with authenticated, one-way instructions. The craft " +
            "verifies the signature and acts independently. Aegis " +
            "remote commands work the same way: a trusted contact " +
            "sends LOCATE, LOCK, or WIPE, authenticated by your PIN. " +
            "No central server. Just a signed command and a device " +
            "that obeys.",
        glyph = "↟",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_remote_cmd,
    ),
    Origin(
        feature = "SIM-swap detection",
        source = "Telecom counter-intelligence",
        tagline = "Detecting IMSI catchers · cell-tower spoofing.",
        body = "Intelligence agencies developed tools to detect rogue " +
            "cell towers and cloned SIM cards, the kind of hardware " +
            "used to intercept calls and track targets. Aegis " +
            "monitors your SIM identity continuously. If the carrier " +
            "or SIM serial changes unexpectedly, your trusted " +
            "contacts are alerted before you even notice.",
        glyph = "▣",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_sim,
    ),
    Origin(
        feature = "QR Code Pairing",
        source = "Intelligence tradecraft · dead drops",
        tagline = "Contact establishment without identity disclosure.",
        body = "Intelligence agents never exchange phone numbers. They " +
            "use one-time rendezvous tokens: a location, a signal, a " +
            "dead drop. Once used, the token is gone. Aegis pairing " +
            "works the same way: a one-time QR code that expires " +
            "after use. No phone number. No username. Nothing to " +
            "surveil.",
        glyph = "▦",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_qr,
    ),
    Origin(
        feature = "Stealth Mode · Ghost",
        source = "Submarine silent running",
        tagline = "The vessel appears dead while actively operating.",
        body = "Submarines go silent-running by cutting active sonar " +
            "and reducing noise to zero. The vessel appears dead " +
            "while actively watching everything. Aegis Ghost dims " +
            "the screen and silences the phone while continuing to " +
            "broadcast your location to trusted contacts. Visible to " +
            "them. Invisible to the room.",
        glyph = "⌬",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_ghost,
    ),
    Origin(
        feature = "Burn after reading",
        source = "CIA · MI6 field protocol",
        tagline = "Orders exist only long enough to be read.",
        body = "Field operatives received written orders delivered by " +
            "hand. The courier watched them read, then destroyed the " +
            "paper on the spot. The information existed only long " +
            "enough to be understood. Aegis burn messages work the " +
            "same way. The moment the recipient closes the viewer, " +
            "the message is deleted from both devices.",
        glyph = "🔥",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_burn,
    ),
    Origin(
        feature = "Compartmentalization",
        source = "Ship bulkheads · military intelligence · firewalls",
        tagline = "Seal the breach. The rest of the ship stays dry.",
        body = "Ships have had watertight bulkheads since the 15th " +
            "century: seal the breached section and the rest stays " +
            "dry. Intelligence agencies applied the same idea to " +
            "information, where each team knows only what it needs. " +
            "Aegis enforces this at compile time. Each trust tier is " +
            "a separate module. Code for a tier you do not use never " +
            "loads.",
        glyph = "▧",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_security,
    ),
    Origin(
        feature = "Default deny",
        source = "Unix permissions · OpenBSD · least privilege",
        tagline = "Nothing is allowed until you say it is.",
        body = "OpenBSD ships with every service turned off. You " +
            "enable what you need and nothing more. Unix file " +
            "permissions work the same way: no read, no write, no " +
            "execute until explicitly granted. The principle is " +
            "older than the internet. Aegis applies it everywhere: " +
            "groups off, location hidden, trust at zero, until you " +
            "consciously open each door.",
        glyph = "⊘",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_lock,
    ),
    Origin(
        feature = "No test mode",
        source = "Knight Capital 2012 · Therac-25 1985 · Chernobyl 1986",
        tagline = "The real system is the only system.",
        body = "Knight Capital lost 440 million dollars in 45 minutes " +
            "when a dormant flag activated dead code. Therac-25 " +
            "killed three patients when test mode bled into " +
            "treatment mode. Chernobyl disabled safety systems for a " +
            "test and created the explosion. Test scaffolding in " +
            "production kills. Aegis has no test mode, no drill " +
            "mode, no debug overrides. The SOS system you test is " +
            "the one that saves your life.",
        glyph = "⚠",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_security,
    ),
    Origin(
        feature = "Codenames",
        source = "Military intelligence · witness protection · Tor · hacker culture",
        tagline = "You can’t leak what doesn’t exist.",
        body = "Field agents use codenames. Witnesses in protection " +
            "get new identities. The principle is ancient: if your " +
            "name can be used against you, do not use your name. " +
            "Aegis takes it further. Your real identity never enters " +
            "the network. Every connection sees a fresh anonymous " +
            "handle. Your name travels only through an encrypted " +
            "overlay, delivered only to contacts you choose to " +
            "trust.",
        glyph = "☸",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_incognito,
    ),
    Origin(
        feature = "Your key, your data",
        source = "PGP 1991 · Diceware 1995 · BIP39 2013",
        tagline = "No backdoor means no one can backdoor you.",
        body = "PGP let you protect a key with words you choose. " +
            "Diceware made it formal: roll dice, pick words, get an " +
            "unguessable key. BIP39 standardised 24-word seed " +
            "phrases. The principle: if nobody can recover it for " +
            "you, nobody can recover it against you. Your 24-word " +
            "phrase encrypts everything. Lose it and your data is " +
            "gone. That is not a flaw. That is the design.",
        glyph = "⚗",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_lock,
    ),
    Origin(
        feature = "Don’t confirm until committed",
        source = "Database transactions · ACID · banking systems",
        tagline = "Two ticks means sealed.",
        body = "Databases do not confirm a write until the data is on " +
            "disk. Banks do not confirm a transfer until the money " +
            "arrives. Confirm before commit and a crash means you " +
            "lied. In Aegis, two ticks means your message is sealed " +
            "with your contact's 256-bit key and the transport copy " +
            "is destroyed. Not delivered. Sealed.",
        glyph = "✓",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_security,
    ),
    Origin(
        feature = "Amnesia",
        source = "Tails OS · The Amnesic Incognito Live System",
        tagline = "When you shut down, nothing happened.",
        body = "Tails boots from a USB stick into RAM. The operating " +
            "system never touches the hard drive. Pull the stick and " +
            "power off: no evidence a computer was ever used. " +
            "Snowden used it to contact reporters. Aegis brings the " +
            "same principle to a phone. The ephemeral profile lives " +
            "in RAM. Lock the app and the conversation never " +
            "happened.",
        glyph = "∅",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_ghost,
    ),
    Origin(
        feature = "Secure wipe",
        source = "DoD 5220.22-M · Gutmann method · NIST 800-88",
        tagline = "Deleted is not destroyed. Zeroed is.",
        body = "Deleting a file removes the pointer, not the data. " +
            "Forensic labs recover deleted files routinely. Military " +
            "standards require overwriting with zeros before data is " +
            "considered destroyed. Aegis zeros every freed database " +
            "page and reclaims the space within milliseconds. " +
            "Deleted AND destroyed.",
        glyph = "⌧",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_wipe,
    ),
    Origin(
        feature = "Length beats complexity",
        source = "XKCD #936 · Randall Munroe · 2011",
        tagline = "correct horse battery staple.",
        body = "Randall Munroe showed that a complex short password is " +
            "hard to remember and easy to crack, while four random " +
            "words are easy to remember and take centuries. Every " +
            "character you add multiplies the work by 26. Special " +
            "characters add nothing. Aegis enforces 12 characters, " +
            "no symbols required. The password mymomlovesme takes " +
            "150 million GPU-years. Munroe was right.",
        glyph = "≡",
        iconRes = app.aether.aegis.R.drawable.ic_aegis_lock,
    ),
    Origin(
        feature = "Tripwire",
        source = "Military perimeter defence",
        tagline = "Every PIN field is a landmine.",
        body = "A tripwire is the oldest perimeter trap: a thread " +
            "the intruder never sees, that fires the instant it's " +
            "touched. Every PIN prompt in the remote-access flow is " +
            "one. The attacker doesn't know how many traps exist, " +
            "which prompts contain them, or what they look like. " +
            "One wrong guess ends the session. One specific wrong " +
            "guess ends the session and silently calls for help.",
        glyph = "⚠",
    ),
    Origin(
        feature = "M.A.D.",
        source = "Cold War deterrence · RAND, 1960s",
        tagline = "The rational move is not to attack.",
        body = "RAND strategists realised the weapon that kept the " +
            "peace was the one nobody could win with: if any attack " +
            "guarantees the attacker's own destruction, the rational " +
            "move is not to attack. Aegis applies the same logic. " +
            "Steal the phone — it broadcasts your face, location, " +
            "and voice to every trusted contact. Coerce a PIN — it " +
            "silently calls the police. Every action the attacker " +
            "takes makes their position worse. The optimal strategy " +
            "is don't engage.",
        glyph = "☢",
    ),
    Origin(
        feature = "No Country for Old Men",
        source = "Coen Brothers · 2007",
        tagline = "The stolen object hunts the thief.",
        body = "Moss steals a briefcase. Inside: a transponder. " +
            "The money he wanted tracks him across Texas. An Aegis " +
            "phone works the same way. The thief is carrying front " +
            "camera, back camera, GPS, microphone, and accelerometer " +
            "— all transmitting live to trusted contacts. Camera and " +
            "GPS fire at 0.5 seconds. First audio at 5. A ten-second " +
            "power hold kills the hardware, but by then the evidence " +
            "is already elsewhere. The phone can be destroyed. The " +
            "data can't.",
        glyph = "◎",
    ),
)

/**
 * Static, scrollable "where this came from" screen. Lists every [ORIGINS]
 * entry as a [OriginCard] under a short intro header. Read-only marketing/
 * trust-building content — no state, no side effects, no permissions; the
 * only action is Back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginsScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.origins_origins)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column {
                    Text(
                        stringResource(R.string.origins_where_this_came_from),
                        color = AegisCyan,
                        fontSize = 13.sp,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.origins_aegis_is_built_on) +
                            "soldiers, astronauts, spies, and bank tellers. None of it is " +
                            "new. All of it is proven. This is the lineage.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            items(ORIGINS) { o -> OriginCard(o) }
        }
    }
}

/**
 * One credits card: hex glyph on the left, feature/source/tagline/body
 * stacked on the right. Renders [Origin.iconRes] inside the hex when set,
 * otherwise falls back to the single-character [Origin.glyph].
 */
@Composable
private fun OriginCard(o: Origin) {
    GlassPanel(modifier = Modifier) {
        Row(modifier = Modifier.padding(14.dp)) {
            HexShape(
                size = 44.dp,
                borderColor = AegisCyan,
                fillColor = AegisPanel,
            ) {
                // Prefer the real vector when the feature has one; the
                // letter/symbol glyph is only the no-art fallback.
                if (o.iconRes != null) {
                    app.aether.aegis.ui.components.AegisIcon(
                        icon = o.iconRes,
                        contentDescription = o.feature,
                        tint = AegisCyan,
                        modifier = Modifier.size(app.aether.aegis.ui.components.hexInnerIcon(44.dp)),
                    )
                } else {
                    Text(
                        o.glyph,
                        color = AegisCyan,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    o.feature,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    o.source,
                    color = AegisCyan,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    o.tagline,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    o.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
