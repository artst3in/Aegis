package app.aether.aegis.simplex

import android.content.Context
import android.net.LocalServerSocket
import android.util.Log
import chat.simplex.common.platform.ChatCtrl
import chat.simplex.common.platform.chatMigrateInit
import chat.simplex.common.platform.chatRecvMsgWait
import chat.simplex.common.platform.chatSendCmdRetry
import chat.simplex.common.platform.initHS
import chat.simplex.common.platform.pipeStdOutToSocket
import java.io.File
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * One-time initialization of the bundled SimpleX Haskell core
 * (libsimplex.so + libsupport.so + libapp-lib.so).
 *
 * Lifecycle matches what the upstream SimpleX Android app does
 * (AppCommon.android.kt): spin up a local-socket log relay so the
 * GHC runtime has somewhere to write stdout/stderr; loadLibrary
 * "app-lib" (which transitively pulls in simplex + support);
 * call initHS() exactly once; then chatMigrateInit() returns a
 * controller handle (ChatCtrl = Long) we hold for the process
 * lifetime.
 *
 * Database: SimpleX manages its own SQLCipher-encrypted SQLite at
 * `${filesDir}/simplex_v1*.db`. The passphrase is derived from
 * the Aegis device identity so a backup of the file alone is
 * useless without the device.
 */
object SimpleXCore {

    @Volatile var ctrl: ChatCtrl = 0L
        private set
    @Volatile var initialised: Boolean = false
        private set
    @Volatile var initError: String? = null
        private set

    private val gate = Semaphore(0)

    @Volatile private var booted = false

    /**
     * One-shot boot: load the native library, start the log relay,
     * call initHS. Idempotent — safe to call multiple times. Does
     * NOT open the DB; that's [tryOpenDb]'s job and can be retried
     * with different passphrases.
     */
    private fun ensureBooted(context: Context) {
        if (booted) return
        synchronized(this) {
            if (booted) return
            ConnectionLog.log(TAG, "boot: starting")
            startLogRelay(context)
            ConnectionLog.log(TAG, "loadLibrary(app-lib)…")
            System.loadLibrary("app-lib")
            gate.acquire()
            ConnectionLog.log(TAG, "log relay acquired; pipeStdOutToSocket")
            pipeStdOutToSocket(context.packageName)
            ConnectionLog.log(TAG, "initHS()")
            initHS()
            booted = true
        }
    }

    /**
     * Attempt to open the SimpleX DB with [passphrase]. Returns true
     * on success (sets [ctrl] + [initialised]); false on failure
     * (sets [initError]). Safely retryable with a DIFFERENT
     * passphrase — chatMigrateInit doesn't leave persistent state
     * when migration reports an error, so we can try the alternate
     * key after a failed first attempt.
     */
    fun tryOpenDb(context: Context, passphrase: String): Boolean {
        ensureBooted(context)
        if (initialised) return true
        synchronized(this) {
            if (initialised) return true
            val dbPrefix = File(context.filesDir, "simplex_v1").absolutePath
            ConnectionLog.log(TAG, "chatMigrateInit prefix=$dbPrefix (trying passphrase)")
            return try {
                val result = chatMigrateInit(dbPrefix, passphrase, "yesUp")
                val migrationJson = result[0] as String
                val ok = migrationJson.contains("\"type\":\"ok\"") ||
                    migrationJson.contains("\"ok\"")
                if (!ok) {
                    initError = "migration: ${migrationJson.take(220)}"
                    ConnectionLog.warn(TAG, "chatMigrateInit reported error: ${migrationJson.take(220)}")
                    return false
                }
                ctrl = (result[1] as Long)
                initialised = ctrl != 0L
                if (initialised) initError = null
                ConnectionLog.log(TAG, "chatMigrateInit OK ctrl=$ctrl")
                initialised
            } catch (t: Throwable) {
                initError = t.message ?: t::class.simpleName
                Log.e(TAG, "tryOpenDb threw", t)
                ConnectionLog.warn(TAG, "tryOpenDb threw: ${t.message ?: t::class.simpleName}")
                false
            }
        }
    }

    /** Open the core DB under [dbPassphrase] if not already open. Idempotent
     *  — a no-op once [initialised]. Both the eager init and the transport's
     *  start() call this with the same Keystore-wrapped key. */
    fun ensureInitialised(context: Context, dbPassphrase: String) {
        if (initialised) return
        tryOpenDb(context, dbPassphrase)
    }

    /**
     * Send a SimpleX terminal-style command (e.g. "/c", "@contact hello").
     * Returns the JSON response as a string.
     */
    fun sendCommand(command: String): String {
        require(initialised) { "SimpleX core not initialised" }
        return chatSendCmdRetry(ctrl, command, 3)
    }

    /**
     * Block until SimpleX emits a JSON event (incoming message, contact
     * connected, etc.) or the timeout elapses. Returns null on timeout.
     */
    fun recvWait(timeoutMs: Int): String? {
        if (!initialised) return null
        val s = chatRecvMsgWait(ctrl, timeoutMs)
        return if (s.isEmpty()) null else s
    }

    private fun startLogRelay(context: Context) {
        // libsupport.so wants a UNIX domain socket named after the package
        // so the GHC runtime can write stdout/stderr without a TTY. We
        // drain it on a background thread; the contents are discarded
        // unless debug logging is wanted.
        thread(name = "simplex-log-relay", isDaemon = true) {
            try {
                val server = LocalServerSocket(context.packageName)
                gate.release()
                val sock = server.accept() ?: return@thread
                sock.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) Log.v(TAG, "simplex: $line")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "log relay died: $e")
                gate.release()
            }
        }
    }

    private const val TAG = "SimpleXCore"
}
