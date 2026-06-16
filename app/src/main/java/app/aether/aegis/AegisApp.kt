package app.aether.aegis

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.aether.aegis.core.MessageType
import app.aether.aegis.core.SOSHandler
import app.aether.aegis.core.ProtocolManager
import app.aether.aegis.data.AegisDatabase
import app.aether.aegis.data.Repository
import app.aether.aegis.identity.Identity
import app.aether.aegis.identity.IdentityStore
import app.aether.aegis.profile.ProfileRegistry
import app.aether.aegis.profile.ProfileRoot
import app.aether.aegis.profile.ProfileStore
import app.aether.aegis.simplex.SimpleXTransport
import app.aether.aegis.transport.InboundMessage
import app.aether.aegis.transport.Transport
import app.aether.aegis.update.BootHealthMonitor
import app.aether.aegis.update.UpdateCheckWorker
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat

class AegisApp : Application() {

    lateinit var protocolManager: ProtocolManager
        private set
    lateinit var identity: Identity
        private set
    /** Active profile root — all identity-bound storage (DB, identity
     *  key, attachments, vault, avatar) lives under here. Phase 1
     *  pins this to [ProfileRoot.DEFAULT_PROFILE_ID]; Phase 2 will
     *  rebind on profile switch. */
    lateinit var profileRoot: ProfileRoot
        private set
    lateinit var repository: Repository
        private set
    lateinit var transports: List<Transport>
        private set
    lateinit var sosHandler: SOSHandler
        private set
    lateinit var profileStore: ProfileStore
        private set
    lateinit var lockState: app.aether.aegis.lock.LockState
        private set
    lateinit var powerBudget: app.aether.aegis.power.PowerBudget
        private set
    lateinit var simSwapMonitor: app.aether.aegis.simswap.SimSwapMonitor
        private set
    /** Target-side gate for the remote-access surface — PIN failure
     *  counter, revoked-senders set, in-memory session map. The
     *  sender-side counterpart is the object [RemoteAccessSession],
     *  which lives in process memory only. */
    lateinit var remoteAccessGate: app.aether.aegis.remote.RemoteAccessGate
        private set

    /** Power-button (×4) sos receiver, held so the trust-container
     *  gate (Phase 1) can register it when the
     *  first sos-eligible contact appears and unregister it when the
     *  last one goes. Null while the sos module is stood down. */
    private var powerSOSReceiver: app.aether.aegis.core.PowerButtonSOSReceiver? = null

