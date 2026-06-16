package app.aether.aegis.backup

import android.content.Context

/**
 * Reminder-cadence settings + last-backup timestamp. Persists in
 * SharedPreferences so the BackupReminderWorker can read it on every
 * 24h tick. The actual backup data lives wherever the user wrote it
 * (SAF-picked location); we never store backup content here.
 */
class BackupPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** How often the worker nags the user about backing up. Off by
     *  default — backups expose the data outside the encrypted DB so
     *  we don't push them on a user who hasn't asked. */
    var intervalDays: Int
        get() = prefs.getInt(KEY_INTERVAL_DAYS, 0)
        set(v) { prefs.edit().putInt(KEY_INTERVAL_DAYS, v).apply() }

    /** Wall-clock of the most recent successful backup. 0 = never. */
    var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_AT, 0L)
        set(v) { prefs.edit().putLong(KEY_LAST_BACKUP_AT, v).apply() }

    /** True if a reminder is currently overdue. */
    fun isReminderDue(now: Long = System.currentTimeMillis()): Boolean {
        val days = intervalDays
        if (days <= 0) return false
        val last = lastBackupAt
        if (last == 0L) return true  // never backed up → due immediately
        val intervalMs = days * 24L * 60L * 60L * 1000L
        return now - last >= intervalMs
    }

    companion object {
        private const val STORE = "aegis_backup"
        private const val KEY_INTERVAL_DAYS = "interval_days"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"

        /** Preset cadence options surfaced in the UI. */
        val intervals = listOf(
            0   to "Off",
            1   to "Daily",
            7   to "Weekly",
            30  to "Monthly",
        )
    }
}
