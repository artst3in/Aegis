package app.aether.aegis.call

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The WebRTC video surface — a WebView running the SimpleX-vendored
 * call.html / call.js pipeline, registered with [CallManager] so the JS
 * engine can ingest the offer/answer and render the remote video.
 *
 * Extracted from CallScreen so it can be hosted in TWO places without
 * duplicating the fragile WebView setup:
 *   - [CallScreen] — the full-screen call surface for ordinary calls.
 *   - the device-control panel — inline, so a remote LIVE-cam stream
 *     renders on the same console as the snapshots / location / actions
 *     instead of bouncing the operator out to a separate screen.
 *
 * The WebView is a singleton owned by [CallManager]; only one host may
 * attach it at a time. That's fine here: a panel-hosted live stream
 * suppresses the CallScreen launch (CallManager skips the incoming-call
 * notification for an operator-initiated live peer), so the two hosts
 * are mutually exclusive in practice.
 *
 * Lifecycle (attach on create, detach/destroy at call end) is owned by
 * the CALLER via [CallManager.detachWebView] / [CallManager.destroyWebView]
 * — this composable only constructs/reuses + attaches.
 */
@Composable
fun CallVideoSurface(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Port of upstream simplex-chat CallView.android.kt
            // (WebRTCView). file:android_asset URL (not the synthetic
            // https asset-loader scheme) because upstream's
            // onPermissionRequest grants only `file:/` origins, so the
            // matching URL scheme is required for getUserMedia to ask.
            // Static WebView reuse (upstream pattern): a cached instance
            // has its page loaded, mic permission granted to that exact
            // Chromium renderer, and the JS pipeline alive — detach from
            // its previous parent first so AndroidView can re-host it.
            val cached = CallManager.cachedWebView()
            if (cached != null) {
                (cached.parent as? android.view.ViewGroup)?.removeView(cached)
                cached
            } else WebView(ctx).apply {
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onPermissionRequest(
                        req: android.webkit.PermissionRequest,
                    ) {
                        if (req.origin.toString().startsWith("file:/")) {
                            req.grant(req.resources)
                        } else {
                            req.deny()
                        }
                    }
                    // Pipe console.* / JS errors to logcat under "CallJS".
                    override fun onConsoleMessage(
                        msg: android.webkit.ConsoleMessage,
                    ): Boolean {
                        val tag = "CallJS"
                        val line = "${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})"
                        when (msg.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR ->
                                app.aether.aegis.diag.DiagLog.e(tag, line)
                            android.webkit.ConsoleMessage.MessageLevel.WARNING ->
                                app.aether.aegis.diag.DiagLog.w(tag, line)
                            else -> app.aether.aegis.diag.DiagLog.i(tag, line)
                        }
                        return true
                    }
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        CallManager.onPageLoaded()
                    }
                }
                clearHistory()
                clearCache(true)
                setBackgroundColor(android.graphics.Color.BLACK)
                WebView.setWebContentsDebuggingEnabled(true)
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                CallManager.attachWebView(this)
                loadUrl("file:///android_asset/www/android/call.html")
            }
        },
    )
}