    /** Master "shield on/off" toggle controlled by the notification's
     *  Turn off button and the in-app re-enable banner. When false,
     *  MainActivity refuses to start ProtocolService on launch. */
    var protectionEnabled: Boolean
        get() = getSharedPreferences("aegis_state", MODE_PRIVATE)
            .getBoolean("protection_enabled", true)
        set(value) {
            getSharedPreferences("aegis_state", MODE_PRIVATE)
                .edit().putBoolean("protection_enabled", value).apply()
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Apply persisted language BEFORE anything else creates a
        // Compose root, so the first frame already has the right
        // locale loaded — avoids a flash of English on cold start.
        runCatching { app.aether.aegis.i18n.LanguagePrefs(this).applyOnBoot() }
            .onFailure { reportStartupError("LanguagePrefs.applyOnBoot", it) }
        // Health monitor first — must install the crash handler before
        // any other code can blow up. Itself wrapped because broken
        // initialisation here is the bug we'd most regret.
        runCatching { BootHealthMonitor(this).onAppCreate() }
            .onFailure { reportStartupError("BootHealthMonitor", it) }
        // OSMDroid global config — must happen BEFORE the MapScreen
        // composable creates a MapView, otherwise tile requests go out
        // with no user agent and the OSM tile servers reject them.
        runCatching {
            val config = org.osmdroid.config.Configuration.getInstance()
            config.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
            config.userAgentValue = packageName
        }.onFailure { reportStartupError("osmdroid config", it) }
        runCatching { UpdateCheckWorker.schedule(this) }
            .onFailure { reportStartupError("UpdateCheckWorker.schedule", it) }
        runCatching { app.aether.aegis.backup.BackupReminderWorker.schedule(this) }
            .onFailure { reportStartupError("BackupReminderWorker.schedule", it) }
        // Cold-start update check — opens the app after a full kill
        // and the foreground check runs in parallel with the 24 h
        // worker. Rate-limited to once per hour inside AutoUpdateCheck
        // so rapid re-opens don't spam GitHub. Sideload channel only:
        // the Play build (SELF_UPDATE=false) has no install permission
        // and lets Play handle updates, so the self-updater stays dormant.
        if (BuildConfig.SELF_UPDATE) {
            runCatching { app.aether.aegis.update.AutoUpdateCheck.maybeRun(this) }
                .onFailure { reportStartupError("AutoUpdateCheck.maybeRun", it) }
        }
        runCatching { app.aether.aegis.canary.CanaryWorker.schedule(this) }
            .onFailure { reportStartupError("CanaryWorker.schedule", it) }
        runCatching { app.aether.aegis.invite.PendingInvitationExpiryWorker.schedule(this) }
            .onFailure { reportStartupError("PendingInvitationExpiryWorker.schedule", it) }
        runCatching { app.aether.aegis.schedule.ScheduledMessageWorker.schedule(this) }
            .onFailure { reportStartupError("ScheduledMessageWorker.schedule", it) }
        simSwapMonitor = app.aether.aegis.simswap.SimSwapMonitor(this).also { it.start() }
        createNotificationChannels()
        // Phase 1 multi-profile:
        // resolve the active profile root and run the one-time
        val registry = ProfileRegistry.get(this)
        profileRoot = registry.current
        // The recovery phrase is SHOW-ONCE and must never be re-revealable.
        // A prior build stored an encrypted, PIN-/
        // biometric-retrievable backup of it — destroy that backup for
        // EVERY profile on startup so the phrase lives only on paper.
        runCatching {
            for (pid in registry.listProfiles()) {
                app.aether.aegis.lock.LockStore.forProfile(this, pid).clearRecoveryPhraseBackup()
            }
        }.onFailure { reportStartupError("clearRecoveryPhraseBackup", it) }
        identity = IdentityStore(profileRoot).loadOrCreate()
        // Ephemeral profiles ("True ephemeral") get a
        // RAM-only database — nothing the profile stores ever touches disk.
        val ephemeralProfile = registry.isEphemeral(registry.activeProfileId)
        val db = AegisDatabase.open(this, identity.privateKeyB64, profileRoot, ephemeralProfile)
        // Sealing wiring: the background receive pipeline encrypts
        // chat content under the active profile's PIN-derived pubkey
        // (cached in LockStore). The matching priv is regenerated
        // into PinSession on REAL-PIN unlock; until then sealed rows
        // surface as blank for the locked-view UX.
        val lockStoreForSeal = app.aether.aegis.lock.LockStore(this)
        val sealing = app.aether.aegis.lock.SealingPolicy(
            sealPubProvider = { lockStoreForSeal.sealPub },
            sealPrivProvider = { app.aether.aegis.lock.PinSession.priv() },
        )
        // On every lock event, wipe any decrypted chat-attachment
        // scratch files so plaintext copies never survive past the
        // user re-locking. Registered once at process start.
        app.aether.aegis.lock.PinSession.addOnLockListener {
            app.aether.aegis.lock.ChatAttachmentSeal.clearDecryptCache(this)
        }
        // The vault (secure notes) follows the APP lock (superseding the
        // earlier "re-lock on every
        // nav-out"): once unlocked it STAYS unlocked until the app
        // itself locks — not on every navigation away from /notes. An
        // explicit lock (lockManual / lockNow) clears PinSession, which
        // fires this listener and drops the vault with it. The
        // timeout-relock-on-foreground path doesn't clear PinSession, so
        // LockState.onForegrounded locks the vault directly there.
        app.aether.aegis.lock.PinSession.addOnLockListener {
            app.aether.aegis.vault.VaultSession.lock()
        }
        repository = Repository(
            messages = db.messages(),
            statuses = db.statuses(),
            outbox = db.outbox(),
            knownPeers = db.knownPeers(),
            secureNotes = db.secureNotes(),
            groupRepo = app.aether.aegis.groups.GroupRepository(db.groups()),
            stories = db.stories(),
            scheduled = db.scheduled(),
            networkHistory = db.networkHistory(),
            pendingInvitations = db.pendingInvitations(),
            selfKey = identity.deviceId,
            sealing = sealing,
            chatEncAttachmentsDir = profileRoot.chatEncAttachmentsDir,
            db = db,
        )
        // Install the cross-module bridge so :feature:groups
        // workers and prefs lookups can reach the narrow set of
        // app-side capabilities they need (profile id +
        // scoped Group* DB methods). The cross-module API is a
        // zero data bridge — interface lives in the feature
        // module, implementation lives here. Module never imports
        // app.aether.aegis.AegisApp, app.aether.aegis.sos.*,
        // or any safety package.
        app.aether.aegis.groups.GroupModuleHostHolder.current =
            object : app.aether.aegis.groups.GroupModuleHost {
                override fun activeProfileId(): String? =
                    runCatching { profileRoot.id }.getOrNull()

                override suspend fun isGroupEnabled(groupId: String): Boolean? =
                    repository.getGroup(groupId)?.enabled

                override suspend fun groupAutoDisableMinutes(groupId: String): Int? =
                    repository.getGroup(groupId)?.autoDisableMinutes

                override suspend fun setGroupEnabled(groupId: String, enabled: Boolean) {
                    repository.setGroupEnabled(groupId, enabled)
                }
            }
        // Presence module host (Phase 2, Stage 2)
        // — gives :core:safety:presence its two primitives (Trusted
        // targets + send) and nothing else. protocolManager/repository
        // are resolved lazily at call time (presence only broadcasts
        // well after onCreate), so this install order is fine.
        app.aether.aegis.presence.PresenceModuleHostHolder.current =
            object : app.aether.aegis.presence.PresenceModuleHost {
                override suspend fun trustedTargets(): List<String> =
                    repository.trustedTargets().map { it.publicKey }

                override suspend fun sendPresence(
                    toPubkey: String,
                    body: String,
                    kind: app.aether.aegis.presence.PresenceKind,
                ) {
                    val type = when (kind) {
                        app.aether.aegis.presence.PresenceKind.LOCATION -> MessageType.LOCATION
                        app.aether.aegis.presence.PresenceKind.STATUS -> MessageType.STATUS
                    }
                    protocolManager.sendMessage(toPubkey, body, type)
                }
            }
        // SOS module host (Phase 2, Stage 3) —
        // gives :core:safety:sos the one app primitive its relocated
        // leaves (SOSAlertStore + SOSAudioPlayer) still need: the
        // application Context, used by SOSAudioPlayer to drop the
        // speakerphone route from a Context-less code path. Nothing
        // else crosses the boundary. Wider sos primitives (targets,
        // send, own-sos gate) get added here as later Stage-3 slices
        // move the entangled sos classes behind this host.
        app.aether.aegis.sos.SOSModuleHostHolder.current =
            object : app.aether.aegis.sos.SOSModuleHost {
                override val appContext: android.content.Context
                    get() = applicationContext

                // Victim-only gate for SOSEvidenceLog. sosHandler is
                // assigned just below in onCreate; this is only ever read
                // during a live sos, long after init, so the lateinit
                // is always set by then — guarded anyway so an early call
                // fails closed (not my sos) instead of throwing.
                override fun isMyOwnSOSActive(): Boolean =
                    runCatching { sosHandler.state.value != null }.getOrDefault(false)

                // Stage-3 widening (SOSCoordinator). All bodies run at
                // sos-time, long after these lateinit/lazy members are
                // assigned below; property getters / method bodies defer
                // evaluation to call-time, so there's no init-order hazard.
                override val selfKey: String get() = identity.deviceId

                override fun sendStatus(peerKey: String, body: String) {
                    protocolManager.sendMessage(
                        peerKey, body, app.aether.aegis.core.MessageType.STATUS,
                    )
                }

                override suspend fun sosTargetKeys(): List<String> =
                    repository.sosTargets().map { it.publicKey }

                override suspend fun displayNameFor(peerKey: String): String? =
                    // Contact-graph sealing: when locked, the name unseals
                    // to "" — fall back to the pubkey prefix so a
                    // sos-while-locked still labels responders (the
                    // receiver side already does the same fallback).
                    repository.knownPeerByKey(peerKey)?.displayName?.ifBlank { peerKey.take(8) }

                override suspend fun isAegis(peerKey: String): Boolean =
                    repository.knownPeerByKey(peerKey)?.isAegis == true

                override suspend fun victimLocation(peerKey: String): Pair<Double, Double>? {
                    val s = repository.latestStatus(peerKey) ?: return null
                    val lat = s.latitude ?: return null
                    val lng = s.longitude ?: return null
                    return lat to lng
                }

                override fun unlockSOSDrillAchievement() {
                    runCatching {
                        app.aether.aegis.achievements.Achievements.unlock(
                            app.aether.aegis.achievements.Achievement.SOS_DRILL,
                        )
                    }
                }
            }
        // The SimpleX core DB passphrase: resolve the Keystore-wrapped key
        // once (unwrap if present, else mint + persist on a fresh install)
        // and share it with both the transport and the eager core init below,
        // so the DB is always opened under one source-of-truth key.
        val simplexDbKey = app.aether.aegis.simplex.SimpleXDbKeyStore(this).let { ks ->
            if (ks.hasWrappedPassphrase) ks.loadPassphrase() else ks.generateAndPersist()
        }
        // SimpleX is the only transport.
        transports = listOf(
            SimpleXTransport(this, simplexDbKey),
        )
        protocolManager = ProtocolManager(repository, transports, identity.deviceId, ::onInboundMessage)
        sosHandler = SOSHandler(this)
        powerBudget = app.aether.aegis.power.PowerBudget(this)

        // Hardware sos via power-button ×4 — counts SCREEN_ON/OFF
        // toggles in a 2 s window. Stock Android won't deliver
        // KEYCODE_POWER to userspace, so the screen-state lock-step
        // is the legal channel.
        //
        // Phase 1: the sos module only runs
        // while there's ≥1 sos-eligible (Trusted ∪ Emergency)
        // contact — with nobody to alert, the screen-toggle receiver
        // is never registered (no orphaned attack surface). Reacts
        // live as contacts are added/removed/retiered.
        //
        // FAIL-OPEN: a *missing* sos trigger is a safety failure; a
        // redundant one is harmless (broadcasts no-op on empty
        // sosTargets()). So any error reading the count registers
        // the receiver anyway.
        notifScope.launch {
            runCatching {
                repository.sosCountFlow()
                    .distinctUntilChanged()
                    .collect { count -> setPowerSOSReceiver(active = count > 0) }
            }.onFailure {
                reportStartupError("PowerButtonSOSReceiver gate", it)
                setPowerSOSReceiver(active = true) // fail-open
            }
        }

        // PackageInstaller session callbacks. Without this receiver,
        // a silent install that the OS demotes to "needs user
        // confirmation" (which happens whenever Aegis isn't its own
        // previous installer — e.g. the first sideload was via adb)
        // has nowhere to deliver the confirm Intent, and the Install
        // button looks unresponsive.
        runCatching {
            val rx = app.aether.aegis.update.InstallSessionReceiver()
            val filter = android.content.IntentFilter(app.aether.aegis.update.InstallSessionReceiver.ACTION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(rx, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(rx, filter)
            }
        }.onFailure { reportStartupError("InstallSessionReceiver", it) }

        // Self-heal: if a previous-process sos engaged the DO-side
        // lockdown (keyguard disabled, status bar disabled) and died
        // before cancelling — easy to do during DO provisioning, since
        // the power-button counter can fire sos from screen toggles
        // — the disables stay applied through the next launch. Reverse
        // them unconditionally at app start so the user never lands on
        // a phone with no fingerprint + no notification shade.
        runCatching {
            app.aether.aegis.core.LockdownController(this).forceClearDpmPolicies()
        }.onFailure { reportStartupError("LockdownController.forceClearDpmPolicies", it) }

        // Self-grant of every dangerous runtime permission Aegis
        // declares — only fires when we're Device Owner. Fixes the
        // failure mode where a user tapped Deny during onboarding
        // and then remote LOCATE/LISTEN/DISPLAY silently no-op'd.
        // As DO we don't need to ask. Idempotent + sticky: also
        // pins Settings → Permissions to GRANTED so the user can't
        // half-revoke afterwards.
        runCatching {
            app.aether.aegis.admin.PermissionAutoGrant.tryGrantAll(this)
        }.onFailure { reportStartupError("PermissionAutoGrant", it) }

        // Cyan tier — one-shot "you wear the brand" notification the
        // first time the user lights every skill-tree node (10/10
        // incl. Device Owner). Resets if they drop below Cyan.
        runCatching {
            app.aether.aegis.admin.CyanAnnouncer.checkAndAnnounce(this)
        }.onFailure { reportStartupError("CyanAnnouncer", it) }

        // Tier-reward broadcast — push our current ShieldTier name
        // to every Trusted contact so their ChatList can colour our
        // avatar frame. Idempotent + gated per peer, runs once per
        // cold start. Call sites that mutate a skill-tree node
        // (LockSettings, VaultPinSettings, etc.) should also invoke
        // TierBroadcaster.broadcastNow() so live tier changes
        // propagate without waiting for the next restart.
        notifScope.launch {
            runCatching { app.aether.aegis.admin.TierBroadcaster.broadcastNow(this@AegisApp) }
                .onFailure { reportStartupError("TierBroadcaster", it) }
        }
        // Hello-bootstrap fan-out moved OFF this cold-start path: launching
        // it here raced the SimpleX transport (often not yet connected at
        // onCreate), so the [aegis:hello] was sent into a dead socket and
        // dropped — never retried — which left contacts paired before the
        // signed control channel permanently un-bootstrapped (presence /
        // ticks / location dead until a manual re-pair). It now fires from
        // SimpleXTransport once the transport reports healthy, and again
        // after apiReconnect, so the hello always goes out on a live link.

        // Voyager curve needs a steady tick to flip its gates as
        // battery drops. ACTION_BATTERY_CHANGED is sticky-only on
        // modern Android (can't register a dynamic receiver), so we
        // poll the sticky intent every 60 s. Cheap — single Intent
        // read + a few comparisons. Drives the LunaGlass auto-disable
        // at ≤50 % among other gates.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        appScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            while (true) {
                runCatching { powerBudget.refresh() }
                kotlinx.coroutines.delay(60_000L)
            }
        }

        // Ephemeral-profile lifecycle. Once the SimpleX
        // core is up: finish any wipe scheduled by a previous lock, then
        // either ACTIVATE the active profile's own SimpleX user (if we
        // just switched into it) or, if a reboot left an ephemeral profile
        // active, wipe it (it must not survive a reboot). Needs the core
        // for create/delete-user, so we poll [SimpleXCore.initialised]
        // briefly first. No-op for normal profiles.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var waited = 0L
            while (!app.aether.aegis.simplex.SimpleXCore.initialised && waited < 30_000L) {
                kotlinx.coroutines.delay(250L)
                waited += 250L
            }
            runCatching {
                app.aether.aegis.profile.EphemeralProfile.onStart(this@AegisApp) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }.onFailure { android.util.Log.w("AegisApp", "ephemeral onStart failed", it) }

