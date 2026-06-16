package app.aether.aegis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import app.aether.aegis.services.ProtocolService
import androidx.activity.SystemBarStyle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.aether.aegis.ui.screens.*
import app.aether.aegis.ui.theme.AegisTheme
import app.aether.aegis.ui.theme.StarfieldBackground

class MainActivity : FragmentActivity() {

    // Per-app language. Applied HERE (not just via
    // AppCompatDelegate.setApplicationLocales) because this is a
    // non-AppCompat FragmentActivity on a platform (android:Theme.*)
    // theme — AppCompat's per-Activity locale overlay never runs for
    // us, so on pre-13 setApplicationLocales was a no-op ("languages
    // do nothing"). Wrapping the base context with the persisted
    // locale works on every API level (29+); the LanguagePicker
    // recreate()s us so a change takes effect immediately.
    override fun attachBaseContext(newBase: android.content.Context) {
        val tag = runCatching {
            app.aether.aegis.i18n.LanguagePrefs(newBase).tag
        }.getOrNull().orEmpty()
        val ctx = if (tag.isNotBlank() && tag != "system") {
            val locale = java.util.Locale.forLanguageTag(tag)
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            newBase.createConfigurationContext(config)
        } else {
            newBase
        }
        super.attachBaseContext(ctx)
    }

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result inspected by callers via Context.checkSelfPermission */ }

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        val scanned = result?.contents?.trim().orEmpty()
        if (scanned.isBlank()) return@registerForActivityResult
        // Every QR scan goes through a per-call callback now —
        // AddContactScreen for invite links, VerifyContactScreen for
        // safety-code matching. The old "no callback → raw pubkey
        // addKnownPeer" fallback was the dead LAN-pairing path; gone
        // along with the Settings card that triggered it.
        val cb = pendingQrCallback
        pendingQrCallback = null
        cb?.invoke(scanned)
    }

    /** Set by callers (via scanQr) before launching the scanner, consumed once on result. */
    @Volatile
    private var pendingQrCallback: ((String) -> Unit)? = null

    /**
     * Tell the lock gate the backgrounding we're about to cause is an
     * in-app picker / scanner / camera launch, not the owner leaving —
     * so the idle relock doesn't fire when they come back. Every SAF
     * picker, the QR scanner, the photo picker, and the camera funnel
     * through the helpers below, so arming here covers all of them in
     * one place. See LockState.armPickerReturn (user bug 2026-06-07:
     * "changing notification sound … forces app lock"; the same trap hit
     * every picker that outlived the 30 s idle window). */
    private fun armPickerReturn() {
        runCatching { AegisApp.instance.lockState.armPickerReturn() }
    }

    /** Scan a QR and pass its decoded text to the callback. Used by Add contact / Accept invitation. */
    fun scanQr(prompt: String, onResult: (String) -> Unit) {
        pendingQrCallback = onResult
        armPickerReturn()
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(prompt)
            setBeepEnabled(false)
            setOrientationLocked(true)
            // Force the scanner activity to portrait — zxing's default
            // CaptureActivity locks to current sensor orientation,
            // which lands sideways if the launching activity was
            // mid-rotation. PortraitCaptureActivity pins
            // requestedOrientation in onCreate.
            captureActivity = app.aether.aegis.util.PortraitCaptureActivity::class.java
        }
        qrScannerLauncher.launch(options)
    }

    /** Pick an image from the gallery / photo picker and try to
     *  decode a QR out of it. Same callback contract as [scanQr] —
     *  fires with the decoded text on success, no-op on cancel /
     *  decode failure (we toast the failure to keep the user
     *  oriented). Lets users hand Aegis a screenshot of an
     *  invite link instead of pointing the camera at another
     *  screen. */
    fun pickQrFromGallery(onResult: (String) -> Unit) {
        pendingQrCallback = onResult
        armPickerReturn()
        // PickVisualMedia (ActivityResultContracts) — system surface,
        // no media permission needed, auto-grants per-URI read.
        pickQrImageLauncher.launch(
            androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
            ),
        )
    }

    private val pickQrImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val cb = pendingQrCallback
        pendingQrCallback = null
        if (uri == null || cb == null) return@registerForActivityResult
        // Decode off the main thread — bitmap decode + zxing scan are
        // both blocking. The callback fires back on the main thread
        // so the caller's Compose state can react normally.
        app.aether.aegis.AegisApp.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val decoded = app.aether.aegis.util.QrDecoder.decode(this@MainActivity, uri)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (decoded.isNullOrBlank()) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "No QR found in that image",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else cb(decoded)
            }
        }
    }

    /** Most recent attachment-picker callback so the picker result reaches the screen that asked. */
    @Volatile
    private var pendingPickerCallback: ((android.net.Uri) -> Unit)? = null
    @Volatile
    private var pendingMultiPickerCallback: ((List<android.net.Uri>) -> Unit)? = null

    private val pickAttachmentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val cb = pendingPickerCallback
        pendingPickerCallback = null
        if (uri != null && cb != null) cb(uri)
    }

    private val pickMultipleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val cb = pendingMultiPickerCallback
        pendingMultiPickerCallback = null
        if (cb != null) cb(uris ?: emptyList())
    }

    /** Google Photo Picker — the recommended Android-13+ flow for
     *  image / video picks. No SAF mime-filter gotchas, no media
     *  permissions needed (the picker is a system surface that grants
     *  per-URI access automatically). Falls back to a backport-style
     *  picker on older devices. */
    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris ->
        val cb = pendingMultiPickerCallback
        pendingMultiPickerCallback = null
        if (cb != null) cb(uris ?: emptyList())
    }

    fun pickAttachment(mimeFilter: String, onResult: (android.net.Uri) -> Unit) {
        pendingPickerCallback = onResult
        armPickerReturn()
        pickAttachmentLauncher.launch(arrayOf(mimeFilter))
    }

    /** SAF Create-Document for backup export. The user picks the
     *  destination + filename; we write encrypted bytes there. */
    @Volatile private var pendingBackupCreateCallback: ((android.net.Uri?) -> Unit)? = null
    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val cb = pendingBackupCreateCallback
        pendingBackupCreateCallback = null
        cb?.invoke(uri)
    }
    fun createBackupFile(suggestedName: String, onResult: (android.net.Uri?) -> Unit) {
        pendingBackupCreateCallback = onResult
        armPickerReturn()
        createBackupLauncher.launch(suggestedName)
    }

    /** SAF Open-Document for backup restore. */
    @Volatile private var pendingBackupOpenCallback: ((android.net.Uri?) -> Unit)? = null
    private val openBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val cb = pendingBackupOpenCallback
        pendingBackupOpenCallback = null
        cb?.invoke(uri)
    }
    fun openBackupFile(onResult: (android.net.Uri?) -> Unit) {
        pendingBackupOpenCallback = onResult
        armPickerReturn()
        openBackupLauncher.launch(arrayOf("application/octet-stream", "*/*"))
    }

    /**
     * Multi-pick variant for "I want to send 5 photos at once". Returns
     * the list of selected URIs (empty if cancelled).
     */
    /** SAF document picker for "any file" picks. Each vararg entry
     *  is an individual mime type. */
    fun pickMultipleAttachments(
        vararg mimeFilters: String,
        onResult: (List<android.net.Uri>) -> Unit,
    ) {
        pendingMultiPickerCallback = onResult
        armPickerReturn()
        pickMultipleLauncher.launch(arrayOf(*mimeFilters))
    }

    /** Photo Picker for images + videos. Use this for the chat
     *  "Gallery" tile — it sidesteps the entire SAF mime-filter
     *  permission stack that was silently failing on Android 13+. */
    fun pickVisualMedia(onResult: (List<android.net.Uri>) -> Unit) {
        pendingMultiPickerCallback = onResult
        armPickerReturn()
        pickVisualMediaLauncher.launch(
            androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts
                    .PickVisualMedia.ImageAndVideo,
            ),
        )
    }

    @Volatile
    private var pendingCameraCallback: ((android.net.Uri) -> Unit)? = null
    @Volatile
    private var pendingCameraUri: android.net.Uri? = null

    /** TakePicture writes to a Uri we provide and gives us a bool on success. */
    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val cb = pendingCameraCallback
        val uri = pendingCameraUri
        pendingCameraCallback = null
        pendingCameraUri = null
        if (success && uri != null && cb != null) cb(uri)
    }

    /**
     * Open the system camera, capture a still, and call [onResult] with
     * a content:// URI that the picker callback can hand to
     * Attachments.import.
     *
     * If CAMERA permission isn't yet granted at runtime, requests it
     * first — without that grant Android silently rejects
     * ACTION_IMAGE_CAPTURE (the manifest declaration alone isn't enough).
     */
    fun takePhoto(onResult: (android.net.Uri) -> Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingCameraOnGrant = onResult
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        launchCamera(onResult)
    }

    private fun launchCamera(onResult: (android.net.Uri) -> Unit) {
        val dst = java.io.File(
            app.aether.aegis.util.Attachments.attachmentsDir(this),
            "camera-${java.util.UUID.randomUUID()}.jpg",
        )
        // Pre-create the empty file so the camera app definitely sees
        // a writable target (some OEM camera apps reject URIs whose
        // backing file doesn't exist yet).
        runCatching { dst.createNewFile() }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", dst,
        )
        pendingCameraCallback = onResult
        pendingCameraUri = uri
        armPickerReturn()
        runCatching { takePhotoLauncher.launch(uri) }
            .onFailure {
                android.util.Log.e("MainActivity", "takePhoto launch failed", it)
                pendingCameraCallback = null
                pendingCameraUri = null
            }
    }

    @Volatile
    private var pendingCameraOnGrant: ((android.net.Uri) -> Unit)? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val cb = pendingCameraOnGrant
        pendingCameraOnGrant = null
        if (granted && cb != null) launchCamera(cb)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Push the PiP flag into CallStore so the call screen can hide
        // its TopAppBar + control bar while the call is windowed.
        app.aether.aegis.call.CallStore.setInPip(isInPictureInPictureMode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        // Compose-side AegisSplashScreen takes over for the brand hold —
        // see start destination below. Keeping the system splash short
        // lets the Compose splash render almost immediately, which means
        // the EB-Garamond AEGIS wordmark is what the user actually sees
        // for the ~1.5 s hold.
        super.onCreate(savedInstanceState)
        // Force light status-bar icons against the cosmic background.
        // SystemBarStyle.dark() means "the scrim is dark, so render the
        // icons light". TRANSPARENT keeps the starfield visible behind.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        requestRuntimePermissions()

        // Screen privacy: apply the persisted "block screenshots"
        // (FLAG_SECURE) state before any content renders, so a cold
        // start that lands straight in a chat is covered from frame
        // one. The live collector in setContent keeps it in sync when
        // the user flips the toggle.
        if (app.aether.aegis.prefs.ScreenSecurityPrefs(this).blockScreenshots) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Always start the service. If the user previously paused it,
        // ProtocolService.onCreate reads protectionEnabled and comes up
        // in the paused state with a "Resume" notification — we never
        // want a fully-silent app after a force-stop, otherwise the
        // family-safety promise is invisible.
        runCatching { startForegroundService(Intent(this, ProtocolService::class.java)) }
            .onFailure { android.util.Log.e("MainActivity", "ProtocolService start failed", it) }

        val widgetTargetChat = intent.extras
            ?.keySet()
            ?.firstOrNull { it == "open_chat" || it.endsWith(".open_chat") }
            ?.let { intent.extras?.getString(it) }
        val sosDashboardPeer = intent.extras?.getString("open_sos_dashboard")
        val openSentinelInbox = intent.extras?.getBoolean("open_sentinel_inbox", false) == true

        val incomingCall = intent.extras?.takeIf {
            it.containsKey("incoming_call_peer")
        }?.let {
            Triple(
                it.getString("incoming_call_peer").orEmpty(),
                it.getString("incoming_call_name").orEmpty(),
                it.getBoolean("incoming_call_video", false),
            )
        }
        // Consume the extras IMMEDIATELY so a later activity recreation
        // (e.g. Aegis PIN unlock recomposing AegisMainScreen) doesn't
        // re-fire the call. Without this, every unlock after the user
        // tapped an Answer notification would silently dial the same
        // peer again — user-visible symptom was "PIN unlock instantly
        // calls my other phone." Same fix for the other intent extras
        // we read above so they don't replay either.
        intent.removeExtra("incoming_call_peer")
        intent.removeExtra("incoming_call_name")
        intent.removeExtra("incoming_call_video")
        intent.removeExtra("open_sos_dashboard")
        intent.removeExtra("open_sentinel_inbox")
        intent.extras?.keySet()?.filter {
            it == "open_chat" || it.endsWith(".open_chat")
        }?.forEach { intent.removeExtra(it) }

        setContent {
            // --- Graphics profile + battery desaturation curve ---
            // The user's preferred profile, capped by the Voyager-published
            // ceiling, drives the window refresh-rate hint. The orthogonal
            // battery-level saturation curve fades the entire app to
            // grayscale below 20 % battery (Witcher-style "low health"
            // warning). Both react live via StateFlow.
            val ctx = LocalContext.current
            val gfxPrefs = remember(ctx) { app.aether.aegis.ui.GraphicsPrefs(ctx) }
            val preferred by gfxPrefs.preferredFlow.collectAsState()
            val ceiling by AegisApp.instance.powerBudget
                .ceilingGraphicsProfile.collectAsState()
            val effectiveProfile = preferred.cappedBy(ceiling)
            val batteryLevel by AegisApp.instance.powerBudget.level.collectAsState()
            val saturation = app.aether.aegis.ui.saturationForBattery(batteryLevel)

            LaunchedEffect(effectiveProfile) {
                val params = window.attributes
                params.preferredRefreshRate = when (effectiveProfile) {
                    app.aether.aegis.ui.GraphicsProfile.Performance -> 0f
                    app.aether.aegis.ui.GraphicsProfile.Balanced    -> 60f
                    app.aether.aegis.ui.GraphicsProfile.PowerSaver  -> 30f
                }
                window.attributes = params
            }

            // Push effective profile into a process-wide holder so the
            // Starfield renderer can pick up PowerSaver and switch to
            // solid black + skip overlays. (StateFlow lives in
            // GraphicsProfile.kt — see EffectiveGraphicsProfile.)
            LaunchedEffect(effectiveProfile) {
                app.aether.aegis.ui.EffectiveGraphicsProfile.set(effectiveProfile)
            }

            // Screen privacy — block screenshots / screen recording /
            // Recents preview when the user enables it. Collected as a
            // StateFlow so flipping the Settings toggle adds/clears
            // FLAG_SECURE on this window live, no restart. (onCreate
            // applies the persisted value for the cold-start frame;
            // this keeps it in sync thereafter.)
            val screenSecPrefs = remember(ctx) {
                app.aether.aegis.prefs.ScreenSecurityPrefs(ctx)
            }
            val blockScreenshots by screenSecPrefs.blockFlow.collectAsState()
            LaunchedEffect(blockScreenshots) {
                if (blockScreenshots) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            AegisTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (saturation >= 0.999f) Modifier
                            else if (Build.VERSION.SDK_INT >= 33) {
                                // "Blood stays" selective desaturation —
                                // Witcher / Sin City visual language. AGSL
                                // RuntimeShader does per-pixel HSL hue
                                // check in a single GPU pass. Red hue stays
                                // at full saturation, everything else fades
                                // to luminance gray.
                                //
                                // The RenderEffect is remember()-keyed on
                                // saturation so a fresh RenderEffect
                                // instance materialises whenever the value
                                // changes (battery tick or preview toggle).
                                // Without the key, the layer's render-node
                                // caches the old effect and ignores the
                                // shader's mutated uniforms — that's why
                                // the preview button looked like a no-op
                                // on 175 (saturation flag flipped, shader
                                // uniform updated, but layer kept showing
                                // the old effect).
                                //
                                // Modifier.graphicsLayer() with explicit
                                // parameters (renderEffect=, compositingStrategy=)
                                // rather than the block form, so Compose
                                // sees the renderEffect param change and
                                // invalidates the layer on every saturation
                                // crossing.
                                val shader = remember { android.graphics.RuntimeShader(BLOOD_STAYS_AGSL) }
                                val effect = remember(saturation) {
                                    shader.setFloatUniform("saturation", saturation)
                                    android.graphics.RenderEffect
                                        .createRuntimeShaderEffect(shader, "content")
                                        .asComposeRenderEffect()
                                }
                                Modifier.graphicsLayer(
                                    renderEffect = effect,
                                    compositingStrategy =
                                        androidx.compose.ui.graphics.CompositingStrategy.Offscreen,
                                )
                            } else {
                                // API 29-32 fallback: simple single-pass
                                // desaturation. No selective red (requires
                                // per-pixel conditional = shader), but no
                                // double-draw darkening either.
                                val matrix = ColorMatrix()
                                    .also { it.setToSaturation(saturation) }
                                val filter = ColorFilter.colorMatrix(matrix)
                                val paint = Paint().also { it.colorFilter = filter }
                                Modifier.drawWithContent {
                                    drawIntoCanvas { canvas ->
                                        val r = Rect(Offset.Zero, size)
                                        canvas.saveLayer(r, paint)
                                        drawContent()
                                        canvas.restore()
                                    }
                                }
                            }
                        ),
                ) {
                    StarfieldBackground {
                        val locked = AegisApp.instance.lockState.isLocked
                        if (locked) {
                            LockScreen()
                        } else {
                            run {
                                // Manual lock curtain: two-finger
                                // drag down locks from anywhere. Disabled while a
                                // sos is active — the sos screen owns the
                                // two-finger gesture (brightness) and locking
                                // mid-broadcast is pointless.
                                val sosActive by AegisApp.instance.sosHandler.state.collectAsState()
                                app.aether.aegis.ui.components.LockCurtain(
                                    enabled = sosActive == null,
                                    onLock = { AegisApp.instance.lockState.lockManual() },
                                ) {
                                    AegisMainScreen(
                                        initialChatPeer = widgetTargetChat,
                                        incomingCall = incomingCall,
                                        initialSOSDashboardPeer = sosDashboardPeer,
                                        initialOpenSentinelInbox = openSentinelInbox,
                                    )
                                    // Launch-time "update available — install now?"
                                    // prompt. Only renders when unlocked (here, not
                                    // over the lock screen) and an update is on offer.
                                    app.aether.aegis.ui.components.UpdateAvailableDialog()
                                }
                            }
                        }
                    }
                    // Debug alignment grid — LAST child of the root Box so
                    // it overlays everything (header included), letting the
                    // centred lock icon etc. be checked against the centre
                    // lines. No-op unless a grid layer is selected in
                    // Diagnostics → Debug overlay.
                    app.aether.aegis.ui.components.DebugGridOverlay()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { AegisApp.instance.lockState.onBackgrounded() }
    }

    override fun onStart() {
        super.onStart()
        runCatching { AegisApp.instance.lockState.onForegrounded() }
        // Status freshness: nudge the protocol manager so the in-app
        // status dot reflects reality the moment the user comes back
        // from background, instead of waiting up to 30 s for the next
        // health tick. If a transport actually died during background
        // doze, this also triggers a restart attempt immediately.
        runCatching { AegisApp.instance.protocolManager.nudgeRecompute() }
        // Canary check-in: opening the app = "I'm still alive, don't
        // fire the dead-man's switch". Worker stays inert as long as
        // this stamp is fresh.
        runCatching {
            app.aether.aegis.canary.CanaryStore(applicationContext).recordCheckIn()
        }
        // Re-share our earned badge set with trusted contacts so their
        // "trusted contact" card populates even for badges we earned
        // before they were trusted (or before the [aegis:badges] routing
        // fix). No-op when we have none; bulletproof.
        runCatching { app.aether.aegis.achievements.Achievements.resyncToTrusted() }
        // Rescue a stuck Installing state. The non-Device-Owner
        // install path goes through the system installer Activity
        // and gives us no callback when the user cancels — without
        // this nudge the UI sits on "Installing…" forever. The
        // sweep is cheap (a couple of File / PackageManager hits)
        // and self-contained: clears nothing when there's no
        // pending install.
        runCatching {
            app.aether.aegis.update.UpdateState.reconcileFromDisk(applicationContext)
        }
        // Wipe any vault decryption temps from a previous foreground
        // session — anything currently being viewed will re-decrypt
        // on demand. Doesn't touch the encrypted originals.
        runCatching {
            app.aether.aegis.vault.VaultAttachmentCrypto.clearDecryptCache(applicationContext)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-entry path: a message / call notification arrived while we
        // were already running. Push the intent extras through a state
        // flow that the Compose tree observes — much safer than recreate()
        // (which tears down the activity and can crash on fragile state).
        setIntent(intent)
        intent.extras?.let { extras ->
            val openChat = extras.keySet().firstOrNull {
                it == "open_chat" || it.endsWith(".open_chat")
            }?.let { extras.getString(it) }
            if (!openChat.isNullOrBlank()) {
                pendingIntentTarget.tryEmit(IntentTarget.Chat(openChat))
            }
            if (extras.containsKey("incoming_call_peer")) {
                pendingIntentTarget.tryEmit(
                    IntentTarget.Call(
                        peer = extras.getString("incoming_call_peer").orEmpty(),
                        name = extras.getString("incoming_call_name").orEmpty(),
                        video = extras.getBoolean("incoming_call_video", false),
                    )
                )
            }
            val openSOSDash = extras.keySet().firstOrNull {
                it == "open_sos_dashboard" || it.endsWith(".open_sos_dashboard")
            }?.let { extras.getString(it) }
            if (!openSOSDash.isNullOrBlank()) {
                // Was: only the cold-start path handled the sos
                // dashboard route, so tapping the notification while
                // Aegis was already foregrounded did nothing — the
                // user just saw the banner sit there. Push it through
                // the same hot flow as chat / call so the Compose
                // tree picks it up on the next collect.
                pendingIntentTarget.tryEmit(IntentTarget.SOSDashboard(openSOSDash))
            }
        }
    }

    sealed class IntentTarget {
        data class Chat(val peerKey: String) : IntentTarget()
        data class Call(val peer: String, val name: String, val video: Boolean) : IntentTarget()
        /** Tap on a sos notification — route to the dashboard
         *  for that peer (map + audio + camera-feed + Accept call /
         *  PTT). */
        data class SOSDashboard(val peerKey: String) : IntentTarget()
    }

    companion object {
        /** Hot flow surfaced from onNewIntent so the Compose tree can react
         *  without an activity recreate. */
        val pendingIntentTarget = kotlinx.coroutines.flow.MutableSharedFlow<IntentTarget>(
            replay = 0,
            extraBufferCapacity = 4,
        )

        /** AGSL shader for "blood stays" selective desaturation (API 33+).
         *  Converts each pixel RGB→HSL hue check. Red hue (0-40° / 320-360°)
         *  keeps full saturation. Everything else follows the global
         *  saturation curve. One GPU pass, no blending artifacts. */
        private const val BLOOD_STAYS_AGSL = """
            uniform float saturation;
            uniform shader content;

            half4 main(float2 coord) {
                half4 c = content.eval(coord);

                float cmax = max(c.r, max(c.g, c.b));
                float cmin = min(c.r, min(c.g, c.b));
                float delta = cmax - cmin;

                float hue = 0.0;
                if (delta > 0.001) {
                    if (cmax == c.r)      hue = mod((c.g - c.b) / delta, 6.0) * 60.0;
                    else if (cmax == c.g) hue = ((c.b - c.r) / delta + 2.0) * 60.0;
                    else                  hue = ((c.r - c.g) / delta + 4.0) * 60.0;
                }
                if (hue < 0.0) hue += 360.0;

                float isRed = smoothstep(40.0, 20.0, hue)
                            + smoothstep(320.0, 340.0, hue);
                isRed = clamp(isRed, 0.0, 1.0);

                float luma = dot(c.rgb, half3(0.213, 0.715, 0.072));
                half3 gray = half3(luma, luma, luma);

                float pixelSat = mix(saturation, 1.0, isRed);
                half3 result = mix(gray, c.rgb, pixelSat);

                return half4(result, c.a);
            }
        """
    }

    private fun requestRuntimePermissions() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            // SimSwapMonitor needs TelephonyManager — without this
            // runtime grant simOperator/networkOperatorName return
            // empty strings and we never observe a swap.
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        runtimePermissionsLauncher.launch(perms.toTypedArray())
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AegisMainScreen(
    initialChatPeer: String? = null,
    incomingCall: Triple<String, String, Boolean>? = null,
    initialSOSDashboardPeer: String? = null,
    initialOpenSentinelInbox: Boolean = false,
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val startCall = app.aether.aegis.call.rememberCallStarter(navController)
    // Routes that show the bottom navigation + the Aegis top bar.
    // Sub-routes (chat/x, profile, contact/x, group/x, contact/add, call/x)
    // bring their own TopAppBar inside their content — we hide the Scaffold's
    // chrome on those so we don't stack two bars on top of each other.
    // LunaGlass tabs: 5 with SOS centered + prominent. Notes is now
    // a top-bar icon on Chats (the dominant entry path) rather than a
    // nav tab; Map stays as a tab so the four nav slots around SOS
    // are: Chats / Status / [SOS] / Map / Settings.
    val tabRoutes = setOf("chats", "map", "sos", "security", "settings")
    val isTabRoute = currentRoute in tabRoutes

    // These launch-intent navigations must fire EXACTLY ONCE, on the launch
    // that carried the extra — NOT on every recreate. A config change (locale
    // switch) recreates the activity with the same intent still attached, so
    // without a one-shot guard the effect re-ran and called navigate() before
    // the freshly-rebuilt NavHost had set its graph → "Navigation graph has
    // not been set" crash. rememberSaveable survives the recreate, so once
    // consumed it stays consumed. runCatching is belt-and-suspenders against
    // any first-frame race.
    var intentNavConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialSOSDashboardPeer) {
        if (!intentNavConsumed && !initialSOSDashboardPeer.isNullOrBlank()) {
            intentNavConsumed = true
            val encoded = java.net.URLEncoder.encode(initialSOSDashboardPeer, "UTF-8")
            runCatching { navController.navigate("sos/dashboard/$encoded") }
        }
    }
    LaunchedEffect(initialOpenSentinelInbox) {
        if (!intentNavConsumed && initialOpenSentinelInbox) {
            intentNavConsumed = true
            runCatching { navController.navigate("settings/sentinel/inbox") }
        }
    }
    LaunchedEffect(initialChatPeer) {
        if (!intentNavConsumed && !initialChatPeer.isNullOrBlank()) {
            intentNavConsumed = true
            // Group-message taps carry an open_chat extra of the form
            // "group:<uuid>" — route those to the group chat screen,
            // not the 1:1 chat which has no such peer.
            runCatching {
                if (initialChatPeer.startsWith("group:")) {
                    val gid = initialChatPeer.removePrefix("group:")
                    navController.navigate("group/$gid")
                } else {
                    val encoded = java.net.URLEncoder.encode(initialChatPeer, "UTF-8")
                    navController.navigate("chat/$encoded")
                }
            }
        }
    }
    LaunchedEffect(incomingCall) {
        incomingCall?.let { (peer, name, video) ->
            // Cold launch FROM a call notification runs this effect before
            // the NavHost (further down in this Scaffold) has composed and
            // set the controller's graph — navigate() then throws
            // "Navigation graph has not been set" and the whole app crashes
            // on the very call it was trying to answer (user report
            // 2026-06-15). The sibling intent-nav effects above sidestep
            // this with a one-shot guard + runCatching; an incoming call
            // can't just be swallowed (the user is waiting on it), so we
            // instead WAIT for the graph to exist, then start the call.
            // navController.graph getter throws until the graph is set, so
            // a failing read is our "not ready yet" signal. ~1 s ceiling so
            // a genuinely broken host can't hang the effect forever.
            var tries = 0
            while (runCatching { navController.graph }.isFailure && tries < 50) {
                kotlinx.coroutines.delay(20)
                tries++
            }
            startCall(peer, name, video)
        }
    }
    // Observe onNewIntent-pushed targets so notification taps while the
    // app is already running route correctly without recreate().
    LaunchedEffect(Unit) {
        MainActivity.pendingIntentTarget.collect { target ->
            when (target) {
                is MainActivity.IntentTarget.Chat -> {
                    if (target.peerKey.startsWith("group:")) {
                        val gid = target.peerKey.removePrefix("group:")
                        navController.navigate("group/$gid")
                    } else {
                        val encoded = java.net.URLEncoder.encode(target.peerKey, "UTF-8")
                        navController.navigate("chat/$encoded")
                    }
                }
                is MainActivity.IntentTarget.Call -> {
                    startCall(target.peer, target.name, target.video)
                }
                is MainActivity.IntentTarget.SOSDashboard -> {
                    val encoded = java.net.URLEncoder.encode(target.peerKey, "UTF-8")
                    navController.navigate("sos/dashboard/$encoded")
                }
            }
        }
    }
    // First-launch onboarding gate moved into AegisSplashScreen — after the
    // 1.5 s brand hold it picks either "chats" or "profile/onboard" based
    // on profileStore.onboarded.

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // Explicit contentColor — Scaffold's default `contentColorFor(Transparent)`
        // returns Unspecified, which propagates as black, making text invisible
        // on the dark cosmic background.
        contentColor = MaterialTheme.colorScheme.onBackground,
        // Zero content insets on purpose. The chrome handles its OWN
        // system-bar insets — AegisHeader does statusBars, AegisBottomNav
        // does navigationBars — so if this Scaffold ALSO inset the content
        // for system bars, sub-routes (which have no header here, but bring
        // their own Scaffold + TopAppBar) got the status bar reserved
        // TWICE: once as this Scaffold's content padding, once by their own
        // TopAppBar. That double inset was the dead band above every
        // sub-screen title. Letting each bar own its inset removes it with
        // no effect on the tab routes.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isTabRoute) {
                app.aether.aegis.ui.components.AegisHeader(navController, currentRoute)
            }
        },
        bottomBar = {
            if (isTabRoute) {
                app.aether.aegis.ui.components.AegisBottomNav(navController, currentRoute)
            }
        }
    ) { innerPadding ->
        // Persistent call-error surface — when CallManager parks a
        // failure on lastError (e.g. "couldn't start audio source"
        // with the full audio-state snapshot) show it as an in-app
        // dialog with Copy. Beats toast cutoff and survives the
        // CallScreen auto-pop.
        val callError by app.aether.aegis.call.CallManager.lastError.collectAsState()
        callError?.let { msg ->
            val clipboardCtx = androidx.compose.ui.platform.LocalContext.current
            AlertDialog(
                onDismissRequest = { app.aether.aegis.call.CallManager.clearLastError() },
                title = { Text("Call failed") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        Text(
                            msg,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val cm = clipboardCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("call-error", msg))
                        app.aether.aegis.call.CallManager.clearLastError()
                    }) { Text("Copy") }
                },
                dismissButton = {
                    TextButton(onClick = { app.aether.aegis.call.CallManager.clearLastError() }) {
                        Text("Dismiss")
                    }
                },
            )
        }
        // Swipe horizontally to switch tabs. Only active when the
        // current destination is one of the five top-level tabs AND
        // the tab isn't "map" (the MapView owns its own horizontal
        // pan and a parent swipe would fight it). The chip rows
        // inside the chat list / etc. are LazyRows that consume the
        // horizontal drag themselves, so the parent never sees those
        // touches — no conflict there. Threshold is generous (140 dp)
        // so a confident flick switches but a wandering thumb on a
        // vertical scroll doesn't.
        val mainCtx = androidx.compose.ui.platform.LocalContext.current
        val tabOrderPrefs = remember(mainCtx) { app.aether.aegis.ui.TabOrderPrefs(mainCtx) }
        val nonSOS by tabOrderPrefs.nonSOSOrder.collectAsState()
        val fullTabs = remember(nonSOS) { tabOrderPrefs.fullOrder() }
        val mainDensity = androidx.compose.ui.platform.LocalDensity.current
        val swipeThresholdPx = with(mainDensity) { 140.dp.toPx() }
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .let { mod ->
                    val swipeable = isTabRoute && currentRoute != "map"
                    if (!swipeable) mod
                    else mod.pointerInput(currentRoute, fullTabs) {
                        var accumulated = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { accumulated = 0f },
                            onDragEnd = {
                                val idx = fullTabs.indexOf(currentRoute)
                                if (idx < 0) return@detectHorizontalDragGestures
                                val target = when {
                                    accumulated <= -swipeThresholdPx && idx < fullTabs.lastIndex ->
                                        fullTabs[idx + 1]
                                    accumulated >= swipeThresholdPx && idx > 0 ->
                                        fullTabs[idx - 1]
                                    else -> null
                                }
                                if (target != null) {
                                    navController.navigate(target) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            onDragCancel = { accumulated = 0f },
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                accumulated += delta
                            },
                        )
                    }
                },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Profile indicator strip.
                // 2 dp coloured bar at the top of every screen so
                // the user can tell at a glance WHICH profile is
                // active. Only visible when >1 profile exists; the
                // strip is empty for single-profile users so it
                // doesn't reveal multi-profile capability to a
                // shoulder-surfer who shouldn't know about it.
                app.aether.aegis.ui.components.ProfileIndicatorStrip()
                // Floating call island pinned to the
                // top whenever a call is active and the user has
                // navigated away from the call screen. Tap returns.
                app.aether.aegis.ui.components.CallIsland(navController)
                val mainCtx = androidx.compose.ui.platform.LocalContext.current
                val startDest = remember(mainCtx) {
                    if (app.aether.aegis.ui.screens.isFirstRun(mainCtx))
                        "first_run" else "splash"
                }
                // Single source of truth for the gap between the shared
                // AEGIS header and the first row of tab content. Each tab
                // screen renders its content flush (no leading spacer of
                // its own) so this 8dp is the ONLY top inset — that's what
                // makes every tab's content start at the exact same Y.
                // Sub-routes (chat/x, settings/x …) bring their own
                // TopAppBar and are excluded.
                if (isTabRoute) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                NavHost(
                    navController = navController,
                    startDestination = startDest,
                    modifier = Modifier.weight(1f, fill = true),
                ) {
                    composable("first_run") {
                        app.aether.aegis.ui.screens.FirstRunScreen(navController)
                    }
                    composable("splash") { AegisSplashScreen(navController) }
                    // Onboarding tutorial.
                    // Auto-shown once on a fresh install via
                    // firstRunNextDestination; also reachable from
                    // Help → "Replay Tutorial" with replay=true so
                    // exiting pops back instead of advancing into
                    // onboarding.
                    composable(
                        "tutorial?replay={replay}",
                        arguments = listOf(
                            androidx.navigation.navArgument("replay") {
                                type = androidx.navigation.NavType.BoolType
                                defaultValue = false
                            },
                        ),
                    ) { entry ->
                        val replay = entry.arguments?.getBoolean("replay") == true
                        app.aether.aegis.ui.screens.TutorialScreen(navController, replay = replay)
                    }
            composable("chats") { ChatListScreen(navController) }
            composable("chat/{memberId}") { backStackEntry ->
                val raw = backStackEntry.arguments?.getString("memberId") ?: ""
                val memberId = java.net.URLDecoder.decode(raw, "UTF-8")
                ChatScreen(memberId, navController)
            }
            composable("map") { MapScreen(navController) }
            composable("sos") { SOSScreen() }
            composable("sos/dashboard/{peerKey}") { entry ->
                val raw = entry.arguments?.getString("peerKey").orEmpty()
                val pk = java.net.URLDecoder.decode(raw, "UTF-8")
                SOSAdapter(pk)
            }
            // Unified DeviceControlScreen route — entry point for
            // the contact-detail "active remote session" auto-nav.
            // The sos side is reached via sos/dashboard/{peerKey}
            // above (which always uses SOSAdapter post-legacy
            // reap). The previous device-control/sos alias was
            // dead-weight redundant — both routes wrapped the same
            // SOSAdapter — and was removed in 2026.06.261 along
            // with the settings/lunaglass redirect alias.
            composable("device-control/remote/{peerKey}") { entry ->
                val raw = entry.arguments?.getString("peerKey").orEmpty()
                val pk = java.net.URLDecoder.decode(raw, "UTF-8")
                RemoteAdapter(pk)
            }
            composable("security") { SecurityScreen(navController) }
            composable("status/device/{peerKey}") { entry ->
                val raw = entry.arguments?.getString("peerKey").orEmpty()
                val pk = java.net.URLDecoder.decode(raw, "UTF-8")
                DeviceStatusScreen(pk, navController)
            }
            // Per-peer Remote Access surface (locate / siren / wipe).
            // Lives under the Radar branch — entry point is on
            // DeviceStatusScreen, not ContactDetailScreen, since
            // these are operational actions tied to the device, not
            // relationship-management toggles tied to the contact.
            composable("remote/{peerKey}") { entry ->
                val raw = entry.arguments?.getString("peerKey").orEmpty()
                val pk = java.net.URLDecoder.decode(raw, "UTF-8")
                RemoteAccessScreen(pk, navController)
            }
            composable("notes") {
                // Vault PIN gate. When a vault
                // PIN is set and the in-memory session is locked,
                // VaultGate renders a PIN prompt instead of the vault
                // contents. With no PIN set, the gate short-circuits
                // and renders the vault directly.
                VaultGate(navController) { SecureNotesScreen(navController) }
            }
            composable("settings/vaultpin") { VaultPinSettingsScreen(navController) }
            composable("settings/profiles") { ProfilesSettingsScreen(navController) }
            composable("diagnostics") { DiagnosticsScreen(navController) }
            composable("developer-tools") { DeveloperToolsScreen(navController) }
            composable("group/{groupId}/members") { entry ->
                val gid = entry.arguments?.getString("groupId").orEmpty()
                GroupMembersScreen(groupId = gid, navController = navController)
            }
            composable("story/compose") { StoryComposerScreen(navController) }
            composable("story/{id}") { entry ->
                val raw = entry.arguments?.getString("id").orEmpty()
                val id = java.net.URLDecoder.decode(raw, "UTF-8")
                StoryViewerScreen(id, navController)
            }
            composable("settings") { SettingsScreen(navController) }
            composable("settings/lock") { LockSettingsScreen(navController) }
            composable("settings/deviceadmin") { DeviceAdminScreen(navController) }
            composable("settings/protectedmode") { ProtectedModeScreen(navController) }
            composable("settings/canary") { CanarySettingsScreen(navController) }
            composable("settings/simswap") { SimSwapSettingsScreen(navController) }
            composable("settings/geofence") { GeofenceSettingsScreen(navController) }
            composable("settings/mugshot") { MugshotSettingsScreen(navController) }
            composable("settings/quiet") { QuietHoursSettingsScreen(navController) }
            composable("settings/hold") { HoldToExecuteSettingsScreen(navController) }
            composable("settings/updates") { UpdateSettingsScreen(navController) }
            composable("settings/capabilities") { CapabilitiesScreen(navController) }
            composable("settings/relays") { RelaySettingsScreen(navController) }
            composable("settings/nav") { TabOrderSettingsScreen(navController) }
            composable("settings/chatdefaults") { ChatDefaultsSettingsScreen(navController) }
            composable("settings/attachments") { AttachmentSettingsScreen(navController) }
            composable("settings/invites") { InvitationExpirySettingsScreen(navController) }
            composable("settings/crashdetection") { CrashDetectionSettingsScreen(navController) }
            composable("settings/experimental") { ExperimentalSettingsScreen(navController) }
            composable("settings/sonar") { SonarScreen(navController) }
            composable("settings/sentinel/inbox") { SentinelInboxScreen(navController) }
            composable("settings/sentinel/log") { SentinelLogScreen(navController) }
            composable("settings/backup") { BackupSettingsScreen(navController) }
            composable("settings/origins") { OriginsScreen(navController) }
            composable("help") { HelpScreen(navController) }
            composable("help/doc/{filename}") { entry ->
                val raw = entry.arguments?.getString("filename").orEmpty()
                DocViewerScreen(filename = raw, navController = navController)
            }
            composable(
                "language?first={first}",
                arguments = listOf(
                    androidx.navigation.navArgument("first") {
                        type = androidx.navigation.NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                val first = entry.arguments?.getBoolean("first") == true
                LanguagePickerScreen(
                    navController = navController,
                    isFirstRun = first,
                    onPicked = if (first) {
                        {
                            // Route through the shared first-run
                            // decision so the onboarding tutorial
                            // gets its turn
                            // after language selection on a fresh
                            // install — splash and picker must agree.
                            val next = app.aether.aegis.ui.screens
                                .firstRunNextDestination(mainCtx)
                            navController.navigate(next) {
                                popUpTo("language?first=true") { inclusive = true }
                            }
                        }
                    } else null,
                )
            }
            composable("settings/calls") { CallSettingsScreen(navController) }
            composable("settings/graphics") { GraphicsSettingsScreen(navController) }
            // (settings/lunaglass redirect alias removed in
            // 2026.06.261; the route was a redirect-only shim
            // for stale deep links / bookmarks from before the
            // Graphics tab replaced the LunaGlass settings
            // screen. Nothing in-app navigates to it anymore.)
            composable("contact/add") { AddContactScreen(navController) }
            composable("contact/add/invite") {
                AddContactScreen(navController, start = app.aether.aegis.ui.screens.Mode.Invite)
            }
            composable("contact/add/accept") {
                AddContactScreen(navController, start = app.aether.aegis.ui.screens.Mode.Accept)
            }
            composable("profile") { ProfileScreen(navController, isOnboarding = false) }
            composable("profile/onboard") { ProfileScreen(navController, isOnboarding = true) }
            composable("contact/{peerKey}") { entry ->
                val raw = entry.arguments?.getString("peerKey").orEmpty()
                val pk = java.net.URLDecoder.decode(raw, "UTF-8")
                ContactDetailScreen(pk, navController)
            }
            composable("verify/{peerKey}") { entry ->
                val raw = entry.arguments?.getString("peerKey").orEmpty()
                val pk = java.net.URLDecoder.decode(raw, "UTF-8")
                VerifyContactScreen(pk, navController)
            }
            composable("group/new") { GroupCreateScreen(navController) }
            composable("group/{groupId}") { entry ->
                val gid = entry.arguments?.getString("groupId").orEmpty()
                GroupChatScreen(gid, navController)
            }
            composable("call/{peer}/{name}/{kind}") { entry ->
                val peer = java.net.URLDecoder.decode(entry.arguments?.getString("peer").orEmpty(), "UTF-8")
                val name = java.net.URLDecoder.decode(entry.arguments?.getString("name").orEmpty(), "UTF-8")
                val video = entry.arguments?.getString("kind") == "video"
                app.aether.aegis.call.CallScreen(peer, name, video, navController)
            }
            composable("note/new") {
                NoteEditorScreen(noteId = null, navController = navController)
            }
            composable("note/{id}") { entry ->
                NoteEditorScreen(
                    noteId = entry.arguments?.getString("id"),
                    navController = navController,
                )
            }
            composable("photo/{path}?name={name}") { entry ->
                val rawPath = entry.arguments?.getString("path").orEmpty()
                val path = java.net.URLDecoder.decode(rawPath, "UTF-8")
                val rawName = entry.arguments?.getString("name").orEmpty()
                val name = if (rawName.isBlank()) null
                else java.net.URLDecoder.decode(rawName, "UTF-8")
                PhotoViewerScreen(path, name, navController)
            }
                }  // close NavHost
            }  // close Column
            // Notes + help reachable from every screen.
            // Rendered as a sibling of the Column inside the same Box
            // so it floats on top without stealing layout space; the
            // overlay itself uses Modifier.align(TopEnd) inside its
            // own Box and is hidden on focus surfaces (sos / call /
            // lock / splash).
            app.aether.aegis.ui.components.GlobalActionsOverlay(navController)
        }  // close outer Box
    }
}

