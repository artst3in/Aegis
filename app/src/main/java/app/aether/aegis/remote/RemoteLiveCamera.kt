package app.aether.aegis.remote

import app.aether.aegis.AegisApp
import app.aether.aegis.call.CallManager
import app.aether.aegis.call.CallState
import app.aether.aegis.call.CallStore
import android.annotation.SuppressLint
import android.content.Context
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
 * Target-side WebRTC live camera stream, fired in response to
 * [RemoteAccessProtocol.KIND_LIVE_CAM_START]. Mirrors
 * [app.aether.aegis.call.SOSLiveStream] but for the remote-access surface:
 *
 *   - Hidden WebView (no Window attached) loads the SimpleX call.html
 *     in the same way the regular CallScreen does. The mic + camera
 *     capture, codec, ICE, and network sockets all live in the JS
 *     engine and don't require a visible UI.
 *
 *   - [CallManager.placeCall] is invoked with stealth=true so the
 *     outgoing call doesn't surface CallIsland banner, a chat-history
 *     row, or any other UI that would tip off whoever is holding the
 *     device. The WebRTC pipeline itself runs identically to a
 *     user-initiated video call.
 *
 *   - The sender is the peer who sent KIND_LIVE_CAM_START. Their
 *     CallManager.onSimpleXCallEvent picks up the SimpleX
 *     callInvitation and renders the regular incoming-call
 *     notification — the sender taps Answer and sees the target's
 *     camera feed in [app.aether.aegis.call.CallScreen].
 *
 * Stealth caveats: on Android 12+ the OS-level camera/mic privacy
 * indicators (the green dot in the status bar) will still light up
 * while capture is active — there's no API to suppress those. The
 * device will be silent in every other respect.
 *
 * Idempotent: a second start() while already streaming is a no-op.
 * stop() can be called from any thread; it hands the teardown to the
 * main looper to keep the WebView destruction on its construction
 * thread.
 */
object RemoteLiveCamera {

    private const val TAG = "RemoteLiveCamera"

    @Volatile private var webView: WebView? = null
    @Volatile private var streaming = false
    @Volatile private var currentSender: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    /** Watchdog that force-stops the stream if the WebRTC handshake
     *  doesn't reach Connected within CONNECT_TIMEOUT_MS. Without this
     *  a sender that never accepts leaves the target's hidden WebView
     *  holding camera + mic + ICE retries indefinitely — the 64%
     *  battery-drain regression user reported under 2026.05.676. */
    private var watchdog: Job? = null
    /** Observer that ties stream lifetime to [CallStore.active]. When
     *  the active call disappears (peer ended, timeout, JS-side
     *  failure), we tear the WebView down so it doesn't keep camera
     *  and mic open forever after the WebRTC pipeline released them. */
    private var callObserver: Job? = null

    private const val CONNECT_TIMEOUT_MS = 60_000L

    /** Begin a stealth WebRTC video stream to [senderPubkey]. The
     *  [lens] hint is forwarded to JS getUserMedia's facingMode after
     *  the call connects ("user" = front, "environment" = rear). Null
     *  defaults to rear: the stolen-phone use case is "what is the
     *  holder looking at", and the front lens is already covered by
     *  the regular LOCATE / WATCH_TICK mugshot path. */
    @SuppressLint("SetJavaScriptEnabled")
    fun start(senderPubkey: String, senderDisplayName: String, lens: String?) {
        if (streaming) {
            Log.i(TAG, "start: already streaming, ignoring")
            return
        }
        streaming = true
        currentSender = senderPubkey
        val ctx = AegisApp.instance
        main.post {
            runCatching {
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

                        override fun onPageFinished(view: WebView, url: String?) {
                            CallManager.onPageLoaded()
                            // Flip to the requested lens once the JS
                            // pipeline is up. CallManager.flipCamera is
                            // idempotent; we only call it if the lens
                            // hint differs from WebRTC's default ("user").
                            val want = when (lens) {
                                "rear" -> "environment"
                                "front" -> "user"
                                else -> "environment"
                            }
                            if (want != "user") {
                                main.postDelayed({
                                    runCatching { CallManager.flipCamera() }
                                }, 1500L)
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(
                            req: android.webkit.PermissionRequest,
                        ) {
                            // CAMERA + RECORD_AUDIO are self-granted by
                            // app.aether.aegis.admin.PermissionAutoGrant on every
                            // cold start when Aegis is Device Owner;
                            // forward whatever JS asks for so the
                            // getUserMedia call succeeds.
                            req.grant(req.resources)
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                }
                webView = wv
                CallManager.attachWebView(wv)
                CallManager.placeCall(
                    peer = senderPubkey,
                    name = senderDisplayName,
                    video = true,
                    stealth = true,
                )
                wv.loadUrl(
                    "https://appassets.androidplatform.net/assets/www/android/call.html"
                )
                armWatchdog()
                armCallObserver()
                Log.i(TAG, "live-cam stream attached for sender=$senderDisplayName lens=$lens")
            }.onFailure {
                Log.w(TAG, "live-cam attach failed", it)
                streaming = false
                currentSender = null
                webView = null
            }
        }
    }

    fun stop() {
        if (!streaming) return
        streaming = false
        currentSender = null
        watchdog?.cancel(); watchdog = null
        callObserver?.cancel(); callObserver = null
        main.post {
            runCatching { CallManager.hangUp() }
            runCatching { CallManager.detachWebView() }
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
            webView = null
            Log.i(TAG, "live-cam stream torn down")
        }
    }

    fun isStreaming(): Boolean = streaming

    fun senderKey(): String? = currentSender

    /** Force-stop the stream if the WebRTC handshake doesn't reach
     *  Connected within [CONNECT_TIMEOUT_MS]. Without it, a sender
     *  that never accepts (or never sees the incoming-call
     *  notification) leaves camera + mic + ICE retries running on
     *  the target indefinitely. */
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

    /** Tie the live-cam lifetime to CallStore.active. When the active
     *  call goes from non-null → null (peer hung up, SimpleX dropped
     *  the invitation, JS engine errored), the WebRTC pipeline is
     *  already torn down — we then release the hidden WebView too so
     *  it doesn't squat in memory. */
    private fun armCallObserver() {
        callObserver?.cancel()
        var seenActive = false
        callObserver = CallStore.active
            .onEach { active ->
                if (active != null) {
                    seenActive = true
                } else if (seenActive && streaming) {
                    Log.i(TAG, "CallStore.active went null — tearing down live-cam")
                    stop()
                }
            }
            .launchIn(scope)
    }
}