            // Pending sos from a sos-while-ephemeral: the ephemeral
            // profile was destroyed + we switched to the primary; now fire
            // the broadcast here. Only fires when the active profile is NOT
            // ephemeral (i.e. we've actually landed on the real profile).
            runCatching {
                val statePrefs = getSharedPreferences("aegis_state", MODE_PRIVATE)
                val pending = statePrefs.getString("pending_sos", null)
                val reg = app.aether.aegis.profile.ProfileRegistry.get(this@AegisApp)
                if (pending != null && !reg.isEphemeral(reg.activeProfileId)) {
                    statePrefs.edit().remove("pending_sos").commit()
                    val trig = runCatching { app.aether.aegis.core.SOSTrigger.valueOf(pending) }
                        .getOrDefault(app.aether.aegis.core.SOSTrigger.LOCKSCREEN)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        sosHandler.trigger(trig)
                    }
                }
            }.onFailure { android.util.Log.w("AegisApp", "pending sos fire failed", it) }
        }

        // Network-usage sampler. Every 60 s,
        // read TrafficStats.getUid{Rx,Tx}Bytes, take the delta since
        // the last sample, and accumulate into the current hour's
        // network_history row. Baselines stored in aegis_state prefs
        // so a process restart picks up where it left off without
        // losing data. Counter resets (device reboot — the TrafficStats
        // counter is per-boot for the UID) yield baseline > current,
        // we treat the delta as just the current value.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val uid = android.os.Process.myUid()
            val prefs = getSharedPreferences("aegis_state", MODE_PRIVATE)
            var lastRx = prefs.getLong("net_baseline_rx", -1L)
            var lastTx = prefs.getLong("net_baseline_tx", -1L)
            while (true) {
                runCatching {
                    val rxNow = android.net.TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
                    val txNow = android.net.TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
                    if (lastRx >= 0 && lastTx >= 0) {
                        val rxDelta = if (rxNow >= lastRx) rxNow - lastRx else rxNow
                        val txDelta = if (txNow >= lastTx) txNow - lastTx else txNow
                        repository.recordNetworkSample(rxDelta, txDelta)
                    }
                    lastRx = rxNow
                    lastTx = txNow
                    prefs.edit()
                        .putLong("net_baseline_rx", rxNow)
                        .putLong("net_baseline_tx", txNow)
                        .apply()
                    // Cheap purge — runs once per sample; the DELETE
                    // is a single index range scan.
                    repository.purgeOldNetworkHistory()
                }
                kotlinx.coroutines.delay(60_000L)
            }
        }

        // One-shot story sweep at boot. observeActive(cutoff) already
        // hides stale rows from the UI; this prevents the table from
        // growing forever for absentee users.
        notifScope.launch {
            runCatching { repository.purgeExpiredStories() }
            // Wipe any control-class rows that leaked into chat before
            // both the outbound + inbound filters were complete. After
            // this lands, control messages stop landing in chat at the
            // source — this is a one-shot cleanup of the spam that
            // accumulated before then.
            runCatching {
                val n = repository.purgeControlMessages()
                if (n > 0) {
                    android.util.Log.i("AegisApp", "purged $n stale control rows from chat")
                }
            }
        }
        profileStore = ProfileStore(this)
        lockState = app.aether.aegis.lock.LockState(this)
        // Seal auto-load (replaces the removed in-app "enter PIN to read"
        // banner). If there is NO lock screen to pass — i.e. the launch
        // lock is off, so lockState starts unlocked — but the profile is
        // phrase-rooted with a TEE-wrapped seal key, load it now so chats
        // are immediately readable without any banner. When the launch lock
        // IS on, lockState starts locked and we skip this: the key stays
        // out of memory until the lock-screen unlock, preserving "lock
        // wipes the key". Legacy PIN-rooted seals can't auto-load (they
        // need the PIN) and migrate to phrase-rooted on the next real unlock.
        runCatching {
            val s = lockState.store
            if (!lockState.isLocked && s.hasRecoveryPhrase && s.hasWrappedSealPriv &&
                !app.aether.aegis.lock.PinSession.isUnlocked) {
                s.unwrapSealKeypair()?.let { app.aether.aegis.lock.PinSession.set(it) }
            }
        }
        // React to the seal key becoming available — at launch (auto-load
        // above, launch-lock off) AND on every later REAL unlock through
        // the lock screen. On each unlock we (1) seal any pre-enrolment
        // plaintext rows that the per-write helpers never covered, and
        // (2) consume a pending post-import re-seal bundle. Observing
        // PinSession (rather than a one-shot call) is what finally wires
        // the "rows get sealed on the next real unlock" guarantee the
        // migration comments long promised. (Security review 2026-06-07.)
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Consume a pending post-import re-seal bundle ONCE at boot,
            // independent of unlock state. This is what makes an import into
            // a NO-PHRASE profile work: such a profile never enters the
            // unlocked PinSession state (no keypair is ever set), so the
            // unlock-flow collector below would never fire and the bundle
            // would sit on disk forever, leaving the imported chats + contact
            // names sealed under the OLD device's key — permanently blank
            // (user report 2026-06-15). consumePendingReseal now re-seals
            // under this profile's pub when one exists, or writes the
            // bundle's plaintext into the plaintext columns when it doesn't
            // (a no-phrase profile stores plaintext at rest anyway; if the
            // user later enrols a phrase, sealLegacyPlaintext seals it). Only
            // the pub is needed to re-seal, so this is correct even while the
            // app is locked.
            consumePendingReseal()
            app.aether.aegis.lock.PinSession.isUnlockedFlow.collect { unlocked ->
                if (!unlocked) return@collect
                runCatching { repository.sealLegacyPlaintext() }
                    .onFailure { reportStartupError("sealLegacyPlaintext", it) }
                // Re-run on real unlock too: covers a phrase enrolled AFTER
                // import (the seal pub appears mid-session) and any bundle
                // that arrived while the app was already running.
                consumePendingReseal()
            }
        }
        remoteAccessGate = app.aether.aegis.remote.RemoteAccessGate(this)
        // Sentinel cold-start re-arm: if the user had Sentinel armed
        // and the OS killed the process (Doze, memory pressure,
        // forced restart), spin the engine back up. Also starts the
        // auto-arm observer if the user opted in — that observer is
        // what triggers bedside arming.
        runCatching {
            app.aether.aegis.sentinel.SentinelState.reArmIfNeeded(this)
        }.onFailure { android.util.Log.w("AegisApp", "sentinel re-arm failed", it) }
        // Wire ProcessLifecycleOwner → InAppActivity so the green
        // "online" dot reflects actual in-app activity, not just the
        // process being alive in the background.
        app.aether.aegis.presence.InAppActivity.attach()

        // Register the call accept/reject receiver app-wide so the
        // notification actions work even when MainActivity isn't on
        // top — RECEIVER_NOT_EXPORTED because only our own PendingIntents
        // fire it.
        runCatching {
            val filter = IntentFilter().apply {
                addAction(app.aether.aegis.call.CallManager.ACTION_ACCEPT)
                addAction(app.aether.aegis.call.CallManager.ACTION_REJECT)
            }
            ContextCompat.registerReceiver(
                this, app.aether.aegis.call.CallActionReceiver(),
                filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { reportStartupError("CallActionReceiver registerReceiver", it) }

        // Kick the SimpleX Haskell core's boot ASAP, in parallel with
        // the rest of the app lifecycle. It takes 5-30 s (loadLibrary +
        // GHC runtime + SQLCipher DB migration); without this eager
        // start, the user can reach Add contact before SimpleXTransport's
        // start() coroutine has even called ensureInitialised, and
        // every command comes back "core not ready". Uses the same
        // Keystore-wrapped passphrase resolved above for the transport.
        // Eager SimpleX core init off the main thread. A code
        // review replaced the bare Thread() with a coroutine
        // so the codebase has one concurrency idiom. Dispatchers.IO
        // is the right pool — chatMigrateInit is blocking JNI +
        // SQLCipher disk work.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                app.aether.aegis.simplex.SimpleXCore.ensureInitialised(this@AegisApp, simplexDbKey)
            }.onFailure {
                reportStartupError("SimpleXCore.ensureInitialised", it)
            }
        }
    }

    /**
     * Register / unregister the power-button sos receiver to match
     * the sos-module gate (Phase 1).
     * `@Synchronized` because the collector and the fail-open path can
     * both reach it; idempotent via the receiver's own registered flag.
     */
    @Synchronized
    private fun setPowerSOSReceiver(active: Boolean) {
        if (active) {
            if (powerSOSReceiver == null) {
                powerSOSReceiver = app.aether.aegis.core.PowerButtonSOSReceiver(this).also { rx ->
                    runCatching { rx.register() }
                        .onFailure {
                            reportStartupError("PowerButtonSOSReceiver.register", it)
                            powerSOSReceiver = null
                        }
                }
            }
        } else {
            powerSOSReceiver?.let { rx -> runCatching { rx.unregister() } }
            powerSOSReceiver = null
        }
    }

    /**
     * Apply a pending re-seal bundle left by a restore, then delete it.
     *
     * The backup carries the sealed fields as PLAINTEXT (unsealed at export
     * under the source key — the master key never travels). On the
     * destination the bundle is the ONLY plaintext copy, so we apply it only
     * when we can do so without losing data (see the 4-way table inline):
     *   - seal key + unlocked session → re-seal under the (proven-usable) key.
     *   - seal PUB but locked → DEFER (don't risk sealing under an orphan pub).
     *   - no seal key + onboarded → write plaintext (genuine no-phrase
     *     profile; imported chats become readable, and [Repository.sealLegacyPlaintext]
     *     seals them if a phrase is enrolled later).
     *   - no seal key + onboarding pending → DEFER (the tutorial will set the
     *     phrase; then we re-seal under it).
     *
     * Runs at boot AND on every real unlock, so a deferred bundle is retried
     * as soon as the precondition is met. Consumed exactly once. Idempotent:
     * a missing file is a no-op.
     */
    private fun consumePendingReseal() {
        val pending = java.io.File(
            profileRoot.root,
            app.aether.aegis.backup.BackupManager.RESEAL_PENDING_FILE,
        )
        if (!pending.exists()) return
        // NOTE: deliberately NOT gated on PinSession.isUnlocked. Applying the
        // bundle needs only the seal PUB (trySeal) — or no key at all (the
        // plaintext path) — never the in-session priv. Gating on unlock
        // stranded the bundle on no-phrase profiles (never unlocked) and
        // needlessly delayed it on locked-but-enrolled ones. The bundle's
        // own wrap is a device-bound TEE key, also unlock-independent.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // Decide whether to apply the bundle NOW or DEFER (leave it on
                // disk for a later boot / unlock). The bundle holds the only
                // plaintext copy, so a wrong choice is unrecoverable — hence
                // the explicit 4-way table:
                val canSeal = repository.canSeal
                val unlocked = app.aether.aegis.lock.PinSession.isUnlocked
                val onboarded = profileStore.onboarded
                val proceed = when {
                    // Have a seal key AND a live session → re-seal under a key
                    // we KNOW is usable (the priv is in memory). Safe.
                    canSeal && unlocked -> true
                    // Have a seal PUB but locked → DEFER. Sealing under a pub
                    // whose priv we can't confirm (e.g. a pub cloned from a
                    // backup, with no recoverable priv on this device) would
                    // make the data permanently unreadable AND consume the
                    // plaintext bundle. Wait for a real unlock to prove the
                    // key, or for a clean re-import.
                    canSeal && !unlocked -> false
                    // No seal key, onboarding already finished → genuine
                    // no-phrase profile (stores plaintext at rest). Write the
                    // bundle's plaintext so the imported chats are readable.
                    !canSeal && onboarded -> true
                    // No seal key, onboarding still pending → the tutorial is
                    // about to set a recovery phrase. DEFER so the content gets
                    // re-sealed under that new key (and attachments stay
                    // encrypted), not written as plaintext now.
                    else -> false
                }
                if (!proceed) {
                    android.util.Log.i(
                        "AegisApp",
                        "pending re-seal deferred (canSeal=$canSeal unlocked=$unlocked onboarded=$onboarded)",
                    )
                    return@runCatching
                }
                val raw = pending.readBytes()
                // Format (see BackupManager): [0x01][ivLen][iv][ciphertext]
                // = TEE-wrapped; [0x00][plaintext] = TEE-less fallback.
                val bundle: ByteArray? = when {
                    raw.isEmpty() -> null
                    raw[0].toInt() == 0x01 -> {
                        val ivLen = raw[1].toInt() and 0xFF
                        val iv = raw.copyOfRange(2, 2 + ivLen)
                        val blob = raw.copyOfRange(2 + ivLen, raw.size)
                        app.aether.aegis.lock.SealKeyVault.unwrapReseal(blob, iv)
                    }
                    raw[0].toInt() == 0x00 -> raw.copyOfRange(1, raw.size)
                    // Defensive: an unmarked legacy stash — treat as raw.
                    else -> raw
                }
                if (bundle != null) {
                    val n = repository.applyResealBundle(bundle)
                    java.util.Arrays.fill(bundle, 0)
                    android.util.Log.i("AegisApp", "post-import re-seal: applied $n fields")
                }
                pending.delete()
                // Drop the one-shot wrapping key now the bundle is gone.
                app.aether.aegis.lock.SealKeyVault.deleteResealKey()
            }.onFailure { reportStartupError("consumePendingReseal", it) }
        }
    }

    private fun reportStartupError(stage: String, t: Throwable) {
        android.util.Log.e("AegisApp", "startup stage failed: $stage", t)
        runCatching {
            val log = java.io.File(filesDir, "startup-errors.log")
            log.appendText("\n--- ${java.util.Date()} ---\n[$stage]\n${android.util.Log.getStackTraceString(t)}\n")
        }
    }

    private fun onInboundMessage(msg: InboundMessage) {
        when (msg.type) {
            MessageType.SOS -> notifySOS(msg)
            MessageType.TEXT, MessageType.PHOTO, MessageType.VOICE, MessageType.FILE -> notifyMessage(msg)
            MessageType.STATUS -> handleInboundStatus(msg)
            MessageType.LOCATION -> handleInboundLocation(msg)
            MessageType.STORY -> handleInboundStory(msg)
            // Burn-after-reading: same notification path as a normal
            // text message; the chat bubble special-cases the type
            // so the body isn't revealed until the viewer opens.
            MessageType.BURN -> notifyMessage(msg)
            // CALL_LOG is a local-only journal type — it should never
            // arrive via the inbound transport. If a malformed packet
            // somehow ends up tagged this way, drop it silently rather
            // than rendering it as a notification.
            MessageType.CALL_LOG -> Unit
            // GROUP_SYSTEM is an in-history audit row (member joined /
            // left / role changed / etc.). Renderer-only — no
            // notification fires for membership noise. Local-write and
            // SimpleX-echo paths in the GroupDispatcher write the row
            // directly; this case exists only so the when() stays
            // exhaustive against an inbound malformed packet.
            MessageType.GROUP_SYSTEM -> Unit
        }
    }

    /**
     * Inbound STORY message — peer is publishing a 24 h ephemeral post.
     * Body format is JSON: `{"id":"<uuid>","text":"<caption>","attachment":"<path?>","mime":"<?>"}`.
     * We insert it into the local stories table; the chat-list strip
     * picks it up via observeActiveStories().
     */
    private fun handleInboundStory(msg: InboundMessage) {
        notifScope.launch {
            runCatching {
                val json = org.json.JSONObject(msg.body)
                // Text-only stories arrive via MessageType.STORY with a
                // JSON body. Photo stories take a different path —
                // SimpleXTransport.recordReceivedAttachment intercepts
                // captions matching `[aegis:story:<id>]<text>` and
                // writes the StoryEntity directly with the file path.
                val story = app.aether.aegis.data.StoryEntity(
                    id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    authorKey = msg.fromKey,
                    body = json.optString("text", ""),
                    attachmentPath = null,
                    attachmentMime = null,
                    createdAt = msg.timestamp,
                    viewed = false,
                )
                repository.upsertStory(story)
            }.onFailure { android.util.Log.w("AegisApp", "handleInboundStory failed", it) }
        }
    }

    /**
     * Inbound STATUS message. Two shapes we care about:
     *
     *   `[aegis:read:<itemId>]`   peer has read everything up to itemId
     *                             — caller side has already stripped or
     *                             kept the prefix; we use the full body
     *                             to match against AEGIS_READ_RECEIPT.
     *   `{"battery":…,"net":…}`   peer's device status (battery,
     *                             network, signal, ts). Drives the
     *                             green/orange/grey dot on the chat
     *                             list, the Status grid, and the Map.
     *                             SimpleXTransport strips the
     *                             `[aegis:status]` prefix on receive,
     *                             so msg.body is the bare JSON here.
     */
    /** Decode an `[aegis:identity]` avatar (`data:image/...;base64,…`)
     *  into a JPEG under `filesDir/peer_avatars/id_<hash>.jpg` and return
     *  the absolute path, or null on any parse/decode/write failure. The
     *  `id_` prefix keeps it distinct from the incognito-profile avatar
     *  filename so an identity reveal can't be clobbered by a SimpleX
     *  contactUpdated. tmp+rename so a reader never sees a half-written
     *  file. */
    private fun decodeIdentityAvatar(peerKey: String, dataUrl: String): String? = runCatching {
        val comma = dataUrl.indexOf(',').takeIf { it >= 0 } ?: return null
        val bytes = android.util.Base64.decode(
            dataUrl.substring(comma + 1), android.util.Base64.DEFAULT,
        )
        if (bytes.isEmpty()) return null
        val dir = java.io.File(filesDir, "peer_avatars").apply { mkdirs() }
        val safe = "id_" + peerKey.hashCode().toString().replace("-", "n") + ".jpg"
        val out = java.io.File(dir, safe)
        val tmp = java.io.File(dir, "$safe.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) {
            out.delete()
            if (!tmp.renameTo(out)) { tmp.delete(); return null }
        }
        out.absolutePath
    }.getOrNull()

    private fun handleInboundStatus(msg: InboundMessage) {
        val body = msg.body
        AEGIS_READ_RECEIPT.matchEntire(body)?.let { match ->
            val itemId = match.groupValues[1].toLongOrNull() ?: return
            notifScope.launch {
                runCatching {
                    repository.markReadUpTo(peerKey = msg.fromKey, maxItemId = itemId)
                }.onFailure { android.util.Log.w("AegisApp", "markReadUpTo failed", it) }
            }
            return
        }
        // Delivery confirmation: the recipient's device received our
        // message(s) up to this itemId. Fired on receipt, before the at-rest
        // seal — this is the single BRIGHT tick ("reached their device"),
        // distinct from the sealed-at-rest two-tick state below.
        AEGIS_DELIVERED_RECEIPT.matchEntire(body)?.let { match ->
            val itemId = match.groupValues[1].toLongOrNull() ?: return
            notifScope.launch {
                runCatching {
                    repository.markDeliveredUpTo(peerKey = msg.fromKey, maxItemId = itemId)
                }.onFailure { android.util.Log.w("AegisApp", "markDeliveredUpTo failed", it) }
            }
            return
        }
        // Seal confirmation: the recipient has
        // sealed our message(s) up to this itemId into their own encrypted
        // store. THAT is what the bright + dim ✓✓ means — sealed at rest, a
        // rung above "delivered to their device".
        AEGIS_SEAL_RECEIPT.matchEntire(body)?.let { match ->
            val itemId = match.groupValues[1].toLongOrNull() ?: return
            notifScope.launch {
                runCatching {
                    repository.markSealedUpTo(peerKey = msg.fromKey, maxItemId = itemId)
                }.onFailure { android.util.Log.w("AegisApp", "markSealedUpTo failed", it) }
            }
            return
        }
        // DNA-keyed receipt twins — the cross-device read-tick fix. The peer
        // echoes a DNA we minted (carried in the chat envelope), so we match
        // OUR OWN outbound rows by messageDna, which is identical on both ends,
        // instead of a per-device itemId. Body shape `[aegis:readdna:<nanos>]`
        // etc. Only Aegis chat messages carry a DNA; legacy/vanilla rows keep
        // the itemId receipts above.
        AEGIS_READ_DNA_RECEIPT.matchEntire(body)?.let { match ->
            val dna = match.groupValues[1].toLongOrNull() ?: return
            notifScope.launch {
                runCatching {
                    repository.markReadUpToDna(peerKey = msg.fromKey, maxDna = dna)
                }.onFailure { android.util.Log.w("AegisApp", "markReadUpToDna failed", it) }
            }
            return
        }
        AEGIS_DELIVERED_DNA_RECEIPT.matchEntire(body)?.let { match ->
            val dna = match.groupValues[1].toLongOrNull() ?: return
            notifScope.launch {
                // Exact-DNA, not up-to (SPEC_LOSSY_LINK_RESILIENCE): mark only
                // THIS message delivered. A watermark would infer lower DNAs
                // reached the same rung, which lies under reorder / seal failure.
                runCatching {
                    repository.markDeliveredByDna(peerKey = msg.fromKey, dna = dna)
                }.onFailure { android.util.Log.w("AegisApp", "markDeliveredByDna failed", it) }
            }
            return
        }
        AEGIS_SEAL_DNA_RECEIPT.matchEntire(body)?.let { match ->
            val dna = match.groupValues[1].toLongOrNull() ?: return
            notifScope.launch {
                // Exact-DNA, not up-to (see above). "Sealed implies delivered"
                // is handled by the ladder guard in the UPDATE, not by
                // watermarking every lower DNA.
                runCatching {
                    repository.markSealedByDna(peerKey = msg.fromKey, dna = dna)
                }.onFailure { android.util.Log.w("AegisApp", "markSealedByDna failed", it) }
            }
            return
        }
        // Receipt reconciliation query (SPEC_LOSSY_LINK_RESILIENCE). The sender
        // asks about specific DNAs whose tick is stuck; we answer with FACTS
        // from our DB, exact per-DNA, never a watermark. A DNA we hold (received
        // + persisted, hence sealed at rest) → re-send its sealed receipt (✓✓,
        // which implies delivered via the ladder). A DNA we don't hold gets NO
        // answer: silence keeps the sender's tick honestly dark, message-loss
        // recovery is the outbox's job, and we never falsely confirm "all good".
        // (Resend-on-not-found + read reconciliation are tracked follow-ups.)
        if (body.startsWith("[aegis:statusq:")) {
            val csv = body.removePrefix("[aegis:statusq:").removeSuffix("]")
            // Cap parsed DNAs so a malformed/huge query can't fan out endlessly.
            val dnas = csv.split(',').mapNotNull { it.trim().toLongOrNull() }.take(64)
            notifScope.launch {
                for (dna in dnas) {
                    val row = runCatching { repository.messageByDna(dna) }.getOrNull()
                    if (row != null && !row.outgoing) {
                        runCatching {
                            protocolManager.sendMessage(
                                to = msg.fromKey,
                                content = "[aegis:sealeddna:$dna]",
                                type = app.aether.aegis.core.MessageType.STATUS,
                            )
                        }.onFailure {
                            android.util.Log.w("AegisApp", "statusq reply failed for dna=$dna", it)
                        }
                    }
                }
            }
            return
        }
        // Burn-after-reading receipt — peer has viewed and closed our
        // burn message, so we wipe our local row too
        // ("gone from both devices"). The id in the receipt is the
        // sender-side row UUID we embedded in the outbound marker.
        AEGIS_BURN_RECEIPT.matchEntire(body)?.let { match ->
            val rowId = match.groupValues[1]
            notifScope.launch {
                runCatching { repository.deleteMessageById(rowId) }
                    .onFailure { android.util.Log.w("AegisApp", "burn-receipt delete failed", it) }
            }
            return
        }
        // SIM swap alert from a paired peer. Surface as a high-priority
        // notification — this is "your family member's SIM changed,
        // possibly stolen", not routine telemetry.
        if (body.startsWith("[aegis:sim-swap]")) {
            notifySimSwap(msg.fromKey, body.removePrefix("[aegis:sim-swap]"))
            return
        }
        if (body.startsWith("[aegis:geofence]")) {
            notifyGeofence(msg.fromKey, body.removePrefix("[aegis:geofence]"))
            return
        }
        // Shield-tier announcement.
        // Sender published their current ShieldTier; we store it on
        // their known_peers row so ChatList can draw the
        // tier-coloured avatar frame. Validate against the enum so a
        // garbled body never poisons the column.
        if (body.startsWith("[aegis:tier]")) {
            val raw = body.removePrefix("[aegis:tier]").trim()
            val tier = runCatching { app.aether.aegis.admin.ShieldTier.valueOf(raw) }.getOrNull()
            if (tier != null) {
                notifScope.launch {
                    runCatching { repository.setPeerReportedTier(msg.fromKey, tier.name) }
                        .onFailure { android.util.Log.w("AegisApp", "setPeerReportedTier failed", it) }
                }
            } else {
                android.util.Log.w("AegisApp", "[aegis:tier] unknown tier name: $raw")
            }
            return
        }
        // Crown-shimmer-style announcement.
        // Sender published their chosen crown style (0/1/2); store it so the
        // peer's Cyan medal renders in their style on our surfaces. Validate to
        // the known range so a garbled body never poisons the column.
        if (body.startsWith("[aegis:crown]")) {
            val raw = body.removePrefix("[aegis:crown]").trim()
            val style = raw.toIntOrNull()
            if (style != null && style in 0..2) {
                notifScope.launch {
                    runCatching { repository.setPeerReportedCrownStyle(msg.fromKey, style) }
                        .onFailure { android.util.Log.w("AegisApp", "setPeerReportedCrownStyle failed", it) }
                }
            } else {
                android.util.Log.w("AegisApp", "[aegis:crown] bad style: $raw")
            }
            return
        }
        // Capability announcement.
        // Sender published the capability tokens their build supports
        // (`[aegis:caps]<csv>`); store them so we only send features they can
        // handle — chiefly the chat envelope, which an older build would lose.
        // Length-capped so a garbled/hostile body can't bloat the row.
        if (body.startsWith("[aegis:caps]")) {
            val caps = body.removePrefix("[aegis:caps]").trim().take(256)
            notifScope.launch {
                runCatching { repository.setPeerCapabilities(msg.fromKey, caps.ifBlank { null }) }
                    .onFailure { android.util.Log.w("AegisApp", "setPeerCapabilities failed", it) }
            }
            return
        }
        // Delete-for-everyone (chat envelope). The sender retracts a message by
        // its shared DNA — native SimpleX broadcast-delete can't reach it once
        // Aegis has sealed + purged the transport copy, so this control frame is
        // the only path that actually removes our stored copy. Bounded to the
        // peer's OWN messages (deleteInboundByDna's outgoing=0 guard) so a peer
        // can't delete something we sent.
        if (body.startsWith("[aegis:deletedna]")) {
            val dna = body.removePrefix("[aegis:deletedna]").trim().toLongOrNull()
            if (dna != null) {
                notifScope.launch {
                    runCatching { repository.deleteInboundByDna(dna) }
                        .onFailure { android.util.Log.w("AegisApp", "deleteInboundByDna failed", it) }
                }
            }
            return
        }
        // Edit-for-everyone (chat envelope). The sender edited a message they
        // sent us; native /_update can't reach the sealed copy and the inbound
        // chatItemUpdated handler only refreshes status, so this frame carries
        // the new text. Body: `[aegis:editdna]{"d":<nanos>,"t":<text>}`.
        if (body.startsWith("[aegis:editdna]")) {
            runCatching {
                val o = org.json.JSONObject(body.removePrefix("[aegis:editdna]"))
                val dna = o.optLong("d", -1L).takeIf { it > 0 }
                val newText = o.optString("t")
                if (dna != null) {
                    notifScope.launch {
                        runCatching { repository.applyInboundEditByDna(dna, newText) }
                            .onFailure { android.util.Log.w("AegisApp", "applyInboundEditByDna failed", it) }
                    }
                }
            }.onFailure { android.util.Log.w("AegisApp", "[aegis:editdna] parse failed", it) }
            return
        }
        // Aegis Protocol identity overlay.
        // A contact who elevated us to Trusted/Emergency revealed their
        // REAL name/bio/avatar over the E2E channel. We store it into the
        // peer's announced* fields so every surface renders their real
        // identity for THIS contact — while the SimpleX layer still only
        // ever knew their random incognito handle. Self-asserted (same
        // trust basis as [aegis:hello]); verification is a roadmap item.
        if (body.startsWith("[aegis:identity]")) {
            val json = body.removePrefix("[aegis:identity]").trim()
            notifScope.launch {
                runCatching {
                    // SECURITY: an identity reveal is honoured ONLY from a
                    // peer we already hold at Trusted/Emergency. Identity is
                    // meant to ride exclusively on trust elevation; without
                    // this gate ANY paired peer — including an Untrusted one
                    // — could set their own displayed name by hand-typing
                    // [aegis:identity]{...} (the manually-injected spoof,
                    // e.g. "Police"). Drop the claim from anyone below
                    // Trusted: authentication of the wire is a separate,
                    // larger change, but the authorization gate is cheap and
                    // closes the demonstrated hole now.
                    val sender = repository.knownPeerByKey(msg.fromKey)
                    val trusted =
                        sender?.trustTier == app.aether.aegis.data.TrustTier.TRUSTED.name ||
                            sender?.trustTier == app.aether.aegis.data.TrustTier.EMERGENCY.name
                    if (!trusted) {
                        android.util.Log.w(
                            "AegisApp",
                            "[aegis:identity] dropped — sender below Trusted",
                        )
                        return@runCatching
                    }
                    val o = org.json.JSONObject(json)
                    val name = o.optString("name").takeIf { it.isNotBlank() }
                    val bio = o.optString("bio").takeIf { it.isNotBlank() }
                    val img = o.optString("img").takeIf { it.isNotBlank() }
                    val avatarPath = img?.let { decodeIdentityAvatar(msg.fromKey, it) }
                    repository.updatePeerProfile(
                        publicKey = msg.fromKey,
                        announcedName = name,
                        announcedBio = bio,
                        announcedAvatarPath = avatarPath,
                    )
                }.onFailure {
                    android.util.Log.w("AegisApp", "[aegis:identity] parse/store failed", it)
                }
            }
            return
        }
        // Auth-gated remote-access surface. Replaces
        // the unauthenticated [aegis:cmd] envelope that let any paired
        // peer fire LOCK/WIPE/SIREN. The new flow requires the sender
        // to know the TARGET's PIN — verified on this side by
        // RemoteAccessHandler against LockStore.
        if (body.startsWith(app.aether.aegis.remote.RemoteAccessProtocol.PREFIX)) {
            notifScope.launch {
                runCatching {
                    app.aether.aegis.remote.RemoteAccessHandler.handle(
                        fromKey = msg.fromKey,
                        body = body.removePrefix(app.aether.aegis.remote.RemoteAccessProtocol.PREFIX),
                    )
                }
            }
            return
        }
        // Per c626800: target broadcasts this to every paired contact
        // right before factory-resetting itself, so contacts know to
        // re-invite once the device comes back rather than thinking
        // the peer just went silent.
        if (body.startsWith(app.aether.aegis.remote.RemoteAccessProtocol.WIPE_BROADCAST_PREFIX)) {
            notifyRemotePeerWiped(msg.fromKey)
            return
        }
        app.aether.aegis.call.CallReactions.MARKER.matchEntire(body)?.let { match ->
            val emoji = match.groupValues[1]
            app.aether.aegis.call.CallReactions.emitInbound(emoji)
            return
        }
        if (body.startsWith("[aegis:typing]")) {
            // Mark peer as actively typing. ChatScreen reads
            // TypingTracker.isTyping() to render the three-dot animation.
            app.aether.aegis.chat.TypingTracker.mark(msg.fromKey)
            return
        }
        // SOS-coordination surfaces.
        // These all dispatch into app.aether.aegis.sos.SOSCoordinator —
        // receiver-side bookkeeping for roster count, responder list,
        // and the optional victim-side relay.
        if (
            body.startsWith("[aegis:sos-roster]") ||
            body.startsWith("[aegis:sos-response:") ||
            body.startsWith("[aegis:sos-responder-loc]") ||
            body.startsWith("[aegis:sos-coord]") ||
            body.startsWith("[aegis:sos-victim-voice]") ||
            body.startsWith("[aegis:sos-distance]") ||
            body.startsWith("[aegis:sos-closest]") ||
            body.startsWith("[aegis:sos-arrived]")
        ) {
            notifScope.launch {
                runCatching {
                    app.aether.aegis.sos.SOSCoordinator.handleInbound(msg.fromKey, body)
                }.onFailure {
                    android.util.Log.w("AegisApp", "sos-coord handle failed", it)
                }
            }
            return
        }
        // Verified-security badges shared by a contact.
        // Cache them; ContactDetailScreen
        // renders them only if we hold the peer at TRUSTED tier.
        if (body.startsWith(app.aether.aegis.achievements.AchievementBroadcaster.ENVELOPE)) {
            notifScope.launch {
                runCatching {
                    app.aether.aegis.achievements.PeerBadgeStore.recordEnvelope(msg.fromKey, body)
                }.onFailure {
                    android.util.Log.w("AegisApp", "badges handle failed", it)
                }
            }
            return
        }
        // Device-status payload — JSON.
        notifScope.launch {
            runCatching {
                val json = org.json.JSONObject(body)
                val battery = if (json.isNull("battery")) null else json.optInt("battery")
                val charging = if (json.isNull("charging")) null else json.optBoolean("charging")
                val net = if (json.isNull("net")) null else json.optString("net").takeIf { it.isNotBlank() }
                val signal = if (json.isNull("signal")) null else json.optInt("signal")
                val ts = json.optLong("ts", msg.timestamp)
                // The sender's "I'm actively using the app" timestamp
                // (InAppActivity-derived) — the foreground heartbeat that
                // drives the green online dot, distinct from `ts` (the
                // background-alive packet stamp). Defaults to `ts` only if a
                // malformed payload omits it.
                val inApp = json.optLong("inApp", ts)
                // Peer's app version (YYYY.MM.BBB); blank/absent → null.
                val version = json.optString("version").takeIf { it.isNotBlank() }
                repository.patchDeviceStatus(
                    peerKey = msg.fromKey,
                    // packetTs = ts (background-alive heartbeat) +
                    // ts param = inApp (foreground heartbeat). Two
                    // timestamps split the Online / Away / Offline
                    // decision instead of
                    // collapsing every non-foreground state to
                    // Offline.
                    packetTs = ts,
                    batteryLevel = battery,
                    isCharging = charging,
                    networkType = net,
                    signalStrength = signal,
                    ts = inApp,
                    appVersion = version,
                )
            }.onFailure { android.util.Log.w("AegisApp", "patchDeviceStatus failed: $body", it) }
        }
    }

    /**
     * Inbound routine location update from a family member. Format is
     * JSON `{"lat":<double>,"lng":<double>,"ts":<long>}` per
     * ProtocolService.broadcastLocationToFamily. We patch the sender's
     * status row so the Map screen + status grid show fresh data; no
     * notification (LOCATION is silent by design).
     */
    private fun handleInboundLocation(msg: InboundMessage) {
        notifScope.launch {
            val (lat, lng, ts) = runCatching {
                val json = org.json.JSONObject(msg.body)
                Triple(
                    json.optDouble("lat", Double.NaN),
                    json.optDouble("lng", Double.NaN),
                    json.optLong("ts", msg.timestamp),
                )
            }.getOrNull() ?: return@launch
            if (lat.isNaN() || lng.isNaN()) return@launch
            repository.patchLocation(
                peerKey = msg.fromKey,
                latitude = lat,
                longitude = lng,
                ts = ts,
            )
        }
    }

    private val notifScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    private fun notifyMessage(msg: InboundMessage) {
        // Their message just landed — drop the "typing…" indicator
        // immediately instead of waiting out the 5 s TypingTracker window,
        // so the header doesn't show "typing" next to a message already on
        // screen.
        app.aether.aegis.chat.TypingTracker.clear(msg.fromKey)
        notifScope.launch {
            // Per-group mute. msg.groupKey is set for group inbound
            // ("group:<uuid>"); when the user has flipped the mute
            // toggle on the group's detail screen we drop the banner
            // and any sound/vibration. The message itself is still
            // recorded into the conversation (the muted state stops
            // attention, not delivery).
            msg.groupKey?.let { gk ->
                val muted = runCatching {
                    app.aether.aegis.prefs.GroupMutePrefs(this@AegisApp).isMuted(gk)
                }.getOrDefault(false)
                if (muted) return@launch
            }
            val peer = runCatching { repository.knownPeerByKey(msg.fromKey) }.getOrNull()
            if (peer?.muted == true) return@launch
            // Quiet hours: suppress chat notifications during the user's
            // configured silence window. SOS + sim-swap + geofence
            // are NOT routed through this path — they always wake the
            // user regardless of quiet hours.
            val quiet = runCatching { app.aether.aegis.quiet.QuietHoursStore(this@AegisApp).isQuietNow() }
                .getOrDefault(false)
            val senderName = peer?.displayName?.takeIf { it.isNotBlank() }
                ?: msg.fromKey.removePrefix("simplex:").ifBlank { "Aegis" }

            // Tap → open the chat. For group messages we send the user
            // to the group conversation; the sender's 1:1 chat is the
            // wrong destination since they may have spoken in the group
            // rather than DM'd us.
            val openTarget = msg.groupKey ?: msg.fromKey
            // Don't buzz for a chat the user is already reading — ActiveChat
            // is non-null only while that conversation is foregrounded.
            if (app.aether.aegis.chat.ActiveChat.key == openTarget) return@launch
            val openChat = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("open_chat", openTarget)
            }
            val tapPI = openChat?.let {
                android.app.PendingIntent.getActivity(
                    this@AegisApp,
                    msg.fromKey.hashCode(),
                    it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            }

            // Person objects for MessagingStyle — Android Auto uses
            // these for sender attribution and for the TTS pipeline.
            val me = androidx.core.app.Person.Builder()
                .setName("You")
                .setKey("self")
                .build()
            val sender = androidx.core.app.Person.Builder()
                .setName(senderName)
                .setKey(msg.fromKey)
                .build()

            // RemoteInput for inline + Android Auto voice reply. The
            // reply broadcast carries the peer key so MessageReplyReceiver
            // knows where to route the response.
            val remoteInput = androidx.core.app.RemoteInput.Builder(MessageReplyReceiver.KEY_REPLY_TEXT)
                .setLabel("Reply to $senderName")
                .build()
            val replyIntent = android.content.Intent(
                this@AegisApp, MessageReplyReceiver::class.java
            ).apply {
                action = MessageReplyReceiver.ACTION_REPLY
                putExtra(MessageReplyReceiver.EXTRA_PEER_KEY, msg.fromKey)
                setPackage(packageName)
            }
            val replyPI = android.app.PendingIntent.getBroadcast(
                this@AegisApp,
                msg.fromKey.hashCode() xor 0x1,
                replyIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_MUTABLE,  // mutable so RemoteInput can attach
            )
            val replyAction = androidx.core.app.NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, "Reply", replyPI,
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(
                    androidx.core.app.NotificationCompat.Action.SEMANTIC_ACTION_REPLY
                )
                // showsUserInterface=false is the contract Android Auto
                // requires — the receiver must handle the reply without
                // opening any UI, otherwise Auto refuses to show the action.
                .setShowsUserInterface(false)
                .build()

            // Mark-as-read action: dismisses the notification, no UI.
            val markReadIntent = android.content.Intent(
                this@AegisApp, MessageReplyReceiver::class.java
            ).apply {
                action = MessageReplyReceiver.ACTION_MARK_READ
                putExtra(MessageReplyReceiver.EXTRA_PEER_KEY, msg.fromKey)
                setPackage(packageName)
            }
            val markReadPI = android.app.PendingIntent.getBroadcast(
                this@AegisApp,
                msg.fromKey.hashCode() xor 0x2,
                markReadIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val markReadAction = androidx.core.app.NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_view, "Mark read", markReadPI,
            )
                .setSemanticAction(
                    androidx.core.app.NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ
                )
                .setShowsUserInterface(false)
                .build()

            // If the user picked a custom notification sound for this
            // peer (ContactDetailScreen → Notification sound), route
            // their messages through a per-peer channel that owns that
            // sound. Channels are the only Android 8+ way to vary
            // sound — setSound() on the builder is ignored.
            val channelId = peer?.notificationSoundUri
                ?.takeIf { it.isNotBlank() }
                ?.let { ensurePeerMessageChannel(msg.fromKey, peer.displayName, it) }
                ?: CHANNEL_MESSAGES
            val notif = NotificationCompat.Builder(this@AegisApp, channelId)
                .setSmallIcon(R.drawable.ic_notif_shield)
                .setColor(BRAND_CYAN_ARGB)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setStyle(
                    NotificationCompat.MessagingStyle(me).addMessage(
                        NotificationCompat.MessagingStyle.Message(
                            msg.body,
                            msg.timestamp,
                            sender,
                        )
                    )
                )
                // In quiet hours: silent (no sound, no vibration). Notification
                // still posts so the shade shows it; sos + control alerts
                // are unaffected (different code paths).
                .apply {
                    if (quiet) {
                        setSilent(true)
                        setPriority(NotificationCompat.PRIORITY_LOW)
                    }
                }
                .addAction(replyAction)
                .addAction(markReadAction)
                .apply { tapPI?.let { setContentIntent(it) } }
                .extend(androidx.core.app.NotificationCompat.CarExtender())
                .build()
            if (canNotify()) {
                // Key the notification to the CONVERSATION (openTarget),
                // not the raw sender — so opening that chat can cancel it
                // by the same id (see ChatScreen ON_RESUME), and a group's
                // messages collapse to one notification per conversation.
                NotificationManagerCompat.from(this@AegisApp)
                    .notify(openTarget.hashCode(), notif)
            }
        }
    }

    /**
     * Surface a SIM-swap alert as a high-priority notification.
     * "$name's SIM changed: $oldCarrier → $newCarrier".
     */
    private fun notifySimSwap(fromKey: String, jsonBody: String) {
        notifScope.launch {
            runCatching {
                val json = org.json.JSONObject(jsonBody)
                val oldC = json.optString("old").ifBlank { "unknown" }
                val newC = json.optString("new").ifBlank { "unknown" }
                val name = repository.knownPeerByKey(fromKey)?.displayName ?: fromKey.take(8)
                val notif = NotificationCompat.Builder(this@AegisApp, CHANNEL_SOS)
                    .setContentTitle("⚠ $name's SIM changed")
                    .setContentText("$oldC → $newC")
                    .setSmallIcon(R.drawable.ic_notif_shield)
                    .setColor(BRAND_SOS_ARGB)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .build()
                if (canNotify()) {
                    NotificationManagerCompat.from(this@AegisApp)
                        .notify(fromKey.hashCode(), notif)
                }
            }
        }
    }

    /**
     * "[name] just tried to access your phone." Fired on every failed
     * remote-auth (wrong PIN, duress PIN, or after auto-revoke). The
     * [tripped] flag is true when this attempt is the one that just
     * triggered the auto-revoke threshold (3 in 60 s) — caller still
     * shows the same notification but with the stronger title.
     */
    fun notifyRemoteAccessAttempt(fromKey: String, tripped: Boolean) {
        notifScope.launch {
            runCatching {
                val name = repository.knownPeerByKey(fromKey)?.displayName ?: fromKey.take(8)
                val title = if (tripped) "🔒 $name auto-revoked — too many tries"
                            else "⚠ $name tried to access your phone"
                val body = if (tripped) "After 3 failed PIN attempts in 60 s, " +
                                "their access was revoked. Open the contact to allow them back."
                           else "Wrong PIN. They cannot access your phone."
                val notif = NotificationCompat.Builder(this@AegisApp, CHANNEL_SOS)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(R.drawable.ic_notif_shield)
                    .setColor(BRAND_SOS_ARGB)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .build()
                if (canNotify()) {
                    NotificationManagerCompat.from(this@AegisApp)
                        .notify("remote-access-$fromKey".hashCode(), notif)
                }
            }
        }
    }

    /** Sender-side: peer told us they revoked our access. Quiet
     *  notification so the user notices the contact's "remote access"
     *  button has just grayed out. */
    fun notifyRemoteAccessRevokedByPeer(fromKey: String) {
        notifScope.launch {
            runCatching {
                val name = repository.knownPeerByKey(fromKey)?.displayName ?: fromKey.take(8)
                val notif = NotificationCompat.Builder(this@AegisApp, CHANNEL_MESSAGES)
                    .setContentTitle("$name revoked your remote access")
                    .setContentText("They can re-enable it from your contact in their app.")
                    .setSmallIcon(R.drawable.ic_notif_shield)
                    .setColor(BRAND_CYAN_ARGB)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
                if (canNotify()) {
                    NotificationManagerCompat.from(this@AegisApp)
                        .notify("remote-revoked-$fromKey".hashCode(), notif)
                }
            }
        }
    }

    /** Per c626800: paired contact just had their device wiped. They
     *  must re-invite for SimpleX to know the new identity. */
    private fun notifyRemotePeerWiped(fromKey: String) {
        notifScope.launch {
            runCatching {
                val name = repository.knownPeerByKey(fromKey)?.displayName ?: fromKey.take(8)
                val notif = NotificationCompat.Builder(this@AegisApp, CHANNEL_SOS)
                    .setContentTitle("💥 $name's Aegis was wiped")
                    .setContentText("Send a new invite link to reconnect — the old identity is gone.")
                    .setSmallIcon(R.drawable.ic_notif_shield)
                    .setColor(BRAND_SOS_ARGB)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .build()
                if (canNotify()) {
                    NotificationManagerCompat.from(this@AegisApp)
                        .notify("remote-wiped-$fromKey".hashCode(), notif)
                }
            }
        }
    }

    private fun notifyGeofence(fromKey: String, jsonBody: String) {
        notifScope.launch {
            runCatching {
                val name = repository.knownPeerByKey(fromKey)?.displayName ?: fromKey.take(8)
                val notif = NotificationCompat.Builder(this@AegisApp, CHANNEL_SOS)
                    .setContentTitle("⚠ $name left their zone")
                    .setContentText("Geofence alert from $name")
                    .setSmallIcon(R.drawable.ic_notif_shield)
                    .setColor(BRAND_SOS_ARGB)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                if (canNotify()) {
                    NotificationManagerCompat.from(this@AegisApp)
                        .notify("geofence-$fromKey".hashCode(), notif)
                }
            }
        }
    }

    private fun notifySOS(msg: InboundMessage) {
        // Distinguish sos-start vs sos-cancel by body prefix. The
        // SimpleX classifier has already stripped the outer `[aegis:sos]`
        // tag; we look at the inner body to decide.
        val body = msg.body
        val isCancel = body.startsWith("[sos cancelled]") || body.startsWith("CANCEL")
        if (isCancel) {
            app.aether.aegis.sos.SOSAlertStore.markEnded(msg.fromKey)
            // Sender ended the sos legitimately — clear the
            // receiver-side dismiss-sticky flag so the NEXT sos
            // from this peer fires alerts again.
            app.aether.aegis.sos.SOSAlertStore.clearDismissed(msg.fromKey)
            // Tear down the receiver-side loops (distance probe,
            // responder GPS) — no need to keep sending pings to a
            // sos that's ended.
            app.aether.aegis.sos.SOSCoordinator.stopReceivingSOS(msg.fromKey)
            NotificationManagerCompat.from(this).cancel(SOS_NOTIF_ID)
            return
        }
        // Receiver previously dismissed THIS peer's sos via the
        // notification action; the sender's 30 s re-broadcast loop
        // is still firing (no cancel envelope received yet). Drop
        // silently so the banner doesn't resurrect every half-minute.
        // Cleared by the sender's proper cancel above, or by the
        // user re-opening the dashboard for this peer.
        if (app.aether.aegis.sos.SOSAlertStore.isDismissed(msg.fromKey)) {
            return
        }
        app.aether.aegis.sos.SOSAlertStore.markActive(msg.fromKey, body)
        // Spin up the receiver-side distance probe (closest-detection
        // feeder). Idempotent — re-entering a still-active sos
        // no-ops because the loop is already running.
        app.aether.aegis.sos.SOSCoordinator.startReceivingSOS(msg.fromKey)
        // Bail before the DB lookup if we can't show the notification
        // anyway. Saves a Room read AND keeps the first-sos banner
        // available for a future sos if the user turns notifications
        // back on — the flag below is only set once the notify() call
        // has actually fired.
        if (!canNotify()) return

        // Build + post asynchronously on the notification scope, so we
        // don't have to runBlocking inside the inbound IO coroutine.
        // The few-millisecond delay between the inbound arriving and
        // the banner showing is invisible — sos notifications already
        // fire on a different (alarm-category, sound + vibration)
        // pipeline that doesn't depend on instant render.
        notifScope.launch {
            runCatching {
                val peer = repository.knownPeerByKey(msg.fromKey)
                val senderName = peer?.displayName ?: peer?.announcedName ?: "Someone"
                val isFirstSOS = peer?.firstSosShownAt == null
                // Duress vs voluntary (SPEC duress trap: sos:duress tag). The
                // wire body carries the SOSTrigger name; a DURESS trigger means
                // the sender entered a duress PIN — coercion, NOT a button press.
                // The classifier strips the outer [aegis:sos], leaving
                // "SOS <TRIGGER> <name>…", so a DURESS start begins "SOS DURESS".
                // A duress alert cannot be accidental (you don't fat-finger a
                // never-used PIN), so contacts are told to treat it as "call the
                // police", vs "call the person first" for a voluntary SOS.
                val isDuress = body.startsWith("SOS DURESS")

                val bigText = when {
                    isDuress ->
                        "$senderName entered a DURESS code — they may be under " +
                            "threat or being forced to act right now. This is NOT an " +
                            "accidental alert. Their location, audio, and a photo are " +
                            "below.\n\n" +
                            "What to do: treat this as an emergency. Consider " +
                            "contacting the police. If there's a voice recording, " +
                            "listen first — it may carry their words.\n\n" +
                            "— SOS payload below —\n" +
                            body
                    isFirstSOS ->
                        "$senderName is signaling distress and has reached out to you " +
                            "because you are part of their safety plan. They have sent " +
                            "their current location, audio recording, and a photo from " +
                            "their device.\n\n" +
                            "What you can do: contact them directly, share this " +
                            "information with emergency services, or pass it to someone " +
                            "who can respond.\n\n" +
                            "— SOS payload below —\n" +
                            body
                    else -> body
                }

                // Tapping the notification routes the user to the dashboard for
                // the SOS peer — map + audio + live-stream Accept + PTT.
                val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("open_sos_dashboard", msg.fromKey)
                }
                val openPi = openIntent?.let {
                    android.app.PendingIntent.getActivity(
                        this@AegisApp, msg.fromKey.hashCode(), it,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                }
                // Receiver-side Dismiss action — the only way out of
                // an ongoing sos banner if the sender never
                // broadcasts a cancel envelope (force-stopped, dead
                // phone, never resolved). Without this the only
                // workaround was force-stopping Aegis itself, which
                // defeats the purpose of running a security app.
                val dismissIntent = android.content.Intent(
                    this@AegisApp, app.aether.aegis.SOSDismissReceiver::class.java,
                ).apply {
                    action = app.aether.aegis.SOSDismissReceiver.ACTION_DISMISS
                    putExtra(app.aether.aegis.SOSDismissReceiver.EXTRA_PEER_KEY, msg.fromKey)
                }
                val dismissPi = android.app.PendingIntent.getBroadcast(
                    this@AegisApp,
                    ("dismiss-${msg.fromKey}").hashCode(),
                    dismissIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                val dismissAction = NotificationCompat.Action.Builder(
                    R.drawable.ic_notif_shield,
                    "Dismiss",
                    dismissPi,
                ).build()

                val notif = NotificationCompat.Builder(this@AegisApp, CHANNEL_SOS)
                    .setContentTitle(
                        // Plain text title — the LunaGlass shield is
                        // the small-icon, so the leading 🚨 emoji
                        // would just duplicate the brand visual with
                        // a non-LunaGlass glyph. Dropped per "drop
                        // completely if can't be LunaGlass."
                        when {
                            isDuress -> "DURESS ALERT — $senderName may be under threat"
                            isFirstSOS -> "You are part of $senderName's safety plan"
                            else -> "SOS from $senderName"
                        }
                    )
                    .setContentText(body.take(120))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                    .setSmallIcon(R.drawable.ic_notif_shield)
                    .setColor(BRAND_SOS_ARGB)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
                    .addAction(dismissAction)
                    .apply {
                        openPi?.let {
                            setContentIntent(it)
                            // Lock-screen takeover:
                            // sos alerts should pop heads-up on the
                            // lock screen, not slide down silently into
                            // the shade. fullScreenIntent forces the
                            // system to inflate as a full-screen alert
                            // when the device is locked OR awake. Same
                            // PendingIntent as the tap action — opens
                            // the dashboard after biometric unlock.
                            setFullScreenIntent(it, true)
                        }
                    }
                    .build()
                NotificationManagerCompat.from(this@AegisApp).notify(SOS_NOTIF_ID, notif)

                // Mark the banner as shown ONLY after notify() succeeded —
                // if notifications are revoked between the canNotify()
                // check above and the actual post, the flag stays null
                // and the banner gets another shot next time.
                if (isFirstSOS) {
                    repository.markFirstSOSShown(msg.fromKey, System.currentTimeMillis())
                }
            }.onFailure { android.util.Log.w("AegisApp", "notifySOS failed", it) }
        }
    }

    private fun canNotify(): Boolean {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Persistent service notification
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Aegis Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Aegis connected"
                setShowBadge(false)
            }
        )
        // Sonar — heads-up + sound so detections actually surface.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SONAR,
                "Sonar detections",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Ultrasonic motion alerts when sonar is armed"
                enableLights(true)
                enableVibration(true)
            }
        )
        // Sentinel inbox — silent by design. "No alarm, ever" applies
        // to receivers too: an inbound sentinel ping should appear in
        // the shade with a soft visual cue, not wake the recipient
        // with a sound that gives away the cascade triggered. Importance
        // LOW keeps it out of heads-up; the inbox screen surfaces them.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SENTINEL_INBOX,
                "Sentinel events from contacts",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Silent notifications when a contact's Sentinel cascade fires"
                setShowBadge(true)
                enableLights(true)
                enableVibration(false)
                setSound(null, null)
            }
        )

        // Messages
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New messages from your contacts"
            }
        )

        // SOS alerts
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SOS,
                "SOS Alerts",
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = "Emergency alerts from your contacts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            }
        )

        // Incoming calls — uses CATEGORY_CALL with PRIORITY_MAX so the
        // notification fires a full-screen heads-up even when the
        // phone is locked, same way regular Phone-app calls do.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Incoming voice and video calls from your contacts"
                enableVibration(true)
                setBypassDnd(true)
                vibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000)
            }
        )
    }

    /**
     * Create (or update) a per-peer message notification channel with the
     * peer's chosen sound URI. Returns the channel id.
     *
     * Android 8+ requires the sound to live on the channel, not the
     * notification. Channels can't have their sound mutated after
     * creation, so when the user picks a different sound we delete the
     * old channel and recreate it. The channel id encodes the peer key
     * hash so it stays stable across renames.
     */
    fun ensurePeerMessageChannel(
        peerKey: String,
        peerDisplayName: String,
        soundUri: String,
    ): String {
        val nm = getSystemService(NotificationManager::class.java) ?: return CHANNEL_MESSAGES
        val channelId = "$CHANNEL_MESSAGES.peer.${peerKey.hashCode().toUInt().toString(16)}"
        val uri = runCatching { android.net.Uri.parse(soundUri) }.getOrNull()
            ?: return CHANNEL_MESSAGES
        val existing = nm.getNotificationChannel(channelId)
        // Recreate iff the sound changed — channel mutation is the only
        // way to swap the sound without a Settings → Notifications detour.
        if (existing != null && existing.sound == uri) return channelId
        if (existing != null) nm.deleteNotificationChannel(channelId)
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Messages from $peerDisplayName",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Custom-sound channel for $peerDisplayName"
                setSound(uri, attrs)
                enableLights(true)
                enableVibration(true)
            }
        )
        return channelId
    }

    companion object {
        lateinit var instance: AegisApp
            private set

        /** Application-lifetime coroutine scope for fire-and-forget
         *  background work that must outlive any single screen but stay
         *  STRUCTURED — one owned scope (cancellable as a group) with a
         *  logging exception handler so a failure surfaces in the log
         *  instead of vanishing or crashing the process. Replaces the
         *  scattered `GlobalScope.launch` the app used to do (unowned,
         *  uncancellable, exceptions lost). Use this for app-scoped work;
         *  use a screen/component's own scope for shorter-lived work. */
        val appScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO +
                kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                    android.util.Log.e("AegisApp", "appScope coroutine failed", e)
                },
        )

        const val CHANNEL_SERVICE = "aegis_service"
        const val CHANNEL_MESSAGES = "aegis_messages"
        const val CHANNEL_SOS = "aegis_sos"
        const val CHANNEL_CALL = "aegis_call"
        /** Sonar detection alerts — DEFAULT importance so the heads-up
         *  fires (CHANNEL_SERVICE is LOW, which posts silently and the
         *  user never sees it scroll past). */
        const val CHANNEL_SONAR = "aegis_sonar"
        const val CHANNEL_SENTINEL_INBOX = "aegis_sentinel_inbox"
        const val SOS_NOTIF_ID = 1000

        /** Brand colours used by NotificationCompat.Builder.setColor().
         *  Without these, Android renders the notification icon badge
         *  with a system-default grey/near-black background — which is
         *  what the user saw as "the icon still has a black background".
         *  Cyan for routine notifications, red for sos-class alerts. */
        const val BRAND_CYAN_ARGB = 0xFF00FFFF.toInt()
        const val BRAND_SOS_ARGB = 0xFFFF0000.toInt()

        /** Read-receipt marker. Body shape: `[aegis:read:<itemId>]`. */
        val AEGIS_READ_RECEIPT = Regex("""\[aegis:read:(\d+)\]""")

        /** Delivery-confirmation marker. Body
         *  shape: `[aegis:delivered:<itemId>]` — recipient's device received
         *  our message (fired before the at-rest seal); drives the single
         *  BRIGHT tick. */
        val AEGIS_DELIVERED_RECEIPT = Regex("""\[aegis:delivered:(\d+)\]""")

        /** Seal-confirmation marker. Body
         *  shape: `[aegis:sealed:<itemId>]` — recipient sealed our message
         *  at rest; drives the bright + dim ✓✓. */
        val AEGIS_SEAL_RECEIPT = Regex("""\[aegis:sealed:(\d+)\]""")

        /** DNA-keyed receipt twins (cross-device read-tick fix). Body shapes
         *  `[aegis:readdna:<nanos>]` / `[aegis:delivereddna:<nanos>]` /
         *  `[aegis:sealeddna:<nanos>]`, where `<nanos>` is the epoch-nanosecond
         *  message DNA the peer echoes back. Matched against our own outbound
         *  rows by messageDna (identical on both ends), not a per-device id. */
        val AEGIS_READ_DNA_RECEIPT = Regex("""\[aegis:readdna:(\d+)\]""")
        val AEGIS_DELIVERED_DNA_RECEIPT = Regex("""\[aegis:delivereddna:(\d+)\]""")
        val AEGIS_SEAL_DNA_RECEIPT = Regex("""\[aegis:sealeddna:(\d+)\]""")

        /** Burn-after-reading receipt — sender wipes the row whose
         *  UUID matches the captured group. Body shape:
         *  `[aegis:burn-receipt:<senderRowId>]`. */
        val AEGIS_BURN_RECEIPT = Regex("""\[aegis:burn-receipt:([^]]+)\]""")
    }
}
