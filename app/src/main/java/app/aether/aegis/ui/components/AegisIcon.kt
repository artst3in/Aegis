package app.aether.aegis.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import app.aether.aegis.R
import androidx.compose.ui.res.stringResource

/**
 * Branded icon wrapper. Routes every icon reference through
 * [AegisIcons] so call sites stay agnostic to the underlying drawable
 * — the same call works whether [AegisIcons.Back] resolves to a
 * Material vector, a custom hex-themed XML, or a future SVG drop-in.
 *
 *   AegisIcon(AegisIcons.Back, "back")
 *   AegisIcon(AegisIcons.Send, label, tint = MaterialTheme.colorScheme.primary)
 *
 * The vector drawables under `res/drawable/ic_aegis_*` are LunaGlass
 * line art (paths from the LunaGlass icon reference) — all 24×24,
 * stroked-white so the [tint] colours them at use.
 */
@Composable
fun AegisIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}

/**
 * Single import point for every icon the app uses. Each entry resolves
 * to one of the LunaGlass vector drawables. Mapping
 * is by semantic role, not literal shape — e.g. [Star] points to
 * `ic_aegis_pin` because the only place we render a "star" today is
 * the pin/unpin affordance on notes.
 */
object AegisIcons {
    /** Chevron-left back arrow — TopAppBar navigation icons. */
    val Back   = R.drawable.ic_aegis_back

    /** Paper-plane send arrow — message composer send button. */
    val Send   = R.drawable.ic_aegis_send

    /** Plus sign — "new" / "add" / "attach" affordances. */
    val Add    = R.drawable.ic_aegis_plus

    /** Phone receiver — voice-call action. */
    val Call   = R.drawable.ic_aegis_phone

    /** X — dismiss, cancel reply, remove attachment. */
    val Close  = R.drawable.ic_aegis_close

    /** Trash can — delete affordances. */
    val Delete = R.drawable.ic_aegis_trash

    /** Three vertical dots — overflow menu trigger. (Not in the
     *  LunaGlass icon reference; LunaGlass-styled custom.) */
    val More   = R.drawable.ic_aegis_more

    /** Filled hex — avatar / "person" placeholder when no photo. */
    val Person = R.drawable.ic_aegis_online

    /** Video-cam (camera body + lens) — video-call action. */
    val Play   = R.drawable.ic_aegis_video

    /** Magnifying glass — search affordances. */
    val Search = R.drawable.ic_aegis_search

    /** Push-pin — pin/unpin (notes, messages). */
    val Star   = R.drawable.ic_aegis_pin

    /** Location pin — radar stringResource(R.string.map_recenter_on_family) button. */
    val Recenter = R.drawable.ic_aegis_location

    /** Notes icon — the in-chat self-note save affordance. */
    val Notes = R.drawable.ic_aegis_notes

    /** Vault icon — cabinet + dial + latch. Distinct from
     *  [Notes] so the user can tell at a glance whether
     *  they're tapping the in-chat save-as-note action vs.
     *  the larger encrypted vault. */
    val Vault = R.drawable.ic_aegis_vault

    /** Help / docs — proper question mark, not the search glass
     *  placeholder it used to be aliased to. */
    val Help = R.drawable.ic_aegis_help

    /** Camera body — chat attach drawer stringResource(R.string.story_screens_camera) tile. */
    val Camera = R.drawable.ic_aegis_camera

    /** Gallery / multi-photo — chat attach drawer stringResource(R.string.chat_gallery) tile. */
    val Gallery = R.drawable.ic_aegis_gallery

    /** File / document — chat attach drawer stringResource(R.string.chat_file) tile. */
    val File = R.drawable.ic_aegis_file

    /** Map pin — chat attach drawer stringResource(R.string.chat_location) tile. Distinct alias
     *  from [Recenter] (same drawable) so the call site reads
     *  semantically at the chat-bar layer. */
    val Location = R.drawable.ic_aegis_location

    /** Microphone — voice-record hex in the chat compose bar. */
    val Mic = R.drawable.ic_aegis_mic

    /** Lightning bolt — charging indicator next to battery
     *  percentages everywhere a peer's device status surfaces.
     *  Replaces the ⚡ emoji which was the last non-LunaGlass
     *  glyph on the battery readouts. */
    val Charging = R.drawable.ic_aegis_charging

    /** Flame — burn-after-reading bubble, attach drawer stringResource(R.string.chat_burn) tile,
     *  Origins page entry. */
    val Burn = R.drawable.ic_aegis_burn

    /** Padlock — encryption-class surfaces (Origins encryption entry,
     *  app-lock affordances). */
    val Lock = R.drawable.ic_aegis_lock

    /** Fingerprint — biometric-unlock affordance on the lock screen /
     *  lock settings. Nested LunaGlass ridges with a whorl core. */
    val Fingerprint = R.drawable.ic_aegis_fingerprint

    /** Siren dome with sound waves — SOS screen siren toggle (off
     *  state). Pairs with [SirenStop] for the on state. */
    val Siren = R.drawable.ic_aegis_siren

    /** Filled stop square — SOS screen siren toggle (on state). */
    val SirenStop = R.drawable.ic_aegis_siren_stop

    // ── Origins icons ─────────────────────────────────────────────
    /** Satellite probe — Voyager Protocol. */
    val Voyager = R.drawable.ic_aegis_voyager
    /** Dead man lever — Dead Man's Switch / Canary. */
    val Canary = R.drawable.ic_aegis_canary
    /** Angular ping waves — Sonar. */
    val Sonar = R.drawable.ic_aegis_sonar
    /** Shield with hidden star — Duress PIN. */
    val Duress = R.drawable.ic_aegis_duress
    /** Flat-top hex perimeter — Geofencing. */
    val Geofence = R.drawable.ic_aegis_geofence
    /** Viewfinder + head — Mugshot. */
    val Mugshot = R.drawable.ic_aegis_mugshot
    /** Ascending bars — Press-and-Hold Arming Sequence. */
    val EdgeHeat = R.drawable.ic_aegis_edge_heat
    /** Three flat-top hexes — Decentralisation. */
    val Mesh = R.drawable.ic_aegis_mesh
    /** Arrow up + signal — Remote Commands. */
    val RemoteCmd = R.drawable.ic_aegis_remote_cmd
    /** SIM card with chip — SIM-swap Detection. */
    val Sim = R.drawable.ic_aegis_sim
    /** QR code pattern — QR Code Pairing. */
    val Qr = R.drawable.ic_aegis_qr
    /** Submarine — Stealth / Ghost mode. */
    val Ghost = R.drawable.ic_aegis_ghost

    /** Faceted LunaGlass heart — empty outline. Support Project Aether
     *  link in Settings footer. Pairs
     *  with [HeartQuarter], [HeartHalf], [HeartThreeQuarter], and
     *  [HeartFull] for the four-step HP ladder. */
    val Heart = R.drawable.ic_aegis_heart
    val HeartFull = R.drawable.ic_aegis_heart_full
    val HeartHalf = R.drawable.ic_aegis_heart_half
    val HeartQuarter = R.drawable.ic_aegis_heart_quarter
    val HeartThreeQuarter = R.drawable.ic_aegis_heart_three_quarter
}
