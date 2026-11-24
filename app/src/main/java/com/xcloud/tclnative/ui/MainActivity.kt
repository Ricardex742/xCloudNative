package com.xcloud.tclnative.ui

import com.xcloud.tclnative.i18n.UiText

import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.*
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.InputDevice
import android.hardware.input.InputManager
import android.content.pm.PackageManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnPreDraw
import kotlin.coroutines.resume
import com.xcloud.tclnative.diagnostics.*
import com.xcloud.tclnative.ui.dashboard.SessionReportPanel
import com.xcloud.tclnative.ui.dashboard.SessionTransitionView
import com.xcloud.tclnative.ui.dashboard.PlaybackSettingsPanel
import com.xcloud.tclnative.ui.dashboard.SettingsPage
import com.xcloud.tclnative.catalog.CatalogCard
import com.xcloud.tclnative.catalog.OfficialGalleryCards
import com.xcloud.tclnative.catalog.CatalogCache
import com.xcloud.tclnative.catalog.withMetadataFallback
import java.io.File
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.signaling.SessionTimeLimit
import com.xcloud.tclnative.touch.OfficialTouchLoad
import com.xcloud.tclnative.touch.OfficialTouchSession
import com.xcloud.tclnative.touch.MinecraftDungeonsTouchLayout
import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.ui.dashboard.GameLoadingView
import com.xcloud.tclnative.ui.dashboard.GameMenuPanel
import com.xcloud.tclnative.ui.dashboard.GameShortcutBar
import com.xcloud.tclnative.ui.dashboard.GameShortcutEditor
import com.xcloud.tclnative.ui.dashboard.GameNetworkPanel
import com.xcloud.tclnative.ui.dashboard.NetworkIndicator
import com.xcloud.tclnative.ui.dashboard.SessionOptionsPanel
import com.xcloud.tclnative.ui.dashboard.ConsoleLoginView
import com.xcloud.tclnative.ui.dashboard.ConsoleDialogs
import com.xcloud.tclnative.ui.dashboard.ConsoleAction
import com.xcloud.tclnative.catalog.CatalogService
import com.xcloud.tclnative.catalog.GalleryProduct
import com.xcloud.tclnative.catalog.OfficialGalleryKind
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.CloudPlaytimeService
import com.xcloud.tclnative.catalog.CloudPlaytime
import com.xcloud.tclnative.catalog.CloudPlaytimeStatus
import com.xcloud.tclnative.catalog.OwnershipFilter
import com.xcloud.tclnative.home.GameLaunchLink
import com.xcloud.tclnative.home.GameShortcutInstaller
import com.xcloud.tclnative.home.PendingGameLaunch
import com.xcloud.tclnative.home.HomeShortcuts
import com.xcloud.tclnative.ui.dashboard.PosterLoader
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import com.xcloud.tclnative.ui.dashboard.GameDashboardView
import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.auth.XalAuthenticator
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import com.xcloud.tclnative.signaling.RemoteConsole
import com.xcloud.tclnative.ui.dashboard.RemotePlayPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.launch

open class MainActivity : Activity() {
    protected open fun createAuthenticator() = XalAuthenticator(this)
    protected open fun createCatalogService(cache: CatalogCache? = null, beforeRequest: suspend () -> Unit = {}) =
        CatalogService(cache=cache,beforeRequest=beforeRequest)

