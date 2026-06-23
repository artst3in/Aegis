package app.aether.aegis.call

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

/**
 * Auto-initiated WebRTC stream from a sos event — audio always,
 * video when the PowerBudget allows it.
 *
 * Architecture: we mint a *hidden* WebView (no Window attached) on the
 * main thread, load call.html the same way CallScreen does, and hand it
 * to CallManager via the existing attach + placeCall pipeline. The
 * mic/camera capture + WebRTC peer connection live entirely in the JS
 * engine — no UI required for capture (Camera2 happily renders into
 * an off-screen surface).
 *
 * Why headless: sos fires with the screen locked, so the regular
 * CallScreen can't help us. The mic, codec, ICE, and network sockets
 * don't care whether a Window exists.
 *
 * Topology: WebRTC is peer-to-peer, so the stream targets ONE peer at
 * a time (the highest-priority paired contact). Every other paired peer
 * still gets the 60 s recorded chunks shipped from SOSHandler. The
 * chunks are the evidence trail; the live stream is the real-time
 * listen-in.
 */
class SOSLiveStream(private val context: Context) {

    @Volatile private var webView: WebView? = null
    @Volatile private var streaming = false
    private val main = Handler(Looper.getMainLooper())

    /** Begin a sos-class WebRTC call to [peerPubkey]. Audio always;
     *  video iff [withVideo] is true (caller decides based on the
     *  PowerBudget — camera is the heaviest sos subsystem and drops
     *  out at ≤40 %). Idempotent. */
    @SuppressLint("SetJavaScriptEnabled")
    fun start(peerPubkey: String, peerName: String, withVideo: Boolean) {
        if (streaming) return
        streaming = true
        main.post {
            runCatching {
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler(
                        "/assets/",
                        WebViewAssetLoader.AssetsPathHandler(context),
                    )
                    .build()
                val wv = WebView(context).apply {
                    webViewClient = object : WebViewClientCompat() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ) = assetLoader.shouldInterceptRequest(request.url)

                        override fun onPageFinished(view: WebView, url: String?) {
                            CallManager.onPageLoaded()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(
                            req: android.webkit.PermissionRequest,
                        ) {
                            // RECORD_AUDIO + CAMERA were granted at
                            // app start; forward whatever the engine
                            // asks for so getUserMedia({audio, video})
                            // succeeds.
                            req.grant(req.resources)
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                }
                webView = wv
                CallManager.attachWebView(wv)
                // CallManager.placeCall arms pendingOutgoingInvite;
                // once the JS engine reports capabilities, /_call
                // invite goes out with the right media type.
                CallManager.placeCall(
                    peer = peerPubkey,
                    name = peerName,
                    video = withVideo,
                )
                wv.loadUrl(
                    "https://appassets.androidplatform.net/assets/www/android/call.html"
                )
                Log.i(TAG, "sos live-stream attached (video=$withVideo) for $peerName")
            }.onFailure {
                Log.w(TAG, "sos live-stream attach failed", it)
                streaming = false
                webView = null
            }
        }
    }

    /** Tear down the stream + hidden WebView. Called from SOSHandler.cancel. */
    fun stop() {
        if (!streaming) return
        streaming = false
        main.post {
            runCatching { CallManager.hangUp() }
            // destroyWebView() nulls CallManager.webView AND destroys the
            // Chromium renderer. Same fix as RemoteLiveCamera / RemoteLiveMic:
            // the old detachWebView() + manual destroy left a stale reference
            // in CallManager → the next normal call reused a dead WebView.
            runCatching { CallManager.destroyWebView() }
            webView = null
        }
    }

    private companion object {
        private const val TAG = "SOSLiveStream"
    }
}
