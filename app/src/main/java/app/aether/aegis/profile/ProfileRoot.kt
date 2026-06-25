package app.aether.aegis.profile

import android.content.Context
import java.io.File

/**
 * Per-profile filesystem root. Every piece of identity-bound state
 * (database, identity keypair, vault container, attachments, avatar,
 * per-profile prefs) lives under this directory and nothing else
 * does. Two profiles on the same device are two siblings under
 * [Companion.profilesParent] — they share zero filesystem state, can
 * be deleted independently, and look like two opaque directories to
 * an attacker who hasn't entered either PIN.
 *
 * Phase 1 of the multi-profile work is just this abstraction + a single
 * default profile. Phase 2
 * adds the multi-profile UI on top.
 *
 * Layout (absolute paths under [Context.getFilesDir]):
 *
 *     profiles/
 *         <profile-uuid>/
 *             databases/app.aether.aegis.db          — Room SQLCipher DB
 *             identity.key                — Curve25519 keypair
 *             avatar.<ext>                — profile avatar image
 *             app_files/                  — message attachments
 *             vault/                      — encrypted vault blobs (later)
 *
 * Files that stay global (NOT inside a profile root):
 *
 *     update.apk, previous.apk            — OTA payload, app-wide
 *     startup-errors.log                  — diagnostic
 *     mugshots/                           — wrong-PIN snapshots fire
 *                                           before any profile is
 *                                           selected, so they're
 *                                           pre-unlock state
 *     osmdroid                            — tile cache, identity-free
 *
 * SharedPreferences namespacing is deferred to a follow-up sub-phase
 * (Phase 1b). Until then prefs stay on their existing global names;
 * single-profile means there's no collision to resolve yet.
 */
class ProfileRoot(
    val id: String,
    val root: File,
) {
    /** Directory Room/SQLCipher reads the DB file out of. Room takes
     *  the DB name as a path relative to this — see
     *  [databaseFile] for the absolute form we pass to Room. */
    val databasesDir: File get() = File(root, DATABASES_SUBDIR).apply { mkdirs() }

    /** Absolute path to the per-profile Room DB. Room accepts this
     *  as the `name` argument when it's an absolute path. */
    val databaseFile: File get() = File(databasesDir, DB_FILE_NAME)

    /** Curve25519 identity keypair for this profile. */
    val identityFile: File get() = File(root, IDENTITY_FILE_NAME)

    /** Attachments directory — mirrors simplex-chat's appFilesDir
     *  but scoped to this profile. Inbound files land here, outbound
     *  sends pick from here. */
    val attachmentsDir: File get() = File(root, ATTACHMENTS_SUBDIR).apply { mkdirs() }

    /** Encrypted vault container directory (used by the later vault
     *  expansion). */
    val vaultDir: File get() = File(root, VAULT_SUBDIR).apply { mkdirs() }

    /** Encrypted vault ATTACHMENTS — AES-GCM-wrapped photos /
     *  videos / files written when the vault PIN is unlocked.
     *  Distinct from [vaultDir] so future structured-vault work
     *  (folder hierarchy, manifest) doesn't collide with raw
     *  encrypted blobs. */
    val vaultEncDir: File get() = File(root, VAULT_ENC_SUBDIR).apply { mkdirs() }

    /** Encrypted CHAT ATTACHMENTS — per-file AES-GCM under a random
     *  DEK that is itself sealed under the REAL-PIN-derived pubkey
     *  ([app.aether.aegis.lock.LockStore.sealPub]). Kept separate from
     *  [attachmentsDir] because SimpleX core treats the latter as
     *  its `appFilesFolder` and may iterate over it; we don't want
     *  our .enc blobs showing up in any SimpleX-side enumeration. */
    val chatEncAttachmentsDir: File
        get() = File(root, CHAT_ENC_ATTACHMENTS_SUBDIR).apply { mkdirs() }

    /** Picks an avatar file path under this profile's root.
     *  Extension chosen by caller from the source MIME. */
    fun avatarFile(ext: String): File = File(root, "avatar.$ext")

    /** Per-group shared-avatar JPEG,
     *  keyed by the local group UUID under a `group_avatars/` subdir.
     *  Distinct from the user's own `avatar.*` — this is the
     *  *group's* image, not a member identity. Always `.jpg` because
     *  both the outbound encode and the inbound decode write JPEG. */
    fun groupAvatarFile(groupId: String): File =
        File(File(root, "group_avatars").apply { mkdirs() }, "$groupId.jpg")

    /** Best-effort: the current avatar file regardless of extension.
     *  Returns null if no `avatar.*` exists. Callers use this to load
     *  + display; [avatarFile] is for writing. */
    fun avatarFileIfPresent(): File? = root.listFiles()
        ?.firstOrNull { it.name.startsWith("avatar.") && it.isFile }

    /** Ensure all subdirs exist. Idempotent; safe to call on every
     *  profile activation. */
    fun ensureLayout() {
        root.mkdirs()
        databasesDir
        attachmentsDir
        vaultDir
        vaultEncDir
        chatEncAttachmentsDir
    }

    /**
     * SharedPreferences store name scoped to this profile. The
     * default profile keeps the bare base name so existing on-disk
     * `.xml` files (LockStore, ProfileStore, GeofenceStore, etc.)
     * are read by their owners with zero migration required. Any
     * non-default profile gets a `__<id>` suffix so Profile B can
     * never accidentally read Profile A's PIN store.
     *
     * Use only for *identity-bound* prefs: per-contact PIN, status
     * sharing toggles, geofence, SIM-swap fingerprint, canary,
     * quiet hours, hold-to-execute, Cyan-tier announce, profile,
     * etc. Truly global prefs (LunaGlass tuning, OSMDroid tile
     * cache, debug overlay toggle) keep their bare names — they
     * tune the *app* not the *identity*.
     */
    fun prefsName(base: String): String =
        if (id == DEFAULT_PROFILE_ID) base else "${base}__$id"

    companion object {
        const val DEFAULT_PROFILE_ID = "default"
        const val PROFILES_SUBDIR = "profiles"
        const val DATABASES_SUBDIR = "databases"
        const val ATTACHMENTS_SUBDIR = "app_files"
        const val VAULT_SUBDIR = "vault"
        const val VAULT_ENC_SUBDIR = "vault_enc"
        const val CHAT_ENC_ATTACHMENTS_SUBDIR = "chat_enc"
        const val DB_FILE_NAME = "app.aether.aegis.db"
        const val IDENTITY_FILE_NAME = "identity.key"

        /** Parent directory under which all profile roots live. */
        fun profilesParent(context: Context): File =
            File(context.filesDir, PROFILES_SUBDIR).apply { mkdirs() }

        /** Build a ProfileRoot for a given profile id. The root
         *  directory is created on first access. */
        fun forId(context: Context, id: String): ProfileRoot {
            val rootDir = File(profilesParent(context), id)
            val root = ProfileRoot(id, rootDir)
            root.ensureLayout()
            return root
        }
    }
}
