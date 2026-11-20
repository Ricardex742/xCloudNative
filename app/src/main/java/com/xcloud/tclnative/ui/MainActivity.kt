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
import com.xcloud.tclnative.ui.dashboard.GameLoadingView
import com.xcloud.tclnative.ui.dashboard.GameMenuPanel
import com.xcloud.tclnative.ui.dashboard.GameShortcutBar
import com.xcloud.tclnative.ui.dashboard.GameShortcutEditor
import com.xcloud.tclnative.ui.dashboard.GameNetworkPanel
import com.xcloud.tclnative.ui.dashboard.NetworkIndicator
import com.xcloud.tclnative.ui.dashboard.SessionOptionsPanel
import com.xcloud.tclnative.ui.dashboard.ConsoleLoginView
import com.xcloud.tclnative.ui.dashboard.MicrosoftLoginDialog
import com.xcloud.tclnative.ui.dashboard.ConsoleDialogs
import com.xcloud.tclnative.ui.dashboard.ConsoleAction
import com.xcloud.tclnative.catalog.CatalogService
import com.xcloud.tclnative.catalog.GalleryProduct
import com.xcloud.tclnative.catalog.OfficialGalleryKind
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.GameOwnershipService
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
    private val controllerPath=ControllerPathTrace()
    private var gamepadState: GamepadState
        get()=controllers.physical(0)
        set(value) { controllers.update(0,value) }
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
    private var currentTouchMode=TouchMode.OFF
    private val hudStore by lazy { com.xcloud.tclnative.touch.GameHudStore(this) }
    private var preferredControllerId: Int?=null
    private val televisionInput by lazy { InputDeviceProfile.read(this).television }
    private val deviceListener=object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) { removeController(deviceId) }
        override fun onInputDeviceRemoved(deviceId: Int) {
            if(preferredControllerId==deviceId) preferredControllerId=null
            removeController(deviceId)
        }
    }
    private var qrPollingJob: Job? = null
    private lateinit var queuePanel: GameLoadingView
    private var pendingLoadingGame: GameTitle? = null
    private var queueTicker: Job? = null
    private val controllerShortcut = ControllerShortcut()
    private var shortcutFlushJob: Job? = null
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
    private var browserLogin: MicrosoftLoginDialog? = null
    private var accountJob: Job? = null
    private var currentSignaling: XCloudSignalingClient? = null
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
                networkIndicator.setCutout(cutout)
                networkPanel?.dialog?.setNetworkCutout(cutout)
                com.xcloud.tclnative.ui.dashboard.StatisticsOverlayStyle.apply(statsView,statisticsSettings,displayCutout)
                renderStatistics()
            }
        }
        refreshStatisticsSettings()
        touchView=GameTouchView(this,
            onGamepad={ state -> controllers.virtual(state); if(currentTouchMode==TouchMode.VIRTUAL && transmissionRecovery?.phase==TransmissionRecoveryState.Phase.ACTIVE) publishGamepad(0) },
            onPointers={ events -> if(transmissionRecovery?.phase==TransmissionRecoveryState.Phase.ACTIVE) webrtcClient?.sendPointers(events) },
            coordinates=surfaceRenderer::touchCoordinates,
            onMenu={ showTouchGameMenu() })
        surfaceRenderer.onDisplayDimensionsChanged = { webrtcClient?.updateDisplayDimensions(it) }
        touchView.onHudSaved={ settings ->
            val game=activeGame
            if(game==null) false else try { hudStore.save(game.titleId,settings); true }
            catch(error: java.io.IOException) { Toast.makeText(this,error.message,Toast.LENGTH_LONG).show(); false }
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

    private fun renderLoginMenu() {
        friendsPanel?.close();friendsPanel=null
        playtimeJob?.cancel();playtimeJob=null;playtimeCheckedAt=null
        ownershipJob?.cancel();ownershipJob=null
        browserLogin?.close();browserLogin=null
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
            signIn = { startQrCodeLoginFlow() },
            back = { if (loginView?.isQr == true) renderLoginMenu() else showExitDialog() },
            openApproval = { uri -> openAccountPage(uri) },browserSignIn = { startBrowserLoginFlow() })
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
    private fun accountFailure(error: Throwable, retry: () -> Unit) {
        if(error is com.xcloud.tclnative.auth.AuthenticationRequiredException) {
            authenticator.clearSession(); renderLoginMenu()
            loginView?.showError(UiText.text("Sua sessão precisa ser renovada. Entre novamente na sua conta."))
        } else if(loginView!=null) loginView?.showError(UiText.text("Não foi possível conectar: {0}",UiText.nested(error.message)))
        else ConsoleDialogs.message(this,UiText.text("Conexão não concluída"),UiText.text("Não foi possível restaurar a conexão agora. Seu login salvo foi preservado."),
            listOf(ConsoleAction(UiText.text("Tentar novamente"),retry),ConsoleAction(UiText.text("Entrar novamente")) { renderLoginMenu() }))
    }

    private fun startQrCodeLoginFlow() {
        browserLogin?.close();browserLogin=null
        qrPollingJob?.cancel()
        val view = loginView ?: return
        view.beginQr()
        qrPollingJob = mainScope.launch {
            val qrRes = authenticator.requestDeviceCodeQr()
            qrRes.onSuccess { info ->
                if (loginView !== view) return@onSuccess
                view.showQr(info)
                authenticator.pollForDeviceCodeLogin(info).onSuccess {
                    if (loginView === view) connectToXboxCloud()
                }.onFailure { error ->
                    if (loginView === view) view.showError(UiText.text("Não foi possível concluir o login. {0}",UiText.nested(error.message.orEmpty())))
                }
            }.onFailure { error ->
                if (loginView === view) view.showError(UiText.text("Não foi possível gerar o QR Code. {0}",UiText.nested(error.message.orEmpty())))
            }
        }
    }

    private fun startBrowserLoginFlow() {
        qrPollingJob?.cancel();browserLogin?.close();browserLogin=null
        val view=loginView ?: return
        val authorization=authenticator.beginBrowserLogin()
        view.showConnecting(UiText.text("Abrindo o login da Microsoft…"))
        try {
            browserLogin=MicrosoftLoginDialog(this,authorization,authorized={ code ->
                browserLogin=null
                if(loginView===view) {
                    view.showConnecting(UiText.text("Conectando sua conta ao Xbox…"))
                    qrPollingJob=mainScope.launch {
                        authenticator.completeBrowserLogin(authorization,code).onSuccess {
                            if(loginView===view) connectToXboxCloud()
                        }.onFailure {
                            if(loginView===view) view.showError(UiText.text("Não foi possível concluir o login. Tente novamente ou use o QR Code."))
                        }
                    }
                }
            },cancelled={ browserLogin=null;if(loginView===view) view.landing() },failed={ message ->
                browserLogin=null;if(loginView===view) view.showError(message)
            })
            browserLogin?.show()
        } catch(_: Exception) {
            browserLogin?.close();browserLogin=null
            view.showError(UiText.text("Não foi possível abrir o login da Microsoft. Tente novamente ou use o QR Code."))
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
                    if (loginView === view) accountFailure(error) { connectToXboxCloud() }
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
        })
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
            if(launchPendingShortcut()) {
                loginView?.close(); loginView=null
                return@launch // No catalog/grid or background galleries while the shortcut enters its game.
            }
            val view = GameDashboardView(this@MainActivity, library,
                onPlay = { game -> chooseLaunchMode(signaling, game) },
                onSearch = { showCatalogSearch() },
                onOptions = { showSettings(session) },
                onExit = { showExitDialog() },
                onFavoritesChanged = { syncHomeShortcuts() },
                onPlayProduct = { product -> playGalleryProduct(signaling,product) },
                onOwnershipNeeded = { requestOwnership() },onGameShortcut={ showGameShortcut(it) },
                onFriends={ showFriends { dashboard?.restoreFocus() } },
                onThemeSettings={ page -> showSettings(session,page) { dashboard?.restoreFocus() } })
            dashboard = view
            controlPanel.removeAllViews()
            controlPanel.setPadding(0, 0, 0, 0)
            controlPanel.setBackgroundColor(Color.TRANSPARENT)
            controlPanel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            controlPanel.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT))
            controlPanel.visibility = View.VISIBLE
            hudTextView.visibility = View.GONE
            view.setGames(allGames)
            revealDashboard(view)
            refreshCloudPlaytime(view)
            refreshCloudHistory(view,signaling,library)
            currentGames = allGames
            currentSignaling = signaling
            syncHomeShortcuts()
            launchPendingShortcut()
            val catalog = createCatalogService(catalogCache,catalogWork::await)
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
                    catalog.hydrateOfficialGallery(gallery,known) { hydrated -> withContext(Dispatchers.Main) {
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
                            withContext(Dispatchers.IO) { library.bindXboxAccount(profile.xuid) }
                            view.refresh(); syncHomeShortcuts()
                        }
                        view.setProfile(profile)
                    }
                }
            }
        }
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
                GameOwnershipService(authorization={ authenticator.loadStoreAuthorization().getOrThrow() },awaitCatalog=catalogWork::await)
                    .fetch(ids) { batch -> withContext(Dispatchers.Main) {
                        if(dashboard===view) view.updateOwnership(batch)
                    } }
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
        val game=activeGame ?: dashboard?.selectedGame
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
            sessionPanel=com.xcloud.tclnative.ui.dashboard.SessionOptionsPanel(this,SessionOptionsStore(this,session.libraryAccountId),session.regions,game?.titleId,game?.name,::back),
            back=onBack,allowAccountChanges=activeGame==null,
            gameControls=if(activeGame==null) null else ({ showGameHudSettings { showSettings(session,SettingsPage.CONTROLS,onBack) } }),
            recovery=if(activeGame==null) null else ({ showTransmissionRecovery { showSettings(session,SettingsPage.DIAGNOSTICS,onBack) } }),
            currentDiagnostics=if(activeGame==null) null else ({ showLiveDiagnostics { showSettings(session,SettingsPage.DIAGNOSTICS,onBack) } }),
            microphone=if(transmissionRecovery?.phase==TransmissionRecoveryState.Phase.ACTIVE) ::toggleMicrophone else null,
            statisticsChanged=::refreshStatisticsSettings,
            microphoneLabel={ webrtcClient?.microphoneLabel() ?: UiText.text("Ativar microfone") })
    }
    private fun showControllerProfiles(back: () -> Unit) {
        com.xcloud.tclnative.ui.dashboard.ControllerProfilePanel(this,controllerProfiles,
            usbSources={ listOfNotNull(usbDirect.source) },hiddenAndroid=usbDirect::ownsAndroid) { if(webrtcClient!=null) (0..3).filter { controllers.sourceFor(it)!=null }.forEach(::publishGamepad) }.show(back)
    }
    private fun showSessionOptionsMenu(session: XCloudSession,onBack: () -> Unit={ if(webrtcClient!=null) showGameMenu() else showSettings(session,SettingsPage.NETWORK) }) {
        val game=activeGame ?: dashboard?.selectedGame
        com.xcloud.tclnative.ui.dashboard.SessionOptionsPanel(this,SessionOptionsStore(this,session.libraryAccountId),
            session.regions,game?.titleId,game?.name,onBack).show()
    }
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
    private suspend fun finishSessionReport(tracker: SessionReportTracker) {
        val report=tracker.takeReport()
        if(report==null) { if(reportTracker===tracker) reportTracker=null; return }
        lastSessionReport=report
        try { withContext(Dispatchers.IO) { reportStore.save(report) } }
        catch(error: java.io.IOException) { if(!isFinishing && !isDestroyed) Toast.makeText(this,error.message,Toast.LENGTH_LONG).show() }
        val current=reportTracker===tracker
        if(current) reportTracker=null
        if(current && activityVisible && !isFinishing && !isDestroyed && activeGame==null && settingsStore.read().sessionReport &&
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
            val updated=settingsStore.read().copy(sessionReport=false)
            settingsStore.save(updated); playbackSettings=updated
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
        playbackSettings=settings
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
        webrtcClient?.setOutputGain(settings.audioGain)
    }
    protected open fun prepareSessionRenderer(settings: PlaybackSettings): org.webrtc.EglBase {
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
        val position=if(InputDeviceProfile.read(this).television) null else GameShortcutBar.Position()
        showGameMenu(position)
    }

    private fun showGameMenu(position: GameShortcutBar.Position?) {
        if (transitionPanel.visibility==View.VISIBLE || gameMenuDialog?.isShowing == true ||
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
            microphone=if(active && webrtcClient?.canUseMicrophone()==true) ::toggleMicrophone else null,
            microphoneLabel={ UiText.text(webrtcClient?.microphoneLabel() ?: "Ativar microfone") })
        val shortcuts = GameMenuPanel.shortcuts(
            image=if(active) ({ liveSettings.showUpscaling(back) }) else null,
            audio=if(active) ({ liveSettings.showAudio(back) }) else null,
            xbox=if(active) ::pressXboxButton else null,
            statistics=::toggleStatistics,
            statisticsOptions={ com.xcloud.tclnative.ui.dashboard.StatisticsSettingsPanel(this,statisticsStore,::refreshStatisticsSettings,back).show() },
            recovery=if(reloadTransmission!=null) ({ showTransmissionRecovery(back) }) else null,
            hudToggle=if(touch) ({
                if(touchView.controlsVisible) hideGameHud() else enableGameHud()
            }) else null,
            hudVisible=touchView.controlsVisible,
            nativeTouch=if(active && currentTouchMode!=TouchMode.NATIVE && webrtcClient?.canUseNativeTouch()==true) ({ enableNativeTouch() }) else null,
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
            controllerProfiles=if(active) ({ showControllerProfiles(back) }) else null,
            mouse=if(!active || sessionDesktopMode==DesktopInputMode.OFF) null else ({
                auxiliaryHandler.post { setDesktopCapture(!desktopCaptured) }
            }))
        touchView.setMenuCovered(position!=null)
        val dialog = if(position==null) GameMenuPanel.show(this,shortcuts,::cancelPlayback) else {
            GameShortcutBar(this,shortcuts,position,displayCutout,::cancelPlayback,back,shortcutSettings.slots()) {
                GameShortcutEditor(this,shortcutSettings,back).also { shortcutEditor=it }.show()
            }.also {
                toolbar=it
                it.show()
            }
        }
        gameMenuDialog=dialog
        dialog.setOnDismissListener {
            if(gameMenuDialog===dialog) {
                gameMenuDialog=null
                touchView.setMenuCovered(false)
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
        shortcutEditor?.close(); shortcutEditor = null
        networkPanel?.dialog?.dismiss(); networkPanel = null
    }
    private fun videoDiagnostics()=ImmersiveWindow.diagnostics(window,rootLayout)+"\n"+surfaceRenderer.diagnostics()
    private fun showLiveDiagnostics(onBack: () -> Unit) {
        showCopyableErrorDialog(UiText.text("Áudio e vídeo"),UiText.text("{0} {1} | Android {2} | {3}\n{4}\n{5}\n{6}\n{7}",UiText.nested(Build.MANUFACTURER),UiText.nested(Build.MODEL),UiText.nested(Build.VERSION.RELEASE),UiText.nested(Build.HARDWARE),UiText.nested(videoDiagnostics()),UiText.nested(currentSignaling?.profileDiagnostics() ?: ""),UiText.nested(webrtcClient?.audioDiagnostics() ?: ""),UiText.nested(streamHistory.report()))+"\n"+(webrtcClient?.inputDiagnostics() ?: ""),onBack=onBack)
    }
    private fun pressXboxButton() {
        val client=webrtcClient ?: return
        val generation=playbackGeneration
        mainScope.launch {
            // Let the native shortcut panel release focus before the explicit button press.
            delay(80)
            if(client!==webrtcClient || generation!=playbackGeneration || !gameplayInputEnabled()) return@launch
            val state=combinedInput(0)
            client.updateGamepad(state.copy(buttons=state.buttons or 2))
            try { delay(120) } finally {
                if(client===webrtcClient && generation==playbackGeneration) client.updateGamepad(combinedInput(0),
                    auxiliary=currentTouchMode==TouchMode.NATIVE && controllers.sourceFor(0)==null)
            }
        }
    }
    private fun enableGameHud(): Boolean {
        if(transmissionRecovery?.phase!=TransmissionRecoveryState.Phase.ACTIVE) return false
        val game=activeGame ?: return false
        if(currentTouchMode==TouchMode.NATIVE && webrtcClient?.setNativeTouchEnabled(false)!=true) {
            Toast.makeText(this,UiText.text("Aguarde a conexão dos controles para ativar o HUD."),Toast.LENGTH_LONG).show()
            return false
        }
        if(currentTouchMode!=TouchMode.VIRTUAL) {
            resetController(); currentTouchMode=TouchMode.VIRTUAL
            touchView.configure(TouchMode.VIRTUAL,game,hudStore.read(game.titleId))
            rumble.enabled=playbackSettings.rumble
        }
        touchView.revealControls()
        return true
    }
    private fun hideGameHud() {
        val game=activeGame ?: return
        resetController();currentTouchMode=TouchMode.OFF
        touchView.configure(TouchMode.OFF,game);rumble.enabled=playbackSettings.rumble
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
        // Release only the app's virtual pad; a real controller keeps its source and slot.
        if(controllers.sourceFor(0)==null) client.removeGamepad(0)
        currentTouchMode=TouchMode.NATIVE
        touchView.configure(TouchMode.NATIVE,game,hudStore.read(game.titleId))
        rumble.enabled=playbackSettings.rumble
        resumeAuxiliaryInput();return true
    }
    private fun showGameHudSettings(onBack: () -> Unit=::showGameMenu) {
        val game=activeGame ?: return
        com.xcloud.tclnative.ui.dashboard.GameHudPanel(this,game,hudStore,
            changed={ touchView.applyHud(it) },edit={ if(enableGameHud()) touchView.beginEditing() },
            hide=::hideGameHud,
            page={ if(enableGameHud()) touchView.showPage(it) },
            activate=if(currentTouchMode==TouchMode.VIRTUAL) null else ({ if(enableGameHud()) showGameHudSettings(onBack) }),
            nativeTouch=if(currentTouchMode==TouchMode.NATIVE || webrtcClient?.canUseNativeTouch()!=true) null else ({ enableNativeTouch() }),back=onBack).show()
    }
    private fun chooseLaunchMode(signaling: XCloudSignalingClient,game: GameTitle) {
        launchCloudGame(signaling,game)
    }

    private fun showFriends(onBack: () -> Unit) {
        val account=currentSession ?: return
        friendsPanel?.close()
        val valid={ currentSession===account && activityVisible && !isFinishing && !isDestroyed }
        friendsPanel=com.xcloud.tclnative.ui.dashboard.FriendsPanel(this,mainScope,
            load={
                val authorization=authenticator.loadXboxSocialAuthorization().getOrThrow()
                check(currentSession===account) { "SOCIAL_ACCOUNT_CHANGED" }
                com.xcloud.tclnative.catalog.XboxFriendsService().load(authorization,UiText.locale.toLanguageTag())
            },games={ currentGames },valid=valid,play={ game ->
                fun launch() { if(valid()) currentSignaling?.let { chooseLaunchMode(it,game) } }
                if(activeGame!=null) ConsoleDialogs.message(this,UiText.text("Abrir jogo na nuvem"),
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

    private fun restoreDashboard() {
        joystickDispatch.request(false)
        playbackDisplay.restore()
        catalogWork.resume()
        if(dashboard==null) {
            currentSession?.let { renderGameDashboard(it,currentGames) }
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
    }
    private fun revealDashboard(view: GameDashboardView) {
        view.doOnPreDraw {
            if(dashboard===view && activeGame==null && controlPanel.visibility==View.VISIBLE) {
                startupArtJob?.cancel(); startupArtJob=null
                transitionPanel.dismiss(); view.restoreFocus(refreshCatalog=false); showPendingReport()
            }
        }
    }
    private fun showCatalogReturn(game: GameTitle?,wasPlaying: Boolean) {
        startupArtJob?.cancel(); startupArtJob=null
        gameMenuDialog?.dismiss()
        closeGameTools()
        networkQuality.reset()
        networkIndicator.visibility = View.GONE
        val fresh=currentGames.firstOrNull { it.titleId==game?.titleId } ?: game
        transitionPanel.showExit(fresh,wasPlaying)
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
                (!televisionInput || AndroidGamepadPolicy.confirmsGamepad(event) ||
                    event.device?.let(AndroidGamepadPolicy::canReserveTvSlot)==true)) preferredControllerId=event.deviceId
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) view.back()
                return true
            }
            if (view.handleKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launchCloudGame(signaling: XCloudSignalingClient, game: GameTitle,
                                touchChoice: TouchMode? = null, fromShortcut: Boolean = false) {
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
        val gameName = game.name
        activeGame = game
        microphoneJob?.cancel();pendingMicrophoneGeneration=null
        val settings = playbackSettings.forSession()
        sessionSettings=settings
        val sessionOptions=currentSession?.let { SessionOptionsStore(this,it.libraryAccountId).read(titleId) } ?: SessionOptions()
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
        val choice=touchChoice ?: settings.touchMode
        val inputDevice=InputDeviceProfile.read(this)
        val touchMode=if(sessionDesktopMode!=DesktopInputMode.OFF) TouchMode.OFF else DeviceInputPolicy.resolve(inputDevice,choice,game)
        currentTouchMode=TouchMode.OFF
        touchView.configure(TouchMode.OFF)
        statistics.beginSession();transmissionReady=false;latestMetrics=null;latestInput=InputMetrics()
        recoverImage=null;reloadTransmission=null
        refreshStatisticsSettings()
        configureVideoOutput(settings, playing = true)
        val origin = if (fromShortcut) PlaybackOrigin.SHORTCUT else PlaybackOrigin.CATALOG
        playback.start(origin, prepare = {
            controlPanel.visibility = View.GONE
            statsView.visibility = View.GONE
            statsView.text = UiText.text("Estatísticas: aguardando amostras WebRTC...")
            hudTextView.visibility = View.GONE
            beginLoading(game)
            hudTextView.text = "PLAY: Solicitando sessão para [$gameName]. Voltar cancela."
            hudTextView.setTextColor(Color.CYAN)
        }) { attempt ->
            val generation = attempt.generation
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
            var startupInput = "LAUNCH/${if (fromShortcut) "SHORTCUT" else "CATALOG"}; locale=${sessionOptions.locale}"
            controllerPath.clear()
            signaling.resetStartupDiagnostics()
            tracker.inputDetails={ "Upscaling=${settings.fsr}; output=${settings.fsrOutput}\n"+controllerPath.diagnostics()+"\n"+signaling.startupDiagnostics() }
            tracker.mediaDetails=::videoDiagnostics
            try {
                resetController()
                controllers.beginSession(settings.localCoOp,touchMode!=TouchMode.OFF || sessionDesktopMode!=DesktopInputMode.OFF)
                usbDirect.source?.let { controllers.slot(it) }
                InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice).filter { !usbDirect.ownsAndroid(it.id) }.filter {
                    AndroidGamepadPolicy.supportsGamepad(it.sources) && (!televisionInput ||
                        it.id==preferredControllerId || AndroidGamepadPolicy.canReserveTvSlot(it))
                }.sortedWith(compareBy<InputDevice> { it.id!=preferredControllerId }.thenBy { it.id }).forEach { controllers.slot(it.id) }
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
                surfaceRenderer.onFirstFrameSubmitted = { submittedAt ->
                    if (generation == playbackGeneration && recoveryState.current(0) && !firstFrame.isCompleted) {
                        timeline.mark(0, PlaybackStage.FIRST_EGL_SUBMISSION, submittedAt)
                        tracker.begin(android.os.SystemClock.elapsedRealtime(),NetworkSnapshot.read(this@MainActivity))
                        runCatching { startupReportStore.clear() }
                        firstFrame.complete(Unit)
                        finishLoading(forPlayback = true)
                        updateJoystickDispatch()
                        currentTouchMode=touchMode
                        touchView.configure(touchMode,game,hudStore.read(titleId))
                        if(touchMode==TouchMode.NATIVE) webrtcClient?.setNativeTouchEnabled(true)
                        resumeAuxiliaryInput()
                        hudTextView.visibility = View.GONE
                        gameLibrary?.recordPlayed(titleId)
                        hudTextView.text = UiText.text("Vídeo nativo ativo [{0}]",UiText.nested(gameName))
                        hudTextView.setTextColor(Color.GREEN)
                        transmissionReady=true;renderStatistics();resynchronizeUsbInput()
                    }
                }
                surfaceRenderer.onRenderError = { error -> transportFailure.complete(RenderFailureException(error)) }
                // Capability discovery and native factory initialization must not block the menu/UI.
                suspend fun buildRtc(): XCloudWebRtcClient {
                    val token=recoveryState.epoch
                    val failure=transportFailure
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
                    }, lowLatencyVideo = settings.videoMode.lowLatency, streamResolution = settings.resolution,
                    nativeTouch = touchMode==TouchMode.NATIVE,
                    onRumble = { command -> if(current() && recoveryState.phase==TransmissionRecoveryState.Phase.ACTIVE) rumble.play(command) },compatibility=compatible,
                    videoCodec=settings.videoCodec,maxBitrateKbps=maxBitrateKbps,
                    ipPreference=settings.ipPreference,nativeKeyboardMouse=sessionDesktopMode==DesktopInputMode.NATIVE,
                    microphoneAllowed=settings.microphone,outputGain=playbackSettings.audioGain,audioMode=settings.audioMode,
                    onServerDisconnect={ notice -> runOnUiThread { if(current()) failure.complete(XboxServerDisconnectException(notice)) } }).also { allocatedRtc=it } }
                }
                var rtc=buildRtc()
                webrtcClient = rtc
                val appVersion=packageManager.getPackageInfo(packageName,0).versionName ?: "n/d"
                tracker.inputDetails = {
                    "$startupInput\n" + (dashboard?.cloudPlaytimeDiagnostics()?.let { "$it\n" } ?: "") +
                        UiText.text("xCLOUD {0} • Entrada inicial: {1} • {2}",UiText.nested(appVersion),
                            UiText.nested(touchMode.label),UiText.nested(rtc.inputDiagnostics())) +
                        "\n${controllerPath.diagnostics()}\n${signaling.startupDiagnostics()}\n" +
                        "USB direto: ${usbDirect.snapshot().status.label} • ${usbDirect.snapshot().valid}/${usbDirect.snapshot().packets} • OUT ${usbDirect.snapshot().writes}\n" +
                        inputDevice.diagnostics() + when (touchMode) {
                            TouchMode.NATIVE -> "\n${touchView.nativeDiagnostics()}"
                            TouchMode.VIRTUAL -> "\n${touchView.hudDiagnostics()}"
                            else -> ""
                        } + "\n${joystickDispatch.diagnostics()}\n${rumble.diagnostics()}\n${gyro.status}\n" +
                        signaling.keepAliveDiagnostics() +
                        "\nMKB/${sessionDesktopMode.name}; capture=$desktopCaptured; " +
                        "androidCapture=${Build.VERSION.SDK_INT >= 26 && window.decorView.hasPointerCapture()}"
                }
                tracker.mediaDetails = {
                    listOf(rtc.audioDiagnostics(), rtc.codecDiagnostics(), rtc.latencyDiagnostics(), rtc.iceDiagnostics(),
                        signaling.profileDiagnostics(), playbackDisplay.diagnostics(window.decorView.display),
                        videoDiagnostics(), recoveryState.diagnostics()).joinToString("\n")
                }
                surfaceRenderer.displayDimensions()?.let(rtc::updateDisplayDimensions)
                if(touchMode!=TouchMode.NATIVE) (0..3).filter { controllers.sourceFor(it)!=null }.forEach { publishGamepad(it) }
                signaling.startAndConnectWebRtcSession(titleId, rtc, resolution = settings.resolution, nativeTouch=touchMode==TouchMode.NATIVE,options=sessionOptions,microphone=settings.microphone, onStage={ timeline.mark(0, it) }, onQueueStatus = { status -> runOnUiThread {
                    if (generation == playbackGeneration) showQueue(status)
                } }) { progress -> runOnUiThread {
                    if (generation == playbackGeneration && !firstFrame.isCompleted) {
                        queuePanel.updateProgress(progress)
                        hudTextView.text = progress
                        hudTextView.setTextColor(Color.YELLOW)
                    }
                } }.getOrThrow()
                if (touchMode == TouchMode.NATIVE) rtc.setNativeTouchEnabled(true)
                if (!firstFrame.isCompleted) queuePanel.updateProgress("VIDEO:")
                attempt.awaitFirstFrame(transportFailure)
                val recovery = VideoRecoveryPolicy(compatible)
                var visibilityEpoch = videoVisibilityEpoch
                val nativeReadiness=com.xcloud.tclnative.webrtc.NativeTouchReadiness()
                var lastToken = -1L
                var recovering = false
                var pendingRecoveryTouch: TouchMode?=null
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
                            tracker.recordInterruption(error,rtc.inputDiagnostics()+"\n"+rtc.codecDiagnostics())
                            recoveryState.interrupted(recoveryState.epoch,error);transmissionReady=false;renderStatistics()
                            pendingRecoveryTouch=currentTouchMode;currentTouchMode=TouchMode.OFF;touchView.configure(TouchMode.OFF)
                            showTransmissionInterrupted()
                        }
                    } else Toast.makeText(this@MainActivity,UiText.text("Use Recarregar transmissão para tentar reconectar."),Toast.LENGTH_SHORT).show()
                }
                reloadTransmission=reload@ { bitrate ->
                    val token=recoveryState.begin() ?: return@reload
                    maxBitrateKbps=bitrate ?: maxBitrateKbps
                    timeline.beginReconnect(token)
                    val previousInput=rtc.inputDiagnostics()+"\n"+rtc.codecDiagnostics()
                    val savedTouch=pendingRecoveryTouch ?: currentTouchMode
                    pendingRecoveryTouch=savedTouch
                    transmissionReady=false;latestMetrics=null;renderStatistics()
                    resetController();currentTouchMode=TouchMode.OFF;touchView.configure(TouchMode.OFF)
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
                                rumble.enabled=settings.rumble
                                (0..3).filter { controllers.sourceFor(it)!=null }.forEach { publishGamepad(it) }
                                transmissionReady=true;updateJoystickDispatch();resumeAuxiliaryInput();renderStatistics();resynchronizeUsbInput()
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
                                showTransmissionInterrupted()
                            }
                        }
                    }
                }
                kotlinx.coroutines.coroutineScope {
                    launch { signaling.maintainSession() }
                    while (isActive) {
                        if(recoveryState.phase!=TransmissionRecoveryState.Phase.ACTIVE) { delay(250);continue }
                        val token=recoveryState.epoch
                        val failure=withTimeoutOrNull(1000) { transportFailure.await() }
                        if(recoveryState.phase!=TransmissionRecoveryState.Phase.ACTIVE || !recoveryState.current(token)) continue
                        if(failure!=null) {
                            if(recoveryState.interrupted(token,failure)) {
                                tracker.recordInterruption(failure,rtc.inputDiagnostics()+"\n"+rtc.codecDiagnostics())
                                transmissionReady=false;renderStatistics();resetController()
                                pendingRecoveryTouch=currentTouchMode;currentTouchMode=TouchMode.OFF;touchView.configure(TouchMode.OFF)
                                hudTextView.visibility=View.VISIBLE;hudTextView.text=UiText.text("A transmissão foi interrompida. Você pode tentar reconectar à sessão atual.")
                                showTransmissionInterrupted()
                            }
                            continue
                        }
                        if (!activityVisible) continue
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
                if (generation == playbackGeneration && (!firstFrame.isCompleted || !settingsStore.read().sessionReport)) {
                    val details = (error.message ?: UiText.text("Falha na sessão"))+
                        if(!firstFrame.isCompleted) "\nUpscaling=${settings.fsr}; output=${settings.fsrOutput}\n${signaling.startupDiagnostics()}\n${controllerPath.diagnostics()}" else ""
                    hudTextView.text = details
                    hudTextView.setTextColor(Color.RED)
                    showCopyableErrorDialog(gameName, details)
                }
            } finally {
                // Always release the local decoder and server session together.
                withContext(NonCancellable) {
                    recoveryState.close()
                    transmissionReloadJob?.cancelAndJoin();transmissionReloadJob=null
                    if(generation==playbackGeneration) {
                        recoverImage=null;reloadTransmission=null;transmissionReady=false;transmissionRecovery=null
                    }
                    val rtc=allocatedRtc
                    if(!firstFrame.isCompleted) runCatching {
                        val version=packageManager.getPackageInfo(packageName,0).versionName ?: "n/d"
                        val snapshot = timeline.snapshot()
                        startupReportStore.save(gameName,"xCLOUD $version\nSTARTUP/NO_VIDEO; input=${touchMode.name}; failure=${startupFailure ?: "NONE"}\n"+
                            "$startupInput\nREQUESTED_PROFILE/${settings.resolution.name}; os=${settings.resolution.osName}; codec=${settings.videoCodec.name}; mode=${settings.videoMode.name}\n"+
                            snapshot.text()+"\n"+signaling.startupDiagnostics()+"\n"+(rtc?.inputDiagnostics() ?: ""),snapshot)
                    }
                    tracker.finish(android.os.SystemClock.elapsedRealtime(),if(failed) SessionEnd.FAILURE else SessionEnd.USER,rtc?.currentInputMetrics())
                    microphoneJob?.cancelAndJoin();microphoneJob=null;pendingMicrophoneGeneration=null
                    rtc?.setMicrophoneMuted(true)
                    gyro.stop();setDesktopCapture(false)
                    if(generation==playbackGeneration) { sessionDesktopMode=DesktopInputMode.OFF;sessionSettings=null }
                    if (generation == playbackGeneration) {
                        showCatalogReturn(game,pendingLoadingGame==null)
                        finishLoading()
                        statsView.visibility = View.GONE
                    }
                    allowTransitionFrame()
                    videoRecoveryDialog?.dismiss();nativeTouchDialog?.dismiss()
                    resetController()
                    currentTouchMode=TouchMode.OFF
                    touchView.configure(TouchMode.OFF)
                    attempt.releaseMedia(surfaceRenderer::clearSession, { rtc?.close() }) {
                        if (webrtcClient === rtc) webrtcClient = null
                        signaling.stop()
                    }
                    if (generation == playbackGeneration) { activeGame=null; restoreDashboard() }
                    finishSessionReport(tracker)
                }
            }
        }
    }

    private fun beginLoading(game: GameTitle) {
        val latest=currentGames.firstOrNull { it.titleId==game.titleId }?.withMetadataFallback(game) ?: game
        pendingLoadingGame = latest
        queuePanel.begin(latest)
        loadingArtJob?.cancel()
        if(latest.posterUrl==null && latest.tileUrl==null) loadingArtJob=mainScope.launch {
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
                        val answer=currentSignaling?.renegotiateChat(offer) ?: throw java.io.IOException("CHAT: sessão encerrada")
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
        if(transitionPanel.exiting) return
        showCatalogReturn(activeGame ?: pendingLoadingGame,pendingLoadingGame==null)
        reportTracker?.finish(android.os.SystemClock.elapsedRealtime(),SessionEnd.USER,webrtcClient?.currentInputMetrics())
        val (generation, closing) = playback.cancel()
        activeGame = null
        shortcutSwitchDialog?.dismiss()
        gameMenuDialog?.dismiss()
        videoRecoveryDialog?.dismiss();nativeTouchDialog?.dismiss()
        resetController()
        currentTouchMode=TouchMode.OFF
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

    private fun gameplayInputEnabled() = controlPanel.visibility == View.GONE &&
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
        return if(slot==0) gyro.apply(if(sessionDesktopMode==DesktopInputMode.EMULATED) ControllerRouter.merge(base,desktopGamepad.state()) else base) else base
    }
    private fun publishGamepad(slot: Int) {
        val auxiliary=slot==0 && currentTouchMode==TouchMode.NATIVE && sessionSettings?.gyroNativeTouch==true && controllers.sourceFor(0)==null
        webrtcClient?.updateGamepad(combinedInput(slot),slot,auxiliary)
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
        if(!gameplayInputEnabled()) return
        gyro.start(settings.gyroMode,settings.gyroSensitivity,currentTouchMode==TouchMode.NATIVE,settings.gyroNativeTouch)
        if(sessionDesktopMode!=DesktopInputMode.OFF) setDesktopCapture(true)
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
    private fun controllerSlot(deviceId: Int,source: Int,confirmedGamepad: Boolean): Int? {
        if(!televisionInput) return if(AndroidGamepadPolicy.supportsGamepad(source)) controllers.slot(deviceId) else 0
        val android=ControllerSource.Android(deviceId)
        controllers.existingSlot(android)?.let { return it }
        val hasPrimary=controllers.sourceFor(0)!=null
        if(hasPrimary && !controllers.coOp) return null
        if(deviceId>=0 && (confirmedGamepad || InputDevice.getDevice(deviceId)?.let(AndroidGamepadPolicy::canReserveTvSlot)==true)) {
            return controllers.slot(android)
        }
        return 0.takeUnless { hasPrimary }
    }
    private fun removeController(deviceId: Int) {
        val slot=controllers.remove(deviceId) ?: return
        if(slot==0) { shortcutFlushJob?.cancel(); controllerShortcut.reset() }
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
        if(slot!=0) { controllers.update(slot,filtered);publishGamepad(slot);return }
        val held=filtered.buttons and ControllerShortcut.BOTH
        val old=usbShortcutHeld;usbShortcutHeld=held
        val forwarded=controllers.physical(0).buttons and ControllerShortcut.BOTH
        controllers.update(0,filtered.copy(buttons=(filtered.buttons and ControllerShortcut.BOTH.inv()) or forwarded))
        publishGamepad(0)
        for(mask in intArrayOf(ControllerShortcut.START,ControllerShortcut.SELECT))
            if((held xor old) and mask!=0) handleControllerShortcut(mask,held and mask!=0)
    }
    private fun removeUsbController(source: ControllerSource.Usb) {
        val slot=controllers.remove(source) ?: return
        usbNavigation.clear();usbBlockedButtons=0;usbShortcutHeld=0
        if(slot==0) { shortcutFlushJob?.cancel();controllerShortcut.reset() }
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
        statsView.visibility=if(statistics.mode!=0 && transmissionReady) View.VISIBLE else View.GONE
        if(statsView.visibility!=View.VISIBLE) return
        val metrics=latestMetrics ?: return
        val settings=sessionSettings ?: playbackSettings
        val showPlaytime=statistics.mode==2 || StatisticField.PLAYTIME in statisticsSettings.fields
        if(showPlaytime) dashboard?.let { refreshCloudPlaytime(it,duringPlayback=true) }
        val playtime=dashboard?.cloudPlaytimeSnapshot() ?: CloudPlaytime()
        com.xcloud.tclnative.ui.dashboard.StatisticsOverlayStyle.constrain(statsView,rootLayout.width,rootLayout.height)
        statsView.text=surfaceRenderer.frameGenerationSummary()?.let { "$it\n" }.orEmpty()+if(statistics.mode==2) playtime.label+"\n"+metrics.overlay(latestInput,"",settings.resolution,settings.videoMode==VideoMode.COMPATIBILITY,webrtcClient?.maxBitrateKbps ?: 0)+
            surfaceRenderer.diagnostics()+"\n"+(webrtcClient?.latencyDiagnostics() ?: "")
            else CustomStatistics.format(metrics,latestInput,statisticsSettings,playtime)
    }
    private fun renderNetworkStatus() {
        if (!::networkIndicator.isInitialized) return
        val live = transmissionReady && activityVisible
        if (!live) networkQuality.reset()
        networkPanel?.update(latestMetrics.takeIf { live }, networkQuality.quality)
        networkIndicator.showQuality(networkQuality.quality)
        networkIndicator.visibility = if (live && networkWarningsEnabled && hasWindowFocus()) View.VISIBLE else View.GONE
    }
    private fun showTransmissionRecovery() = showTransmissionRecovery(::showGameMenu)
    private fun showTransmissionRecovery(onBack: () -> Unit) {
        if(reloadTransmission==null) return
        if(transmissionRecovery?.phase==TransmissionRecoveryState.Phase.SESSION_ENDED) { showTransmissionInterrupted();return }
        com.xcloud.tclnative.ui.dashboard.TransmissionRecoveryPanel.show(this,{ recoverImage?.invoke() },{ reloadTransmission?.invoke(null) },onBack)
    }
    private fun showTransmissionInterrupted() {
        if (!activityVisible) return
        (gameMenuDialog as? GameShortcutBar)?.dismiss()
        videoRecoveryDialog?.dismiss();nativeTouchDialog?.dismiss()
        videoRecoveryDialog=com.xcloud.tclnative.ui.dashboard.TransmissionRecoveryPanel.failed(this,
            { reloadTransmission?.invoke(null) },{ cancelPlayback() },::showGameMenu,
            sessionEnded=transmissionRecovery?.phase==TransmissionRecoveryState.Phase.SESSION_ENDED).also { dialog ->
                dialog.setOnDismissListener { if(videoRecoveryDialog===dialog) videoRecoveryDialog=null }
            }
    }

    private fun applyShortcut(update: ShortcutUpdate) {
        val base = gamepadState.buttons and ControllerShortcut.BOTH.inv()
        if (update.pulse != 0) {
            webrtcClient?.updateGamepad(combinedInput(0,gamepadState.copy(buttons = base or update.forwardedButtons or update.pulse)),0)
        }
        gamepadState = gamepadState.copy(buttons = base or update.forwardedButtons)
        publishGamepad(0)
        if (update.openMenu) showGameMenu()
    }

    private fun handleControllerShortcut(mask: Int, down: Boolean): Boolean {
        if (mask != ControllerShortcut.START && mask != ControllerShortcut.SELECT) return false
        shortcutFlushJob?.cancel()
        applyShortcut(controllerShortcut.change(mask, down, android.os.SystemClock.elapsedRealtime()))
        controllerShortcut.flushAtMs?.let { deadline ->
            shortcutFlushJob = mainScope.launch {
                delay((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (gameplayInputEnabled()) applyShortcut(controllerShortcut.flush(android.os.SystemClock.elapsedRealtime()))
            }
        }
        return true
    }

    private fun resetController() {
        gyro.stop();setDesktopCapture(false)
        shortcutFlushJob?.cancel()
        shortcutFlushJob = null
        controllerShortcut.reset();usbShortcutHeld=0
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
            val slot=controllerSlot(event.deviceId,event.source,confirmedGamepad=true) ?: return true
            val receivedAt=android.os.SystemClock.uptimeMillis()
            val device=event.device
            webrtcClient?.recordControllerEvent(event.eventTime,receivedAt,event.historySize,device?.name)
            val previous=controllers.physical(slot)
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
            var buttons = previous.buttons and 0xF0FF
            val x = stick(MotionEvent.AXIS_HAT_X); val y = stick(MotionEvent.AXIS_HAT_Y)
            if (y < -0.5f) buttons = buttons or 256
            if (y > 0.5f) buttons = buttons or 512
            if (x < -0.5f) buttons = buttons or 1024
            if (x > 0.5f) buttons = buttons or 2048
            val rightX = if (range(MotionEvent.AXIS_Z) == null && range(MotionEvent.AXIS_RX) != null) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
            val rightY = if (range(MotionEvent.AXIS_RZ) == null && range(MotionEvent.AXIS_RY) != null) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ
            controllers.update(slot,previous.copy(buttons = buttons,
                leftX = stick(MotionEvent.AXIS_X, true), leftY = stick(MotionEvent.AXIS_Y, true),
                rightX = stick(rightX), rightY = stick(rightY),
                leftTrigger = trigger(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
                rightTrigger = trigger(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)))
            touchView.physicalInput(controllers.physical(slot))
            publishGamepad(slot)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun gamepadButton(key: Int): Int = when (key) {
        KeyEvent.KEYCODE_BUTTON_MODE -> 2
        KeyEvent.KEYCODE_BUTTON_START -> 4
        KeyEvent.KEYCODE_BUTTON_SELECT -> 8
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> 16
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

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val mask = gamepadButton(keyCode)
        if (mask != 0 && gameplayInputEnabled()) {
            val slot=controllerSlot(event.deviceId,event.source,AndroidGamepadPolicy.confirmsGamepad(event)) ?: return true
            if (slot==0 && handleControllerShortcut(mask, false)) return true
            controllers.update(slot,controllers.physical(slot).copy(buttons=controllers.physical(slot).buttons and mask.inv()))
            publishGamepad(slot)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        renderNetworkStatus()
        updateJoystickDispatch()
        if (!hasFocus) resetController()
        else {
            rumble.enabled=playbackSettings.rumble && webrtcClient!=null
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
        val mask = gamepadButton(keyCode)
        if (mask != 0 && gameplayInputEnabled()) {
            val slot=controllerSlot(event.deviceId,event.source,AndroidGamepadPolicy.confirmsGamepad(event)) ?: return true
            touchView.physicalInput(GamepadState(buttons=mask))
            if (slot==0 && handleControllerShortcut(mask, true)) return true
            controllers.update(slot,controllers.physical(slot).copy(buttons=controllers.physical(slot).buttons or mask))
            publishGamepad(slot)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart(); activityVisible=true; reportTracker?.setVisible(true); webrtcClient?.resumeAudio();webrtcClient?.setAudioForeground(true)
        usbRumble.setForeground(true);usbDirect.setForeground(true)
        rumble.enabled=playbackSettings.rumble && webrtcClient!=null
        updateJoystickDispatch()
        renderStatistics()
        val recoveryPhase = transmissionRecovery?.phase
        if (recoveryPhase == TransmissionRecoveryState.Phase.WAITING_RETRY ||
            recoveryPhase == TransmissionRecoveryState.Phase.SESSION_ENDED) showTransmissionInterrupted()
        if(activeGame==null && dashboard!=null && controlPanel.visibility==View.VISIBLE && transitionPanel.visibility!=View.VISIBLE) {
            refreshCloudPlaytime(dashboard!!)
            val signaling=currentSignaling; val library=gameLibrary
            if(signaling!=null && library!=null) refreshCloudHistory(dashboard!!,signaling,library)
        }
    }
    override fun onStop() {
        activityVisible = false
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
        closeGameTools()
        shortcutSwitchDialog?.dismiss()
        friendsPanel?.close();friendsPanel=null
        transmissionRecovery?.close()
        browserLogin?.close();browserLogin=null
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