    private lateinit var rootLayout: FrameLayout
    private val playbackDisplay by lazy { com.xcloud.tclnative.renderer.PlaybackDisplayPreference(window) }
    private val joystickDispatch by lazy { com.xcloud.tclnative.webrtc.JoystickDispatchPreference(window.decorView) }
    private var immediateJoystickForSession=false
    private lateinit var surfaceRenderer: AiPqSurfaceRenderer
    private lateinit var hudTextView: TextView
    private lateinit var statsView: TextView
    private lateinit var networkIndicator: NetworkIndicator
    private val shortcutSettings by lazy { GameShortcutSettings(this) }
    private var shortcutEditor: GameShortcutEditor? = null
    private var networkPanel: GameNetworkPanel? = null
    private val networkQuality = NetworkQualityMonitor()
    private var networkWarningsEnabled = false
    private val statistics = StatisticsVisibility()
    private val statisticsStore by lazy { StatisticsSettingsStore(this) }
    private var statisticsSettings=StatisticsSettings()
    private var latestMetrics: StreamMetrics?=null
    private var latestInput=InputMetrics()
    private var transmissionReady=false
    private var sessionSaved = false
    private var pendingResumeResolution: com.xcloud.tclnative.webrtc.StreamResolution? = null
    private var transmissionRecovery: TransmissionRecoveryState?=null
    private var transmissionReloadJob: Job?=null
    private var recoverImage: (() -> Unit)?=null
    private var reloadTransmission: ((Int?) -> Unit)?=null
    private lateinit var settingsStore: PlaybackSettingsStore
    private var playbackSettings = PlaybackSettings()
    private lateinit var controlPanel: LinearLayout
    private lateinit var authenticator: XalAuthenticator
    private var webrtcClient: XCloudWebRtcClient? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val playback = PlaybackCoordinator(mainScope, android.os.SystemClock::elapsedRealtime)
    private var microphoneJob: Job?=null
    private var pendingMicrophoneGeneration: Int?=null
    private var sessionSettings: PlaybackSettings?=null
    private var sessionDesktopMode=DesktopInputMode.OFF
    private var desktopCaptured=false
    private var desktopFocusState: Pair<Boolean,Boolean>?=null
    private var lastMousePosition: Pair<Float,Float>?=null
    private val desktopGamepad=DesktopGamepad()
    private val auxiliaryHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val releaseMouseMotion=Runnable { desktopGamepad.releaseMotion();if(gameplayInputEnabled()) publishGamepad(0) }
    private val controllerProfiles by lazy { ControllerProfileStore(this) }
    private val gyro by lazy { GyroController(this,{ window.decorView.display?.rotation ?: android.view.Surface.ROTATION_0 }) {
        if(gameplayInputEnabled()) publishGamepad(0)
    } }
    private val playbackGeneration get() = playback.generation
    private val controllers=ControllerRouter()
    private var xboxButtonJob: Job? = null
    private val controllerPath=ControllerPathTrace()
    private val usbNavigation by lazy { com.xcloud.tclnative.ui.dashboard.UsbMenuNavigation(this) }
    private var usbBlockedButtons=0
    private var usbShortcutHeld=0
    private val usbDirect: UsbDirectController by lazy { UsbDirectController(this,
        connected={ source,androidId ->
            val aliases=usbDirect.androidAliases()
            val retired=(aliases+listOfNotNull(androidId)).mapNotNull { controllers.existingSlot(ControllerSource.Android(it)) }.toSet()
            controllers.attachUsb(source,androidId,aliases)?.let { slot ->
                retired.filter { it!=slot }.forEach { webrtcClient?.removeGamepad(it) }
                if(gameplayInputEnabled()) publishGamepad(slot)
            }
        },input=::handleUsbInput,disconnected=::removeUsbController).also {
            com.xcloud.tclnative.ui.dashboard.AppControllerWindows.usb(this,it)
        }
    }
    private val usbRumble by lazy { com.xcloud.tclnative.webrtc.UsbControllerRumble(this) }
    private val rumble by lazy { ControllerRumble(controllers::deviceFor,usbRumble) {
        if(InputDeviceProfile.read(this).touchDevice && currentTouchMode!=TouchMode.OFF)
            getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator else null
    }.apply {
        directRumble={ command ->
            val source=controllers.sourceFor(command.player) as? ControllerSource.Usb
            if(source==null) false else { usbDirect.play(command,source);true }
        }
        stopDirect=usbDirect::stopRumble
        directDiagnostics={ if(usbDirect.source!=null) usbDirect.snapshot().status.label else null }
    } }
    private lateinit var touchView: GameTouchView
    private var officialTouch: OfficialTouchSession? = null
    private var loadOfficialTouch: (() -> Unit)? = null
    private lateinit var sessionTimeView: TextView
    private lateinit var sessionStartNotice: SessionStartNotice
    private val dismissSessionTimeNotice = Runnable { sessionTimeView.visibility = View.GONE }
    private lateinit var guideTouchControls: GuideTouchControls
    private var currentTouchMode=TouchMode.OFF
    private val hudStore by lazy { com.xcloud.tclnative.touch.GameHudStore(this) }
    private var preferredControllerId: Int?=null
    private val televisionInput by lazy { InputDeviceProfile.read(this).television }
    private val deviceListener=object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { refreshControllerGyro() }
        override fun onInputDeviceChanged(deviceId: Int) = onInputDeviceRemoved(deviceId)
        override fun onInputDeviceRemoved(deviceId: Int) {
            if(preferredControllerId==deviceId) preferredControllerId=null
            removeController(deviceId)
            refreshControllerGyro()
        }
    }
    private var qrPollingJob: Job? = null
    private lateinit var queuePanel: GameLoadingView
    private var pendingLoadingGame: GameTitle? = null
    private var queueTicker: Job? = null
    private val controllerShortcuts = Array(4) { ControllerShortcut() }
    private val shortcutFlushJobs = arrayOfNulls<Job>(controllerShortcuts.size)
    private val recoveryMenuShortcut = ControllerShortcut()
    private var recoveryMenuDeviceId: Int? = null
    private var videoRecoveryDialog: Dialog? = null
    private var nativeTouchDialog: Dialog? = null
    private var dashboard: GameDashboardView? = null
    private var dashboardJob: Job? = null
    private var galleryLaunchJob: Job? = null
    private var recentHistoryJob: Job? = null
    private var ownershipJob: Job? = null
    private var playtimeJob: Job? = null
    private var friendsPanel: com.xcloud.tclnative.ui.dashboard.FriendsPanel?=null
    private var playtimeCheckedAt: Long?=null
    private var gameLibrary: GameLibrary? = null
    private var loginView: ConsoleLoginView? = null
    private var accountJob: Job? = null
    private var currentSignaling: XCloudSignalingClient? = null
    private var playbackSignaling: XCloudSignalingClient? = null
    private var remotePlayPanel: RemotePlayPanel? = null
    private var remotePlayReturn: (() -> Unit)? = null
    private var currentSession: XCloudSession? = null
    private lateinit var transitionPanel: SessionTransitionView
    private var startupArtJob: Job? = null
    private var catalogReturnJob: Job? = null
    private var pendingAutomaticReport: SessionReport? = null
    private var loadingArtJob: Job? = null
    private val catalogCache by lazy { CatalogCache(File(cacheDir,"catalog-art-v2.json")) }
    private var currentGames = emptyList<GameTitle>()
    private val catalogWork = com.xcloud.tclnative.catalog.CatalogWorkGate()
    private val streamHistory = com.xcloud.tclnative.webrtc.StreamDiagnosticHistory()
    private var reportTracker: SessionReportTracker?=null
    private val reportStore by lazy { SessionReportStore(applicationContext) }
    private val startupReportStore by lazy { StartupDiagnosticsStore(applicationContext) }
    private var lastSessionReport: SessionReport?=null
    private var pendingDiagnosticExport: String?=null
    private var postSessionDialog: Dialog?=null
    @Volatile private var activityVisible=false
    private var videoVisibilityEpoch = 0
    private var activeGame: GameTitle? = null
    private var pendingLaunch = PendingGameLaunch()
    private val homeShortcuts by lazy { HomeShortcuts(this) }
    private val shortcutPosters by lazy { PosterLoader(this,mainScope) }
    private val homeJobs by lazy { com.xcloud.tclnative.home.HomePublicationJobs(mainScope) }
    private var shortcutInstallJob: Job? = null
    private var pendingShortcutApk: File? = null
    private var pendingHomeChannel: Long? = null
    private var lastShortcutRequest: String? = null
    private companion object {
        const val REQUEST_HOME_CHANNEL=4101
        const val REQUEST_SHORTCUT_INSTALL_PERMISSION=4102
        const val REQUEST_DIAGNOSTIC_EXPORT=4103
    }
    private var gameMenuDialog: Dialog? = null
    private var displayCutout=android.graphics.Rect()
    private var shortcutSwitchDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        com.xcloud.tclnative.i18n.UiText.install(this)
        com.xcloud.tclnative.ui.dashboard.DashboardTheme.install(this)
        super.onCreate(savedInstanceState)
        pendingDiagnosticExport = savedInstanceState?.getString("diagnostic_export")
        AndroidControllerMonitor.clear()
        volumeControlStream=android.media.AudioManager.STREAM_MUSIC
        pendingLaunch = PendingGameLaunch(savedInstanceState?.getString("pending_game"))
        lastShortcutRequest=savedInstanceState?.getString("last_shortcut_request")
        val shortcutRequest=intent.getStringExtra("shortcut_request")
        if (savedInstanceState == null || (shortcutRequest!=null && shortcutRequest!=lastShortcutRequest)) pendingLaunch.receive(intent)
        lastShortcutRequest=shortcutRequest ?: lastShortcutRequest
        pendingHomeChannel=savedInstanceState?.getLong("pending_home_channel",-1)?.takeIf { it>=0 }
        savedInstanceState?.getString("pending_shortcut_apk")?.takeIf { GameShortcutInstaller.isShortcutApkName(it) }
            ?.let { pendingShortcutApk=File(cacheDir,"game-shortcuts/$it").takeIf(File::isFile) }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ImmersiveWindow.apply(window,edgeToEdge=true)
        authenticator = createAuthenticator()
        settingsStore = PlaybackSettingsStore(this)
        playbackSettings = settingsStore.read()
        configureVideoOutput(playbackSettings)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        surfaceRenderer = AiPqSurfaceRenderer(this)
        surfaceRenderer.minimalPostProcessingRequested = playbackSettings.videoMode.lowLatency && Build.VERSION.SDK_INT >= 30
        surfaceRenderer.nativeUpscaling = playbackSettings.nativeUpscaling
        surfaceRenderer.aspect = playbackSettings.aspect
        rootLayout.addView(
            surfaceRenderer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val androidVer = Build.VERSION.RELEASE
        val socModel = Build.HARDWARE
        hudTextView = com.xcloud.tclnative.i18n.LocalizedTextView(this).apply {
            text = UiText.text("xCLOUD [Android {0} | SoC: {1}] | Aguardando sessão",UiText.nested(androidVer),UiText.nested(socModel))
            setTextColor(Color.GREEN)
            textSize = 12f
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(20, 8, 20, 8)
        }

        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 12
        }
        rootLayout.addView(hudTextView, hudParams)

        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(235, 16, 16, 20))
            setPadding(36, 24, 36, 24)
        }

        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            topMargin = 56
            bottomMargin = 16
            marginStart = 48
            marginEnd = 48
        }
        rootLayout.addView(controlPanel, panelParams)

        queuePanel = GameLoadingView(this) { cancelPlayback() }
        rootLayout.addView(queuePanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        statsView = com.xcloud.tclnative.i18n.LocalizedTextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(14, 10, 14, 10)
            visibility = View.GONE
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        rootLayout.addView(statsView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
        ).apply { topMargin = (56 * resources.displayMetrics.density).toInt(); marginStart = 12 })
        networkWarningsEnabled = shortcutSettings.networkWarnings
        networkIndicator = NetworkIndicator(this) {
            if (gameplayInputEnabled()) { resetController(); showNetworkDetails {} }
        }
        ImmersiveWindow.observeCutout(rootLayout) { cutout ->
            if(displayCutout!=cutout) {
                displayCutout=cutout
                (gameMenuDialog as? GameShortcutBar)?.setCutout(cutout)
                if (::guideTouchControls.isInitialized) guideTouchControls.setCutout(cutout)
                networkIndicator.setCutout(cutout, if (::guideTouchControls.isInitialized && guideTouchControls.active)
                    guideTouchControls.cornerControlSize else 0)
                networkPanel?.dialog?.setNetworkCutout(cutout)
                if (::sessionTimeView.isInitialized) {
                    val params = sessionTimeView.layoutParams as FrameLayout.LayoutParams
                    params.topMargin = (48 * resources.displayMetrics.density).toInt() + cutout.top
                    sessionTimeView.layoutParams = params
                }
                if (::sessionStartNotice.isInitialized) {
                    val params = sessionStartNotice.layoutParams as FrameLayout.LayoutParams
                    val inset = (16 * resources.displayMetrics.density).toInt()
                    params.leftMargin = inset + cutout.left
                    params.rightMargin = inset + cutout.right
                    params.bottomMargin = (18 * resources.displayMetrics.density).toInt() + cutout.bottom
                    sessionStartNotice.layoutParams = params
                }
                com.xcloud.tclnative.ui.dashboard.StatisticsOverlayStyle.apply(statsView,statisticsSettings,displayCutout)
                renderStatistics()
            }
        }
        refreshStatisticsSettings()
        touchView=GameTouchView(this,
            onGamepad={ state ->
                controllers.virtual(state)
                val officialWithNative = currentTouchMode==TouchMode.NATIVE && touchView.usingOfficialControls
                if ((currentTouchMode==TouchMode.VIRTUAL || officialWithNative) &&
                    transmissionRecovery?.phase==TransmissionRecoveryState.Phase.ACTIVE)
                    publishGamepad(0, temporary = officialWithNative, preserveButtonEdges = officialWithNative)
            },
            onPointers={ events -> if(transmissionRecovery?.phase==TransmissionRecoveryState.Phase.ACTIVE) webrtcClient?.sendPointers(events) },
            coordinates=surfaceRenderer::touchCoordinates,
            onMenu={ showTouchGameMenu() })
        surfaceRenderer.onDisplayDimensionsChanged = { webrtcClient?.updateDisplayDimensions(it) }
        touchView.onHudSaved={ settings ->
            val game=activeGame
            if(game==null) false else try { hudStore.save(game.titleId,settings); true }
            catch(error: java.io.IOException) { Toast.makeText(this,error.message,Toast.LENGTH_LONG).show(); false }
        }
        touchView.onReadOfficialHud = { layout ->
            activeGame?.let { hudStore.readOfficial(it.titleId, layout) }.orEmpty()
        }
        touchView.onOfficialHudSaved = { layout, positions ->
            val game = activeGame
            if (game == null) false else try {
                hudStore.saveOfficial(game.titleId, layout, positions)
                true
            } catch (error: java.io.IOException) {
                Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                false
            }
        }
        touchView.onBindingRequested={ id,action ->
            com.xcloud.tclnative.ui.dashboard.HudActionDialogs.action(this,action) { chosen ->
                if(chosen!=null) touchView.setEditedAction(id,chosen)
            }
        }
        touchView.onGesturesRequested={ id,gestures ->
            com.xcloud.tclnative.ui.dashboard.HudActionDialogs.gestures(this,gestures) { touchView.setEditedGestures(id,it) }
        }
        rootLayout.addView(touchView,FrameLayout.LayoutParams(-1,-1))
        guideTouchControls = GuideTouchControls(this,
            onButtonsChanged = { if (gameplayInputEnabled()) publishGamepad(0, temporary = true) },
            onClose = { hideGuideTouchControls(); resumeAuxiliaryInput() })
        rootLayout.addView(guideTouchControls,FrameLayout.LayoutParams(-1,-1))
        guideTouchControls.setCutout(displayCutout)
        val density = resources.displayMetrics.density
        sessionTimeView = com.xcloud.tclnative.i18n.LocalizedTextView(this).apply {
            setPadding((10 * density).toInt(),(4 * density).toInt(),(10 * density).toInt(),(4 * density).toInt())
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(204, 12, 18, 20))
            textSize = 12f
            visibility = View.GONE
        }
        rootLayout.addView(sessionTimeView, FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (48 * density).toInt() + displayCutout.top
        })
        sessionStartNotice = SessionStartNotice(this)
        rootLayout.addView(sessionStartNotice, FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (18 * density).toInt() + displayCutout.bottom
            leftMargin = (16 * density).toInt() + displayCutout.left
            rightMargin = (16 * density).toInt() + displayCutout.right
        })
        rootLayout.addView(networkIndicator)
        transitionPanel=SessionTransitionView(this)
        rootLayout.addView(transitionPanel,FrameLayout.LayoutParams(-1,-1))
        setContentView(rootLayout)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(deviceListener,null)

        val savedTicket = authenticator.getSavedRpsTicket()
        if (!savedTicket.isNullOrEmpty() && authenticator.supportsConsoleTransfer()) {
            renderSessionRestore()
            connectToXboxCloud()
        } else {
            renderLoginMenu()
            if(!savedTicket.isNullOrEmpty()) loginView?.showError(UiText.text("Atualize seu login para conectar os jogos."))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val request = intent.getStringExtra("shortcut_request")
        if (request != null && request == lastShortcutRequest) return
        if (pendingLaunch.receive(intent)) {
            setIntent(intent)
            lastShortcutRequest=request
            if (playback.job?.isActive != true && transitionPanel.visibility==View.VISIBLE && !transitionPanel.exiting) {
                transitionPanel.showStartup(pendingLaunch.titleId?.let { GameTitle(it,UiText.text("Seu jogo")) },UiText.text("Preparando seu jogo…"))
                loadStartupArtwork()
            }
            launchPendingShortcut()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_game",pendingLaunch.titleId)
        outState.putString("last_shortcut_request",lastShortcutRequest)
        outState.putString("pending_shortcut_apk",pendingShortcutApk?.name)
        outState.putLong("pending_home_channel",pendingHomeChannel ?: -1)
        outState.putString("diagnostic_export", pendingDiagnosticExport)
        super.onSaveInstanceState(outState)
    }

    private fun launchPendingShortcut(): Boolean {
        val signaling = currentSignaling ?: return false // Login/catalog loading retain the pending game.
        if (pendingLaunch.titleId == null) return false
        val game = pendingLaunch.consume(currentGames)
        if (game == null) {
            ConsoleDialogs.message(this,UiText.text("Atalho indisponível"),UiText.text("Este jogo não está disponível no catálogo desta conta. Atualize o catálogo nas configurações."))
        } else if (playback.job?.isActive != true) {
            launchCloudGame(signaling, game, fromShortcut = true)
            return true
        } else if (sessionSaved && remotePlayReturn == null && activeGame?.titleId == game.titleId) {
            resumeSavedSession()
            return true
        } else if (activeGame?.titleId != game.titleId && shortcutSwitchDialog == null) {
            val generation = playbackGeneration
            val dialog = ConsoleDialogs.message(this, UiText.text("Trocar de jogo"),
                UiText.text("Encerrar a sessão atual e abrir {0}?", UiText.nested(game.name)),
                listOf(ConsoleAction(UiText.text("Cancelar")) {}, ConsoleAction(UiText.text("Jogar na nuvem")) {
                    if (generation == playbackGeneration && currentSignaling === signaling && activityVisible) {
                        launchCloudGame(signaling, game, fromShortcut = true)
                    }
                }))
            shortcutSwitchDialog = dialog
            dialog.setOnDismissListener { if (shortcutSwitchDialog === dialog) shortcutSwitchDialog = null }
        }
        return false
    }

    private fun configureVideoOutput(settings: PlaybackSettings, playing: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 30) window.setPreferMinimalPostProcessing(settings.videoMode.lowLatency)
        if (!playing) return
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        val display = windowManager.defaultDisplay
        fun mode(value: android.view.Display.Mode) = com.xcloud.tclnative.renderer.OutputMode(
            value.modeId,value.physicalWidth,value.physicalHeight,value.refreshRate)
        val current=mode(display.mode)
        val supported=display.supportedModes.map(::mode)
        val tv4k = if (uiMode.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION &&
            (settings.nativeUpscaling || (settings.fsr==com.xcloud.tclnative.settings.FsrMode.FSR1 && settings.videoMode!=VideoMode.COMPATIBILITY)))
            com.xcloud.tclnative.renderer.DisplayModePolicy.preferred4k60(current,supported) else null
        val fps=if(settings.videoMode==VideoMode.COMPATIBILITY) 30 else 60
        playbackDisplay.request(tv4k ?: com.xcloud.tclnative.renderer.DisplayModePolicy.preferredCadence(current,supported,fps))
    }

    internal fun hasPlaybackBehindPanels() = sessionSettings != null && activeGame != null && !sessionSaved

    private fun renderLoginMenu() {
        if (sessionSaved) {
            playback.cancel()
            activeGame = null
            clearSavedSession()
        }
        remotePlayPanel?.dialog?.dismiss()
        remotePlayPanel = null
        remotePlayReturn = null
        friendsPanel?.close();friendsPanel=null
        playtimeJob?.cancel();playtimeJob=null;playtimeCheckedAt=null
        ownershipJob?.cancel();ownershipJob=null
        galleryLaunchJob?.cancel(); galleryLaunchJob=null
        recentHistoryJob?.cancel(); recentHistoryJob=null
        accountJob?.cancel()
        qrPollingJob?.cancel()
        dashboardJob?.cancel()
        dashboard?.close(); dashboard = null
        loginView?.close()
        gameLibrary = null
        currentSignaling = null
        currentSession = null
        startupArtJob?.cancel(); startupArtJob=null
        transitionPanel.dismiss(); pendingAutomaticReport=null
        currentGames = emptyList()
        controlPanel.removeAllViews()
        controlPanel.setPadding(0,0,0,0)
        controlPanel.setBackgroundColor(Color.TRANSPARENT)
        controlPanel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT)
        val view = ConsoleLoginView(this,
            signIn = { startDeviceCodeLoginFlow(openInBrowser = false) },
            back = { if (loginView?.isQr == true) renderLoginMenu() else showExitDialog() },
            openApproval = ::openAccountPage,
            browserSignIn = { startDeviceCodeLoginFlow(openInBrowser = true) })
        loginView = view
        controlPanel.addView(view, LinearLayout.LayoutParams(-1,-1))
        controlPanel.visibility = View.VISIBLE
        hudTextView.visibility = View.GONE
    }

    private fun renderSessionRestore() {
        loginView?.close(); loginView=null
        controlPanel.removeAllViews(); controlPanel.visibility=View.GONE
        val id=pendingLaunch.titleId
        transitionPanel.showStartup(id?.let { GameTitle(it,UiText.text("Seu jogo")) },
            if(id!=null) UiText.text("Preparando seu jogo…") else UiText.text("Restaurando sua sessão…"))
        hudTextView.visibility=View.GONE
        loadStartupArtwork()
    }
    private fun loadStartupArtwork() {
        startupArtJob?.cancel()
        val id=pendingLaunch.titleId ?: return
        startupArtJob=mainScope.launch {
            val game=withContext(Dispatchers.IO) { catalogCache.artwork(id) }
            if(game!=null && pendingLaunch.titleId==id && transitionPanel.visibility==View.VISIBLE && !transitionPanel.exiting)
                transitionPanel.setGame(game)
        }
    }
    private fun accountProgress(message: String) {
        loginView?.showConnecting(message)
        if(transitionPanel.visibility==View.VISIBLE && !transitionPanel.exiting) transitionPanel.updateStatus(message)
    }
    private fun accountFailure(error: Throwable, cloudOnly: Boolean = false, retry: () -> Unit) {
        if(error is com.xcloud.tclnative.auth.AuthenticationRequiredException && !cloudOnly) {
            authenticator.clearSession(); renderLoginMenu()
            loginView?.showError(UiText.text("Sua sessão precisa ser renovada. Entre novamente na sua conta."))
        } else {
            val message = UiText.text("Não foi possível restaurar a conexão agora. Seu login salvo foi preservado.")
            loginView?.showError(message)
            ConsoleDialogs.message(this,UiText.text("Conexão não concluída"),message,listOfNotNull(
                ConsoleAction(UiText.text("Tentar novamente"),retry),
                ConsoleAction(UiText.text("Reprodução remota")) { showRemotePlay(onBack = retry) }
                    .takeIf { authenticator.getSavedRpsTicket() != null },
                ConsoleAction(UiText.text("Entrar novamente")) { renderLoginMenu() }))
        }
    }

    private fun startDeviceCodeLoginFlow(openInBrowser: Boolean) {
        qrPollingJob?.cancel()
        val view = loginView ?: return
        view.beginQr(openInBrowser)
        qrPollingJob = mainScope.launch {
            val qrRes = authenticator.requestDeviceCodeQr()
            qrRes.onSuccess { info ->
                if (loginView !== view) return@onSuccess
                view.showQr(info)
                authenticator.pollForDeviceCodeLogin(info) { interrupted ->
                    withContext(Dispatchers.Main) {
                        if (loginView === view) view.showApprovalConnection(interrupted)
                    }
                }.onSuccess {
                    if (loginView === view) connectToXboxCloud()
                }.onFailure { error ->
                    if (loginView === view) view.showError(UiText.text("Não foi possível concluir o login. {0}",UiText.nested(error.message.orEmpty())))
                }
            }.onFailure { error ->
                if (loginView === view) view.showError(UiText.text("Não foi possível gerar o QR Code. {0}",UiText.nested(error.message.orEmpty())))
            }
        }
    }

    private fun connectToXboxCloud() {
        accountJob?.cancel()
        val view = loginView
        accountProgress(UiText.text("Conectando sua conta ao Xbox…"))
        accountJob = mainScope.launch {
            authenticator.authenticateToXCloud().onSuccess { session ->
                try {
                    authenticator.getConsoleTransferToken(session.credentialGeneration)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (loginView === view) accountFailure(error, cloudOnly = true) { connectToXboxCloud() }
                    return@launch
                }
                if (loginView !== view) return@launch
                currentSession=session
                accountProgress(if(pendingLaunch.titleId!=null) UiText.text("Preparando o jogo do atalho…") else UiText.text("Conta conectada. Carregando seu catálogo…"))
                renderGameDashboard(session)
            }.onFailure { error ->
                if (loginView === view) accountFailure(error) { connectToXboxCloud() }
            }
        }
    }

    private fun showExitDialog() {
        ConsoleDialogs.message(this,UiText.text("Sair do aplicativo?"),
            UiText.text("Seu login e seus favoritos continuarão salvos neste aparelho."), listOf(
                ConsoleAction(UiText.text("Continuar")) {}, ConsoleAction(UiText.text("Sair")) { finish() }))
    }

    private fun renderGameDashboard(session: XCloudSession, validatedGames: List<GameTitle>? = null) {
        playtimeJob?.cancel();playtimeJob=null;playtimeCheckedAt=null
        ownershipJob?.cancel();ownershipJob=null
        galleryLaunchJob?.cancel(); galleryLaunchJob=null
        dashboardJob?.cancel()
        recentHistoryJob?.cancel(); recentHistoryJob=null
        val signaling = XCloudSignalingClient(session, transferTokenProvider = {
            authenticator.getConsoleTransferToken(session.credentialGeneration)
        }, renewCloudAuthorization = authenticator::renewCloudAuthorization)
        dashboardJob = mainScope.launch {
            val allGames = try {
                validatedGames ?: signaling.fetchCatalogGames().let { raw ->
                    withContext(Dispatchers.IO) { catalogCache.restore(raw) }
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                accountFailure(error) { renderGameDashboard(session,validatedGames) }
                return@launch
            }
            dashboard?.close()
            loginView?.close(); loginView = null
            val library = validatedGames?.let { gameLibrary } ?: withContext(Dispatchers.IO) {
                GameLibrary(this@MainActivity, session.libraryAccountId, session.userHash)
            }
            gameLibrary = library
            currentGames=allGames; currentSignaling=signaling
            if(!sessionSaved && launchPendingShortcut()) {
                loginView?.close(); loginView=null
                return@launch // No catalog/grid or background galleries while the shortcut enters its game.
            }
            val catalog = createCatalogService(catalogCache,catalogWork::await)
            val progress = com.xcloud.tclnative.catalog.XboxGameProgressService()
            val view = GameDashboardView(this@MainActivity, library,
                onPlay = { game -> chooseLaunchMode(signaling, game) },
                onSearch = { showCatalogSearch() },
                onOptions = { showSettings(session) },
                onExit = { if (sessionSaved) resumeSavedSession() else showExitDialog() },
                onFavoritesChanged = { syncHomeShortcuts() },
                onPlayProduct = { product -> playGalleryProduct(signaling,product) },
                onOwnershipNeeded = { requestOwnership() },onGameShortcut={ showGameShortcut(it) },
                onFriends={ showFriends { dashboard?.restoreFocus() } },
                onThemeSettings={ page -> showSettings(session,page) { dashboard?.restoreFocus() } },
                onRemotePlay={ showRemotePlay() }, onLoadGameDetails=catalog::gameDetails,
                onLoadGameProgress={ title ->
                    check(currentSession === session)
                    val xuid=session.xuid ?: authenticator.loadXboxProfile().getOrThrow().xuid
                    if (xuid == null) null else {
                        val authorization=authenticator.loadXboxSocialAuthorization().getOrThrow()
                        check(currentSession === session)
                        progress.load(authorization, xuid, title, UiText.locale.toLanguageTag())
                    }
                },
                onGameOptions={ game ->
                    SessionOptionsPanel(this@MainActivity,sessionOptionsStore(session),
                        session.regions,game.titleId,game.name) {}.show()
                },
                onStoreProduct={ id -> openAccountPage("https://www.xbox.com/pt-BR/games/store/game/$id") },
                onLoadFriends={ loadXboxFriends(session) })
            dashboard = view
            controlPanel.removeAllViews()
            controlPanel.setPadding(0, 0, 0, 0)
            controlPanel.setBackgroundColor(Color.TRANSPARENT)
            controlPanel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            controlPanel.addView(view, LinearLayout.LayoutParams(-1, -1))
            controlPanel.visibility = View.VISIBLE
            hudTextView.visibility = View.GONE
            view.setGames(allGames)
            showSavedSessionButton(view)
            revealDashboard(view)
            refreshCloudPlaytime(view)
            refreshCloudHistory(view,signaling,library)
            currentGames = allGames
            currentSignaling = signaling
            syncHomeShortcuts()
            if (!sessionSaved) launchPendingShortcut()
            val baseIds=allGames.mapTo(hashSetOf()) { it.titleId }
            val nativeMetadata = async {
                catalog.hydrate(allGames, onProgress = { progress -> withContext(Dispatchers.Main) {
                    if (dashboard === view) view.setMetadataProgress(progress)
                } }) { hydrated -> withContext(Dispatchers.Main) {
                    if (dashboard === view) {
                        val known = currentGames.associateBy { it.titleId }
                        currentGames = hydrated.map { fresh -> known[fresh.titleId]?.let(fresh::withMetadataFallback) ?: fresh } +
                            currentGames.filter { it.titleId !in baseIds }
                        if(controlPanel.visibility==View.VISIBLE) view.updateMetadata(currentGames)
                        refreshLoadingArt(); syncHomeShortcuts()
                    }
                } }
            }
            OfficialGalleryKind.entries.forEach { kind -> launch {
                val gallery=try { catalog.officialGallery(kind) }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { null }
                if(dashboard===view) view.setOfficialGallery(kind,gallery)
                if(gallery!=null) {
                    nativeMetadata.await()
                    val known=currentGames.filter { it.name!=it.titleId && (it.posterUrl!=null || it.tileUrl!=null) }
                        .mapNotNull { it.productId?.let(com.xcloud.tclnative.catalog.GamePassCatalog::productKey) }.toSet()
                    val knownEnglish = currentGames.filter { it.englishName != null }
                        .mapNotNull { it.productId?.let(com.xcloud.tclnative.catalog.GamePassCatalog::productKey) }.toSet()
                    val knownGenres = currentGames.filter { it.genres.isNotEmpty() }
                        .mapNotNull { it.productId?.let(com.xcloud.tclnative.catalog.GamePassCatalog::productKey) }.toSet()
                    catalog.hydrateOfficialGallery(gallery, known, knownEnglish, knownGenres) { hydrated -> withContext(Dispatchers.Main) {
                        if(dashboard===view) view.updateOfficialGallery(hydrated)
                    } }
                }
            } }
            launch {
                view.setAddedGallery(catalog.gallery(CatalogService.RECENTLY_ADDED))
            }
            launch {
                authenticator.loadXboxProfile().onSuccess { profile ->
                    if (dashboard === view) {
                        if (profile.xuid != null && (session.xuid == null || session.xuid == profile.xuid)) {
                            withContext(Dispatchers.IO) {
                                library.bindXboxAccount(profile.xuid)
                                SessionOptionsStore(this@MainActivity, "xuid:${profile.xuid}", session.userHash)
                            }
                            view.refresh(); syncHomeShortcuts()
                        }
                        view.setProfile(profile)
                    }
                }
            }
        }
    }

    private fun showRemotePlay(selectedId: String? = null, onBack: (() -> Unit)? = null) {
        if (!activityVisible || activeGame != null || isFinishing || isDestroyed) return
        remotePlayPanel?.dialog?.dismiss()
        transitionPanel.dismiss()
        val panel = RemotePlayPanel(this, ::openAccountPage)
        remotePlayPanel = panel
        var discover: Job? = null
        var connecting = false
        fun refresh() {
            discover?.cancel()
            panel.loading()
            discover = mainScope.launch {
                try {
                    val session = authenticator.authenticateToRemotePlay().getOrThrow()
                    val client = XCloudSignalingClient(session, transferTokenProvider = {
                        authenticator.getConsoleTransferToken(session.credentialGeneration)
                    }, renewRemotePlayAuthorization = authenticator::renewRemotePlayAuthorization)
                    val consoles = client.fetchRemoteConsoles()
                    if (remotePlayPanel !== panel) return@launch
                    panel.showConsoles(consoles, selectedId, ::refresh) { console ->
                        if (remotePlayPanel !== panel || !activityVisible || activeGame != null) return@showConsoles
                        connecting = true
                        panel.dialog.dismiss()
                        remotePlayReturn = { showRemotePlay(console.serverId, onBack) }
                        // This display-only label never enters the catalog or per-title settings.
                        val display = GameTitle("remote-play", console.name)
                        launchPlayback(client, display, PlaybackOrigin.REMOTE_PLAY, remoteConsole = console)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (remotePlayPanel !== panel) return@launch
                    val message = if (error is com.xcloud.tclnative.auth.AuthenticationRequiredException)
                        UiText.text("Sua sessão precisa ser renovada. Entre novamente na sua conta.")
                    else UiText.text("Não foi possível consultar seus consoles Xbox. Tente novamente.")
                    panel.failure(message, ::refresh)
                }
            }
        }
        panel.dialog.setOnDismissListener {
            discover?.cancel()
            if (remotePlayPanel === panel) {
                remotePlayPanel = null
                if (!connecting && !isFinishing && !isDestroyed) {
                    if (onBack != null) onBack() else dashboard?.focusServiceSelector()
                }
            }
        }
        panel.dialog.show()
        refresh()
    }

    private fun playGalleryProduct(signaling: XCloudSignalingClient, product: GalleryProduct) {
        val view=dashboard ?: return
        val generation=playbackGeneration
        galleryLaunchJob?.cancel()
        Toast.makeText(this,UiText.text("Verificando disponibilidade do jogo no Xbox Cloud…"),Toast.LENGTH_SHORT).show()
        galleryLaunchJob=mainScope.launch {
            try {
                val known=currentGames
                val resolved=withContext(Dispatchers.Default) { OfficialGalleryCards.resolve(product,known) }
                    ?: signaling.resolveGalleryProduct(product.productId)
                if(dashboard!==view || currentSignaling!==signaling || generation!=playbackGeneration || controlPanel.visibility!=View.VISIBLE) return@launch
                if(resolved==null) {
                    ConsoleDialogs.message(this@MainActivity,UiText.text("Vínculo do jogo não confirmado"),
                        UiText.text("Não foi possível associar esta edição a um título jogável do catálogo autenticado. Isso não confirma falta de acesso na sua conta/região. Atualize o catálogo e tente novamente. Produto: {0}.",UiText.nested(product.productId)))
                    return@launch
                }
                val native=currentGames.firstOrNull { it.titleId.equals(resolved.titleId,true) } ?: resolved
                val card=CatalogCard(native,product,view.isGamePassProduct(product.productId))
                currentGames=currentGames.filterNot { it.titleId.equals(native.titleId,true) }+native
                view.setGames(currentGames)
                view.bindGalleryProduct(product.productId,native)
                chooseLaunchMode(signaling,card.playableGame()!!)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) {
                if(dashboard===view && currentSignaling===signaling)
                    ConsoleDialogs.message(this@MainActivity,UiText.text("Disponibilidade não confirmada"),
                        UiText.text("Não foi possível verificar o jogo. Tente novamente ou atualize o catálogo. Jogos comprados exigem a licença digital e as condições do serviço Xbox."))
            }
        }
    }

    private fun refreshCloudHistory(view: GameDashboardView,signaling: XCloudSignalingClient,library: GameLibrary) {
        recentHistoryJob?.cancel()
        view.beginRecentRefresh()
        recentHistoryJob=mainScope.launch {
            try {
                catalogWork.await()
                val ids=signaling.fetchRecentGames(currentGames)
                if(dashboard===view) {
                    withContext(Dispatchers.IO) { library.saveCloudRecent(ids) }
                    if(dashboard===view) view.setRecentHistory(ids)
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { if(dashboard===view) view.setRecentHistory(null) }
        }
    }

    private fun showCatalogSearch() {
        val view = dashboard ?: return
        ConsoleDialogs.search(this, view.searchQuery,ConsoleAction(UiText.text("Jogos: {0}",UiText.nested(view.ownershipFilter.label))) {
            showOwnershipFilter()
        }) { query -> view.setSearch(query) }
    }
    private fun refreshCloudPlaytime(view: GameDashboardView,force: Boolean=false,duringPlayback: Boolean=false) {
        if(dashboard!==view || activeGame!=null && !duringPlayback || duringPlayback && !transmissionReady || !activityVisible || playtimeJob?.isActive==true) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(!force && view.cloudPlaytimeSnapshot().status!=CloudPlaytimeStatus.CHECKING && playtimeCheckedAt?.let { now-it<300_000 }==true) return
        playtimeCheckedAt=now;view.setCloudPlaytime(CloudPlaytime(CloudPlaytimeStatus.CHECKING))
        playtimeJob=mainScope.launch {
            try {
                val version=packageManager.getPackageInfo(packageName,0).versionName ?: "1.0"
                val gate: suspend () -> Unit=if(duringPlayback) ({}) else catalogWork::await
                val value=CloudPlaytimeService({ authenticator.loadStoreAuthorization().getOrThrow() },gate,appVersion=version).fetch()
                if(dashboard===view) { view.setCloudPlaytime(value);if(duringPlayback) renderStatistics() }
            } finally { if(playtimeJob===currentCoroutineContext()[Job]) playtimeJob=null }
        }
    }

    private fun showOwnershipFilter() {
        val view=dashboard ?: return
        com.xcloud.tclnative.ui.dashboard.LibraryFilterPanel.show(this,view.ownershipFilter,view.ownershipInformation,
            choose={ filter -> if(dashboard===view) { view.setOwnershipFilter(filter);showCatalogSearch() } },
            refresh={ requestOwnership(retry=true);showCatalogSearch() },onBack={ showCatalogSearch() })
    }

    private fun requestOwnership(retry: Boolean=false) {
        val view=dashboard ?: return
        if(ownershipJob?.isActive==true) return
        val ids=view.beginOwnershipRequest(retry)
        if(ids.isEmpty()) return
        ownershipJob=mainScope.launch {
            try {
                val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
                val rights = CloudPlaytimeService({ authenticator.loadStoreAuthorization().getOrThrow() },
                    catalogWork::await, appVersion = version).fetchOwnership(ids)
                if (dashboard === view) view.updateOwnership(rights)
            } finally {
                if(dashboard===view) { ownershipJob=null;view.finishOwnershipRequest() }
            }
        }
    }

    private fun showSettings(session: XCloudSession,startPage: SettingsPage=SettingsPage.ROOT,onBack: () -> Unit={}) {
        settingsPanel(session,onBack).open(startPage)
    }
    private fun settingsPanel(session: XCloudSession,onBack: () -> Unit={}): PlaybackSettingsPanel {
        var returnPage=SettingsPage.ROOT
        val game=if (remotePlayReturn != null) null else activeGame ?: dashboard?.selectedGame
        fun back() { showSettings(session,returnPage,onBack) }
        return PlaybackSettingsPanel(this,settingsStore,
            changed = ::applyPlaybackSettings,
            subscription = { openAccountPage("https://account.microsoft.com/services") },
            refresh = { renderGameDashboard(session) },
            logout = { authenticator.clearSession(); renderLoginMenu(); syncHomeShortcuts() },
            testRumble = { showRumbleDiagnostics(::back) },
            homeShortcuts = { showHomeShortcuts(::back) },lastReport={ showLatestReport(::back) },
            sessionOptions={ showSessionOptionsMenu(session,::back) },controllerProfiles={ showControllerProfiles(::back) },
            onPageEntered={ returnPage=it },catalogInformation={ dashboard?.catalogInformation },
            backgrounds={ dashboard?.wallpaperChoices() ?: com.xcloud.tclnative.ui.dashboard.SeriesWallpaperGallery.fromGames(currentGames,listOfNotNull(activeGame)) },
            refreshPlaytime={ dashboard?.let { refreshCloudPlaytime(it,force=true) } },
            playtimeInformation={ dashboard?.cloudPlaytimeDiagnostics() ?: CloudPlaytime().diagnostics() },
            friends={ showFriends(::back) },gameShortcut=game?.let { selected -> ({ showGameShortcut(selected,::back) }) },
            sessionPanel=SessionOptionsPanel(this,sessionOptionsStore(session),session.regions,game?.titleId,game?.name,::back),
            back=onBack,allowAccountChanges=activeGame==null,
            gameControls=if(activeGame==null || sessionSaved) null else ({ showGameHudSettings { showSettings(session,SettingsPage.CONTROLS,onBack) } }),
            recovery=if(activeGame==null || sessionSaved) null else ({ showTransmissionRecovery { showSettings(session,SettingsPage.DIAGNOSTICS,onBack) } }),
            currentDiagnostics=if(activeGame==null) null else ({ showLiveDiagnostics { showSettings(session,SettingsPage.DIAGNOSTICS,onBack) } }),
            microphone=if(!sessionSaved && transmissionRecovery?.phase==TransmissionRecoveryState.Phase.ACTIVE) ::toggleMicrophone else null,
            statisticsChanged=::refreshStatisticsSettings,
            microphoneLabel={ webrtcClient?.microphoneLabel() ?: UiText.text("Ativar microfone") },
            videoMemoryInformation={ surfaceRenderer.memoryDiagnostics().takeIf { activeGame != null } })
    }
    private fun showControllerProfiles(back: () -> Unit) {
        com.xcloud.tclnative.ui.dashboard.ControllerProfilePanel(this,controllerProfiles,
            usbSources={ listOfNotNull(usbDirect.source) },hiddenAndroid=usbDirect::ownsAndroid) { if(webrtcClient!=null) (0..3).filter { controllers.sourceFor(it)!=null }.forEach(::publishGamepad) }.show(back)
    }
    private fun showSessionOptionsMenu(session: XCloudSession,onBack: () -> Unit={ if(webrtcClient!=null) showGameMenu() else showSettings(session,SettingsPage.NETWORK) }) {
        val game=if (remotePlayReturn != null) null else activeGame ?: dashboard?.selectedGame
        SessionOptionsPanel(this,sessionOptionsStore(session),
            session.regions,game?.titleId,game?.name,onBack).show()
    }
    private fun sessionOptionsStore(session: XCloudSession) = SessionOptionsStore(this,session.libraryAccountId,session.userHash)
    private fun showLatestReport(onBack: (() -> Unit)?=null) {
        startupReportStore.read()?.let { startup ->
            val timeline = startup.timeline
            showCopyableErrorDialog(startup.game,startup.text,onBack,export = timeline?.let {
                { exportDiagnostic(it.json().toString(2)) }
            })
            return
        }
        val report=lastSessionReport ?: reportStore.read()
        if(report==null) ConsoleDialogs.message(this,UiText.text("Último diagnóstico"),UiText.text("Ainda não há uma partida com vídeo para mostrar."),listOf(ConsoleAction(UiText.text("Voltar")) { onBack?.invoke() }),onCancel=onBack)
        else postSessionDialog=SessionReportPanel.show(this,report,onBack=onBack,export=report.exportJson()?.let {
            { exportDiagnostic(it.toString(2)) }
        })
    }
    private suspend fun finishSessionReport(tracker: SessionReportTracker, showAutomatically: Boolean = true) {
        val report=tracker.takeReport()
        if(report==null) { if(reportTracker===tracker) reportTracker=null; return }
        lastSessionReport=report
        try { withContext(Dispatchers.IO) { reportStore.save(report) } }
        catch(error: java.io.IOException) { if(!isFinishing && !isDestroyed) Toast.makeText(this,error.message,Toast.LENGTH_LONG).show() }
        val current=reportTracker===tracker
        if(current) reportTracker=null
        if(showAutomatically && current && activityVisible && !isFinishing && !isDestroyed && activeGame==null && settingsStore.read().sessionReport &&
            (report.durationMs>=5000 || report.end==SessionEnd.FAILURE)) {
            pendingAutomaticReport=report; showPendingReport()
        }
    }
    private fun showPendingReport() {
        if(!activityVisible || isFinishing || isDestroyed || activeGame!=null || dashboard==null ||
            controlPanel.visibility!=View.VISIBLE || transitionPanel.visibility==View.VISIBLE) return
        val report=pendingAutomaticReport ?: return
        pendingAutomaticReport=null
        if(!settingsStore.read().sessionReport) return
        postSessionDialog?.dismiss()
        postSessionDialog=SessionReportPanel.show(this,report) {
            val current=settingsStore.read()
            playbackSettings=settingsStore.update(current,current.copy(sessionReport=false))
        }
    }
    private fun syncHomeShortcuts(requestVisibility: Boolean = false) {
        if (!homeShortcuts.supportsChannel || !homeShortcuts.enabled) return
        homeJobs.schedule(requestVisibility) {
            try {
                val favorites=currentGames.filter { gameLibrary?.isFavorite(it.titleId)==true }
                val channel=withContext(Dispatchers.IO) { homeShortcuts.sync(favorites) }
                if(requestVisibility && channel!=null) requestHomeChannel(channel)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                if(requestVisibility) ConsoleDialogs.message(this@MainActivity,UiText.text("Canal da tela inicial"),
                    UiText.text("A TV não autorizou o canal de favoritos. Use Instalar atalho na TV para criar entradas individuais dos jogos."))
            }
        }
    }
    private fun requestHomeChannel(channel: Long) {
        pendingHomeChannel=channel
        val request=homeShortcuts.browsableRequest(channel) ?: return
        if(request.resolveActivity(packageManager)!=null) startActivityForResult(request,REQUEST_HOME_CHANNEL)
        else {
            homeShortcuts.requestVisible(channel)
            verifyHomeChannel(channel)
        }
    }
    private fun verifyHomeChannel(channel: Long) {
        mainScope.launch {
            val visible=withContext(Dispatchers.IO) { runCatching { homeShortcuts.isBrowsable(channel) }.getOrDefault(false) }
            pendingHomeChannel=null
            ConsoleDialogs.message(this@MainActivity,UiText.text("Canal de favoritos"),if(visible)
                UiText.text("O sistema autorizou o canal Favoritos xCLOUD. Sua exibição depende da tela inicial da TV. Para criar um aplicativo de acesso direto, use Instalar atalho na TV.")
                else UiText.text("O canal não ficou visível na tela inicial. Use Instalar atalho na TV: o jogo aparecerá em Aplicativos, inclusive no Google TV."))
        }
    }
    private fun showHomeShortcuts(onBack: (() -> Unit)?=null) {
        val favorites=currentGames.filter { gameLibrary?.isFavorite(it.titleId)==true }
        val actions=mutableListOf<ConsoleAction>()
        favorites.forEach { game ->
            actions+=ConsoleAction(UiText.text("Instalar atalho na TV: {0}",UiText.nested(game.name))) { createInstalledShortcut(game) }
        }
        if(homeShortcuts.supportsChannel) {
            actions+=ConsoleAction(UiText.text("Solicitar canal de favoritos na tela inicial")) {
                if(favorites.isEmpty()) ConsoleDialogs.message(this,UiText.text("Seus favoritos"),UiText.text("Adicione jogos com Y antes de criar os atalhos."))
                else { homeShortcuts.enable(true); syncHomeShortcuts(requestVisibility=true) }
            }
            if(homeShortcuts.enabled) actions+=ConsoleAction(UiText.text("Remover canal de favoritos")) {
                homeShortcuts.enable(false); homeJobs.cancel()
                homeJobs.schedule(true) { withContext(Dispatchers.IO) { runCatching { homeShortcuts.sync(emptyList()) } } }
            }
        }
        if(homeShortcuts.supportsPin) favorites.forEach { game ->
            actions+=ConsoleAction(UiText.text("Pedir atalho ao launcher: {0}",UiText.nested(game.name))) {
                val snapshot=currentGames
                mainScope.launch(Dispatchers.IO) { catalogCache.save(snapshot) }
                val image=ImageView(this)
                shortcutPosters.load(image,game.tileUrl ?: game.posterUrl,192,192,notifyPlaceholder=false) {
                    val accepted=homeShortcuts.pin(game,(image.drawable as? BitmapDrawable)?.bitmap)
                    Toast.makeText(this,if(accepted) UiText.text("Pedido enviado ao launcher. Confirme na tela inicial.") else UiText.text("O launcher não aceitou o pedido."),Toast.LENGTH_LONG).show()
                }
            }
        }
        if(actions.isEmpty()) ConsoleDialogs.message(this,UiText.text("Atalhos dos favoritos"),UiText.text("Adicione jogos aos favoritos com Y para criar seus atalhos."),listOf(ConsoleAction(UiText.text("Voltar")) { onBack?.invoke() }),onCancel=onBack)
        else ConsoleDialogs.menu(this,UiText.text("Atalhos dos favoritos"),actions,onBack=onBack ?: {})
    }
    private fun showGameShortcut(game: GameTitle,onBack: () -> Unit={}) {
        val actions=mutableListOf<ConsoleAction>()
        if(homeShortcuts.supportsPin) actions+=ConsoleAction(UiText.text("Fixar na tela inicial")) { pinGameShortcut(game) }
        actions+=ConsoleAction(UiText.text("Instalar atalho do jogo")) { createInstalledShortcut(game) }
        ConsoleDialogs.menu(this,UiText.text("Atalho • {0}",UiText.nested(game.name)),actions,onBack=onBack)
    }
    private fun pinGameShortcut(game: GameTitle) {
        val snapshot=currentGames
        mainScope.launch(Dispatchers.IO) { catalogCache.save(snapshot) }
        val image=ImageView(this)
        shortcutPosters.load(image,game.tileUrl ?: game.posterUrl,192,192,notifyPlaceholder=false) {
            val accepted=homeShortcuts.pin(game,(image.drawable as? BitmapDrawable)?.bitmap)
            Toast.makeText(this,if(accepted) UiText.text("Pedido enviado ao launcher. Confirme na tela inicial.") else UiText.text("O launcher não aceitou o pedido."),Toast.LENGTH_LONG).show()
        }
    }
    private fun createInstalledShortcut(game: GameTitle) {
        if(shortcutInstallJob?.isActive==true) return
        shortcutInstallJob=mainScope.launch {
            val snapshot=currentGames
            withContext(Dispatchers.IO) { catalogCache.save(snapshot) }
            val image=ImageView(this@MainActivity)
            val waiting=ConsoleDialogs.message(this@MainActivity,UiText.text("Criando atalho"),game.name,
                listOf(ConsoleAction(UiText.text("Cancelar")) { shortcutInstallJob?.cancel() }),onCancel={ shortcutInstallJob?.cancel() })
            var unclaimedApk: File? = null
            try {
                val artwork=CompletableDeferred<android.graphics.Bitmap?>()
                shortcutPosters.load(image,game.tileUrl ?: game.posterUrl,256,256,notifyPlaceholder=false) {
                    artwork.complete((image.drawable as? BitmapDrawable)?.bitmap)
                }
                val bitmap=artwork.await()
                val previous = pendingShortcutApk
                val apk = withContext(Dispatchers.IO) {
                    GameShortcutInstaller(this@MainActivity).create(game, bitmap, previous).also { unclaimedApk = it }
                }
                waiting.dismiss()
                pendingShortcutApk=apk
                unclaimedApk=null
                ConsoleDialogs.message(this@MainActivity,UiText.text("Instalar atalho: {0}",UiText.nested(game.name)),
                    UiText.text("Será instalado um pequeno aplicativo que abre este jogo no xCLOUD. Depois, procure o jogo em Aplicativos. No Google TV, adicione-o à fileira de apps favoritos pela própria tela inicial."),
                    listOf(ConsoleAction(UiText.text("Instalar atalho")) { if (pendingShortcutApk == apk) installPendingShortcut() },
                        ConsoleAction(UiText.text("Cancelar")) { discardPendingShortcut(apk) }),
                    onCancel={ discardPendingShortcut(apk) })
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { showCopyableErrorDialog(UiText.text("Atalho do jogo"),error.message ?: UiText.text("Não foi possível criar o atalho neste aparelho.")) }
            finally {
                shortcutPosters.cancel(image)
                waiting.dismiss()
                unclaimedApk?.let { apk -> withContext(NonCancellable + Dispatchers.IO) { apk.delete() } }
            }
        }
    }
    private fun discardPendingShortcut(apk: File) {
        if (pendingShortcutApk != apk) return
        pendingShortcutApk = null
        mainScope.launch(Dispatchers.IO) { apk.delete() }
    }
    private fun installPendingShortcut() {
        val apk=pendingShortcutApk?.takeIf(File::isFile) ?: return
        if(Build.VERSION.SDK_INT>=26 && !packageManager.canRequestPackageInstalls()) {
            try { startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")),REQUEST_SHORTCUT_INSTALL_PERMISSION) }
            catch(_: android.content.ActivityNotFoundException) { ConsoleDialogs.message(this,UiText.text("Permitir instalação"),UiText.text("Nas configurações da TV, permita que o xCLOUD instale os atalhos dos jogos.")) }
            return
        }
        mainScope.launch {
            val renewed = withContext(Dispatchers.IO) { apk.setLastModified(System.currentTimeMillis()) }
            if (pendingShortcutApk != apk) return@launch
            val uri=androidx.core.content.FileProvider.getUriForFile(this@MainActivity,"$packageName.shortcuts",apk)
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                if (renewed) pendingShortcutApk = null
            } catch(_: android.content.ActivityNotFoundException) {
                ConsoleDialogs.message(this@MainActivity,UiText.text("Instalador indisponível"),UiText.text("A TV não disponibilizou um instalador de aplicativos para o atalho."))
            }
        }
    }
    private fun exportDiagnostic(payload: String) {
        if (pendingDiagnosticExport != null) return
        pendingDiagnosticExport = payload
        val request = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "xcloud-diagnostics.json")
        try {
            startActivityForResult(request, REQUEST_DIAGNOSTIC_EXPORT)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingDiagnosticExport = null
            showDiagnosticExportError()
        }
    }

    private fun showDiagnosticExportError() {
        Toast.makeText(this, UiText.text("Não foi possível salvar o diagnóstico neste aparelho."), Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == REQUEST_DIAGNOSTIC_EXPORT) {
            val payload = pendingDiagnosticExport
            pendingDiagnosticExport = null
            val uri = data?.data
            if (resultCode == RESULT_OK && payload != null && uri != null) {
                mainScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val stream = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException()
                            stream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { showDiagnosticExportError() }
                }
            }
            return
        }
        if(requestCode==REQUEST_HOME_CHANNEL) pendingHomeChannel?.let(::verifyHomeChannel)
        if(requestCode==REQUEST_SHORTCUT_INSTALL_PERMISSION) {
            if(Build.VERSION.SDK_INT<26 || packageManager.canRequestPackageInstalls()) installPendingShortcut()
            else ConsoleDialogs.message(this,UiText.text("Instalação não autorizada"),UiText.text("O atalho foi criado, mas a permissão de instalação não foi concedida pela TV."))
        }
    }
    private fun applyPlaybackSettings(settings: PlaybackSettings) {
        val gyroChanged = playbackSettings.gyroMode!=settings.gyroMode ||
            playbackSettings.gyroSensitivity!=settings.gyroSensitivity || playbackSettings.gyroNativeTouch!=settings.gyroNativeTouch ||
            playbackSettings.gyroSource!=settings.gyroSource
        playbackSettings=settings
        applyLiveVideo(settings)
        webrtcClient?.setOutputGain(settings.audioGain)
        if (gyroChanged && sessionSettings!=null) {
            sessionSettings=sessionSettings?.copy(gyroMode=settings.gyroMode,gyroSensitivity=settings.gyroSensitivity,
                gyroNativeTouch=settings.gyroNativeTouch,gyroSource=settings.gyroSource)
            if (gameplayInputEnabled()) {
                resumeAuxiliaryInput()
                publishGamepad(0)
            }
        }
    }

    private fun applyLiveVideo(settings: PlaybackSettings) {
        if(surfaceRenderer.aspect!=settings.aspect) {
            // End contacts in the old coordinate space before changing the displayed video.
            touchView.releaseInputs()
            surfaceRenderer.aspect=settings.aspect
        }
        surfaceRenderer.setImageAdjustments(settings.sharpness,settings.saturation,settings.brightness,settings.contrast)
        surfaceRenderer.setImageTreatment(settings.imageTreatment)
        surfaceRenderer.setReconstruction(settings.reconstruction)
        surfaceRenderer.setImageLab(settings.imageLab)
        surfaceRenderer.setImageComparison(settings.effectiveImageComparison())
        surfaceRenderer.setFsr(settings.fsr,settings.fsrSharpness,settings.fsrOutput)
    }
    protected open fun prepareSessionRenderer(settings: PlaybackSettings): org.webrtc.EglBase {
        com.xcloud.tclnative.ui.dashboard.ArtworkBackend.get(this).trimForPlayback()
        surfaceRenderer.compatibility=settings.videoMode==VideoMode.COMPATIBILITY
        surfaceRenderer.setFsr(settings.fsr,settings.fsrSharpness,settings.fsrOutput)
        surfaceRenderer.setImageTreatment(settings.imageTreatment)
        surfaceRenderer.setReconstruction(settings.reconstruction)
        surfaceRenderer.setImageLab(settings.imageLab)
        surfaceRenderer.setImageComparison(settings.effectiveImageComparison())
        surfaceRenderer.setPresentationPacing(settings.presentationPacing)
        surfaceRenderer.nativeUpscaling=settings.nativeUpscaling
        surfaceRenderer.aspect=settings.aspect
        surfaceRenderer.setImageAdjustments(settings.sharpness,settings.saturation,settings.brightness,settings.contrast)
        surfaceRenderer.minimalPostProcessingRequested=settings.videoMode.lowLatency && Build.VERSION.SDK_INT>=30
        surfaceRenderer.prepareForSession(settings.resolution.width,settings.resolution.height)
        return surfaceRenderer.eglBase
    }
    private fun showRumbleDiagnostics(back: () -> Unit) {
        val active=usbDirect.snapshot().status in setOf(UsbControllerStatus.READY,UsbControllerStatus.WAITING_INPUT)
        val actions=mutableListOf(ConsoleAction(UiText.text("USB direto e teste de comandos")) {
            com.xcloud.tclnative.ui.dashboard.UsbControllerPanel(this,usbDirect,activate={ candidate,exclusive ->
                usbRumble.disable { if(!isFinishing && !isDestroyed) usbDirect.enable(candidate,exclusive) }
            },profiles=::showControllerProfiles).show { showRumbleDiagnostics(back) }
        })
        if(active) {
            actions+=ConsoleAction(UiText.text("Testar vibração")) { usbDirect.test();showRumbleDiagnostics(back) }
            actions+=ConsoleAction(UiText.text("Desativar")) { usbDirect.disable { if(!isFinishing && !isDestroyed) showRumbleDiagnostics(back) } }
        } else {
            actions+=ConsoleAction(UiText.text("Testar via Android")) { rumble.test();showRumbleDiagnostics(back) }
            usbRumble.candidates().forEach { candidate ->
                actions+=ConsoleAction(UiText.text("Somente vibração USB — {0}",UiText.nested(candidate.label))) {
                    usbDirect.disable { if(!isFinishing && !isDestroyed)
                        usbRumble.enableAndTest(candidate) { if(!isFinishing && !isDestroyed) showRumbleDiagnostics(back) }
                    }
                }
            }
            actions+=ConsoleAction(UiText.text("Desativar teste USB")) { usbRumble.disable();showRumbleDiagnostics(back) }
        }
        val description=if(active) usbDirect.snapshot().status.label+"\n"+
            com.xcloud.tclnative.ui.dashboard.UsbControllerPanel.describe(usbDirect.snapshot(),AndroidControllerMonitor.snapshot())
            else usbDirect.snapshot().status.label+"\n\n"+rumble.describe()+"\n\n"+usbRumble.describe()
        com.xcloud.tclnative.ui.dashboard.RumbleDiagnosticsPanel.show(this,description,actions,back)
    }
    private fun showGameMenu() = showGameMenu(null)

    private fun showTouchGameMenu() {
        controllerPath.menuTouched()
        val position=if(InputDeviceProfile.read(this).television) null else GameShortcutBar.Position()
        showGameMenu(position)
    }

    private fun showGameMenu(position: GameShortcutBar.Position?) {
        if (sessionSaved || controlPanel.visibility != View.GONE || transitionPanel.visibility==View.VISIBLE ||
            gameMenuDialog?.isShowing == true ||
            shortcutEditor?.isShowing == true || networkPanel?.dialog?.isShowing == true ||
            (webrtcClient == null && reloadTransmission==null) || queuePanel.visibility == View.VISIBLE) return
        resetController()
        val generation=playbackGeneration
        var toolbar: GameShortcutBar? = null
        val back: () -> Unit = { if(generation==playbackGeneration) showGameMenu(toolbar?.position) }
        val active=transmissionRecovery?.phase==TransmissionRecoveryState.Phase.ACTIVE
        val touch=active && InputDeviceProfile.read(this).touchDevice
        val client=webrtcClient
        val reload=reloadTransmission
        val liveSettings=PlaybackSettingsPanel(this,settingsStore,::applyPlaybackSettings,{},{},{},
            liveVideoMode=sessionSettings?.videoMode ?: playbackSettings.videoMode,
            liveGyro=true,
            microphone=if(active && webrtcClient?.canUseMicrophone()==true) ::toggleMicrophone else null,
            microphoneLabel={ UiText.text(webrtcClient?.microphoneLabel() ?: "Ativar microfone") })
        val shortcuts = GameMenuPanel.shortcuts(
            image=if(sessionSettings!=null) ({ liveSettings.showUpscaling(back) }) else null,
            audio=if(sessionSettings!=null) ({ liveSettings.showAudio(back) }) else null,
            gyro=if(sessionSettings!=null) ({ liveSettings.showGyro(back) }) else null,
            xbox=if(active) ::pressXboxButton else null,
            statistics=::toggleStatistics,
            statisticsOptions={ com.xcloud.tclnative.ui.dashboard.StatisticsSettingsPanel(this,statisticsStore,::refreshStatisticsSettings,back).show() },
            recovery=if(reloadTransmission!=null) ({ showTransmissionRecovery(back) }) else null,
            hudToggle=if(touch) ({
                if (touchView.controlsVisible) {
                    if (currentTouchMode==TouchMode.NATIVE) disableOfficialTouch() else hideGameHud()
                } else enableGameHud()
            }) else null,
            hudVisible=touchView.controlsVisible,
            nativeTouch=if(active && supportsSessionNativeTouch()) ({ showSessionInput(back) }) else null,
            diagnostics={ showLiveDiagnostics(back) },
            network={
                networkWarningsEnabled = !networkWarningsEnabled
                shortcutSettings.networkWarnings = networkWarningsEnabled
                renderNetworkStatus()
                back()
            },
            networkWarningsEnabled=networkWarningsEnabled,
            networkDetails={ showNetworkDetails(back) },
            hud=if(touch) ({ showGameHudSettings(back) }) else null,
            bitrate=if(active && client!=null && reload!=null) ({
                SessionOptionsPanel.liveBitrate(this,client.maxBitrateKbps,back) { kbps ->
                    if(generation==playbackGeneration && webrtcClient===client && reloadTransmission===reload &&
                        transmissionRecovery?.phase==TransmissionRecoveryState.Phase.ACTIVE) reload(kbps)
                }
            }) else null,
            saveSession=if(active && transmissionReady && currentSession!=null && reload!=null) ({ confirmSaveSession(back) }) else null,
            controllerProfiles=if(active) ({ showControllerProfiles(back) }) else null,
            mouse=if(!active || sessionDesktopMode==DesktopInputMode.OFF) null else ({
                auxiliaryHandler.post { setDesktopCapture(!desktopCaptured) }
            }))
        touchView.setMenuCovered(position!=null)
        val remote = remotePlayReturn != null
        val dialog = if(position==null) GameMenuPanel.show(this,shortcuts,::cancelPlayback,remote) else {
            GameShortcutBar(this,shortcuts,position,displayCutout,::cancelPlayback,back,shortcutSettings.slots(),remote) {
                GameShortcutEditor(this,shortcutSettings,back).also { shortcutEditor=it }.show()
            }.also {
                toolbar=it
                it.show()
            }
        }
        gameMenuDialog=dialog
        controllerPath.menuOpened()
        dialog.setOnDismissListener {
            if(gameMenuDialog===dialog) {
                gameMenuDialog=null
                touchView.setMenuCovered(false)
                resumeAuxiliaryInput()
            }
        }
    }
    private fun showNetworkDetails(back: () -> Unit) {
        val panel = GameNetworkPanel(this, back)
        panel.dialog.setNetworkCutout(displayCutout)
        networkPanel = panel
        panel.dialog.addCleanup { if (networkPanel === panel) networkPanel = null }
        renderNetworkStatus()
        panel.dialog.show()
    }

    private fun closeGameTools() {
        hideGuideTouchControls()
        shortcutEditor?.close(); shortcutEditor = null
        networkPanel?.dialog?.dismiss(); networkPanel = null
        gameMenuDialog?.dismiss(); gameMenuDialog = null
        if (::touchView.isInitialized) touchView.setMenuCovered(false)
    }
    private fun videoDiagnostics()=ImmersiveWindow.diagnostics(window,rootLayout)+"\n"+surfaceRenderer.diagnostics()
    private fun showLiveDiagnostics(onBack: () -> Unit) {
        val session = listOfNotNull(controllerPath.buttonDiagnostics(), gyro.diagnostics(),
            playbackSignaling?.keepAliveDiagnostics(), webrtcClient?.inputDiagnostics(), webrtcClient?.iceDiagnostics(),
            transmissionRecovery?.diagnostics(), officialTouch?.diagnostics()).joinToString("\n")
        showCopyableErrorDialog(UiText.text("Áudio e vídeo"),UiText.text("{0} {1} | Android {2} | {3}\n{4}\n{5}\n{6}\n{7}",UiText.nested(Build.MANUFACTURER),UiText.nested(Build.MODEL),UiText.nested(Build.VERSION.RELEASE),UiText.nested(Build.HARDWARE),UiText.nested(videoDiagnostics()),UiText.nested(playbackSignaling?.profileDiagnostics() ?: ""),UiText.nested(webrtcClient?.audioDiagnostics() ?: ""),UiText.nested(streamHistory.report()))+"\n"+session,onBack=onBack)
    }
    private fun pressXboxButton() {
        controllerPath.xboxMenuRequested()
        if (xboxButtonJob?.isActive == true) return
        val client=webrtcClient ?: return
        val generation=playbackGeneration
        xboxButtonJob = mainScope.launch {
            val focused = withTimeoutOrNull(1500) {
                while (activityVisible && client === webrtcClient && generation == playbackGeneration) {
                    if (gameplayInputEnabled()) return@withTimeoutOrNull true
                    delay(20)
                }
                false
            } == true
            if (!focused) return@launch
            if (InputDeviceProfile.read(this@MainActivity).touchDevice && sessionDesktopMode!=DesktopInputMode.NATIVE) {
                gyro.stop()
                touchView.suspendGameplayTouch(true)
                guideTouchControls.show()
                networkIndicator.setCutout(displayCutout, guideTouchControls.cornerControlSize)
            }
            controllerPath.xboxMenuPulse()
            controllers.xboxButton(true)
            publishGamepad(0)
            try { delay(120) } finally {
                if (client === webrtcClient && generation == playbackGeneration) {
                    controllers.xboxButton(false)
                    client.updateGamepad(combinedInput(0),
                        auxiliary = currentTouchMode == TouchMode.NATIVE && controllers.sourceFor(0) == null)
                }
            }
        }
    }
    private fun hideGuideTouchControls() {
        if (!::guideTouchControls.isInitialized || !guideTouchControls.active) return
        guideTouchControls.hide()
        touchView.suspendGameplayTouch(false)
        networkIndicator.setCutout(displayCutout)
    }
    private fun enableGameHud(keepOfficial: Boolean = false): Boolean {
        if(transmissionRecovery?.phase!=TransmissionRecoveryState.Phase.ACTIVE) return false
        val game=activeGame ?: return false
        if (currentTouchMode == TouchMode.NATIVE) {
            touchView.releaseInputs()
            if (webrtcClient?.setNativeTouchEnabled(false) != true) {
                Toast.makeText(this,UiText.text("Aguarde a conexão dos controles para ativar o HUD."),Toast.LENGTH_LONG).show()
                return false
            }
        }
        if(currentTouchMode!=TouchMode.VIRTUAL) {
            resetController(); currentTouchMode=TouchMode.VIRTUAL
            touchView.configure(TouchMode.VIRTUAL,game,hudStore.read(game.titleId))
            rumble.enabled=playbackSettings.rumble
            resumeAuxiliaryInput()
        }
        if (!keepOfficial) disableOfficialTouch()
        touchView.revealControls()
        return true
    }
    private fun applyOfficialTouch(session: OfficialTouchSession): Boolean {
        if (officialTouch!==session || !transmissionReady || sessionSaved ||
            controlPanel.visibility!=View.GONE || transitionPanel.exiting ||
            transmissionRecovery?.phase!=TransmissionRecoveryState.Phase.ACTIVE) return false
        val layout = session.layout()
        if (layout==null) {
            touchView.installOfficialControls(null)
            return false
        }
        if (currentTouchMode == TouchMode.OFF && !enableGameHud(keepOfficial = true)) return false
        touchView.installOfficialControls(layout)
        return true
    }
    private fun useOfficialTouch() {
        val session = officialTouch ?: return
        val load = loadOfficialTouch ?: return
        session.selectManually()
        if (!session.loaded) {
            load()
            Toast.makeText(this,UiText.text("Carregando os controles oficiais do Xbox…"),Toast.LENGTH_SHORT).show()
        } else finishManualOfficialTouch(session)
    }
    private fun finishManualOfficialTouch(session: OfficialTouchSession) {
        if (!activityVisible || sessionSaved || transitionPanel.exiting ||
            transmissionRecovery?.phase!=TransmissionRecoveryState.Phase.ACTIVE) return
        if (applyOfficialTouch(session)) touchView.revealControls()
        else Toast.makeText(this,UiText.text("O layout oficial do Xbox está indisponível nesta partida."),Toast.LENGTH_SHORT).show()
    }
    private fun disableOfficialTouch() {
        officialTouch?.disable()
        touchView.installOfficialControls(null)
    }
    private fun hideGameHud() {
        val game=activeGame ?: return
        disableOfficialTouch()
        resetController();currentTouchMode=TouchMode.OFF
        touchView.configure(TouchMode.OFF,game);rumble.enabled=playbackSettings.rumble
        resumeAuxiliaryInput()
    }
    private fun enableNativeTouch(): Boolean {
        val game=activeGame ?: return false
        val client=webrtcClient ?: return false
        if(!client.canUseNativeTouch() || transmissionRecovery?.phase!=TransmissionRecoveryState.Phase.ACTIVE) return false
        if(!client.setNativeTouchEnabled(true)) {
            Toast.makeText(this,UiText.text("Aguarde a conexão dos controles para mudar o modo de toque."),Toast.LENGTH_LONG).show()
            return false
        }
        resetController()
        disableOfficialTouch()
        // Release only the app's virtual pad; a real controller keeps its source and slot.
        if(controllers.sourceFor(0)==null) client.removeGamepad(0)
        currentTouchMode=TouchMode.NATIVE
        touchView.configure(TouchMode.NATIVE,game,hudStore.read(game.titleId))
        rumble.enabled=playbackSettings.rumble
        resumeAuxiliaryInput();return true
    }
    private fun supportsSessionNativeTouch(): Boolean = activeGame?.let { game ->
        remotePlayReturn==null && sessionDesktopMode==DesktopInputMode.OFF &&
            InputDeviceProfile.read(this).touchDevice && DeviceInputPolicy.supportsNativeTouch(game)
    } == true

    private fun showSessionInput(back: () -> Unit) {
        val game = activeGame ?: return
        if (!supportsSessionNativeTouch()) return
        val preferred = hudStore.prefersNativeTouchWithController(game.titleId)
        val virtualPreferred = hudStore.prefersVirtualTouch(game.titleId)
        val nativeActive = currentTouchMode==TouchMode.NATIVE
        val canSwitch = webrtcClient?.canUseNativeTouch()==true && !controllers.reservesPlayerOne
        val state = if (preferred) UiText.text("Ativado") else UiText.text("Desativado")
        com.xcloud.tclnative.ui.dashboard.ConsoleDialog(this,UiText.text("Toque nativo e controle")).apply {
            body.addView(text(if (canSwitch) UiText.text("A combinação depende do jogo.")
                else UiText.text("Vale na próxima partida."),20))
            if (canSwitch) {
                if (nativeActive) addMenuItem(UiText.text("Usar apenas controle nesta partida")) { disableSessionNativeTouch() }
                else addMenuItem(UiText.text("Usar toque nativo e controle nesta partida")) { enableNativeTouch() }
            }
            if(currentTouchMode!=TouchMode.VIRTUAL) {
                addMenuItem(UiText.text("Usar controle virtual nesta partida")) { enableGameHud() }
            }
            addMenuItem(UiText.text("Iniciar este jogo com toque e controle: {0}", UiText.nested(state))) {
                hudStore.setTouchPreference(game.titleId,if(preferred) TouchMode.AUTO else TouchMode.NATIVE)
                showSessionInput(back)
            }
            addMenuItem((if(virtualPreferred) "✓  " else "")+UiText.text("Iniciar este jogo com controle virtual")) {
                hudStore.setTouchPreference(game.titleId,if(virtualPreferred) TouchMode.AUTO else TouchMode.VIRTUAL)
                showSessionInput(back)
            }
            addAction(UiText.text("Voltar"),back)
            setOnCancelListener { back() }
            show()
        }
    }
    private fun disableSessionNativeTouch() {
        if (transmissionRecovery?.phase!=TransmissionRecoveryState.Phase.ACTIVE || currentTouchMode!=TouchMode.NATIVE) return
        touchView.releaseInputs()
        if (webrtcClient?.setNativeTouchEnabled(false)==true) hideGameHud()
        else Toast.makeText(this,UiText.text("Aguarde a conexão dos controles para mudar o modo de toque."),Toast.LENGTH_LONG).show()
    }
    private fun showGameHudSettings(onBack: () -> Unit=::showGameMenu) {
        val game=activeGame ?: return
        com.xcloud.tclnative.ui.dashboard.GameHudPanel(this,game,hudStore,
            changed={ touchView.applyHud(it) },edit={ if(enableGameHud()) touchView.beginEditing() },
            hide=::hideGameHud,
            page={ if(enableGameHud()) touchView.showPage(it) },
            activate=if(currentTouchMode==TouchMode.VIRTUAL && !touchView.usingOfficialControls) null
                else ({ if(enableGameHud()) showGameHudSettings(onBack) }),
            nativeTouch=if(currentTouchMode==TouchMode.NATIVE || webrtcClient?.canUseNativeTouch()!=true) null else ({ enableNativeTouch() }),
            officialTouch=officialTouch?.let { ::useOfficialTouch },back=onBack,
            editOfficial = if (touchView.canEditOfficialHud) touchView::beginOfficialEditing else null).show()
    }
    private fun chooseLaunchMode(signaling: XCloudSignalingClient,game: GameTitle) {
        val saved = activeGame
        if (!sessionSaved || saved == null) {
            launchCloudGame(signaling, game)
        } else if (remotePlayReturn == null && saved.titleId == game.titleId) {
            resumeSavedSession()
        } else {
            val generation = playbackGeneration
            ConsoleDialogs.message(this, UiText.text("Trocar de jogo"),
                UiText.text("Encerrar a sessão atual e abrir {0}?", UiText.nested(game.name)),
                listOf(ConsoleAction(UiText.text("Cancelar")) {}, ConsoleAction(UiText.text("Jogar na nuvem")) {
                    if (generation == playbackGeneration && sessionSaved && currentSignaling === signaling && activityVisible)
                        launchCloudGame(signaling, game)
                }))
        }
    }

    private fun confirmSaveSession(onBack: () -> Unit) {
        val generation = playbackGeneration
        ConsoleDialogs.message(this, UiText.text("Salvar sessão"),
            UiText.text("O jogo pode continuar no Xbox enquanto você navega. Retome antes que o serviço encerre a sessão."),
            listOf(ConsoleAction(UiText.text("Cancelar"), onBack), ConsoleAction(UiText.text("Salvar sessão")) {
                if (generation == playbackGeneration) saveSession()
            }), onCancel = onBack)
    }

    private fun showSavedSessionButton(view: GameDashboardView) {
        view.setSavedSession(if (sessionSaved) ::resumeSavedSession else null)
    }

    private fun saveSession() {
        val account = currentSession ?: return
        val client = webrtcClient ?: return
        if (!activityVisible || sessionSaved || !transmissionReady || reloadTransmission == null ||
            transmissionRecovery?.phase != TransmissionRecoveryState.Phase.ACTIVE) return
        sessionSaved = true
        hideSessionTimeNotice()
        closeGameTools()
        videoRecoveryDialog?.dismiss()
        nativeTouchDialog?.dismiss()
        joystickDispatch.request(false)
        resetController()
        microphoneJob?.cancel()
        pendingMicrophoneGeneration = null
        client.setAudioForeground(false)
        client.suppressGameAudio(true)
        reportTracker?.setVisible(false)
        touchView.visibility = View.INVISIBLE
        surfaceRenderer.visibility = View.INVISIBLE
        hudTextView.visibility = View.GONE
        renderStatistics()
        playbackDisplay.restore()
        catalogWork.resume()
        val view = dashboard
        if (view == null) renderGameDashboard(account, currentGames)
        else {
            view.setGames(currentGames)
            view.resumeArtwork()
            showSavedSessionButton(view)
            controlPanel.visibility = View.VISIBLE
            revealDashboard(view)
        }
    }

    private fun clearSavedSession() {
        if (!sessionSaved) return
        sessionSaved = false
        dashboard?.setSavedSession(null)
        surfaceRenderer.visibility = View.VISIBLE
    }

    private fun applySavedResolution() {
        val requested = pendingResumeResolution ?: return
        if (webrtcClient?.requestResolution(requested) == true) {
            sessionSettings = sessionSettings?.copy(resolution = requested)
            pendingResumeResolution = null
        } else {
            Toast.makeText(this, UiText.text("Não foi possível solicitar a nova resolução."), Toast.LENGTH_SHORT).show()
        }
    }

    private fun resumeSavedSession() {
        if (!sessionSaved || !activityVisible) return
        val game = activeGame ?: return
        val settings = sessionSettings ?: return
        val phase = transmissionRecovery?.phase ?: return
        if (phase != TransmissionRecoveryState.Phase.ACTIVE && phase != TransmissionRecoveryState.Phase.WAITING_RETRY) return
        val configured = settingsStore.read()
        val video = configured.copy(videoMode = settings.videoMode, videoCodec = settings.videoCodec).forSession()
        val resolution = if (remotePlayReturn != null) remoteResolution(video.resolution) else video.resolution
        pendingResumeResolution = resolution.takeUnless { it == settings.resolution }
        controlPanel.visibility = View.GONE
        dashboard?.closeServiceMenu()
        dashboard?.pauseArtwork()
        catalogWork.pause()
        clearSavedSession()
        videoVisibilityEpoch++
        playbackSettings = configured
        applyLiveVideo(video)
        surfaceRenderer.nativeUpscaling = video.nativeUpscaling
        configureVideoOutput(settings.copy(nativeUpscaling = video.nativeUpscaling, fsr = video.fsr), playing = true)
        reportTracker?.setVisible(true)
        if (phase == TransmissionRecoveryState.Phase.WAITING_RETRY) {
            reloadTransmission?.invoke(null)
            return
        }
        applySavedResolution()
        touchView.configure(currentTouchMode, game, hudStore.read(game.titleId))
        officialTouch?.let(::applyOfficialTouch)
        webrtcClient?.let { client ->
            client.setAudioForeground(true)
            client.suppressGameAudio(false)
            client.resumeAudio()
            client.requestVideoKeyframe()
        }
        rumble.enabled = settings.rumble
        updateJoystickDispatch()
        resumeAuxiliaryInput()
        resynchronizeUsbInput()
        renderStatistics()
    }

    private fun remoteResolution(resolution: com.xcloud.tclnative.webrtc.StreamResolution) =
        if (resolution.width <= 1280) com.xcloud.tclnative.webrtc.StreamResolution.HD_720
        else com.xcloud.tclnative.webrtc.StreamResolution.HD_1080

    private suspend fun loadXboxFriends(account: XCloudSession): List<com.xcloud.tclnative.catalog.XboxFriend> {
        check(currentSession === account)
        val authorization = authenticator.loadXboxSocialAuthorization().getOrThrow()
        check(currentSession === account)
        val snapshot = com.xcloud.tclnative.catalog.XboxFriendsService().load(authorization, UiText.locale.toLanguageTag())
        check(currentSession === account)
        return snapshot
    }

    private fun showFriends(onBack: () -> Unit) {
        val account=currentSession ?: return
        friendsPanel?.close()
        val valid={ currentSession===account && activityVisible && !isFinishing && !isDestroyed }
        friendsPanel=com.xcloud.tclnative.ui.dashboard.FriendsPanel(this,mainScope,
            load={ loadXboxFriends(account) },games={ currentGames },valid=valid,play={ game ->
                fun launch() { if(valid()) currentSignaling?.let { chooseLaunchMode(it,game) } }
                if(activeGame!=null && !sessionSaved) ConsoleDialogs.message(this,UiText.text("Abrir jogo na nuvem"),
                    UiText.text("A partida atual será encerrada para abrir {0} na sua própria sessão.",UiText.nested(game.name)),listOf(
                        ConsoleAction(UiText.text("Continuar")) { launch() },
                        ConsoleAction(UiText.text("Voltar")) { showFriends(onBack) }),onCancel={ showFriends(onBack) })
                else launch()
            },back=onBack).also { it.show() }
    }

    private fun openAccountPage(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: android.content.ActivityNotFoundException) {
            ConsoleDialogs.message(this,UiText.text("Acesse pelo celular"),url)
        }
    }

    private fun restoreDashboard(reopenRemote: Boolean = true) {
        hideSessionTimeNotice()
        joystickDispatch.request(false)
        playbackDisplay.restore()
        catalogWork.resume()
        val remoteReturn = remotePlayReturn
        remotePlayReturn = null
        if(dashboard==null) {
            if (remoteReturn != null) {
                transitionPanel.dismiss()
                controlPanel.visibility = View.VISIBLE
                if (reopenRemote) remoteReturn()
            } else currentSession?.let { renderGameDashboard(it,currentGames) }
            return
        }
        dashboard?.setGames(currentGames)
        dashboard?.resumeArtwork()
        controlPanel.visibility = View.VISIBLE
        dashboard?.let { view ->
            hudTextView.visibility=View.GONE; revealDashboard(view)
            refreshCloudPlaytime(view,force=true)
            val signaling=currentSignaling; val library=gameLibrary
            if(signaling!=null && library!=null) refreshCloudHistory(view,signaling,library)
        }
        if (reopenRemote) remoteReturn?.invoke()
    }
    private fun revealDashboard(view: GameDashboardView) {
        view.doOnPreDraw {
            if(dashboard===view && (activeGame==null || sessionSaved) && controlPanel.visibility==View.VISIBLE) {
                startupArtJob?.cancel(); startupArtJob=null
                transitionPanel.dismiss()
                if (sessionSaved) view.focusSavedSession() else view.restoreFocus(refreshCatalog=false)
                showPendingReport()
            }
        }
    }
    private fun showCatalogReturn(game: GameTitle?,wasPlaying: Boolean) {
        startupArtJob?.cancel(); startupArtJob=null
        closeGameTools()
        networkQuality.reset()
        networkIndicator.visibility = View.GONE
        val fresh=currentGames.firstOrNull { it.titleId==game?.titleId } ?: game
        if (remotePlayReturn != null) transitionPanel.showRemotePlayExit(fresh)
        else transitionPanel.showExit(fresh,wasPlaying)
        controlPanel.visibility=View.GONE; statsView.visibility=View.GONE; hudTextView.visibility=View.GONE
    }
    private suspend fun allowTransitionFrame() {
        if(!activityVisible || !transitionPanel.isAttachedToWindow || !transitionPanel.exiting) return
        // Let the branded screen paint before native decoder/surface cleanup can block the UI.
        withTimeoutOrNull(100) {
            suspendCancellableCoroutine<Unit> { waiting ->
                transitionPanel.postOnAnimation { transitionPanel.post { if(waiting.isActive) waiting.resume(Unit) } }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        AndroidControllerMonitor.key(event)
        if (activeGame!=null && event.keyCode==KeyEvent.KEYCODE_BUTTON_MODE && event.repeatCount==0 &&
            event.action in setOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) {
            controllerPath.xboxHardware(event.action==KeyEvent.ACTION_DOWN)
        }
        if(usbDirect.ownsAndroid(event.deviceId)) return true
        if(sessionDesktopMode!=DesktopInputMode.OFF && gameplayInputEnabled() &&
            event.device?.keyboardType==InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            if(event.keyCode==KeyEvent.KEYCODE_F7) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) showGameMenu()
                return true
            }
            if(event.keyCode==KeyEvent.KEYCODE_F8) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) setDesktopCapture(!desktopCaptured)
                return true
            }
            if(desktopCaptured && event.action in setOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) {
                val down=event.action==KeyEvent.ACTION_DOWN
                if(sessionDesktopMode==DesktopInputMode.NATIVE) {
                    val code=DesktopKeys.windowsKey(event.keyCode)
                    if(code!=null) { if(event.repeatCount==0) webrtcClient?.desktopKey(NativeKey(code,down));return true }
                } else if(desktopGamepad.key(event.keyCode,down)) { publishGamepad(0);return true }
            }
        }
        if (gameplayInputEnabled() && dispatchGameplayButton(event)) return true
        if (canOpenRecoveryMenu() && dispatchRecoveryMenuButton(event)) return true
        if(transitionPanel.visibility==View.VISIBLE && event.keyCode in setOf(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if(!transitionPanel.exiting && event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 &&
                event.keyCode in setOf(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_BUTTON_B)) showExitDialog()
            return true
        }
        if (queuePanel.visibility == View.VISIBLE) {
            val key = event.keyCode
            if (key in setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) cancelPlayback()
                    else queuePanel.cancelFromController()
                }
                return true
            }
        }
        val login = loginView
        if (login != null && controlPanel.visibility == View.VISIBLE && login.handleKey(event)) return true
        val view = dashboard
        if (view != null && controlPanel.visibility == View.VISIBLE && queuePanel.visibility != View.VISIBLE) {
            if(event.deviceId>=0 && event.deviceId!=preferredControllerId && event.action==KeyEvent.ACTION_DOWN &&
                AndroidGamepadPolicy.supportsGamepad(event.source) &&
                (!televisionInput || AndroidGamepadPolicy.confirmsGamepad(event))) preferredControllerId=event.deviceId
            if (view.handleKey(event)) return true
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) view.back()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun launchCloudGame(signaling: XCloudSignalingClient, game: GameTitle,
                                touchChoice: TouchMode? = null, fromShortcut: Boolean = false) {
        dashboard?.closeServiceMenu()
        remotePlayPanel?.let { panel -> remotePlayPanel = null; panel.dialog.dismiss() }
        remotePlayReturn = null
        launchPlayback(signaling, game, if (fromShortcut) PlaybackOrigin.SHORTCUT else PlaybackOrigin.CATALOG, touchChoice)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launchPlayback(signaling: XCloudSignalingClient, game: GameTitle, origin: PlaybackOrigin,
                               touchChoice: TouchMode? = null, remoteConsole: RemoteConsole? = null) {
        hideSessionTimeNotice()
        clearSavedSession()
        pendingResumeResolution = null
        val remote = remoteConsole != null
        val returnToConsoleList = remotePlayReturn
        shortcutSwitchDialog?.dismiss()
        closeGameTools()
        networkQuality.reset()
        friendsPanel?.close();friendsPanel=null
        playtimeJob?.cancel();playtimeJob=null
        recentHistoryJob?.cancel(); recentHistoryJob=null
        catalogReturnJob?.cancel(); catalogReturnJob=null
        startupArtJob?.cancel(); startupArtJob=null
        transitionPanel.dismiss(); pendingAutomaticReport=null
        val titleId = game.titleId
        val gameName = if (remote) "Remote Play" else game.name
        activeGame = game
        microphoneJob?.cancel();pendingMicrophoneGeneration=null
        val playbackProfile = playbackSettings.forSession()
        val settings = if (remote) playbackProfile.copy(
            resolution = remoteResolution(playbackProfile.resolution),
            videoCodec = if (playbackProfile.videoCodec == com.xcloud.tclnative.settings.VideoCodecMode.H264_BASELINE)
                playbackProfile.videoCodec else com.xcloud.tclnative.settings.VideoCodecMode.H264,
            microphone = false, desktopInput = DesktopInputMode.OFF
        ) else playbackProfile
        sessionSettings=settings
        val storedOptions = currentSession?.let { sessionOptionsStore(it).read(if (remote) null else titleId) } ?: SessionOptions()
        val sessionOptions = when {
            remote -> SessionOptions(locale = storedOptions.locale)
            storedOptions.dns == SessionDnsPreference.ADAPTIVE && mustUseSystemDns() ->
                storedOptions.copy(dns = SessionDnsPreference.SYSTEM)
            else -> storedOptions
        }
        sessionDesktopMode=if(settings.desktopInput==DesktopInputMode.NATIVE && !DesktopKeys.nativeSupported(game.supportedInputTypes)) {
            Toast.makeText(this,UiText.text("Este jogo não anunciou mouse e teclado nativos. Use emulação ou controle."),Toast.LENGTH_LONG).show()
            DesktopInputMode.OFF
        } else settings.desktopInput
        joystickDispatch.request(false)
        immediateJoystickForSession=settings.videoMode.lowLatency
        val compatible=settings.videoMode==com.xcloud.tclnative.settings.VideoMode.COMPATIBILITY
        reportTracker?.finish(android.os.SystemClock.elapsedRealtime(),SessionEnd.REPLACED,webrtcClient?.currentInputMetrics())
        joystickDispatch.beginSession()
        touchView.beginSession()
        touchView.immediateNativeDispatch=settings.videoMode.lowLatency
        postSessionDialog?.dismiss()
        val tracker=SessionReportTracker(gameName,UiText.text("{0} {1} • Android {2}",UiText.nested(Build.MANUFACTURER),UiText.nested(Build.MODEL),UiText.nested(Build.VERSION.RELEASE)),
            UiText.text("{0} • {1} • {2}",UiText.nested(settings.resolution.label),UiText.nested(settings.videoMode.label),UiText.nested(settings.videoCodec.label)),if(compatible) 30 else 60)
        tracker.setVisible(activityVisible); reportTracker=tracker
        streamHistory.clear()
        catalogWork.pause()
        dashboard?.pauseArtwork()
        val choice=touchChoice ?: if(!remote && hudStore.prefersVirtualTouch(titleId)) TouchMode.VIRTUAL else settings.touchMode
        val inputDevice=InputDeviceProfile.read(this)
        val preferHybrid = !remote && sessionDesktopMode==DesktopInputMode.OFF && inputDevice.touchDevice &&
            DeviceInputPolicy.supportsNativeTouch(game) && touchChoice==null && hudStore.prefersNativeTouchWithController(titleId)
        val touchMode = when {
            remote -> if (choice != TouchMode.OFF && inputDevice.touchDevice) TouchMode.VIRTUAL else TouchMode.OFF
            sessionDesktopMode!=DesktopInputMode.OFF -> TouchMode.OFF
            preferHybrid -> TouchMode.NATIVE
            else -> DeviceInputPolicy.resolve(inputDevice,choice,game)
        }
        val official = if (!remote && inputDevice.touchDevice && sessionDesktopMode==DesktopInputMode.OFF &&
            game.offersOfficialTouch()) OfficialTouchSession(choice==TouchMode.AUTO && !preferHybrid,
                preserveManualLayoutOnHide = touchMode == TouchMode.NATIVE && MinecraftDungeonsTouchLayout.appliesTo(game)) else null
        currentTouchMode=TouchMode.OFF
        touchView.configure(TouchMode.OFF)
        statistics.beginSession();transmissionReady=false;latestMetrics=null;latestInput=InputMetrics()
        recoverImage=null;reloadTransmission=null
        refreshStatisticsSettings()
        configureVideoOutput(settings, playing = true)
        playback.start(origin, prepare = {
            officialTouch = official
            loadOfficialTouch = null
            controlPanel.visibility = View.GONE
            statsView.visibility = View.GONE
            statsView.text = UiText.text("Estatísticas: aguardando amostras WebRTC...")
            hudTextView.visibility = View.GONE
            beginLoading(game, remote)
            hudTextView.text = "PLAY: Solicitando sessão para [$gameName]. Voltar cancela."
            hudTextView.setTextColor(Color.CYAN)
        }) { attempt ->
            val generation = attempt.generation
            playbackSignaling = signaling
            val firstFrame = attempt.firstFrame
            val timeline = attempt.timeline
            tracker.timeline = timeline
            tracker.submittedFrames = { surfaceRenderer.probeVideoHealth().submittedToken }
            var transportFailure = CompletableDeferred<Exception>()
            var maxBitrateKbps = sessionOptions.maxBitrateKbps
            val recoveryState = attempt.recovery
            transmissionRecovery=recoveryState
            val playbackScope=this
            var allocatedRtc: XCloudWebRtcClient?=null
            var failed=false
            var startupFailure: String?=null
            var remoteError: String?=null
            var startupInput = "LAUNCH/${origin.name}; locale=${sessionOptions.locale}"
            controllerPath.clear()
            gyro.resetDiagnostics()
            signaling.resetStartupDiagnostics()
            tracker.inputDetails={ "Upscaling=${settings.fsr}; output=${settings.fsrOutput}\n"+controllerPath.diagnostics()+"\n"+signaling.startupDiagnostics() }
            tracker.mediaDetails=::videoDiagnostics
            try {
                resetController()
                // The HUD and a physical pad drive player one together, including in local co-op.
                controllers.beginSession(settings.localCoOp,sessionDesktopMode!=DesktopInputMode.OFF)
                usbDirect.source?.let { controllers.slot(it) }
                // A TV shortcut can start with stale or misleading Android device capabilities.
                // Leave Android slots unassigned until input unless the catalog selected a pad.
                val androidIds = if (!televisionInput) InputDevice.getDeviceIds()
                    else if (origin == PlaybackOrigin.CATALOG) listOfNotNull(preferredControllerId).toIntArray()
                    else intArrayOf()
                androidIds.asSequence().mapNotNull(InputDevice::getDevice)
                    .filter { !usbDirect.ownsAndroid(it.id) && AndroidGamepadPolicy.supportsGamepad(it.sources) }
                    .sortedWith(compareBy<InputDevice> { it.id != preferredControllerId }.thenBy { it.id })
                    .forEach { controllers.slot(it.id) }
                val primary = controllers.sourceFor(0)
                val source = when (primary) {
                    is ControllerSource.Android -> "ANDROID"
                    is ControllerSource.Usb -> "USB"
                    null -> "NONE"
                }
                startupInput += "; primary=$source; catalog_preference=${primary is ControllerSource.Android && primary.deviceId == preferredControllerId}"
                rumble.enabled=settings.rumble
                rumble.touchRumble=settings.touchRumble
                val playbackEgl=try { prepareSessionRenderer(settings) } catch(error: Exception) {
                    if(error is CancellationException) throw error
                    throw RenderFailureException(error)
                }
                timeline.mark(0, PlaybackStage.RENDERER_READY)
                val sessionTime = SessionTimeLimit()
                var sessionStartedAtMs = 0L
                surfaceRenderer.onFirstFrameSubmitted = { submittedAt ->
                    if (generation == playbackGeneration && recoveryState.current(0) && !firstFrame.isCompleted) {
                        timeline.mark(0, PlaybackStage.FIRST_EGL_SUBMISSION, submittedAt)
                        sessionStartedAtMs = android.os.SystemClock.elapsedRealtime()
                        tracker.begin(sessionStartedAtMs,NetworkSnapshot.read(this@MainActivity))
                        runCatching { startupReportStore.clear() }
                        finishLoading(forPlayback = true)
                        updateJoystickDispatch()
                        currentTouchMode=touchMode
                        touchView.configure(touchMode,game,hudStore.read(titleId))
                        if(touchMode==TouchMode.NATIVE) webrtcClient?.setNativeTouchEnabled(true)
                        resumeAuxiliaryInput()
                        hudTextView.visibility = View.GONE
                        if (!remote) gameLibrary?.recordPlayed(titleId)
                        hudTextView.text = UiText.text("Vídeo nativo ativo [{0}]",UiText.nested(gameName))
                        hudTextView.setTextColor(Color.GREEN)
                        transmissionReady=true;renderStatistics();resynchronizeUsbInput()
                        firstFrame.complete(Unit)
                    }
                }
                surfaceRenderer.onRenderError = { error -> transportFailure.complete(RenderFailureException(error)) }
                // Capability discovery and native factory initialization must not block the menu/UI.
                suspend fun buildRtc(): XCloudWebRtcClient {
                    val token=recoveryState.epoch
                    val failure=transportFailure
                    val resolution=sessionSettings?.resolution ?: settings.resolution
                    fun current()=generation==playbackGeneration && recoveryState.current(token)
                    return withContext(Dispatchers.Default) { XCloudWebRtcClient(this@MainActivity, playbackEgl,
                    onVideoTrackReady = { track ->
                        runOnUiThread {
                            if (current()) surfaceRenderer.attachVideoTrack(track) { deliveredAt ->
                                if (current()) timeline.mark(token, PlaybackStage.FIRST_TRACK_FRAME, deliveredAt)
                            }
                        }
                    },
                    onConnectionStateChanged = { state -> runOnUiThread {
                        if (current()) {
                            if (state.contains("FAILED") || state.contains("CLOSED") || state.startsWith("CHANNELS:") || state.startsWith("VIDEO_CODEC:")) {
                                failure.complete(java.io.IOException(state))
                            }
                            if (!firstFrame.isCompleted) hudTextView.text = state
                        }
                    } },
                    onStatistics = stats@ { metrics, input ->
                        if(current()) {
                            val now=android.os.SystemClock.elapsedRealtime()
                            timeline.observe(token, metrics.codec, metrics.width, metrics.height)
                            streamHistory.add(now,metrics,input); tracker.add(now,metrics,input)
                            runOnUiThread {
                                if (current()) {
                                    if (!compatible) surfaceRenderer.observeFsrPerformance(now, metrics,
                                        firstFrame.isCompleted && activityVisible && gameplayInputEnabled())
                                    latestMetrics = metrics
                                    latestInput = input
                                    if (transmissionReady && activityVisible) networkQuality.observe(metrics)
                                    if (activityVisible) renderStatistics()
                                }
                            }
                        }
                    }, lowLatencyVideo = settings.videoMode.lowLatency, streamResolution = resolution,
                    nativeTouch = touchMode==TouchMode.NATIVE,
                    onRumble = { command -> if(current() && recoveryState.phase==TransmissionRecoveryState.Phase.ACTIVE) rumble.play(command) },compatibility=compatible,
                    videoCodec=settings.videoCodec,maxBitrateKbps=maxBitrateKbps,
                    ipPreference=settings.ipPreference,nativeKeyboardMouse=sessionDesktopMode==DesktopInputMode.NATIVE,
                    microphoneAllowed=settings.microphone,outputGain=playbackSettings.audioGain,audioMode=settings.audioMode,
                    onServerDisconnect={ notice -> runOnUiThread { if(current()) failure.complete(XboxServerDisconnectException(notice)) } },
                    remotePlay=remote,
                    onTitleFocusChanged={ focused -> runOnUiThread { if(current()) guideTouchControls.onTitleFocusChanged(focused) } },
                    onGameTimeChanged={ time -> runOnUiThread {
                        if(!remote && current()) {
                            sessionTime.updateFromXbox(time.secondsLeft,android.os.SystemClock.elapsedRealtime())
                            if(firstFrame.isCompleted && recoveryState.phase==TransmissionRecoveryState.Phase.ACTIVE)
                                renderSessionTime(sessionTime)
                        }
                    } },
                    onTouchLayoutChanged={ notice -> if (official!=null) runOnUiThread {
                        if (current()) {
                            official.receive(notice)
                            applyOfficialTouch(official)
                        }
                    } }
                    ).also { allocatedRtc=it } }
                }
                var rtc=buildRtc()
                webrtcClient = rtc
                val appVersion=packageManager.getPackageInfo(packageName,0).versionName ?: "n/d"
                tracker.inputDetails = {
                    "$startupInput\n" + (if (remote) "" else
                        dashboard?.cloudPlaytimeDiagnostics()?.let { "$it\n" }.orEmpty() +
                            sessionTime.diagnostics(android.os.SystemClock.elapsedRealtime()) + "\n") +
                        UiText.text("xCLOUD {0} • Entrada inicial: {1} • {2}",UiText.nested(appVersion),
                            UiText.nested(touchMode.label),UiText.nested(rtc.inputDiagnostics())) +
                        "\n${controllerPath.diagnostics()}\n${controllerPath.buttonDiagnostics()}\n${signaling.startupDiagnostics()}\n" +
                        "USB direto: ${usbDirect.snapshot().status.label} • ${usbDirect.snapshot().valid}/${usbDirect.snapshot().packets} • OUT ${usbDirect.snapshot().writes}\n" +
                        inputDevice.diagnostics() + (official?.let { "\n${it.diagnostics()}\n${it.noticeDiagnostics()}" } ?: "") + when (touchMode) {
                            TouchMode.NATIVE -> "\n${touchView.hudDiagnostics()}"
                            TouchMode.VIRTUAL -> "\n${touchView.hudDiagnostics()}"
                            else -> ""
                        } + "\n${joystickDispatch.diagnostics()}\n${rumble.diagnostics()}\n${gyro.diagnostics()}\n" +
                        signaling.keepAliveDiagnostics() + "\n" + signaling.dnsDiagnostics() +
                        "\nMKB/${sessionDesktopMode.name}; capture=$desktopCaptured; " +
                        "androidCapture=${Build.VERSION.SDK_INT >= 26 && window.decorView.hasPointerCapture()}"
                }
                tracker.mediaDetails = {
                    listOf(rtc.audioDiagnostics(), rtc.codecDiagnostics(), rtc.latencyDiagnostics(), rtc.iceDiagnostics(),
                        signaling.profileDiagnostics(), playbackDisplay.diagnostics(window.decorView.display),
                        videoDiagnostics(), surfaceRenderer.memoryDiagnostics(), recoveryState.diagnostics()).joinToString("\n")
                }
                surfaceRenderer.displayDimensions()?.let(rtc::updateDisplayDimensions)
                if(touchMode!=TouchMode.NATIVE) (0..3).filter { controllers.sourceFor(it)!=null }.forEach { publishGamepad(it) }
                val startupStage: (PlaybackStage) -> Unit = { timeline.mark(0, it) }
                val startupProgress: (String) -> Unit = { progress -> runOnUiThread {
                    if (generation == playbackGeneration && !firstFrame.isCompleted) {
                        queuePanel.updateProgress(progress)
                        hudTextView.text = progress
                        hudTextView.setTextColor(Color.YELLOW)
                    }
                } }
                val connected = if (remoteConsole != null) signaling.startRemotePlay(remoteConsole, rtc, settings.resolution,
                    sessionOptions, startupStage, startupProgress)
                else signaling.startAndConnectWebRtcSession(titleId, rtc, resolution = settings.resolution,
                    nativeTouch=touchMode==TouchMode.NATIVE,options=sessionOptions,microphone=settings.microphone,
                    onStage=startupStage,onQueueStatus = { status -> runOnUiThread {
                        if (generation == playbackGeneration) showQueue(status)
                    } },onProgress=startupProgress)
                connected.getOrThrow()
                if (touchMode == TouchMode.NATIVE) rtc.setNativeTouchEnabled(true)
                if (!firstFrame.isCompleted) queuePanel.updateProgress("VIDEO:")
                attempt.awaitFirstFrame(transportFailure)
                sessionTime.startFromSessionState(signaling.sessionTimeLimitSeconds(),sessionStartedAtMs)
                if (!remote && activityVisible && transmissionReady && gameplayInputEnabled()) {
                    sessionStartNotice.show(sessionTime.remainingSeconds(android.os.SystemClock.elapsedRealtime()))
                }
                renderSessionTime(sessionTime)
                val recovery = VideoRecoveryPolicy(compatible)
                var visibilityEpoch = videoVisibilityEpoch
                val nativeReadiness=com.xcloud.tclnative.webrtc.NativeTouchReadiness()
                var lastToken = -1L
                var recovering = false
                var pendingRecoveryTouch: TouchMode?=null
                fun interruptionDetails() = listOf(controllerPath.buttonDiagnostics(), signaling.keepAliveDiagnostics(),
                    rtc.iceDiagnostics(),rtc.inputDiagnostics(),rtc.codecDiagnostics()).joinToString("\n")
                recoverImage={
                    if(recoveryState.phase==TransmissionRecoveryState.Phase.ACTIVE) {
                        try {
                            resetController();recovery.retry(android.os.SystemClock.elapsedRealtime());recoveryState.repaired()
                            surfaceRenderer.rebuildSurface()
                            val requested=rtc.recoverVideoDecoder()
                            rumble.enabled=settings.rumble;resumeAuxiliaryInput();resynchronizeUsbInput()
                            Toast.makeText(this@MainActivity,if(requested) UiText.text("Recuperação de imagem solicitada.")
                                else UiText.text("Use Recarregar transmissão para tentar reconectar."),Toast.LENGTH_SHORT).show()
                        } catch(error: Exception) {
                            tracker.recordInterruption(error,interruptionDetails())
                            recoveryState.interrupted(recoveryState.epoch,error);transmissionReady=false;renderStatistics()
                            pendingRecoveryTouch=currentTouchMode;currentTouchMode=TouchMode.OFF;touchView.configure(TouchMode.OFF,game)
                            presentTransmissionInterruption()
                        }
                    } else Toast.makeText(this@MainActivity,UiText.text("Use Recarregar transmissão para tentar reconectar."),Toast.LENGTH_SHORT).show()
                }
                reloadTransmission=reload@ { bitrate ->
                    val token=recoveryState.begin() ?: return@reload
                    maxBitrateKbps=bitrate ?: maxBitrateKbps
                    timeline.beginReconnect(token)
                    val previousInput=interruptionDetails()
                    val savedTouch=pendingRecoveryTouch ?: currentTouchMode
                    pendingRecoveryTouch=savedTouch
                    transmissionReady=false;latestMetrics=null;renderStatistics()
                    resetController();currentTouchMode=TouchMode.OFF;touchView.configure(TouchMode.OFF,game)
                    videoRecoveryDialog?.dismiss();nativeTouchDialog?.dismiss()
                    hudTextView.visibility=View.VISIBLE;hudTextView.setTextColor(Color.YELLOW)
                    hudTextView.text=UiText.text("Recarregando áudio e vídeo da partida atual…")
                    transmissionReloadJob=playbackScope.launch {
                        try {
                            val completed=withTimeoutOrNull(65_000) {
                                microphoneJob?.cancelAndJoin();microphoneJob=null;pendingMicrophoneGeneration=null
                                // Detach the sink before disposing its native VideoTrack/decoder.
                                surfaceRenderer.detachTrack()
                                val previousRtc=allocatedRtc
                                if(webrtcClient===previousRtc) webrtcClient=null
                                previousRtc?.close();allocatedRtc=null
                                surfaceRenderer.rebuildSurface()
                                timeline.mark(token, PlaybackStage.RENDERER_READY)
                                transportFailure=CompletableDeferred()
                                val failure=transportFailure
                                val frame=CompletableDeferred<Unit>()
                                surfaceRenderer.onFirstFrameSubmitted={ submittedAt ->
                                    if (generation == playbackGeneration && recoveryState.current(token)) {
                                        timeline.mark(token, PlaybackStage.FIRST_EGL_SUBMISSION, submittedAt)
                                        frame.complete(Unit)
                                    }
                                }
                                surfaceRenderer.onRenderError={ if(generation==playbackGeneration && recoveryState.current(token)) failure.complete(RenderFailureException(it)) }
                                rtc=buildRtc();webrtcClient=rtc
                                rtc.setAudioForeground(activityVisible)
                                surfaceRenderer.displayDimensions()?.let(rtc::updateDisplayDimensions)
                                signaling.reconnectExistingSession(rtc,microphone=settings.microphone,
                                    onStage={ timeline.mark(token, it) }).getOrThrow()
                                rtc.setNativeTouchEnabled(savedTouch==TouchMode.NATIVE)
                                awaitTransmissionFrame(frame,failure)
                                true
                            } ?: false
                            if(!completed) throw java.io.IOException("RECONNECT_TIMEOUT: tempo limite aguardando transmissão")
                            if(generation==playbackGeneration && recoveryState.connected(token)) {
                                recovery.retry(android.os.SystemClock.elapsedRealtime());recovering=false
                                currentTouchMode=savedTouch;touchView.configure(savedTouch,game,hudStore.read(titleId))
                                pendingRecoveryTouch=null
                                applySavedResolution()
                                rumble.enabled=settings.rumble
                                (0..3).filter { controllers.sourceFor(it)!=null }.forEach { publishGamepad(it) }
                                transmissionReady=true;updateJoystickDispatch();resumeAuxiliaryInput();renderStatistics();resynchronizeUsbInput()
                                official?.let(::applyOfficialTouch)
                                hudTextView.visibility=View.GONE
                            }
                        } catch(cancelled: CancellationException) { throw cancelled }
                        catch(error: Exception) {
                            if(generation==playbackGeneration && recoveryState.current(token)) {
                                surfaceRenderer.detachTrack();allocatedRtc?.close()
                                webrtcClient=null;allocatedRtc=null
                                tracker.recordInterruption(error,previousInput)
                                recoveryState.failed(token,error);transmissionReady=false;renderStatistics()
                                hudTextView.text=if(recoveryState.phase==TransmissionRecoveryState.Phase.SESSION_ENDED) UiText.text("A sessão Xbox foi encerrada. Volte ao catálogo para iniciar outra partida.") else UiText.text("A transmissão não voltou. Você pode tentar reconectar à sessão atual.")
                                presentTransmissionInterruption()
                            }
                        }
                    }
                }
                kotlinx.coroutines.coroutineScope {
                    launch { signaling.maintainSession() }
                    if (official!=null) {
                        loadOfficialTouch = {
                            if (generation==playbackGeneration && officialTouch===official && official.beginLoad()) launch {
                                val result = try {
                                    withTimeoutOrNull(20_000) {
                                        signaling.officialTouchBundle(game,sessionOptions.locale,cacheDir)
                                    } ?: OfficialTouchLoad.Failed(OfficialTouchLoad.Stage.TIMEOUT)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    OfficialTouchLoad.Failed(OfficialTouchLoad.Stage.METADATA)
                                }
                                if (generation==playbackGeneration && officialTouch===official) {
                                    val manual = official.waitingForManualLoad
                                    official.load(result)
                                    if (manual) finishManualOfficialTouch(official) else applyOfficialTouch(official)
                                }
                            }
                        }
                        loadOfficialTouch?.invoke()
                    }
                    while (isActive) {
                        if(recoveryState.phase!=TransmissionRecoveryState.Phase.ACTIVE) {
                            hideSessionTimeNotice()
                            delay(250)
                            continue
                        }
                        val token=recoveryState.epoch
                        val failure=withTimeoutOrNull(1000) { transportFailure.await() }
                        if(recoveryState.phase!=TransmissionRecoveryState.Phase.ACTIVE || !recoveryState.current(token)) continue
                        if(failure!=null) {
                            if(recoveryState.interrupted(token,failure)) {
                                if (sessionSaved && recoveryState.phase==TransmissionRecoveryState.Phase.SESSION_ENDED) throw failure
                                tracker.recordInterruption(failure,interruptionDetails())
                                transmissionReady=false;renderStatistics();resetController()
                                pendingRecoveryTouch=currentTouchMode;currentTouchMode=TouchMode.OFF
                                touchView.configure(TouchMode.OFF,game.takeUnless { sessionSaved })
                                presentTransmissionInterruption()
                            }
                            continue
                        }
                        renderSessionTime(sessionTime)
                        if (!activityVisible || sessionSaved) continue
                        if (visibilityEpoch != videoVisibilityEpoch) {
                            visibilityEpoch = videoVisibilityEpoch
                            recovery.reset()
                        }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if(nativeReadiness.offer(now,currentTouchMode==TouchMode.NATIVE,rtc.nativeTouchMetadataReady(),
                                activityVisible && gameplayInputEnabled())) {
                            nativeTouchDialog=com.xcloud.tclnative.ui.dashboard.NativeTouchPanel.waiting(this@MainActivity) { enableGameHud() }
                                .also { dialog -> dialog.setOnDismissListener { if(nativeTouchDialog===dialog) nativeTouchDialog=null } }
                        }
                        val health = surfaceRenderer.probeVideoHealth()
                        val receiving=streamHistory.receivingVideo(now)
                        val action = recovery.observe(now, health.submittedToken, health.deliveredRecently,receiving)
                        tracker.observe(now,health.submittedToken,receiving,action)
                        if (health.submittedToken != lastToken && recovering) {
                            recovering = false
                            hudTextView.visibility = View.GONE
                            videoRecoveryDialog?.dismiss();nativeTouchDialog?.dismiss()
                            hudTextView.text = UiText.text("Vídeo nativo ativo [{0}]",UiText.nested(gameName))
                            hudTextView.setTextColor(Color.GREEN)
                        }
                        lastToken = health.submittedToken
                        when (action) {
                            VideoRecoveryAction.NONE -> Unit
                            VideoRecoveryAction.REQUEST_KEYFRAME -> {
                                recovering = true
                                hudTextView.visibility = View.VISIBLE
                                hudTextView.text = UiText.text("Vídeo sem novos quadros. Solicitando atualização da imagem…")
                                hudTextView.setTextColor(Color.YELLOW)
                                rtc.requestVideoKeyframe()
                            }
                            VideoRecoveryAction.RESET_DECODER -> {
                                hudTextView.text = UiText.text("Recuperando vídeo: reiniciando decoder no próximo quadro-chave…")
                                rtc.recoverVideoDecoder()
                            }
                            VideoRecoveryAction.REBUILD_SURFACE -> {
                                hudTextView.text = UiText.text("Recuperando vídeo: recriando superfície de exibição…")
                                surfaceRenderer.rebuildSurface()
                                rtc.requestVideoKeyframe()
                            }
                            VideoRecoveryAction.OFFER_RETRY -> {
                                hudTextView.text = UiText.text("O vídeo ainda não voltou. A sessão Xbox continua aberta.")
                                resetController()
                                val dialog = ConsoleDialogs.message(this@MainActivity,UiText.text("Imagem sem atualização"),
                                    UiText.text("A imagem não voltou após 30 segundos. Recuperar o vídeo mantém a partida aberta. Voltar ao catálogo encerra a sessão."),
                                    listOf(ConsoleAction(UiText.text("Recuperar vídeo")) {
                                        recovery.retry(android.os.SystemClock.elapsedRealtime())
                                        if (surfaceRenderer.probeVideoHealth().deliveredRecently) surfaceRenderer.rebuildSurface()
                                        rtc.recoverVideoDecoder()
                                    }, ConsoleAction(UiText.text("Recarregar transmissão — experimental")) { showTransmissionRecovery() },
                                        ConsoleAction(UiText.text("Voltar ao catálogo")) { cancelPlayback() }),
                                    onCancel = { showGameMenu() })
                                videoRecoveryDialog = dialog
                                dialog.setOnDismissListener { if (videoRecoveryDialog === dialog) videoRecoveryDialog = null }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed=true
                startupFailure=SessionFailure.from(error).code
                tracker.recordFailure(error)
                if (generation == playbackGeneration && !sessionSaved && (!firstFrame.isCompleted || !settingsStore.read().sessionReport)) {
                    val noCloudHours = error is CloudHttpException && error.stage=="PLAY" &&
                        error.serviceCode.equals("CloudHoursExhausted", ignoreCase=true)
                    val catalogPlaytime = if(noCloudHours) dashboard?.cloudPlaytimeDiagnostics() else null
                    val details = (if(noCloudHours) UiText.text("O Xbox informou que as horas de jogo na nuvem acabaram.")+"\n\n" else "")+
                        (error.message ?: UiText.text("Falha na sessão"))+
                        (catalogPlaytime?.let { "\nCATALOG_PLAYTIME/$it" } ?: "")+
                        if(!firstFrame.isCompleted) "\nUpscaling=${settings.fsr}; output=${settings.fsrOutput}\n${signaling.startupDiagnostics()}\n${controllerPath.diagnostics()}" else ""
                    hudTextView.text = details
                    hudTextView.setTextColor(Color.RED)
                    if (remote) remoteError = details else showCopyableErrorDialog(gameName, details)
                }
            } finally {
                // Always release the local decoder and server session together.
                withContext(NonCancellable) {
                    val expiredWhileSaved = generation == playbackGeneration && sessionSaved
                    if (expiredWhileSaved) clearSavedSession()
                    recoveryState.close()
                    transmissionReloadJob?.cancelAndJoin();transmissionReloadJob=null
                    if(generation==playbackGeneration) {
                        recoverImage=null;reloadTransmission=null;transmissionReady=false;transmissionRecovery=null
                        pendingResumeResolution=null
                    }
                    val rtc=allocatedRtc
                    if(!firstFrame.isCompleted) runCatching {
                        val version=packageManager.getPackageInfo(packageName,0).versionName ?: "n/d"
                        val snapshot = timeline.snapshot()
                        startupReportStore.save(gameName,"xCLOUD $version\nSTARTUP/NO_VIDEO; input=${touchMode.name}; failure=${startupFailure ?: "NONE"}\n"+
                            "$startupInput\nREQUESTED_PROFILE/${settings.resolution.name}; os=${settings.resolution.osName}; codec=${settings.videoCodec.name}; mode=${settings.videoMode.name}\n"+
                            snapshot.text()+"\n"+signaling.startupDiagnostics()+"\n"+surfaceRenderer.memoryDiagnostics()+"\n"+
                            (rtc?.inputDiagnostics() ?: ""),snapshot)
                    }
                    tracker.finish(android.os.SystemClock.elapsedRealtime(),if(failed) SessionEnd.FAILURE else SessionEnd.USER,rtc?.currentInputMetrics())
                    microphoneJob?.cancelAndJoin();microphoneJob=null;pendingMicrophoneGeneration=null
                    rtc?.setMicrophoneMuted(true)
                    gyro.stop();setDesktopCapture(false)
                    if(generation==playbackGeneration) { sessionDesktopMode=DesktopInputMode.OFF;sessionSettings=null }
                    if (generation == playbackGeneration) {
                        if (!expiredWhileSaved) showCatalogReturn(game,pendingLoadingGame==null)
                        finishLoading()
                        statsView.visibility = View.GONE
                        hideSessionTimeNotice()
                        officialTouch = null
                        loadOfficialTouch = null
                    }
                    allowTransitionFrame()
                    videoRecoveryDialog?.dismiss();nativeTouchDialog?.dismiss()
                    resetController()
                    currentTouchMode=TouchMode.OFF
                    touchView.installOfficialControls(null)
                    touchView.configure(TouchMode.OFF)
                    attempt.releaseMedia(surfaceRenderer::clearSession, { rtc?.close() }) {
                        if (webrtcClient === rtc) webrtcClient = null
                        if (playbackSignaling === signaling) playbackSignaling = null
                        signaling.stop()
                    }
                    if (generation == playbackGeneration) {
                        activeGame=null
                        if (expiredWhileSaved && dashboard != null) {
                            remotePlayReturn = null
                            if (failed && activityVisible) Toast.makeText(this@MainActivity,
                                UiText.text("A sessão salva não está mais disponível."), Toast.LENGTH_LONG).show()
                        } else restoreDashboard(reopenRemote = !failed)
                        remoteError?.takeIf { activityVisible && !isFinishing && !isDestroyed }?.let {
                            showCopyableErrorDialog(gameName, it, onBack = returnToConsoleList)
                        }
                    }
                    finishSessionReport(tracker, showAutomatically = !expiredWhileSaved)
                }
            }
        }
    }

    private fun mustUseSystemDns(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return true
        return manager.defaultProxy != null ||
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
            (Build.VERSION.SDK_INT >= 28 && manager.getLinkProperties(network)?.isPrivateDnsActive == true)
    }

    private fun beginLoading(game: GameTitle, remote: Boolean = false) {
        val latest=currentGames.firstOrNull { it.titleId==game.titleId }?.withMetadataFallback(game) ?: game
        pendingLoadingGame = latest
        queuePanel.begin(latest, remotePlay = remote)
        loadingArtJob?.cancel()
        if(!remote && latest.posterUrl==null && latest.tileUrl==null) loadingArtJob=mainScope.launch {
            // Resolve only this validated title; bulk catalog work remains paused while playing.
            createCatalogService().hydrate(listOf(latest)) { enriched -> withContext(Dispatchers.Main) {
                val fresh=enriched.firstOrNull() ?: return@withContext
                if(fresh.posterUrl==null && fresh.tileUrl==null) return@withContext
                if(pendingLoadingGame?.titleId==game.titleId) {
                    currentGames=currentGames.map { if(it.titleId==game.titleId) fresh.withMetadataFallback(it) else it }
                    refreshLoadingArt()
                    val snapshot=currentGames
                    withContext(Dispatchers.IO) { catalogCache.save(snapshot) }
                }
            } }
        }
        queueTicker?.cancel()
        queueTicker = mainScope.launch {
            while (isActive && pendingLoadingGame != null) {
                queuePanel.render(System.nanoTime()/1_000_000)
                delay(1000)
            }
        }
    }
    private fun refreshLoadingArt() {
        val pending = pendingLoadingGame ?: return
        currentGames.firstOrNull { it.titleId==pending.titleId }?.let { game -> pendingLoadingGame = game; queuePanel.setGame(game) }
    }
    private fun finishLoading(forPlayback: Boolean = false) {
        pendingLoadingGame = null
        loadingArtJob?.cancel(); loadingArtJob=null
        queueTicker?.cancel(); queueTicker = null
        queuePanel.dismiss(forPlayback)
    }
    private fun showQueue(status: com.xcloud.tclnative.signaling.QueueStatus?) {
        if (pendingLoadingGame != null) queuePanel.updateQueue(status)
    }

    private fun toggleMicrophone() {
        if (sessionSaved) return
        if(transmissionRecovery?.phase!=TransmissionRecoveryState.Phase.ACTIVE) return
        val rtc=webrtcClient ?: return
        if(rtc.microphoneActive) { rtc.setMicrophoneMuted(true);return }
        if(microphoneJob?.isActive==true) { Toast.makeText(this,UiText.text("Negociando microfone…"),Toast.LENGTH_SHORT).show();return }
        if(!rtc.canUseMicrophone()) {
            ConsoleDialogs.message(this,UiText.text("Microfone"),rtc.microphoneUnavailableReason())
            return
        }
        if(androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            pendingMicrophoneGeneration=playbackGeneration
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO),2206)
            return
        }
        val generation=playbackGeneration
        microphoneJob=mainScope.launch {
            try {
                if(!rtc.microphoneNegotiated()) {
                    withTimeout(35000) {
                        val offer=rtc.createChatOffer()
                        val answer=playbackSignaling?.renegotiateChat(offer) ?: throw java.io.IOException("CHAT: sessão encerrada")
                        ensureActive()
                        if(generation!=playbackGeneration || webrtcClient!==rtc) throw CancellationException("session replaced")
                        rtc.applyChatAnswer(answer)
                    }
                }
                if(activityVisible && generation==playbackGeneration && webrtcClient===rtc) rtc.setMicrophoneMuted(false)
            } catch(cancelled: CancellationException) {
                withContext(NonCancellable) { rtc.rejectChat() };throw cancelled
            } catch(error: Exception) {
                rtc.rejectChat()
                if(generation==playbackGeneration) showCopyableErrorDialog(UiText.text("Microfone"),error.message ?: "CHAT: não foi possível negociar o microfone")
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==2206) {
            val generation=pendingMicrophoneGeneration;pendingMicrophoneGeneration=null
            if(generation==playbackGeneration && activityVisible && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) toggleMicrophone()
        }
    }
    private fun cancelPlayback() {
        hideSessionTimeNotice()
        if(transitionPanel.exiting) return
        clearSavedSession()
        showCatalogReturn(activeGame ?: pendingLoadingGame,pendingLoadingGame==null)
        reportTracker?.finish(android.os.SystemClock.elapsedRealtime(),SessionEnd.USER,webrtcClient?.currentInputMetrics())
        val (generation, closing) = playback.cancel()
        activeGame = null
        officialTouch = null
        loadOfficialTouch = null
        shortcutSwitchDialog?.dismiss()
        gameMenuDialog?.dismiss()
        videoRecoveryDialog?.dismiss();nativeTouchDialog?.dismiss()
        resetController()
        currentTouchMode=TouchMode.OFF
        touchView.installOfficialControls(null)
        touchView.configure(TouchMode.OFF)
        finishLoading()
        statsView.visibility = View.GONE
        hudTextView.visibility = View.GONE
        catalogReturnJob=mainScope.launch {
            closing?.join()
            if(generation==playbackGeneration && transitionPanel.exiting) {
                reportTracker?.let { finishSessionReport(it) }
                restoreDashboard()
            }
        }
    }

    private fun hideSessionTimeNotice() {
        if (::sessionStartNotice.isInitialized) sessionStartNotice.hide()
        hideSessionTimeWarning()
    }

    private fun hideSessionTimeWarning() {
        if (!::sessionTimeView.isInitialized) return
        sessionTimeView.removeCallbacks(dismissSessionTimeNotice)
        sessionTimeView.visibility = View.GONE
    }

    private fun renderSessionTime(limit: SessionTimeLimit) {
        val seconds = limit.remainingSeconds(android.os.SystemClock.elapsedRealtime())
        if (!activityVisible || !transmissionReady || !gameplayInputEnabled()) {
            hideSessionTimeNotice()
            return
        }
        sessionStartNotice.update(seconds)
        if (seconds == null || seconds == 0L) {
            hideSessionTimeWarning()
            return
        }
        val minutes = limit.takeWarningMinutes(seconds) ?: return
        sessionTimeView.text = if (minutes == 1)
            UiText.text("Resta até 1 minuto nesta partida. Salve seu progresso no jogo.")
        else UiText.text("Restam até 5 minutos nesta partida. Salve seu progresso no jogo.")
        sessionTimeView.visibility = View.VISIBLE
        sessionTimeView.announceForAccessibility(sessionTimeView.text)
        sessionTimeView.removeCallbacks(dismissSessionTimeNotice)
        sessionTimeView.postDelayed(dismissSessionTimeNotice, 4_500)
    }

    private fun showCopyableErrorDialog(gameName: String, fullError: String,onBack: (() -> Unit)?=null,
                                        export: (() -> Unit)?=null) {
        ConsoleDialogs.message(this,UiText.text("Diagnóstico de sessão ({0})",UiText.nested(gameName)),fullError,listOf(
            ConsoleAction(UiText.text("Copiar detalhes")) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(UiText.text("xCloud Error"), fullError))
                Toast.makeText(this,UiText.text("Detalhes copiados!"),Toast.LENGTH_LONG).show()
                onBack?.invoke()
            }, ConsoleAction(UiText.text("Fechar")) { onBack?.invoke() }),onCancel={ onBack?.invoke() })
            .also { dialog -> if (export != null) dialog.addPersistentAction(UiText.text("Exportar diagnóstico"), export) }
    }

    private fun gameplayInputEnabled() = !sessionSaved && controlPanel.visibility == View.GONE &&
        transitionPanel.visibility!=View.VISIBLE && queuePanel.visibility != View.VISIBLE && gameMenuDialog?.isShowing != true && videoRecoveryDialog?.isShowing != true && nativeTouchDialog?.isShowing != true && webrtcClient != null &&
        transmissionRecovery?.phase==TransmissionRecoveryState.Phase.ACTIVE && !touchView.isEditing && hasWindowFocus()

    private fun updateJoystickDispatch() { joystickDispatch.request(activityVisible && immediateJoystickForSession && gameplayInputEnabled()) }

    private fun combinedInput(slot: Int,physicalOverride: GamepadState?=null): GamepadState {
        val raw=physicalOverride ?: controllers.physical(slot)
        val device=controllers.deviceFor(slot)?.let(InputDevice::getDevice)
        val source=controllers.sourceFor(slot)
        val profile=when {
            source is ControllerSource.Usb -> controllerProfiles.read(source.profileKey)
            device!=null -> controllerProfiles.read(controllerProfiles.key(device))
            else -> null
        }
        val physical=profile?.apply(raw) ?: raw
        controllerPath.record(slot,source,raw,physical,profile)
        val base=controllers.combined(slot,physical)
        if (slot!=0) return base
        val merged=if(sessionDesktopMode==DesktopInputMode.EMULATED) ControllerRouter.merge(base,desktopGamepad.state()) else base
        val guideButtons=if (::guideTouchControls.isInitialized) guideTouchControls.buttons else 0
        return gyro.apply(if (guideButtons==0) merged else merged.copy(buttons=merged.buttons or guideButtons))
    }
    private fun publishGamepad(slot: Int, temporary: Boolean = false, preserveButtonEdges: Boolean = false) {
        val auxiliary=slot==0 && currentTouchMode==TouchMode.NATIVE && controllers.sourceFor(0)==null &&
            (temporary || sessionSettings?.gyroNativeTouch==true)
        val officialTouch = slot == 0 && currentTouchMode == TouchMode.NATIVE &&
            controllers.sourceFor(0) == null && touchView.usingOfficialControls
        webrtcClient?.updateGamepad(combinedInput(slot), slot, auxiliary, preserveButtonEdges, officialTouch)
    }
    private fun setDesktopCapture(enabled: Boolean) {
        val active=enabled && sessionDesktopMode!=DesktopInputMode.OFF && webrtcClient!=null
        desktopCaptured=active;lastMousePosition=null
        if(!active) { desktopGamepad.reset();auxiliaryHandler.removeCallbacks(releaseMouseMotion);webrtcClient?.releaseDesktop() }
        if(Build.VERSION.SDK_INT>=26) {
            if(active) {
                if(desktopFocusState==null) desktopFocusState=window.decorView.isFocusable to window.decorView.isFocusableInTouchMode
                window.decorView.isFocusableInTouchMode=true;window.decorView.isFocusable=true
                window.decorView.setOnCapturedPointerListener { _,event -> handleDesktopMouse(event,true) }
                window.decorView.requestFocus();window.decorView.requestPointerCapture()
            } else { window.decorView.releasePointerCapture();window.decorView.setOnCapturedPointerListener(null)
                desktopFocusState?.let { window.decorView.isFocusableInTouchMode=it.second;window.decorView.isFocusable=it.first };desktopFocusState=null }
        }
        if(webrtcClient!=null) publishGamepad(0)
    }
    private fun resumeAuxiliaryInput() {
        val settings=sessionSettings ?: return
        if(!gameplayInputEnabled() || guideTouchControls.active) return
        gyro.start(settings.gyroMode,settings.gyroSensitivity,currentTouchMode==TouchMode.NATIVE,settings.gyroNativeTouch,
            settings.gyroSource,controllers.deviceFor(0) ?: preferredControllerId)
        if(sessionDesktopMode!=DesktopInputMode.OFF) setDesktopCapture(true)
    }
    private fun refreshControllerGyro() {
        if(sessionSettings?.gyroSource!=GyroSource.CONTROLLER || !gameplayInputEnabled()) return
        resumeAuxiliaryInput()
        publishGamepad(0)
    }

    private fun handleDesktopMouse(event: MotionEvent,captured: Boolean=false): Boolean {
        if(!desktopCaptured || sessionDesktopMode==DesktopInputMode.OFF || !gameplayInputEnabled()) return false
        val movement=event.actionMasked==MotionEvent.ACTION_MOVE || event.actionMasked==MotionEvent.ACTION_HOVER_MOVE
        val previous=lastMousePosition
        val dx=if(!movement) 0f else if(captured) event.x else if(previous==null) 0f else event.x-previous.first
        val dy=if(!movement) 0f else if(captured) event.y else if(previous==null) 0f else event.y-previous.second
        if(movement) lastMousePosition=event.x to event.y
        if(sessionDesktopMode==DesktopInputMode.NATIVE) {
            webrtcClient?.desktopMouse(NativeMouse(dx.toInt(),dy.toInt(),(event.getAxisValue(MotionEvent.AXIS_HSCROLL)*120).toInt(),
                (event.getAxisValue(MotionEvent.AXIS_VSCROLL)*120).toInt(),event.buttonState))
        } else {
            desktopGamepad.mouse(dx,dy,event.buttonState,sessionSettings?.mouseSensitivity ?: 100);publishGamepad(0)
            auxiliaryHandler.removeCallbacks(releaseMouseMotion);auxiliaryHandler.postDelayed(releaseMouseMotion,30)
        }
        return true
    }
    private fun controllerSlot(deviceId: Int,source: Int,confirmedGamepad: Boolean,register: Boolean = true): Int? {
        val android=ControllerSource.Android(deviceId)
        controllers.existingSlot(android)?.let { return it }
        if (!televisionInput) {
            if (!AndroidGamepadPolicy.supportsGamepad(source)) return 0
            return if (register) controllers.slot(android).also { if(it==0) refreshControllerGyro() } else null
        }
        val hasPrimary=controllers.sourceFor(0)!=null
        if(hasPrimary && !controllers.coOp) return null
        if(deviceId>=0 && confirmedGamepad && register) {
            return controllers.slot(android).also { if(it==0) refreshControllerGyro() }
        }
        return 0.takeUnless { hasPrimary }
    }
    private fun removeController(deviceId: Int) {
        if (recoveryMenuDeviceId == deviceId) {
            recoveryMenuShortcut.reset()
            recoveryMenuDeviceId = null
        }
        val slot=controllers.remove(deviceId) ?: return
        resetControllerShortcut(slot)
        webrtcClient?.removeGamepad(slot)
        if(slot==0 && currentTouchMode==TouchMode.VIRTUAL) publishGamepad(0)
        rumble.stop()
    }

    private fun resynchronizeUsbInput() {
        if(!gameplayInputEnabled()) return
        val source=usbDirect.source ?: return
        val state=usbDirect.currentInput()
        usbBlockedButtons=usbBlockedButtons or state.buttons
        handleUsbInput(source,state)
    }
    private fun handleUsbInput(source: ControllerSource.Usb,state: GamepadState) {
        if(usbDirect.source!=source || !activityVisible) return
        if(!gameplayInputEnabled()) {
            usbBlockedButtons=state.buttons
            usbNavigation.update(state)
            return
        }
        usbNavigation.clear()
        val slot=controllers.slot(source) ?: return
        usbBlockedButtons=usbBlockedButtons and state.buttons
        val filtered=state.copy(buttons=state.buttons and usbBlockedButtons.inv())
        touchView.physicalInput(filtered)
        val held=filtered.buttons and ControllerShortcut.BOTH
        val old=usbShortcutHeld;usbShortcutHeld=held
        val forwarded=controllers.physical(slot).buttons and ControllerShortcut.BOTH
        controllers.update(slot,filtered.copy(buttons=(filtered.buttons and ControllerShortcut.BOTH.inv()) or forwarded))
        publishGamepad(slot)
        for(mask in intArrayOf(ControllerShortcut.START,ControllerShortcut.SELECT))
            if((held xor old) and mask!=0) handleControllerShortcut(slot,mask,held and mask!=0)
    }
    private fun removeUsbController(source: ControllerSource.Usb) {
        val slot=controllers.remove(source) ?: return
        usbNavigation.clear();usbBlockedButtons=0;usbShortcutHeld=0
        resetControllerShortcut(slot)
        webrtcClient?.removeGamepad(slot)
        if(slot==0 && currentTouchMode==TouchMode.VIRTUAL) publishGamepad(0)
    }

    private fun toggleStatistics() {
        statistics.toggle(statisticsSettings.detailed)
        renderStatistics()
    }
    private fun refreshStatisticsSettings() {
        statisticsSettings=statisticsStore.read();statistics.configure(statisticsSettings.detailed)
        if(::statsView.isInitialized) { com.xcloud.tclnative.ui.dashboard.StatisticsOverlayStyle.apply(statsView,statisticsSettings,displayCutout);renderStatistics() }
    }
    private fun renderStatistics() {
        renderNetworkStatus()
        if(!::statsView.isInitialized) return
        statsView.visibility=if(statistics.mode!=0 && transmissionReady && !sessionSaved) View.VISIBLE else View.GONE
        if(statsView.visibility!=View.VISIBLE) return
        val metrics=latestMetrics ?: return
        val settings=sessionSettings ?: playbackSettings
        val remote = remotePlayReturn != null
        val showPlaytime=!remote && (statistics.mode==2 || StatisticField.PLAYTIME in statisticsSettings.fields)
        if(showPlaytime) dashboard?.let { refreshCloudPlaytime(it,duringPlayback=true) }
        val playtime=dashboard?.cloudPlaytimeSnapshot() ?: CloudPlaytime()
        com.xcloud.tclnative.ui.dashboard.StatisticsOverlayStyle.constrain(statsView,rootLayout.width,rootLayout.height)
        statsView.text=surfaceRenderer.frameGenerationSummary()?.let { "$it\n" }.orEmpty()+if(statistics.mode==2) (if (remote) "" else playtime.label+"\n")+metrics.overlay(latestInput,"",settings.resolution,settings.videoMode==VideoMode.COMPATIBILITY,webrtcClient?.maxBitrateKbps ?: 0)+
            surfaceRenderer.diagnostics()+"\n"+(webrtcClient?.latencyDiagnostics() ?: "")
            else CustomStatistics.format(metrics,latestInput,
                if (remote) statisticsSettings.copy(fields = statisticsSettings.fields - StatisticField.PLAYTIME) else statisticsSettings,playtime)
    }
    private fun renderNetworkStatus() {
        if (!::networkIndicator.isInitialized) return
        val live = transmissionReady && activityVisible && !sessionSaved
        if (!live) networkQuality.reset()
        networkPanel?.update(latestMetrics.takeIf { live }, networkQuality.quality)
        networkIndicator.showQuality(networkQuality.quality)
        networkIndicator.visibility = if (live && networkWarningsEnabled && hasWindowFocus()) View.VISIBLE else View.GONE
    }
    private fun showTransmissionRecovery() = showTransmissionRecovery(::showGameMenu)
    private fun showTransmissionRecovery(onBack: () -> Unit) {
        if(sessionSaved || reloadTransmission==null) return
        when (transmissionRecovery?.phase) {
            TransmissionRecoveryState.Phase.WAITING_RETRY, TransmissionRecoveryState.Phase.SESSION_ENDED -> {
                showTransmissionInterrupted(onBack); return
            }
            TransmissionRecoveryState.Phase.ACTIVE -> Unit
            else -> return
        }
        com.xcloud.tclnative.ui.dashboard.TransmissionRecoveryPanel.show(this,{ recoverImage?.invoke() },{ reloadTransmission?.invoke(null) },onBack)
    }
    private fun presentTransmissionInterruption() {
        if (!activityVisible || sessionSaved) return
        val phase = transmissionRecovery?.phase
        videoRecoveryDialog?.dismiss(); nativeTouchDialog?.dismiss()
        if (phase==TransmissionRecoveryState.Phase.WAITING_RETRY && InputDeviceProfile.read(this).touchDevice) {
            hudTextView.visibility=View.VISIBLE
            hudTextView.setTextColor(Color.YELLOW)
            hudTextView.text=UiText.text("A transmissão foi interrompida. Você pode tentar reconectar à sessão atual.")
        } else showTransmissionInterrupted()
    }
    private fun showTransmissionInterrupted(onBack: () -> Unit = ::showGameMenu) {
        if (!activityVisible || sessionSaved) return
        (gameMenuDialog as? GameShortcutBar)?.dismiss()
        videoRecoveryDialog?.dismiss();nativeTouchDialog?.dismiss()
        videoRecoveryDialog=com.xcloud.tclnative.ui.dashboard.TransmissionRecoveryPanel.failed(this,
            { reloadTransmission?.invoke(null) },{ cancelPlayback() },onBack,
            sessionEnded=transmissionRecovery?.phase==TransmissionRecoveryState.Phase.SESSION_ENDED).also { dialog ->
                dialog.setOnDismissListener { if(videoRecoveryDialog===dialog) videoRecoveryDialog=null }
            }
    }

    private fun applyShortcut(slot: Int, update: ShortcutUpdate) {
        val state = controllers.physical(slot)
        val forwarded = state.copy(buttons = (state.buttons and ControllerShortcut.BOTH.inv()) or update.forwardedButtons)
        if (update.pulse != 0) {
            webrtcClient?.updateGamepad(combinedInput(slot,forwarded.copy(buttons = forwarded.buttons or update.pulse)),slot)
        }
        controllers.update(slot,forwarded)
        publishGamepad(slot)
        if (update.openMenu) {
            controllerPath.menuChord()
            showGameMenu()
        }
    }

    private fun handleControllerShortcut(slot: Int, mask: Int, down: Boolean, emitPulse: Boolean = true): Boolean {
        if (mask != ControllerShortcut.START && mask != ControllerShortcut.SELECT) return false
        val shortcut = controllerShortcuts[slot]
        shortcutFlushJobs[slot]?.cancel()
        applyShortcut(slot,shortcut.change(mask, down, android.os.SystemClock.elapsedRealtime(), emitPulse))
        shortcut.flushAtMs?.let { deadline ->
            shortcutFlushJobs[slot] = mainScope.launch {
                delay((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (gameplayInputEnabled()) applyShortcut(slot,shortcut.flush(android.os.SystemClock.elapsedRealtime()))
            }
        }
        return true
    }

    private fun resetControllerShortcut(slot: Int) {
        shortcutFlushJobs[slot]?.cancel()
        shortcutFlushJobs[slot] = null
        controllerShortcuts[slot].reset()
    }

    private fun resetController() {
        xboxButtonJob?.cancel()
        hideGuideTouchControls()
        gyro.stop();setDesktopCapture(false)
        controllerShortcuts.indices.forEach(::resetControllerShortcut)
        recoveryMenuShortcut.reset()
        recoveryMenuDeviceId = null
        usbShortcutHeld=0
        usbNavigation.clear();usbBlockedButtons=usbDirect.currentInput().buttons
        if(::touchView.isInitialized) touchView.releaseInputs()
        controllers.reset()
        webrtcClient?.resetInput()
        rumble.enabled=false; rumble.stop()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        AndroidControllerMonitor.motion(event)
        if(usbDirect.ownsAndroid(event.deviceId)) return true
        return super.dispatchGenericMotionEvent(event)
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if(event.isFromSource(InputDevice.SOURCE_MOUSE) && handleDesktopMouse(event)) return true
        if (gameplayInputEnabled() &&
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            val receivedAt=android.os.SystemClock.uptimeMillis()
            val device=event.device
            fun range(id: Int): com.xcloud.tclnative.webrtc.ControllerAxisRange? =
                device?.getMotionRange(id, event.source)?.let {
                    com.xcloud.tclnative.webrtc.ControllerAxisRange(it.min, it.max, it.flat)
                }
            fun stick(id: Int, hardwareDeadzone: Boolean = false) = com.xcloud.tclnative.webrtc.ControllerAxes.stick(
                event.getAxisValue(id), range(id), hardwareDeadzone)
            fun trigger(primary: Int, fallback: Int): Float {
                fun read(id: Int): Float {
                    val limits = range(id)
                    return com.xcloud.tclnative.webrtc.ControllerAxes.trigger(event.getAxisValue(id), limits)
                }
                return maxOf(read(primary), read(fallback))
            }
            var buttons = 0
            val x = stick(MotionEvent.AXIS_HAT_X); val y = stick(MotionEvent.AXIS_HAT_Y)
            if (y < -0.5f) buttons = buttons or 256
            if (y > 0.5f) buttons = buttons or 512
            if (x < -0.5f) buttons = buttons or 1024
            if (x > 0.5f) buttons = buttons or 2048
            val rightX = if (range(MotionEvent.AXIS_Z) == null && range(MotionEvent.AXIS_RX) != null) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
            val rightY = if (range(MotionEvent.AXIS_RZ) == null && range(MotionEvent.AXIS_RY) != null) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ
            val axes = GamepadState(buttons = buttons,
                leftX = stick(MotionEvent.AXIS_X, true), leftY = stick(MotionEvent.AXIS_Y, true),
                rightX = stick(rightX), rightY = stick(rightY),
                leftTrigger = trigger(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
                rightTrigger = trigger(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS))
            if (televisionInput && controllers.existingSlot(ControllerSource.Android(event.deviceId)) == null &&
                !AndroidGamepadPolicy.confirmsJoystick(axes)) return true
            val slot = controllerSlot(event.deviceId, event.source, confirmedGamepad = true) ?: return true
            webrtcClient?.recordControllerEvent(event.eventTime,receivedAt,event.historySize,device?.name)
            controllers.updateAndroidMotion(slot, axes)
            touchView.physicalInput(controllers.physical(slot))
            publishGamepad(slot)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun gamepadButton(key: Int, event: KeyEvent): Int = when (key) {
        KeyEvent.KEYCODE_BUTTON_MODE -> ControllerRouter.XBOX_BUTTON
        KeyEvent.KEYCODE_BUTTON_START -> 4
        KeyEvent.KEYCODE_BUTTON_SELECT -> 8
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> 16
        KeyEvent.KEYCODE_ENTER -> if (televisionInput && event.device?.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) 16 else 0
        KeyEvent.KEYCODE_BUTTON_B -> 32
        KeyEvent.KEYCODE_BUTTON_X -> 64
        KeyEvent.KEYCODE_BUTTON_Y -> 128
        KeyEvent.KEYCODE_DPAD_UP -> 256
        KeyEvent.KEYCODE_DPAD_DOWN -> 512
        KeyEvent.KEYCODE_DPAD_LEFT -> 1024
        KeyEvent.KEYCODE_DPAD_RIGHT -> 2048
        KeyEvent.KEYCODE_BUTTON_L1 -> 4096
        KeyEvent.KEYCODE_BUTTON_R1 -> 8192
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 16384
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 32768
        else -> 0
    }

    private fun dispatchGameplayButton(event: KeyEvent): Boolean {
        val mask = gamepadButton(event.keyCode, event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (mask == 0 || (!down && event.action != KeyEvent.ACTION_UP)) return false
        if (down && event.repeatCount > 0) {
            controllerPath.ignoredRepeat()
            return true
        }
        if (down && event.isCanceled) return true
        val slot = controllerSlot(event.deviceId, event.source, confirmedGamepad = down &&
            televisionInput && AndroidGamepadPolicy.confirmsGamepad(event), register = down) ?: return true
        if (down) touchView.physicalInput(GamepadState(buttons = mask))
        if (handleControllerShortcut(slot, mask, down, emitPulse = !event.isCanceled)) return true
        if (controllers.updateAndroidKey(slot, event.deviceId, event.keyCode, mask, down)) publishGamepad(slot)
        return true
    }

    private fun canOpenRecoveryMenu() = !sessionSaved && hasWindowFocus() && controlPanel.visibility==View.GONE &&
        transitionPanel.visibility!=View.VISIBLE && queuePanel.visibility!=View.VISIBLE && reloadTransmission!=null &&
        (transmissionRecovery?.phase==TransmissionRecoveryState.Phase.WAITING_RETRY ||
            transmissionRecovery?.phase==TransmissionRecoveryState.Phase.RELOADING)

    private fun dispatchRecoveryMenuButton(event: KeyEvent): Boolean {
        if (event.keyCode==KeyEvent.KEYCODE_F7 && event.device?.keyboardType==InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            if (event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) showGameMenu()
            return true
        }
        val mask = gamepadButton(event.keyCode,event)
        if (mask!=ControllerShortcut.START && mask!=ControllerShortcut.SELECT) return false
        val down = event.action==KeyEvent.ACTION_DOWN
        if (!down && event.action!=KeyEvent.ACTION_UP || event.repeatCount>0 || (down && event.isCanceled)) return true
        if (down && recoveryMenuDeviceId==null) recoveryMenuDeviceId=event.deviceId
        if (recoveryMenuDeviceId!=event.deviceId) return true
        val update = recoveryMenuShortcut.change(mask,down,android.os.SystemClock.elapsedRealtime(),emitPulse=false)
        if (update.openMenu) {
            controllerPath.menuChord()
            showGameMenu()
        } else if (recoveryMenuShortcut.flushAtMs==null) recoveryMenuDeviceId=null
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        renderNetworkStatus()
        updateJoystickDispatch()
        if (!hasFocus) {
            hideSessionTimeNotice()
            resetController()
        }
        else {
            rumble.enabled=!sessionSaved && playbackSettings.rumble && webrtcClient!=null
            resumeAuxiliaryInput();resynchronizeUsbInput()
            ImmersiveWindow.apply(window,edgeToEdge=true)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(transitionPanel.visibility==View.VISIBLE && (keyCode==KeyEvent.KEYCODE_BACK || keyCode==KeyEvent.KEYCODE_BUTTON_B)) {
            if(!transitionPanel.exiting && event.repeatCount==0) showExitDialog()
            return true
        }
        if(::touchView.isInitialized && touchView.isEditing && (keyCode==KeyEvent.KEYCODE_BACK || keyCode==KeyEvent.KEYCODE_BUTTON_B)) {
            if(event.repeatCount==0) touchView.cancelEditing()
            return true
        }
        if ((keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_F10) &&
            transitionPanel.visibility!=View.VISIBLE && controlPanel.visibility == View.GONE && queuePanel.visibility != View.VISIBLE) {
            if (event.repeatCount == 0) {
                toggleStatistics()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && controlPanel.visibility == View.GONE) {
            if(touchView.isEditing) { touchView.cancelEditing(); return true }
            if (queuePanel.visibility == View.VISIBLE) cancelPlayback() else if(event.repeatCount == 0) showGameMenu()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart(); activityVisible=true; reportTracker?.setVisible(!sessionSaved)
        if (!sessionSaved) { webrtcClient?.resumeAudio();webrtcClient?.setAudioForeground(true) }
        usbRumble.setForeground(true);usbDirect.setForeground(true)
        rumble.enabled=!sessionSaved && playbackSettings.rumble && webrtcClient!=null
        updateJoystickDispatch()
        renderStatistics()
        val recoveryPhase = transmissionRecovery?.phase
        if (recoveryPhase == TransmissionRecoveryState.Phase.WAITING_RETRY ||
            recoveryPhase == TransmissionRecoveryState.Phase.SESSION_ENDED) presentTransmissionInterruption()
        if(activeGame==null && dashboard!=null && controlPanel.visibility==View.VISIBLE && transitionPanel.visibility!=View.VISIBLE) {
            refreshCloudPlaytime(dashboard!!)
            val signaling=currentSignaling; val library=gameLibrary
            if(signaling!=null && library!=null) refreshCloudHistory(dashboard!!,signaling,library)
        }
    }
    override fun onStop() {
        activityVisible = false
        hideSessionTimeNotice()
        dashboard?.closeServiceMenu()
        renderNetworkStatus()
        videoVisibilityEpoch++
        shortcutSwitchDialog?.dismiss()
        friendsPanel?.close();friendsPanel=null
        val heldUsb=usbDirect.currentInput().buttons
        joystickDispatch.request(false);rumble.enabled=false;rumble.stop()
        usbRumble.setForeground(false);usbDirect.setForeground(false);usbNavigation.clear();resetController()
        usbBlockedButtons=heldUsb
        microphoneJob?.cancel();webrtcClient?.setAudioForeground(false)
        reportTracker?.setVisible(false);super.onStop()
    }

    override fun onDestroy() {
        activityVisible = false
        hideSessionTimeNotice()
        remotePlayPanel?.let { panel -> remotePlayPanel = null; panel.dialog.dismiss() }
        remotePlayReturn = null
        closeGameTools()
        shortcutSwitchDialog?.dismiss()
        friendsPanel?.close();friendsPanel=null
        transmissionRecovery?.close()
        joystickDispatch.request(false)
        playbackDisplay.restore()
        reportTracker?.finish(android.os.SystemClock.elapsedRealtime(),SessionEnd.APP_CLOSED,webrtcClient?.currentInputMetrics())
        postSessionDialog?.dismiss()
        playback.cancel()
        gyro.stop();setDesktopCapture(false);auxiliaryHandler.removeCallbacksAndMessages(null)
        val activityJobs = mainScope.coroutineContext.job
        activityJobs.cancel()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(deviceListener)
        rumble.enabled=false; rumble.stop()
        usbRumble.close();usbDirect.close();usbNavigation.clear()
        com.xcloud.tclnative.ui.dashboard.AppControllerWindows.clear(this)
        dashboard?.close()
        shortcutPosters.close()
        gameMenuDialog?.dismiss()
        loginView?.close()
        transitionPanel.close()
        queuePanel.close()
        videoRecoveryDialog?.dismiss();nativeTouchDialog?.dismiss()
        // A cancelled startup may still be returning from a native peer operation. Its owner
        // releases the track and transport first, while keeping the shared EGL context alive.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            activityJobs.join()
            surfaceRenderer.clearSession()
            webrtcClient?.close()
            surfaceRenderer.release()
        }
        super.onDestroy()
    }
}
