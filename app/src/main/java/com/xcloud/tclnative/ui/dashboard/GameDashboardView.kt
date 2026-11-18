package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import com.xcloud.tclnative.settings.ConsoleScene
import com.xcloud.tclnative.settings.DevicePerformancePolicy
import com.xcloud.tclnative.settings.SeriesPersonalizationStore
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TextClock
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.doOnNextLayout
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.GamePassCatalog
import com.xcloud.tclnative.catalog.LibraryTab
import com.xcloud.tclnative.catalog.XboxProfile
import com.xcloud.tclnative.catalog.CloudPlaytime
import com.xcloud.tclnative.catalog.CatalogCard
import com.xcloud.tclnative.catalog.GalleryProduct
import com.xcloud.tclnative.catalog.OfficialGallery
import com.xcloud.tclnative.catalog.OfficialGalleryKind
import com.xcloud.tclnative.catalog.CatalogInput
import com.xcloud.tclnative.catalog.CatalogPresentation
import com.xcloud.tclnative.catalog.CatalogPresentationEngine
import com.xcloud.tclnative.catalog.CatalogChanges
import com.xcloud.tclnative.catalog.Ownership
import com.xcloud.tclnative.catalog.OwnershipFilter
import com.xcloud.tclnative.catalog.ownershipProductKey
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** Native TV catalog: real catalog art, recycled portrait covers and deterministic controller focus. */
class GameDashboardView(
    context: Context,
    private val library: GameLibrary,
    private val onPlay: (GameTitle) -> Unit,
    private val onSearch: () -> Unit,
    private val onOptions: () -> Unit,
    private val onExit: () -> Unit,
    private val onFavoritesChanged: () -> Unit = {},
    private val onPlayProduct: (GalleryProduct) -> Unit = {},
    private val onOwnershipNeeded: () -> Unit = {},
    private val onGameShortcut: ((GameTitle) -> Unit)?=null,
    private val onFriends: (() -> Unit)?=null,
    private val onThemeSettings: (SettingsPage) -> Unit={ onOptions() }
) : FrameLayout(context) {
    private val devicePolicy=DevicePerformancePolicy.read(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val posters = PosterLoader(context, scope,frameBound=true)
    private val consoleBackdrop = ConsoleThemeBackdrop(context)
    private val seriesStore=SeriesPersonalizationStore(context)
    private var seriesPreferences=seriesStore.read()
    private val appearancePrefs=context.getSharedPreferences("app_appearance",Context.MODE_PRIVATE)
    private var appearanceListening=false
    private var seriesRefreshPending=false
    private val appearanceListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key in setOf("series_library_grid","series_badge_pass","series_badge_xs","series_guide","series_gallery") && !seriesRefreshPending) {
            seriesRefreshPending=true;post { seriesRefreshPending=false;if(!closed) refreshSeriesPreferences() }
        }
    }
    private val seriesGrid get()=designScene==ConsoleScene.XBOX_SERIES && activeTab==LibraryTab.ALL && seriesPreferences.libraryGrid
    private val seriesGlyphs=listOf(ConsoleGlyph.HOME,ConsoleGlyph.FAVORITE,ConsoleGlyph.GAME_PASS,ConsoleGlyph.PURCHASED,ConsoleGlyph.RECENT,ConsoleGlyph.LIBRARY)
    private var games = emptyList<GameTitle>()
    private var gamePassProducts: Set<String>? = null
    private var gamePassTitles = emptySet<String>()
    private var gamePassLoaded = false
    private val officialGalleries = mutableMapOf<OfficialGalleryKind, OfficialGallery?>()
    private val pendingGalleries = mutableMapOf<OfficialGalleryKind, OfficialGallery>()
    private val galleryBindings = mutableMapOf<String, GameTitle>()
    private var artworkPaused = false
    private var metadataStatus = ""
    private var added: List<String>? = null
    private var galleriesLoaded = false
    private var recent=library.cloudRecent()
    private var recentLoading=true
    private var recentFailed=false
    private var profile = XboxProfile()
    private var cloudPlaytime=CloudPlaytime()
    var activeTab = LibraryTab.RECENT
        private set
    var searchQuery = ""
        private set
    var ownershipFilter=OwnershipFilter.ALL
        private set
    private val ownership=mutableMapOf<String,Ownership>()
    private val ownershipRequested=mutableSetOf<String>()
    private var ownershipLoading=false
    private var renderedOwnershipFilter=ownershipFilter
    private var ownershipGames: List<GameTitle>?=null
    private var ownershipGalleries: List<OfficialGallery>?=null
    private var ownershipIds=emptySet<String>()
    fun setOwnershipFilter(value: OwnershipFilter) { ownershipFilter=value;refresh();focusFirstCover() }
    private fun ownershipProducts(): Set<String> {
        val galleries=officialGalleries.values.filterNotNull()
        if(ownershipGames!==games || ownershipGalleries!=galleries) {
            ownershipGames=games;ownershipGalleries=galleries
            ownershipIds=(games.mapNotNull { ownershipProductKey(it.productId) }+
                galleries.flatMap { it.products }.mapNotNull { ownershipProductKey(it.productId) }).toSet()
        }
        return ownershipIds
    }
    fun beginOwnershipRequest(retry: Boolean=false): Set<String> {
        if(retry) { ownershipRequested.clear();ownership.clear() }
        return (ownershipProducts()-ownershipRequested).also { ownershipRequested+=it;ownershipLoading=it.isNotEmpty() }
    }
    fun updateOwnership(batch: Map<String,Ownership>) { ownership.putAll(batch);refresh() }
    fun finishOwnershipRequest() { ownershipLoading=false;refresh() }
    val ownershipInformation: String get() {
        val ids=ownershipProducts()
        return UiText.text("Adquiridos: {0} • Não adquiridos: {1} • Não confirmados: {2}",
            UiText.nested(ids.count { ownership[it]==Ownership.OWNED }),UiText.nested(ids.count { ownership[it]==Ownership.NOT_OWNED }),
            UiText.nested(ids.count { ownership[it]==null || ownership[it]==Ownership.UNKNOWN }))
    }
    private var selectedCard: CatalogCard? = null
    val selectedGame: GameTitle? get() = selectedCard?.playableGame()
    private var scale = 1f
    private var coverHeight = 1
    private var coverArtSize = ArtworkSize(192,320)
    private var headerArtJob: Job? = null
    private var metadataJob: Job? = null
    private var pendingMetadata: List<GameTitle>? = null
    private var catalogJob: Job? = null
    private val catalogEngine=CatalogPresentationEngine()
    private var pendingCatalog: CatalogInput? = null
    private var renderedTab = activeTab
    private var renderedQuery = searchQuery
    private var positions = emptyMap<String, Int>()
    private var pendingFocus: Pair<String?, Int>? = null
    internal val isCatalogUpdating: Boolean get() = catalogJob?.isActive == true || metadataJob?.isActive == true
    private var designScene=DashboardTheme.palette.theme.scene
    private var originalBrowsing=false
    private var cardColumns=COLUMNS
    private var cardHorizontal=false
    private var playStationMobile=false
    private var cardWidth=0
    private var headerArtSize=ArtworkSize(128,128)
    private var failedHeaderArt: Pair<String,String>?=null
    private var coverVerticalPadding = 8f
    private var lastGeometry = 0L
    private var closed = false
    private var focusRequest = 0
    private var carouselTarget: String? = null
    private val tabs = mutableListOf<TextView>()
    private val tabPlates = mutableListOf<FocusPlate>()
    private val headerIcon = FixedArtworkView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; cropToPadding=true }
    private val headerTitle = label(44f)
    private val profilePanel = FrameLayout(context).apply {
        id = generateViewId(); isFocusable = true; isClickable = true
        DashboardTheme.customFocus(this)
        setOnClickListener { openGuideOrOptions() }
    }
    private val avatar = FixedArtworkView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = DashboardTheme.shape(UiColor.AVATAR, 1000f); clipToOutline = true
    }
    private val avatarLetter = label(28f).apply { gravity = Gravity.CENTER; text = "X" }
    private val accountName = label(26f)
    private val gamertag = label(24f).apply { setTextColor(DashboardTheme.muted) }
    private val subscription = AccountStatusBadge(context,true)
    private val remainingTime=AccountStatusBadge(context,false)
    private val settingsButton = DashboardTheme.button(context,"⚙") { onOptions() }.apply {
        id=generateViewId(); contentDescription=UiText.text("Configurações")
        typeface=android.graphics.Typeface.DEFAULT
        setPadding(0,0,0,0)
    }
    private val settingsDefaultBackground=settingsButton.background
    private val settingsDefaultGravity=settingsButton.gravity
    private val playStationBrowseTab=DashboardTheme.button(context,UiText.text("Biblioteca")) { showPlayStationLibrary() }.apply { id=generateViewId() }
    private fun titleCase(value: String)=value.lowercase(UiText.locale).replaceFirstChar { it.titlecase(UiText.locale) }
    private val playStationFeature=label(12f,false).apply { text="XBOX CLOUD GAMING";letterSpacing=.18f }
    private val playStationDescription=label(15f,false).apply { text=UiText.text("Seu catálogo em destaque. Retome o jogo e descubra sua próxima partida.");maxLines=2 }
    private val playStationLibrary=DashboardTheme.button(context,"") { if(designScene==ConsoleScene.PS5) { selectTab(LibraryTab.RECENT);focusFirstCover() } else showPlayStationLibrary() }.apply {
        id=generateViewId();contentDescription=UiText.text("Biblioteca");setPadding(0,0,0,0)
    }
    private val playStationAllGames=DashboardTheme.button(context,"") { showPlayStationLibrary() }.apply {
        id=generateViewId();contentDescription=UiText.text("Biblioteca");setPadding(0,0,0,0)
    }
    private val playStationCaption=label(12f,false)
    private val playStationQuotaValue=label(44f,false).apply { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val playStationBenefits=FrameLayout(context).apply {
        id=generateViewId();isFocusable=true;isClickable=true
        DashboardTheme.customFocus(this);setOnClickListener { openGuideOrOptions() }
        contentDescription=UiText.text("Conta e amigos")
    }
    private val playStationAccountTitle=label(17f,false).apply { text=UiText.text("Conta e amigos") }
    private val playStationFavorite=DashboardTheme.button(context,"") { if(designScene==ConsoleScene.PS5) showPlayStationOptions() else toggleSelectedFavorite() }.apply {
        id=generateViewId();contentDescription=UiText.text("FAVORITAR");setPadding(0,0,0,0)
    }
    private val playStationFriends=DashboardTheme.button(context,"") { onFriends?.invoke() }.apply {
        id=generateViewId();contentDescription=UiText.text("Amigos");setPadding(0,0,0,0)
    }
    private val heroBackdrop=FixedArtworkView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val consoleChrome=ConsoleDashboardChrome { activeTab.ordinal }
    private val consoleBrand=ConsoleBrandView(context)
    private val dashboardClock=TextClock(context).apply { setTextColor(Color.WHITE);format12Hour="h:mm a";format24Hour="HH:mm";isFocusable=false }
    private val shortcutSkins=List(4) { SeriesShortcutBackground(it) }
    private var selectionRefreshPosted=false
    private val sectionTitle=label(27f,false)
    private val launchButton=DashboardTheme.button(context,UiText.text("JOGAR")) { selectedCard?.let(::playCard) }.apply { id=generateViewId() }
    private val consoleBack=DashboardTheme.button(context,UiText.text("Voltar")) { showOriginalCategories() }.apply { id=generateViewId() }
    private val shortcuts=FrameLayout(context)
    private val quickActions=listOf(
        DashboardTheme.button(context,LibraryTab.ALL.label) { if(designScene==ConsoleScene.PS5) onThemeSettings(SettingsPage.CONTROLS) else { selectTab(LibraryTab.ALL);focusFirstCover() } },
        DashboardTheme.button(context,LibraryTab.GAME_PASS.label) { selectTab(LibraryTab.GAME_PASS);focusFirstCover() },
        DashboardTheme.button(context,LibraryTab.PURCHASED.label) { selectTab(LibraryTab.PURCHASED);focusFirstCover() },
        DashboardTheme.button(context,UiText.text("Configurações")) { if(designScene==ConsoleScene.PS5) onThemeSettings(SettingsPage.VIDEO) else onOptions() }
    ).onEach { it.id=generateViewId();shortcuts.addView(it) }
    private val searchButton = SearchButton(context,onSearch)
    private val connected = View(context).apply { background = rounded(0xff22b444.toInt(), 1000f) }
    private val navigation = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val tabRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = DashboardTheme.shape(UiColor.TAB_BAR, 7f)
        clipChildren = false; clipToPadding = false
    }
    private val consoleTabRow=FrameLayout(context).apply { clipChildren=false;clipToPadding=false }
    private val lb = label(24f).apply { text = "LB"; gravity = Gravity.CENTER }
    private val rb = label(24f).apply { text = "RB"; gravity = Gravity.CENTER }
    private val grid = object : RecyclerView(context) {
        override fun focusSearch(focused: View, direction: Int): View? {
            if(seriesGrid && hasFocus()) {
                val key=when(direction) {
                    FOCUS_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT;FOCUS_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                    FOCUS_UP -> KeyEvent.KEYCODE_DPAD_UP;FOCUS_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN;else -> null
                }
                if(key!=null) { handleSeriesGridKey(KeyEvent(KeyEvent.ACTION_DOWN,key));return findFocus() ?: focused }
            }
            // Spatial search can prefer the header over an off-screen, differently sized tile.
            if(cardHorizontal && hasFocus() && direction in listOf(FOCUS_LEFT,FOCUS_RIGHT)) {
                moveCarousel(if(direction==FOCUS_RIGHT) 1 else -1)
                return findFocus() ?: focused
            }
            return super.focusSearch(focused,direction)
        }
        override fun dispatchDraw(canvas: Canvas) {
            val saved = canvas.save()
            val bleed=if(playStationMobile) 0f else 12*scale
            canvas.clipRect(-bleed, -12*scale, width+bleed, height.toFloat())
            super.dispatchDraw(canvas)
            canvas.restoreToCount(saved)
        }
    }.apply {
        id = generateViewId()
        DashboardTheme.customFocus(this)
        layoutManager = GridLayoutManager(context, COLUMNS)
        clipToPadding = false
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        setItemViewCacheSize(if(devicePolicy.constrainedMemory) 2 else 12)
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
    private val emptyState = label(25f, false).apply {
        gravity = Gravity.CENTER; setTextColor(DashboardTheme.color(UiColor.CATALOG_NOTICE)); visibility = View.GONE
        maxLines = 4
    }
    private val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val metadataNote = label(17f, false).apply { setTextColor(DashboardTheme.muted) }
    private val catalogNote = label(18f, false).apply { gravity = Gravity.END; setTextColor(DashboardTheme.muted) }
    private val adapter = CoversAdapter()

    init {
        background = DashboardBackground(consoleArtwork=false)
        clipChildren = false; clipToPadding = false
        addView(heroBackdrop,LayoutParams(-1,-1))
        addView(consoleBackdrop,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        consoleBackdrop.setChrome(consoleChrome)
        addView(consoleBrand);addView(dashboardClock);addView(sectionTitle);addView(launchButton);addView(consoleBack);addView(shortcuts)
        addView(playStationBenefits);playStationBenefits.addView(playStationAccountTitle)
        addView(playStationFavorite);addView(playStationFriends)
        addView(playStationFeature);addView(playStationDescription);addView(playStationLibrary);addView(playStationAllGames);addView(playStationCaption);playStationBenefits.addView(playStationQuotaValue)
        headerIcon.background = DashboardTheme.shape(UiColor.HEADER_ICON, 8f); headerIcon.clipToOutline = true
        headerTitle.maxLines = 2; headerTitle.setLineSpacing(0f, .96f)
        addView(headerIcon); addView(headerTitle)
        addView(searchButton)
        profilePanel.addView(avatar); profilePanel.addView(avatarLetter)
        profilePanel.addView(connected); profilePanel.addView(accountName); profilePanel.addView(gamertag)
        profilePanel.addView(subscription);profilePanel.addView(remainingTime);profilePanel.addView(settingsButton)
        profilePanel.setOnFocusChangeListener { _, focused ->
            profilePanel.background = if(!focused) null else if(designScene==ConsoleScene.NONE) DashboardTheme.shape(UiColor.PROFILE_FOCUS,9f)
                else rounded(Color.TRANSPARENT,2f,if(designScene==ConsoleScene.XBOX_360) 0xff52721d.toInt() else DashboardTheme.accent)
        }
        addView(profilePanel)
        searchButton.nextFocusRightId=profilePanel.id
        profilePanel.nextFocusLeftId=searchButton.id
        navigation.addView(tabRow);navigation.addView(consoleTabRow);consoleTabRow.addView(playStationBrowseTab); navigation.addView(lb); navigation.addView(rb)
        lb.background = rounded(0xff252527.toInt(), 6f, Color.LTGRAY)
        rb.background = rounded(0xff252527.toInt(), 6f, Color.LTGRAY)
        lb.isClickable = true; rb.isClickable = true
        lb.contentDescription = UiText.text("Categoria anterior, LB"); rb.contentDescription = UiText.text("Próxima categoria, RB")
        lb.setOnClickListener { cycleTab(-1) }; rb.setOnClickListener { cycleTab(1) }
        lb.isFocusable = false; rb.isFocusable = false
        LibraryTab.entries.forEach { tab ->
            val plate = FocusPlate(7f, 4f)
            val view = label(25f).apply {
                id = generateViewId(); text = tab.label; gravity = Gravity.CENTER
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true; background = plate
                DashboardTheme.customFocus(this)
                setOnClickListener { selectTab(tab);focusFirstCover() }
                setOnFocusChangeListener { _, focused ->
                    if (focused) selectTab(tab)
                    if(designScene.playStation) { lastGeometry=0L;this@GameDashboardView.requestLayout() }
                }
                nextFocusUpId = profilePanel.id
            }
            tabs.add(view); tabPlates.add(plate)
            tabRow.addView(view, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, when (tab) {
                LibraryTab.RECENT -> 1.65f; LibraryTab.ADDED -> 1.35f; LibraryTab.ALL -> 1.16f; LibraryTab.PURCHASED -> 1.95f
                else -> 1f
            }))
        }
        addView(navigation)
        searchButton.nextFocusDownId=tabs.first().id
        grid.adapter = adapter
        addView(grid); addView(emptyState)
        addView(footer); addView(catalogNote); addView(metadataNote)
        hint("A", UiText.text("SELECIONAR")) { selectedCard?.let(::playCard) }
        hint("B", UiText.text("VOLTAR")) { back() }
        hint("Y", UiText.text("FAVORITAR")) { toggleSelectedFavorite() }
        hint("X", UiText.text("BUSCAR"), onSearch)
        hint("☰", UiText.text("CONFIGURAÇÕES"), onOptions)
        renderProfile()
        refreshAppearance()
        updateTabs()
    }

    private fun label(size: Float, heavy: Boolean = true) = com.xcloud.tclnative.i18n.LocalizedTextView(context).apply {
        typeface = DashboardTheme.font(context, heavy)
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke != null) setStroke(2, stroke)
    }
    private fun hint(button: String, text: String, click: () -> Unit) {
        footer.addView(label(22f, false).apply {
            this.text = button
            tag = button; gravity = Gravity.CENTER
            background = rounded(Color.TRANSPARENT, if (button == "☰") 6f else 100f, Color.WHITE)
            setOnClickListener { click() }
            isFocusable = false
        })
        footer.addView(label(22f, false).apply { this.text = text; setOnClickListener { click() }; isFocusable = false })
    }

    fun setGames(titles: List<GameTitle>) {
        metadataJob?.cancel(); metadataJob=null; pendingMetadata=null
        officialGalleries.putAll(pendingGalleries); pendingGalleries.clear()
        games=titles
        refresh()
    }
    fun updateMetadata(titles: List<GameTitle>) {
        pendingMetadata=titles
        scheduleMetadata()
    }
    private fun scheduleMetadata() {
        if(artworkPaused || metadataJob?.isActive==true) return
        metadataJob=scope.launch {
            delay(400)
            val latest=pendingMetadata
            pendingMetadata=null; metadataJob=null
            if(!closed) {
                officialGalleries.putAll(pendingGalleries); pendingGalleries.clear()
                if(latest!=null) games=latest
                refresh()
            }
        }
    }
    fun gameById(id: String): GameTitle? = games.firstOrNull { it.titleId == id }
    fun pauseArtwork() {
        consoleBackdrop.setPresentationEnabled(false)
        consoleBackdrop.setArtwork(null)
        shortcutSkins.forEach { it.clear() }
        artworkPaused=true
        pendingCatalog=null; pendingFocus=null; carouselTarget=null; focusRequest++
        metadataJob?.cancel(); metadataJob=null; pendingMetadata=null
        headerArtJob?.cancel(); posters.pause()
    }
    fun resumeArtwork() {
        consoleBackdrop.setPresentationEnabled(true)
        artworkPaused=false
        officialGalleries.putAll(pendingGalleries); pendingGalleries.clear()
        refresh()
        posters.resume(); renderProfile()
        selectedCard?.let { loadHeaderArtwork(it) }
        adapter.notifyItemRangeChanged(0,adapter.itemCount,"artwork")
    }
    fun setGamePassProducts(products: Set<String>?, resolvedTitles: Set<String> = emptySet()) {
        gamePassProducts = products
        gamePassTitles = resolvedTitles
        gamePassLoaded = true
        setGames(games)
    }
    fun setOfficialGallery(kind: OfficialGalleryKind, gallery: OfficialGallery?) {
        require(gallery==null || gallery.kind==kind)
        pendingGalleries.remove(kind)
        officialGalleries[kind]=gallery
        if(kind==OfficialGalleryKind.GAME_PASS) {
            gamePassTitles=emptySet(); gamePassLoaded=true
        }
        refresh()
    }
    fun updateOfficialGallery(gallery: OfficialGallery) {
        pendingGalleries[gallery.kind]=gallery
        scheduleMetadata()
    }
    fun bindGalleryProduct(productId: String, game: GameTitle) {
        galleryBindings[GamePassCatalog.productKey(productId)]=game
        refresh()
    }
    fun isGamePassProduct(productId: String) = GamePassCatalog.productKey(productId) in (gamePassProducts ?: emptySet())
    fun setMetadataProgress(progress: com.xcloud.tclnative.catalog.CatalogService.Progress) {
        metadataStatus = when {
            !progress.finished -> UiText.text("CARREGANDO INFORMAÇÕES E CAPAS: {0}/{1}",UiText.nested(progress.covers),UiText.nested(progress.total))
            progress.covers == 0 -> UiText.text("CAPAS INDISPONÍVEIS. START → ATUALIZAR CATÁLOGO.")
            progress.covers < progress.total -> UiText.text("ALGUMAS CAPAS INDISPONÍVEIS. ATUALIZE NAS CONFIGURAÇÕES.")
            else -> ""
        }
        renderMetadataNote()
    }
    fun setProfile(value: XboxProfile) {
        val updated=value.copy(subscription=value.subscription ?: profile.subscription)
        if(updated!=profile) { profile=updated; renderProfile() }
    }
    fun setCloudPlaytime(value: CloudPlaytime) { cloudPlaytime=value;remainingTime.bindTime(value);updatePlayStationQuota() }
    private fun updatePlayStationQuota() {
        if(!designScene.playStation) return
        playStationQuotaValue.text=when(cloudPlaytime.status) {
            com.xcloud.tclnative.catalog.CloudPlaytimeStatus.UNLIMITED -> "∞"
            com.xcloud.tclnative.catalog.CloudPlaytimeStatus.LIMITED -> if(cloudPlaytime.seconds>=3600) UiText.text("{0}h{1}min",cloudPlaytime.seconds/3600,String.format(Locale.ROOT,"%02d",cloudPlaytime.seconds/60%60))
                else UiText.text("{0}min",cloudPlaytime.seconds.coerceAtLeast(0)/60)
            else -> "—"
        }
        playStationBenefits.contentDescription=UiText.text("Conta e amigos")+". "+cloudPlaytime.label
    }
    fun cloudPlaytimeSnapshot()=cloudPlaytime
    fun cloudPlaytimeDiagnostics()=cloudPlaytime.diagnostics()
    private fun renderProfile() {
        accountName.text = if(designScene==ConsoleScene.NONE) profile.displayName.uppercase(Locale.getDefault()) else profile.displayName
        gamertag.text = if(designScene==ConsoleScene.NONE) profile.gamertag.uppercase(Locale.getDefault()) else profile.gamertag
        subscription.bindPlan(profile.subscription?.let { if (it == "FREE") "FREE" else "GAME PASS $it" } ?: UiText.text("ASSINATURA NÃO INFORMADA"))
        subscription.refreshBadge(scale)
        remainingTime.bindTime(cloudPlaytime)
        avatarLetter.text = profile.displayName.take(1).uppercase(Locale.getDefault())
        connected.contentDescription = UiText.text("Conta autenticada no Xbox Cloud")
        profilePanel.contentDescription = if(SeriesGuidePanel.enabled(context))
            UiText.text("{0}, {1}. Abrir Guia",UiText.nested(profile.displayName),UiText.nested(profile.gamertag))
            else UiText.text("{0}, {1}. Abrir configurações",UiText.nested(profile.displayName),UiText.nested(profile.gamertag))
        val size=ArtworkSize.forDisplay((80*scale).roundToInt(),(80*scale).roundToInt())
        posters.load(avatar, profile.avatarUrl, size.width, size.height) { ready -> avatarLetter.visibility = if (ready) View.GONE else View.VISIBLE }
    }
    fun setAddedGallery(added: List<String>?) {
        this.added = added; galleriesLoaded = true; refresh()
    }
    fun beginRecentRefresh() { recentLoading=true; recentFailed=false; refresh() }
    fun setRecentHistory(ids: List<String>?) {
        recentLoading=false; recentFailed=ids==null
        if(ids!=null) recent=ids
        refresh()
    }
    fun setSearch(query: String) {
        searchQuery=query.trim()
        // The main search must also find games outside the account's recent history.
        if(searchQuery.isNotBlank() && activeTab!=LibraryTab.ALL) selectTab(LibraryTab.ALL) else refresh()
        focusFirstCover()
    }
    fun refresh() {
        if(closed) return
        if(ownershipFilter!=OwnershipFilter.ALL && (ownershipProducts()-ownershipRequested).isNotEmpty()) onOwnershipNeeded()
        if(artworkPaused) return
        if(renderedTab!=activeTab || renderedQuery!=searchQuery || renderedOwnershipFilter!=ownershipFilter) {
            // Do not leave clickable cards from the previous category while preparing the next.
            renderedTab=activeTab; renderedQuery=searchQuery;renderedOwnershipFilter=ownershipFilter
            adapter.submit(emptyList(),CatalogChanges(true,emptyList()))
            positions=emptyMap(); updateSelected(null)
            renderCatalogState(0,preparing=true)
        }
        pendingCatalog=CatalogInput(games,officialGalleries.toMap(),galleryBindings.toMap(),
            gamePassProducts,gamePassTitles,activeTab,library.favorites,added,recent,searchQuery,ownershipFilter,ownership.toMap())
        if(catalogJob?.isActive==true) return
        // One worker, one pending snapshot: bursts cannot build an unbounded CPU queue.
        catalogJob=scope.launch {
            while(!closed && !artworkPaused) {
                val input=pendingCatalog ?: break
                pendingCatalog=null
                val before=adapter.items
                val (presentation,changes)=withContext(Dispatchers.Default) {
                    val result=catalogEngine.build(input)
                    result to CatalogChanges.between(before,result.cards)
                }
                // Metadata arriving during a build must not starve an otherwise current category.
                if(!closed && !artworkPaused && input.tab==activeTab && input.query==searchQuery && input.ownershipFilter==ownershipFilter)
                    applyCatalog(presentation,if(adapter.items!==before) CatalogChanges(true,emptyList()) else changes)
            }
        }
    }
    private fun applyCatalog(presentation: CatalogPresentation, changes: CatalogChanges) {
        val selectedKey=selectedCard?.key
        val focused=grid.hasFocus()
        val manager=grid.layoutManager as GridLayoutManager
        val anchor=manager.findFirstVisibleItemPosition()
        val anchorKey=adapter.items.getOrNull(anchor)?.key
        val anchorOffset=manager.findViewByPosition(anchor)?.let {
            if(manager.orientation==RecyclerView.HORIZONTAL) it.left-grid.paddingLeft else it.top-grid.paddingTop
        } ?: 0
        positions=presentation.positions
        gamePassProducts=presentation.gamePassProducts
        val updated=profile.copy(subscription=presentation.subscription ?: profile.subscription)
        if(updated!=profile) { profile=updated; renderProfile() }
        adapter.submit(presentation.cards,changes)
        val selectedPosition=positions[selectedKey]
        updateSelected(selectedPosition?.let { presentation.cards[it] } ?: presentation.cards.firstOrNull())
        renderCatalogState(presentation.cards.size)
        val requested=pendingFocus
        pendingFocus=null
        if(requested!=null) focusCoverNow(positions[requested.first] ?: requested.second)
        else if(changes.replace && focused) focusCoverNow(selectedPosition ?: 0)
        else if(changes.replace && anchorKey!=null) positions[anchorKey]?.let { manager.scrollToPositionWithOffset(it,anchorOffset) }
    }
    private fun renderCatalogState(count: Int, preparing: Boolean = false) {
        val kind=when(activeTab) { LibraryTab.GAME_PASS -> OfficialGalleryKind.GAME_PASS; LibraryTab.PURCHASED -> OfficialGalleryKind.PURCHASED; else -> null }
        val gallery=kind?.let { officialGalleries[it] }
        val originalHome=designScene==ConsoleScene.XBOX_ORIGINAL && !originalBrowsing
        emptyState.visibility = if (count==0 && !originalHome) View.VISIBLE else View.GONE
        emptyState.text = when {
            preparing -> UiText.text("Carregando catálogo…")
            ownershipFilter!=OwnershipFilter.ALL && ownershipLoading -> UiText.text("Consultando as licenças da conta…")
            ownershipFilter!=OwnershipFilter.ALL -> UiText.text("Nenhum jogo confirmado para este filtro.\nA propriedade não confirmada fica em uma opção separada.")
            searchQuery.isNotBlank() -> UiText.text("Nenhum jogo encontrado para “{0}”.\nX para pesquisar novamente.",UiText.nested(searchQuery))
            activeTab == LibraryTab.FAVORITES -> UiText.text("Seus favoritos aparecem aqui.\nSelecione um jogo e pressione Y para favoritar.")
            activeTab == LibraryTab.RECENT && recentLoading -> UiText.text("Consultando seu histórico do Xbox Cloud…")
            activeTab == LibraryTab.RECENT && recentFailed -> UiText.text("Não foi possível consultar o histórico do Xbox.\nAtualize o catálogo nas configurações para tentar novamente.")
            activeTab == LibraryTab.RECENT -> UiText.text("Seus jogos recentes do Xbox Cloud Gaming aparecerão aqui.")
            activeTab == LibraryTab.ADDED && !galleriesLoaded -> UiText.text("Carregando adições recentes…")
            activeTab == LibraryTab.ADDED && added == null -> UiText.text("Adições recentes indisponíveis no momento.\nOs jogos continuam na aba Todos os jogos.")
            activeTab == LibraryTab.GAME_PASS && !gamePassLoaded -> UiText.text("Carregando jogos do Game Pass…")
            activeTab == LibraryTab.GAME_PASS && gamePassProducts == null -> UiText.text("Não foi possível confirmar os jogos do Game Pass.\nAtualize o catálogo nas configurações.")
            activeTab == LibraryTab.GAME_PASS -> UiText.text("Nenhum título do Game Pass disponível nesta lista.")
            activeTab == LibraryTab.PURCHASED && !officialGalleries.containsKey(OfficialGalleryKind.PURCHASED) -> UiText.text("Carregando a lista oficial de jogos comprados compatíveis…")
            activeTab == LibraryTab.PURCHASED && gallery == null -> UiText.text("Não foi possível consultar a lista oficial.\nAtualize o catálogo nas configurações.")
            activeTab == LibraryTab.PURCHASED -> UiText.text("A galeria oficial não retornou jogos nesta região.")
            else -> UiText.text("Nenhum jogo disponível nesta categoria.")
        }
        catalogNote.text = when {
            preparing -> UiText.text("CARREGANDO CATÁLOGO…")
            ownershipFilter!=OwnershipFilter.ALL -> UiText.text("{0} • {1} JOGOS • {2}",UiText.nested(ownershipFilter.label),UiText.nested(count),
                UiText.nested(if(ownershipLoading) UiText.text("Consultando as licenças da conta…") else
                    "${OwnershipFilter.UNKNOWN.label}: ${ownershipProducts().count { ownership[it]==null || ownership[it]==Ownership.UNKNOWN }}"))
            searchQuery.isNotBlank() -> UiText.text("BUSCA: {0}  •  {1} JOGOS",UiText.nested(searchQuery),UiText.nested(count))
            activeTab == LibraryTab.GAME_PASS && !gamePassLoaded -> UiText.text("{0} JOGOS • ATUALIZANDO GAME PASS…",UiText.nested(count))
            activeTab == LibraryTab.RECENT && recentLoading -> UiText.text("{0} JOGOS • ATUALIZANDO HISTÓRICO…",UiText.nested(count))
            activeTab == LibraryTab.RECENT && recentFailed && recent!=null -> UiText.text("{0} JOGOS • HISTÓRICO SALVO • ATUALIZAÇÃO INDISPONÍVEL",UiText.nested(count))
            kind!=null && officialGalleries.containsKey(kind) && gallery==null -> UiText.text("LISTA OFICIAL INDISPONÍVEL")
            gallery!=null && !gallery.metadataComplete -> UiText.text("{0} PRODUTOS • ATUALIZANDO",UiText.nested(count))
            gallery?.metadataFailed==true -> UiText.text("{0} PRODUTOS • DADOS PARCIAIS",UiText.nested(count))
            gallery!=null -> UiText.text("{0} JOGOS • LISTA OFICIAL",UiText.nested(count))
            else -> UiText.text("{0} JOGOS",UiText.nested(count))
        }
        renderMetadataNote()
    }
    private fun renderMetadataNote() {
        val kind=when(activeTab) { LibraryTab.GAME_PASS -> OfficialGalleryKind.GAME_PASS; LibraryTab.PURCHASED -> OfficialGalleryKind.PURCHASED; else -> null }
        val gallery=kind?.let { officialGalleries[it] }
        metadataNote.text=when {
            activeTab==LibraryTab.PURCHASED && gallery?.metadataFailed==true -> UiText.text("Algumas informações indisponíveis. A lista não confirma a propriedade; exige compra digital e condições do serviço.")
            activeTab==LibraryTab.PURCHASED && gallery!=null && !gallery.metadataComplete -> UiText.text("Carregando informações da lista oficial. Não confirma a propriedade; exige compra digital e condições do serviço.")
            activeTab==LibraryTab.PURCHASED -> UiText.text("Lista oficial de jogos compatíveis; exige compra digital e condições do serviço. Não confirma a propriedade na conta.")
            gallery?.metadataFailed==true -> UiText.text("Lista oficial completa • Algumas informações/capas indisponíveis; atualize o catálogo nas configurações.")
            gallery!=null && !gallery.metadataComplete -> UiText.text("Lista oficial completa • Carregando informações e capas…")
            gallery!=null -> UiText.text("Galeria oficial do Xbox Cloud Gaming • Inclui opções gratuitas; acesso depende da conta e das condições do serviço.")
            else -> metadataStatus
        }
        metadataNote.visibility=GONE
    }
    val catalogInformation: String get()=metadataNote.text.toString()
    fun selectTab(tab: LibraryTab) {
        if (activeTab != tab) {
            pendingFocus=null; carouselTarget=null; focusRequest++
            activeTab = tab; selectedCard = null
            if(designScene==ConsoleScene.XBOX_SERIES) { configureCardLayout();lastGeometry=0L;requestLayout() }
            refresh(); grid.scrollToPosition(0)
        }
        updateTabs()
    }
    private fun updateTabs() {
        sectionTitle.text=activeTab.label
        consoleBackdrop.refreshStructure()
        if(designScene!=ConsoleScene.NONE) {
            tabs.forEachIndexed { i,view ->
                view.background=ConsoleControlPlate(designScene,ConsoleControl.TAB,i==activeTab.ordinal,if(designScene.playStation) null else seriesGlyphs[i])
                if(designScene==ConsoleScene.XBOX_SERIES) view.text=if(i==activeTab.ordinal) LibraryTab.entries[i].label else ""
                view.setTextColor(if(designScene==ConsoleScene.XBOX_ORIGINAL) {
                    if(i==activeTab.ordinal) 0xff183000.toInt() else 0xffc2f364.toInt()
                } else consoleTextColor())
                if(designScene.playStation) view.setTextColor(playStationControlColors(designScene))
            }
            if(designScene.playStation || designScene==ConsoleScene.XBOX_360 || designScene==ConsoleScene.XBOX_360_NXE || designScene==ConsoleScene.XBOX_360_METRO) { lastGeometry=0L;requestLayout() }
        }
        profilePanel.nextFocusDownId = categoryFocus().id
        LibraryTab.entries.forEachIndexed { index, tab ->
            tabPlates[index].highlighted = activeTab == tab
            tabs[index].isSelected = activeTab == tab
            tabs[index].contentDescription = tab.label + if (activeTab == tab) UiText.text(", selecionado") else ""
        }
    }
    private val playStationTabs get()=listOf(LibraryTab.RECENT,LibraryTab.ALL,LibraryTab.FAVORITES)
    private fun categoryFocus()=if(designScene.playStation && activeTab !in playStationTabs) playStationBrowseTab else tabs[activeTab.ordinal]
    private fun showPlayStationLibrary() {
        ConsoleDialogs.menu(context,UiText.text("Biblioteca"),LibraryTab.entries.map { tab ->
            ConsoleAction(tab.label) { selectTab(tab);focusFirstCover() }
        },onBack={ categoryFocus().requestFocus() })
    }
    private fun cycleTab(step: Int) {
        val next = (activeTab.ordinal + step + LibraryTab.entries.size) % LibraryTab.entries.size
        selectTab(LibraryTab.entries[next]); focusFirstCover()
    }
    private fun focusFirstCover() {
        focusCoverAt(0)
    }
    private fun focusCoverAt(position: Int) {
        if(designScene==ConsoleScene.XBOX_ORIGINAL && !originalBrowsing) {
            originalBrowsing=true;navigation.visibility=GONE;grid.visibility=VISIBLE;consoleBack.visibility=VISIBLE
            lastGeometry=0L;requestLayout();updateConsoleTitle()
        }
        if(isCatalogUpdating) {
            val target=adapter.items.getOrNull(position)?.key
            pendingFocus=target to position
            if(cardHorizontal) carouselTarget=target
            return
        }
        focusCoverNow(position)
    }
    private fun focusCoverNow(position: Int) {
        val request=++focusRequest
        if(adapter.itemCount==0) { carouselTarget=null; if(designScene==ConsoleScene.XBOX_ORIGINAL && originalBrowsing) consoleBack.requestFocus() else categoryFocus().requestFocus();return }
        val target=adapter.items[position.coerceIn(0,adapter.itemCount-1)].key
        if(cardHorizontal) carouselTarget=target
        fun focus(attempt: Int) {
            if(closed || request!=focusRequest) return
            val index=positions[target] ?: run { carouselTarget=null;return }
            val card=grid.findViewHolderForAdapterPosition(index)?.itemView
            if(card!=null) {
                card.requestFocus()
                // Selection changes the tile's width; reveal it after that layout, too.
                grid.doOnNextLayout {
                    if(!closed && request==focusRequest && card.hasFocus())
                        card.requestRectangleOnScreen(Rect(0,0,card.width,card.height),true)
                }
                grid.requestLayout()
                carouselTarget=null
            } else if(attempt<3) {
                grid.doOnNextLayout { focus(attempt+1) }
                grid.scrollToPosition(index);grid.requestLayout()
            } else carouselTarget=null
        }
        focus(0)
    }
    private fun moveCarousel(step: Int) {
        val position=positions[carouselTarget ?: selectedCard?.key] ?: return
        val next=position+step
        if(next<0 && designScene==ConsoleScene.XBOX_360_NXE) {
            ++focusRequest;carouselTarget=null;pendingFocus=null;categoryFocus().requestFocus()
        } else if(designScene==ConsoleScene.PS5 && next<0) {
            ++focusRequest;carouselTarget=null;pendingFocus=null;playStationLibrary.requestFocus()
        } else if(designScene==ConsoleScene.PS5 && next>=adapter.itemCount) {
            ++focusRequest;carouselTarget=null;pendingFocus=null;playStationAllGames.requestFocus()
        } else if(next in adapter.items.indices) focusCoverAt(next)
        // Keep focus on the final game instead of falling into the header or shortcuts.
    }
    private fun handleCarouselKey(event: KeyEvent): Boolean {
        if(!cardHorizontal || (!grid.hasFocus() && carouselTarget==null)) return false
        when(event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if(event.action==KeyEvent.ACTION_DOWN) moveCarousel(if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN -> if(event.action==KeyEvent.ACTION_DOWN) {
                ++focusRequest;carouselTarget=null;pendingFocus=null
            }
        }
        return false
    }
    private fun handleSeriesGridKey(event: KeyEvent): Boolean {
        if(!seriesGrid || !grid.hasFocus()) return false
        val delta=when(event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1;KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -cardColumns;KeyEvent.KEYCODE_DPAD_DOWN -> cardColumns
            else -> return false
        }
        if(event.action==KeyEvent.ACTION_DOWN) {
            val at=positions[carouselTarget ?: selectedCard?.key] ?: return true
            val next=at+delta
            when {
                delta==-cardColumns && at<cardColumns -> { carouselTarget=null;categoryFocus().requestFocus() }
                delta==-1 && at%cardColumns==0 || delta==1 && at%cardColumns==cardColumns-1 -> Unit
                next in adapter.items.indices -> { carouselTarget=adapter.items[next].key;focusCoverAt(next) }
            }
        }
        return true
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleSeriesGridKey(event) || handleCarouselKey(event) || super.dispatchKeyEvent(event)
    fun restoreFocus() {
        refresh()
        if(designScene==ConsoleScene.XBOX_ORIGINAL && !originalBrowsing) { categoryFocus().requestFocus();return }
        val position = positions[selectedCard?.key] ?: 0
        focusCoverAt(position)
    }
    private fun updateSelected(game: CatalogCard?) {
        val previous = selectedCard
        selectedCard = game
        refreshSeriesSelection()
        if (previous?.key==game?.key && previous?.name==game?.name && previous?.tileUrl==game?.tileUrl &&
            previous?.posterUrl==game?.posterUrl && previous?.product?.heroUrl==game?.product?.heroUrl &&
            previous?.game?.heroUrl==game?.game?.heroUrl && headerTitle.text.isNotEmpty()) return
        headerArtJob?.cancel()
        if (game == null) {
            headerTitle.text = UiText.text("SUA BIBLIOTECA");posters.load(headerIcon,null);posters.cancel(heroBackdrop)
            if(designScene.playStation) consoleBackdrop.setArtwork(null)
            launchButton.isEnabled=false;return
        }
        launchButton.isEnabled=true
        if(designScene!=ConsoleScene.NONE) {
            updateConsoleTitle()
            headerIcon.contentDescription=UiText.text("Ícone de {0}",UiText.nested(game.name))
            headerArtJob=scope.launch { delay(120);if(selectedCard?.key==game.key) loadHeaderArtwork(game) }
            return
        }
        val words = game.name.uppercase(Locale.getDefault()).split(Regex("\\s+"))
        val split = if (words.size <= 1) words.size else if (words[0].length >= 10) 1 else
            (1 until words.size).minByOrNull { kotlin.math.abs(words.take(it).joinToString(" ").length - game.name.length / 2) } ?: 1
        val first = words.take(split).joinToString(" ")
        val second = words.drop(split).joinToString(" ")
        val title = if (second.isEmpty()) first else "$first\n$second"
        headerTitle.text = SpannableString(title).apply {
            if (second.isNotEmpty()) setSpan(ForegroundColorSpan(DashboardTheme.accent), first.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        headerIcon.contentDescription = UiText.text("Ícone de {0}",UiText.nested(game.name))
        // Fast D-pad navigation updates text immediately and loads art only after focus settles.
        headerArtJob=scope.launch { delay(120); if(selectedCard?.key==game.key) loadHeaderArtwork(game) }
    }
    private fun loadHeaderArtwork(game: CatalogCard) {
        if(designScene.playStation) {
            posters.cancel(headerIcon)
            val hero=game.product?.heroUrl ?: game.game?.heroUrl
            if(designScene==ConsoleScene.PS5 && hero!=null) {
                val target=if(devicePolicy.constrainedMemory) ArtworkSize(640,360) else ArtworkSize(1024,576)
                posters.load(heroBackdrop,hero,target.width,target.height) { ready ->
                    if(selectedCard?.key==game.key && designScene==ConsoleScene.PS5) consoleBackdrop.setArtwork(if(ready) heroBackdrop.drawable else null)
                }
            } else { posters.cancel(heroBackdrop);consoleBackdrop.setArtwork(null) }
            return
        }
        if(designScene==ConsoleScene.XBOX_360_NXE || designScene==ConsoleScene.XBOX_360_METRO) { posters.cancel(headerIcon);posters.cancel(heroBackdrop);return }
        if(designScene==ConsoleScene.XBOX_ORIGINAL && !originalBrowsing) { posters.cancel(headerIcon);return }
        val hero=game.product?.heroUrl ?: game.game?.heroUrl
        val art=if(designScene==ConsoleScene.XBOX_360 || designScene==ConsoleScene.XBOX_ORIGINAL) game.posterUrl ?: game.tileUrl
            else hero ?: game.tileUrl ?: game.posterUrl
        val size=if(designScene==ConsoleScene.NONE) ArtworkSize.forDisplay((116*scale).roundToInt(),(116*scale).roundToInt()) else headerArtSize
        if(designScene==ConsoleScene.XBOX_SERIES && hero!=null) posters.cancel(headerIcon)
        else if(designScene==ConsoleScene.XBOX_ONE) {
            val fallback=game.tileUrl ?: game.posterUrl
            if(hero==null || failedHeaderArt?.let { it.first==game.key && it.second==hero }==true) {
                headerIcon.scaleType=ImageView.ScaleType.FIT_CENTER;posters.load(headerIcon,fallback,size.width,size.height)
            } else {
                headerIcon.scaleType=ImageView.ScaleType.CENTER_CROP
                posters.load(headerIcon,hero,size.width,size.height,notifyPlaceholder=false) { ready ->
                    if(!ready && !artworkPaused && !closed && designScene==ConsoleScene.XBOX_ONE && selectedCard?.key==game.key) {
                        failedHeaderArt=game.key to hero;headerIcon.scaleType=ImageView.ScaleType.FIT_CENTER
                        posters.load(headerIcon,fallback,size.width,size.height)
                    }
                }
            }
        } else posters.load(headerIcon,if(designScene==ConsoleScene.NONE) game.tileUrl ?: game.posterUrl else art,size.width,size.height)
        if(designScene==ConsoleScene.XBOX_SERIES) {
            if(seriesPreferences.gallery) { posters.cancel(heroBackdrop);consoleBackdrop.setArtwork(null);headerIcon.visibility=INVISIBLE;return }
            val target=if(devicePolicy.constrainedMemory) ArtworkSize(640,360) else ArtworkSize(1024,576)
            if(hero!=null) {
                posters.load(heroBackdrop,hero,target.width,target.height) { ready ->
                    if(selectedCard?.key==game.key && designScene==ConsoleScene.XBOX_SERIES)
                        consoleBackdrop.setArtwork(if(ready) heroBackdrop.drawable else null)
                }
                headerIcon.visibility=INVISIBLE
            } else { posters.cancel(heroBackdrop);consoleBackdrop.setArtwork(null);headerIcon.visibility=VISIBLE;headerIcon.scaleType=ImageView.ScaleType.FIT_CENTER }
            consoleBackdrop.visibility=VISIBLE
        } else posters.cancel(heroBackdrop)
    }
    private fun toggleSelectedFavorite() {
        val game = selectedGame ?: run {
            if(selectedCard?.product!=null) android.widget.Toast.makeText(context,UiText.text("Selecione o jogo para verificar sua disponibilidade antes de favoritar."),android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val added = try { library.toggleFavorite(game.titleId) } catch (error: java.io.IOException) {
            android.widget.Toast.makeText(context,error.message,android.widget.Toast.LENGTH_LONG).show()
            return
        }
        onFavoritesChanged()
        announceForAccessibility(if (added) UiText.text("{0} adicionado aos favoritos",UiText.nested(game.name)) else UiText.text("{0} removido dos favoritos",UiText.nested(game.name)))
        if (activeTab == LibraryTab.FAVORITES) { refresh(); focusFirstCover() }
        else adapter.items.indices.filter { adapter.items[it].game?.titleId==game.titleId }
            .forEach { adapter.notifyItemChanged(it,"favorite") }
    }
    private fun playCard(card: CatalogCard) {
        val game=card.playableGame()
        if(game!=null) onPlay(game) else card.product?.let(onPlayProduct)
    }
    private fun showOriginalCategories() {
        originalBrowsing=false;navigation.visibility=VISIBLE;consoleTabRow.visibility=VISIBLE;grid.visibility=GONE;consoleBack.visibility=GONE;emptyState.visibility=GONE
        ++focusRequest // Cancel a grid focus request still waiting for layout.
        post { if(!closed && !originalBrowsing) categoryFocus().requestFocus() }
        lastGeometry=0L;requestLayout();updateConsoleTitle();categoryFocus().requestFocus()
    }
    fun back() {
        ++focusRequest;carouselTarget=null;pendingFocus=null
        if(designScene==ConsoleScene.XBOX_ORIGINAL && originalBrowsing) { showOriginalCategories();return }
        when {
            searchQuery.isNotBlank() -> setSearch("")
            grid.hasFocus() -> categoryFocus().requestFocus()
            else -> onExit()
        }
    }
    /** Handle gamepad keys only while the catalog is visible, before the native gameplay route. */
    fun handleKey(event: KeyEvent): Boolean {
        if(handleSeriesGridKey(event)) return true
        if(handleCarouselKey(event)) return true
        val key = event.keyCode
        val handled = key in setOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B) ||
            key==KeyEvent.KEYCODE_BUTTON_SELECT && onGameShortcut!=null && selectedGame!=null
        if (!handled) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (key) {
            KeyEvent.KEYCODE_BUTTON_L1 -> cycleTab(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> cycleTab(1)
            KeyEvent.KEYCODE_BUTTON_Y -> toggleSelectedFavorite()
            KeyEvent.KEYCODE_BUTTON_X -> onSearch()
            KeyEvent.KEYCODE_BUTTON_START -> if(designScene.playStation) onOptions() else openGuideOrOptions()
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectedGame?.let { onGameShortcut?.invoke(it) }
            KeyEvent.KEYCODE_BUTTON_A -> findFocus()?.performClick()
            KeyEvent.KEYCODE_BUTTON_B -> back()
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val geometry = (w.toLong() shl 32) or h.toLong()
        if (!closed && geometry != lastGeometry && w > 0 && h > 0) {
            lastGeometry = geometry; scale = w / 1280f; layoutGeometry(w, h)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun place(view: View, x: Float, y: Float, w: Float, h: Float) {
        view.layoutParams = LayoutParams(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)).apply {
            leftMargin = x.roundToInt(); topMargin = y.roundToInt()
        }
    }
    private fun textSize(view: TextView, value: Float) = view.setTextSize(TypedValue.COMPLEX_UNIT_PX, value * scale)
    private fun layoutGeometry(w: Int, h: Int) {
        if(designScene!=ConsoleScene.NONE) { layoutConsole(w,h);return }
        listOf(headerIcon,headerTitle,profilePanel,searchButton,navigation,lb,rb,grid).forEach { it.visibility=VISIBLE }
        metadataNote.visibility=GONE
        settingsButton.visibility=VISIBLE;headerTitle.gravity=Gravity.START
        cardWidth=0;cardColumns=COLUMNS;cardHorizontal=false
        val s = scale
        // Reserve the full header for the game and profile. The portrait grid scrolls below it.
        coverVerticalPadding = 7*s
        val coverWidth = (w-150*s)/COLUMNS - 20*s
        coverHeight = (coverWidth*1.52f+2*coverVerticalPadding).roundToInt().coerceAtLeast(1)
        coverArtSize=ArtworkSize.forDisplay(coverWidth.roundToInt(),(coverWidth*1.52f).roundToInt())
        val footerTop = h-42*s
        val headerTop = 20*s
        val headerSize = 116*s
        val navHeight = 54*s
        val navTop = headerTop+headerSize+30*s
        val top = navTop+navHeight+18*s
        place(headerIcon, 106*s, headerTop, headerSize, headerSize)
        selectedCard?.let(::loadHeaderArtwork)
        place(searchButton, 8*s, headerTop+14*s, 88*s, 88*s)
        place(headerTitle, 242*s, headerTop+8*s, 465*s, 110*s)
        textSize(headerTitle, 46f)
        if (android.os.Build.VERSION.SDK_INT >= 26) headerTitle.setAutoSizeTextTypeUniformWithConfiguration(
            (24*s).roundToInt().coerceAtLeast(1), (46*s).roundToInt().coerceAtLeast(2), 1, TypedValue.COMPLEX_UNIT_PX)
        place(profilePanel, w-492*s, headerTop+2*s, 440*s, headerSize)
        val a = 80*s
        place(avatarLetter, 10*s, 12*s, a, a); place(avatar, 10*s, 12*s, a, a)
        place(connected, 10*s+a-19*s, 12*s+a-18*s, 15*s, 15*s)
        val tx = 106*s
        place(accountName, tx, 0f, 320*s, 31*s)
        place(gamertag, tx, 31*s, 320*s, 26*s)
        place(subscription, tx, 60*s, 250*s, 25*s)
        place(remainingTime,tx,88*s,250*s,22*s);textSize(remainingTime,17f)
        place(settingsButton, tx+264*s, 64*s, 60*s, 42*s)
        textSize(accountName, 27f); textSize(gamertag, 24f); textSize(subscription, 20f)
        textSize(settingsButton, 28f); textSize(avatarLetter, 28f)
        subscription.refreshBadge(s);remainingTime.refreshBadge(s)
        place(metadataNote, 106*s, headerTop+headerSize+5*s, w-210*s, 22*s); textSize(metadataNote, 17f)
        place(navigation, 48*s, navTop, w-84*s, navHeight+3*s)
        val shoulderTop = (navHeight-29*s)/2
        place(lb, 3*s, shoulderTop, 40*s, 29*s); place(rb, w-128*s, shoulderTop, 40*s, 29*s)
        place(tabRow, 58*s, 0f, w-204*s, navHeight)
        tabs.forEach { textSize(it, 22f) }; textSize(lb, 23f); textSize(rb, 23f)
        place(grid, 96*s, top, w-150*s, (footerTop-top-10*s).coerceAtLeast(coverHeight.toFloat()))
        place(emptyState, 106*s, top, w-210*s, h*.50f)
        textSize(emptyState, 26f)
        place(footer, 60*s, footerTop, w-120*s, 34*s)
        for (i in 0 until footer.childCount step 2) {
            val glyph = footer.getChildAt(i) as TextView
            glyph.text = glyph.tag.toString(); textSize(glyph, 22f)
            glyph.layoutParams = LinearLayout.LayoutParams((28*s).roundToInt(), (28*s).roundToInt())
                .apply { marginEnd = (8*s).roundToInt() }
            val text = footer.getChildAt(i+1) as TextView
            textSize(text, 22f)
            text.layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (25*s).roundToInt() }
        }
        place(catalogNote, w-380*s, footerTop+s, 320*s, 32*s); textSize(catalogNote, 18f)
        renderProfile()
        adapter.notifyDataSetChanged()
    }

    private fun refreshSeriesSelection() {
        if(designScene!=ConsoleScene.XBOX_SERIES && designScene!=ConsoleScene.XBOX_360_NXE && !designScene.playStation) return
        if(grid.isComputingLayout) {
            if(!selectionRefreshPosted) { selectionRefreshPosted=true;grid.post { selectionRefreshPosted=false;if(!closed) refreshSeriesSelection() } }
            return
        }
        for(i in 0 until grid.childCount) (grid.getChildAt(i) as? GameCard)?.refreshSeriesSelection()
    }
    private fun refreshSeriesMosaic() {
        if(designScene!=ConsoleScene.XBOX_SERIES || artworkPaused) return
        shortcutSkins[0].mosaic((0 until grid.childCount).mapNotNull { (grid.getChildAt(it) as? GameCard)?.image?.drawable })
    }
    private fun consoleTextColor()=if(designScene==ConsoleScene.XBOX_360 || designScene==ConsoleScene.XBOX_360_METRO) 0xff263024.toInt() else Color.WHITE
    private fun carouselWidth(selected: Boolean): Float {
        if(playStationMobile) return if(designScene==ConsoleScene.PS5) { if(height>width) { if(selected) 96f else 80f } else if(selected) 64f else 48f }
            else if(height>width) { if(selected) 128f else 104f } else if(selected) 96f else 80f
        return when(designScene) {
        ConsoleScene.PS4 -> if(width>height && (scale<.8f || 48f*resources.displayMetrics.density/scale>80f)) { maxOf(if(selected) 190f else 144f,48f*resources.displayMetrics.density/scale+16f) } else if(selected) 235f else 191f
        ConsoleScene.PS5 -> maxOf(if(selected) 116f else 86f,48f*resources.displayMetrics.density/scale+16f)
        ConsoleScene.XBOX_360_NXE -> if(selected) 288f else 206f
        else -> if(selected) 232f else 168f
        }
    }
    private fun updateConsoleTitle() {
        val title=if(designScene==ConsoleScene.XBOX_ORIGINAL && !originalBrowsing) UiText.text("SUA BIBLIOTECA") else selectedCard?.name ?: UiText.text("SUA BIBLIOTECA")
        headerTitle.text=if(designScene==ConsoleScene.XBOX_ORIGINAL || designScene==ConsoleScene.PS5) UiText.uppercase(title) else title
        playStationCaption.text=if(designScene==ConsoleScene.PS5) selectedCard?.name ?: "" else "Xbox Cloud Gaming"
        if(designScene.playStation && headerTitle.layoutParams!=null) {
            val p=headerTitle.layoutParams
            val unit=if(playStationMobile) resources.displayMetrics.scaledDensity else scale
            fitConsoleText(headerTitle,p.width,p.height,(if(playStationMobile) 16 else if(designScene==ConsoleScene.PS4) 22 else 26)*unit,(if(playStationMobile) 28 else if(designScene==ConsoleScene.PS4) 34 else 52)*unit,if(playStationMobile) 4 else 3)
        }
    }
    /** Recompose the existing catalog; account data, stable IDs and adapter are retained. */
    internal fun refreshAppearance() {
        val next=DashboardTheme.palette.theme.scene
        playStationBrowseTab.visibility=if(next.playStation) VISIBLE else GONE
        if(!next.playStation) playStationMobile=false
        val focused=grid.hasFocus();val position=positions[selectedCard?.key] ?: 0
        val changed=designScene!=next
        if(changed) { ++focusRequest;carouselTarget=null;originalBrowsing=next==ConsoleScene.XBOX_ORIGINAL && focused }
        designScene=next
        seriesPreferences=seriesStore.read()
        val console=next!=ConsoleScene.NONE
        accountName.visibility=VISIBLE;gamertag.visibility=VISIBLE;connected.visibility=VISIBLE;footer.visibility=VISIBLE
        subscription.visibility=VISIBLE;remainingTime.visibility=VISIBLE;subscription.maxLines=1;remainingTime.maxLines=1
        launchButton.text=UiText.text("JOGAR")
        for(i in 0 until footer.childCount step 2) (footer.getChildAt(i) as TextView).let { it.text=it.tag.toString() }
        for(i in 0 until footer.childCount) footer.getChildAt(i).isClickable=!next.playStation
        settingsButton.text="⚙";settingsButton.setCompoundDrawables(null,null,null,null);settingsButton.setPadding(0,0,0,0);settingsButton.gravity=settingsDefaultGravity
        if(!next.playStation) settingsButton.background=settingsDefaultBackground
        val toolsParent: FrameLayout=if(next.playStation) this else profilePanel
        if(settingsButton.parent!==toolsParent) { (settingsButton.parent as? ViewGroup)?.removeView(settingsButton);toolsParent.addView(settingsButton) }
        val benefitParent=if(next.playStation) playStationBenefits else profilePanel
        for(view in listOf(subscription,remainingTime)) if(view.parent!==benefitParent) { (view.parent as? ViewGroup)?.removeView(view);benefitParent.addView(view) }
        if(!next.playStation) profilePanel.bringChildToFront(settingsButton)
        listOf(playStationBenefits,playStationFavorite,playStationFriends).forEach { it.visibility=if(next.playStation) VISIBLE else GONE }
        listOf(playStationFeature,playStationDescription,playStationLibrary,playStationAllGames,playStationCaption).forEach { it.visibility=if(next==ConsoleScene.PS5) VISIBLE else GONE }
        listOf(consoleBrand,sectionTitle,launchButton,consoleTabRow).forEach { it.visibility=if(console) VISIBLE else GONE }
        consoleBack.visibility=GONE;shortcuts.visibility=GONE
        heroBackdrop.visibility=INVISIBLE
        consoleBackdrop.visibility=VISIBLE
        dashboardClock.visibility=if(next==ConsoleScene.XBOX_SERIES || next.playStation) VISIBLE else GONE
        if(next!=ConsoleScene.XBOX_SERIES) shortcutSkins.forEach { it.clear() }
        metadataNote.background=null;metadataNote.setPadding(0,0,0,0);metadataNote.maxLines=1
        if(next!=ConsoleScene.XBOX_SERIES && next!=ConsoleScene.PS5) { posters.cancel(heroBackdrop);consoleBackdrop.setArtwork(null) }
        tabRow.visibility=if(console) GONE else VISIBLE
        val parent=if(console) consoleTabRow else tabRow
        tabs.forEachIndexed { i,view ->
            view.visibility=VISIBLE
            if(android.os.Build.VERSION.SDK_INT>=26) view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            if(view.parent!==parent) { (view.parent as? ViewGroup)?.removeView(view);parent.addView(view) }
            view.rotation=0f;view.gravity=Gravity.CENTER;view.maxLines=1;view.setPadding(0,0,0,0);view.letterSpacing=0f
            view.nextFocusLeftId=View.NO_ID;view.nextFocusRightId=View.NO_ID;view.nextFocusUpId=profilePanel.id;view.nextFocusDownId=grid.id
            view.text=LibraryTab.entries[i].label;view.setCompoundDrawables(null,null,null,null)
            if(next.playStation) view.setTextColor(playStationControlColors(next)) else view.setTextColor(if(console) consoleTextColor() else Color.WHITE)
            view.typeface=if(console) Typeface.create(if(next==ConsoleScene.XBOX_ORIGINAL) "sans-serif-condensed" else "sans-serif-medium",Typeface.NORMAL) else DashboardTheme.font(context)
            view.background=if(console) ConsoleControlPlate(next,ConsoleControl.TAB,i==activeTab.ordinal,seriesGlyphs[i]) else tabPlates[i]
            if(!console) view.layoutParams=LinearLayout.LayoutParams(0,-1,when(LibraryTab.entries[i]) {
                LibraryTab.RECENT -> 1.65f;LibraryTab.ADDED -> 1.35f;LibraryTab.ALL -> 1.16f;LibraryTab.PURCHASED -> 1.95f;else -> 1f
            })
        }
        listOf(headerTitle,accountName,gamertag,subscription,remainingTime,metadataNote,catalogNote,emptyState).forEach { view ->
            view.typeface=if(console) Typeface.create(if(next==ConsoleScene.XBOX_ORIGINAL) "sans-serif-condensed" else "sans-serif-medium",Typeface.NORMAL) else DashboardTheme.font(context,view!==metadataNote && view!==catalogNote && view!==emptyState)
            view.setTextColor(if(console) consoleTextColor() else if(view===gamertag || view===remainingTime || view===metadataNote || view===catalogNote) DashboardTheme.muted else if(view===emptyState) DashboardTheme.color(UiColor.CATALOG_NOTICE) else Color.WHITE)
        }
        if(android.os.Build.VERSION.SDK_INT>=26) headerTitle.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        headerTitle.maxLines=2
        headerIcon.scaleType=if(next==ConsoleScene.XBOX_360 || next==ConsoleScene.XBOX_ORIGINAL) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        headerIcon.clipToOutline=next==ConsoleScene.NONE
        headerIcon.background=if(console) null else DashboardTheme.shape(UiColor.HEADER_ICON,8f)
        consoleBrand.refresh();footer.alpha=1f
        sectionTitle.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);sectionTitle.setTextColor(consoleTextColor())
        launchButton.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        launchButton.background=ConsoleControlPlate(next,ConsoleControl.ACTION,true)
        launchButton.setTextColor(if(next==ConsoleScene.XBOX_360) 0xff263024.toInt() else Color.WHITE)
        if(next.playStation) launchButton.setTextColor(playStationControlColors(next))
        consoleBack.background=ConsoleControlPlate(next,ConsoleControl.ACTION,false)
        consoleBack.setTextColor(if(next==ConsoleScene.XBOX_ORIGINAL) 0xff142400.toInt() else Color.WHITE)
        quickActions.forEachIndexed { i,it ->
            it.visibility=VISIBLE
            it.text=when(i) { 0 -> LibraryTab.ALL.label;1 -> LibraryTab.GAME_PASS.label;2 -> LibraryTab.PURCHASED.label;else -> UiText.text("Configurações") }
            it.background=if(next==ConsoleScene.XBOX_SERIES) shortcutSkins[i] else if(next==ConsoleScene.XBOX_360_METRO) MetroShortcutPlate(i) else ConsoleControlPlate(next,ConsoleControl.QUICK,false)
            it.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);it.setTextColor(Color.WHITE)
            it.setCompoundDrawables(null,null,null,null);it.setLineSpacing(0f,1f);it.contentDescription=null
        }
        configureCardLayout()
        if(console) {
            for(i in 0 until footer.childCount) (footer.getChildAt(i) as TextView).apply { setTextColor(consoleTextColor());typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
        } else {
            for(i in 0 until footer.childCount) (footer.getChildAt(i) as TextView).apply {
                setTextColor(Color.WHITE);typeface=DashboardTheme.font(context,false)
                if(i%2==0) background=rounded(Color.TRANSPARENT,if(tag=="☰") 6f else 100f,Color.WHITE)
            }
        }
        val selected=selectedCard;selectedCard=null;updateSelected(selected)
        renderProfile();updateTabs();renderCatalogState(adapter.itemCount,isCatalogUpdating);lastGeometry=0L;requestLayout()
        adapter.notifyItemRangeChanged(0,adapter.itemCount,"skin")
        if(changed && focused) grid.post { if(!closed) focusCoverNow(position) }
        consoleBackdrop.refreshStructure()
    }
    private fun configureCardLayout() {
        val manager=grid.layoutManager as GridLayoutManager
        cardHorizontal=(designScene==ConsoleScene.XBOX_SERIES && !seriesGrid) || designScene==ConsoleScene.XBOX_360_NXE || designScene.playStation
        cardColumns=if(seriesGrid) 6 else when(designScene) {
            ConsoleScene.XBOX_360,ConsoleScene.XBOX_ORIGINAL,ConsoleScene.XBOX_SERIES,ConsoleScene.XBOX_360_NXE,ConsoleScene.PS4,ConsoleScene.PS5 -> 1
            ConsoleScene.XBOX_ONE,ConsoleScene.XBOX_360_METRO -> 3;else -> COLUMNS
        }
        grid.stopScroll();manager.orientation=if(cardHorizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL;manager.spanCount=cardColumns
        grid.setPadding(0,0,0,if(seriesGrid) (6*scale).roundToInt() else 0)
        manager.spanSizeLookup=object: GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int)=if(designScene==ConsoleScene.XBOX_360_METRO && position%5==0) 2 else 1
        }
    }
    private fun refreshSeriesPreferences() {
        val wasGrid=seriesGrid;val wasGallery=seriesPreferences.gallery;val position=positions[selectedCard?.key] ?: 0
        seriesPreferences=seriesStore.read()
        if(designScene!=ConsoleScene.XBOX_SERIES) return
        if(wasGallery!=seriesPreferences.gallery) selectedCard?.let(::loadHeaderArtwork)
        renderProfile()
        if(wasGrid!=seriesGrid) {
            val focused=grid.hasFocus();++focusRequest;carouselTarget=null
            configureCardLayout();lastGeometry=0L;requestLayout();adapter.notifyDataSetChanged()
            if(focused) grid.post { if(!closed) focusCoverAt(position) }
        } else adapter.notifyItemRangeChanged(0,adapter.itemCount,"skin")
    }
    fun wallpaperChoices()=SeriesWallpaperGallery.fromGames(games,
        listOfNotNull(selectedGame)+games.asSequence().filter { library.isFavorite(it.titleId) }.take(20).toList())
    fun profileSnapshot()=profile
    private fun showPlayStationAccount() {
        val dialog=ConsoleDialog(context,UiText.text("Conta e amigos"),ConsolePanelPurpose.PROFILE)
        listOf(profile.displayName,profile.gamertag,subscription.text.toString(),cloudPlaytime.label).distinct().forEachIndexed { index,value ->
            dialog.body.addView(dialog.text(value,if(index==0) 28 else 20),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dialog.px(18) })
        }
        onFriends?.let { action -> dialog.addMenuItem(UiText.text("Amigos"),action) }
        dialog.addMenuItem(UiText.text("Configurações"),onOptions)
        dialog.addAction(UiText.text("Voltar")) { restoreFocus() }
        dialog.setOnCancelListener { restoreFocus() };dialog.show()
    }
    private fun showPlayStationOptions() {
        ConsoleDialogs.menu(context,selectedCard?.name ?: UiText.text("Jogos"),listOf(
            ConsoleAction(UiText.text("FAVORITAR")) { toggleSelectedFavorite() },
            ConsoleAction(UiText.text("Controles e toque")) { onThemeSettings(SettingsPage.CONTROLS) },
            ConsoleAction(UiText.text("Vídeo e conexão")) { onThemeSettings(SettingsPage.VIDEO) },
            ConsoleAction(UiText.text("Conta e amigos")) { showPlayStationAccount() }
        )) { restoreFocus() }
    }
    private fun openGuideOrOptions() {
        if(designScene.playStation) { showPlayStationAccount();return }
        if(!SeriesGuidePanel.enabled(context)) { onOptions();return }
        val quick=listOfNotNull(onFriends?.let { ConsoleAction(UiText.text("Amigos"),it) },
            selectedGame?.takeIf { onGameShortcut!=null }?.let { game -> ConsoleAction(UiText.text("Atalho na tela inicial")) { onGameShortcut?.invoke(game) } })
        SeriesGuidePanel.show(context,UiText.text("Guia"),profile,quick+listOf(
            ConsoleAction(LibraryTab.RECENT.label) { selectTab(LibraryTab.RECENT);focusFirstCover() },
            ConsoleAction(LibraryTab.ALL.label) { selectTab(LibraryTab.ALL);focusFirstCover() },
            ConsoleAction(LibraryTab.FAVORITES.label) { selectTab(LibraryTab.FAVORITES);focusFirstCover() },
            ConsoleAction(UiText.text("Pesquisar jogos"),onSearch),
            ConsoleAction(UiText.text("Personalizar Series X|S")) { SeriesPersonalizationPanel(context,::wallpaperChoices,onOptions).show() },
            ConsoleAction(UiText.text("Configurações"),onOptions),
            ConsoleAction(UiText.text("Sair"),onExit)
        ))
    }
    private fun playStationControlColors(@Suppress("UNUSED_PARAMETER") scene: ConsoleScene)=android.content.res.ColorStateList.valueOf(Color.WHITE)

    /** Native layout measured against the supplied 1280x720 home screenshots. */
    private fun layoutPlayStation(w: Int,h: Int) {
        playStationMobile=consolePhone(context,w,h)
        if(playStationMobile) { layoutPlayStationPhone(w,h);return }
        val portrait=h>w;val ps5=designScene==ConsoleScene.PS5
        scale=if(portrait) w/600f else minOf(w/1280f,h/720f)
        val s=scale;val wide=w/s;val tall=h/s;val edge=if(portrait) 28f else if(ps5) 54f else 60f
        val touch=maxOf(48f*resources.displayMetrics.density/s,48f)
        val compact=!portrait && touch>80f
        fun pos(v: View,x: Float,y: Float,width: Float,height: Float)=place(v,x*s,y*s,width*s,height*s)
        fun text(v: TextView,size: Float)=textSize(v,size)
        fun icon(v: TextView,glyph: ConsoleGlyph,card: Boolean=false) {
            v.text="";v.background=ConsoleControlPlate(designScene,if(card) ConsoleControl.QUICK else ConsoleControl.TAB,icon=if(card) glyph else null)
            v.setCompoundDrawables(null,null,null,null);v.setPadding(0,0,0,0)
            if(!card) {
                v.setCompoundDrawablesWithIntrinsicBounds(null,ConsoleIconDrawable(glyph,(25*s).roundToInt(),android.content.res.ColorStateList.valueOf(Color.WHITE)),null,null)
                v.setPadding(0,((touch-25)*s/2).roundToInt(),0,0);v.gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
        listOf(sectionTitle,consoleBack,headerIcon,metadataNote,lb,rb).forEach { it.visibility=GONE }
        listOf(profilePanel,navigation,consoleTabRow,grid,headerTitle,playStationBenefits,playStationFavorite,searchButton,settingsButton,consoleBrand,playStationFeature,playStationCaption).forEach { it.visibility=VISIBLE }
        playStationFriends.visibility=if(onFriends!=null && !ps5) VISIBLE else GONE
        listOf(playStationLibrary,playStationAllGames,playStationDescription).forEach { it.visibility=if(ps5) VISIBLE else GONE }
        connected.visibility=GONE;dashboardClock.visibility=VISIBLE
        listOf(playStationFeature,playStationCaption,playStationDescription,playStationQuotaValue,subscription,remainingTime,dashboardClock).forEach { it.typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
        val profileWidth=if(ps5 && !portrait) maxOf(touch,54f) else if(portrait) 220f else 144f
        val profileX=if(portrait) wide-profileWidth-edge else if(ps5) wide-214f else wide-304f
        val utilitiesY=if(portrait) 110f else 24f
        pos(profilePanel,profileX,if(portrait) 28f else 32f,profileWidth,touch)
        pos(avatar,0f,6f,36f,36f);pos(avatarLetter,0f,6f,36f,36f);text(avatarLetter,16f)
        pos(accountName,45f,7f,profileWidth-45f,20f);text(accountName,12f)
        pos(gamertag,45f,28f,profileWidth-45f,18f);text(gamertag,10f)
        accountName.visibility=if(ps5 && !portrait) GONE else VISIBLE;gamertag.visibility=accountName.visibility
        val searchSide=maxOf(touch,if(searchButton.phoneTarget) 76f/s else 58f)
        val settingsX=if(portrait) edge+searchSide+16f else if(ps5) wide-287f else wide-386f
        pos(settingsButton,settingsX,utilitiesY,touch,touch);icon(settingsButton,ConsoleGlyph.SETTINGS)
        val searchX=if(portrait) edge else if(ps5) settingsX-searchSide-6f else settingsX-searchSide-touch-22f
        pos(searchButton,searchX,if(!portrait && !compact) 51f-searchSide/2f else utilitiesY,searchSide,searchSide)
        pos(playStationFriends,if(portrait) settingsX+touch+16f else settingsX-touch-14f,utilitiesY,touch,touch);icon(playStationFriends,ConsoleGlyph.ACCOUNT)
        pos(dashboardClock,wide-154f,if(portrait) 126f else 39f,130f,36f);text(dashboardClock,22f)
        pos(consoleBrand,if(ps5 && !portrait) wide-441f else edge,if(portrait) 26f else 30f,if(ps5) 76f else 105f,if(ps5) 32f else 38f)
        val navTop=if(portrait) { if(ps5) 220f else 292f } else if(ps5) 23f else 126f
        val navHeight=maxOf(54f,touch)
        val navWidth=if(portrait) wide-2*edge else maxOf(540f,4*touch+27f)
        pos(navigation,edge,navTop,navWidth,navHeight);pos(consoleTabRow,0f,0f,navWidth,navHeight)
        tabs.forEachIndexed { index,v -> v.visibility=if(LibraryTab.entries[index] in playStationTabs) VISIBLE else GONE }
        val controls=listOf(tabs[LibraryTab.RECENT.ordinal],tabs[LibraryTab.ALL.ordinal],playStationBrowseTab,tabs[LibraryTab.FAVORITES.ordinal])
        val weights=if(ps5) floatArrayOf(82f,170f,136f,132f) else floatArrayOf(134f,158f,112f,112f)
        var x=0f
        controls.forEachIndexed { i,v ->
            val tw=navWidth*weights[i]/weights.sum()
            pos(v,x,0f,tw-9f,navHeight);x+=tw
            v.text=when(i) { 0 -> if(ps5) UiText.text("Jogos") else UiText.text("Voltar a jogar");1 -> titleCase(LibraryTab.ALL.label);2 -> UiText.text("Biblioteca");else -> titleCase(LibraryTab.FAVORITES.label) }
            v.gravity=Gravity.CENTER_VERTICAL or Gravity.START;v.setPadding(0,0,0,0);v.setCompoundDrawables(null,null,null,null)
            v.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
            fitConsoleText(v,((tw-9)*s).toInt(),(navHeight*s).toInt(),16*s,(if(ps5) 23 else 18)*s,1)
            v.nextFocusUpId=profilePanel.id;v.nextFocusDownId=grid.id
            v.nextFocusLeftId=if(i>0) controls[i-1].id else searchButton.id
            v.nextFocusRightId=if(i<3) controls[i+1].id else settingsButton.id
            val selected=if(i==2) activeTab !in playStationTabs else activeTab==when(i) { 0 -> LibraryTab.RECENT;1 -> LibraryTab.ALL;else -> LibraryTab.FAVORITES }
            v.background=ConsoleControlPlate(designScene,ConsoleControl.TAB,selected)
            v.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(Color.WHITE,if(selected || !ps5) Color.WHITE else 0xffa4b5c6.toInt())))
        }
        playStationBrowseTab.visibility=VISIBLE
        val rowTop=if(portrait) navTop+navHeight+24f else if(ps5) { if(compact) maxOf(92f,utilitiesY+searchSide+10f) else 92f } else maxOf(197f,navTop+navHeight+17f)
        val band=if(ps5) maxOf(132f,touch+28f) else if(compact || !portrait && s<.8f) maxOf(230f,touch+70f) else 280f
        coverHeight=(band*s).roundToInt()
        val switcherOffset=if(ps5) maxOf(85f,touch+15f) else 0f
        pos(playStationLibrary,edge,rowTop+19f,maxOf(70f,touch),maxOf(70f,touch));icon(playStationLibrary,ConsoleGlyph.CLOUD,true)
        playStationLibrary.contentDescription=if(ps5) UiText.text("Jogos") else UiText.text("Biblioteca")
        val gridLeft=((edge+switcherOffset-8f)*s).roundToInt()
        val railWidth=if(ps5 && adapter.itemCount in 1..8) carouselWidth(true)+(adapter.itemCount-1)*carouselWidth(false) else wide
        val allX=if(ps5) minOf(edge+switcherOffset+railWidth-1f,wide-edge-maxOf(70f,touch)) else 0f
        val gridRight=if(ps5) ((allX-5f)*s).roundToInt().coerceAtMost(w) else w
        place(grid,gridLeft.toFloat(),rowTop*s,(gridRight-gridLeft).coerceAtLeast((touch*s).roundToInt()).toFloat(),band*s)
        pos(playStationAllGames,allX,rowTop+19f,maxOf(70f,touch),maxOf(70f,touch));icon(playStationAllGames,ConsoleGlyph.GRID,true)
        pos(emptyState,edge+switcherOffset,rowTop,wide-2*edge-switcherOffset,band);text(emptyState,22f)
        val titleTop=if(portrait) rowTop+band+52f else if(ps5) { if(compact) rowTop+band+18f else maxOf(300f,rowTop+band+60f) } else rowTop+band+2f
        pos(headerTitle,edge+if(ps5) 8f else 0f,titleTop,if(portrait) wide-2*edge else if(ps5) 620f else wide-2*edge-touch-20f,if(ps5 && !compact) 122f else 80f)
        val titleSize=if(portrait) 34f else if(ps5) { if((selectedCard?.name?.length ?: 0)>24) 48f else 52f } else 34f
        text(headerTitle,titleSize);headerTitle.typeface=Typeface.create(if(ps5) "sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        headerTitle.maxLines=2;headerTitle.gravity=Gravity.TOP or Gravity.START
        playStationFeature.text=if(ps5) "XBOX CLOUD GAMING" else UiText.uppercase(UiText.text("Biblioteca na nuvem"))
        playStationFeature.letterSpacing=if(ps5) .22f else .12f
        pos(playStationFeature,if(ps5) edge+8f else edge+116f,if(ps5) titleTop-29f else if(portrait) 72f else 40f,if(ps5) 600f else 290f,22f);text(playStationFeature,if(ps5) 11f else 10f)
        pos(playStationCaption,if(ps5) edge+switcherOffset else edge,if(ps5) rowTop+118f else titleTop+90f,if(portrait) wide-2*edge else if(ps5) 400f else 650f,25f);text(playStationCaption,if(ps5) 11f else 12f)
        pos(playStationDescription,edge+8f,titleTop+125f,if(portrait) wide-2*edge else 450f,44f);text(playStationDescription,14f)
        if(compact) playStationDescription.visibility=GONE
        val launchTop=if(ps5) titleTop+if(compact) 100f else 192f else titleTop+98f
        launchButton.visibility=if(ps5 || portrait) VISIBLE else GONE
        launchButton.text=if(ps5) UiText.text("Jogar na nuvem") else UiText.text("JOGAR")
        pos(launchButton,edge+if(ps5) 8f else 0f,launchTop,if(portrait) 270f else 254f,maxOf(48f,touch));text(launchButton,17f)
        if(ps5) launchButton.setTextColor(0xff15171c.toInt())
        launchButton.nextFocusUpId=grid.id
        pos(playStationFavorite,if(ps5) edge+276f else wide-edge-touch,if(ps5 || portrait) launchTop else titleTop+14f,touch,touch)
        if(ps5) {
            playStationFavorite.contentDescription=UiText.text("Opções: {0}",selectedCard?.name ?: UiText.text("Jogos"))
            playStationFavorite.setCompoundDrawables(null,null,null,null);playStationFavorite.text="•••";text(playStationFavorite,21f)
            playStationFavorite.gravity=Gravity.CENTER;playStationFavorite.setPadding(0,0,0,0)
            playStationFavorite.background=android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),rounded(0x88434b58.toInt(),1000f,Color.WHITE))
                addState(intArrayOf(),rounded(0x55171b21,1000f,0x55ffffff))
            }
            playStationFavorite.setTextColor(Color.WHITE)
        } else { playStationFavorite.contentDescription=UiText.text("FAVORITAR");icon(playStationFavorite,ConsoleGlyph.FAVORITE) }
        playStationFavorite.nextFocusUpId=grid.id
        playStationLibrary.nextFocusRightId=grid.id;playStationLibrary.nextFocusUpId=categoryFocus().id;playStationLibrary.nextFocusDownId=launchButton.id
        playStationAllGames.nextFocusRightId=playStationAllGames.id;playStationLibrary.nextFocusLeftId=playStationLibrary.id
        playStationAllGames.nextFocusLeftId=grid.id;playStationAllGames.nextFocusUpId=categoryFocus().id;playStationAllGames.nextFocusDownId=launchButton.id
        shortcuts.visibility=if(ps5) VISIBLE else GONE
        playStationBenefits.isFocusable=ps5;playStationBenefits.isClickable=ps5;playStationBenefits.clipChildren=false
        playStationQuotaValue.visibility=VISIBLE;remainingTime.visibility=VISIBLE;subscription.visibility=VISIBLE
        subscription.maxLines=1;remainingTime.maxLines=if(ps5) 2 else 1
        subscription.refreshBadge(s);remainingTime.refreshBadge(s)
        if(ps5) {
            playStationAccountTitle.visibility=GONE
            val quickY=maxOf(launchTop+touch+42f,if(portrait) tall-285f else tall-128f)
            val available=wide-2*(edge+8f);val gap=16f;val content=available-2*gap
            val first=if(portrait) available/2-gap/2 else content*.375f
            val second=if(portrait) first else content*.3125f
            val third=if(portrait) available else content*.3125f
            val quickHeight=maxOf(102f,touch)
            pos(shortcuts,edge+8f,quickY,available,quickHeight)
            quickActions.forEachIndexed { i,v -> v.visibility=if(i==0 || i==3) VISIBLE else GONE }
            listOf(quickActions[0],quickActions[3]).forEachIndexed { i,v ->
                pos(v,if(i==0) 0f else first+gap,0f,if(i==0) first else second,quickHeight)
                val eyebrow=UiText.uppercase(if(i==0) UiText.text("Acesso rápido") else UiText.text("Imagem e tela"))
                val title=if(i==0) UiText.text("Controles e partida") else UiText.text("Veja cada detalhe")
                v.text=SpannableString("$eyebrow\n$title").apply {
                    setSpan(android.text.style.AbsoluteSizeSpan((10*s).roundToInt()),0,eyebrow.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(0xffb6bcc6.toInt()),0,eyebrow.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                v.contentDescription=if(i==0) UiText.text("Controles e toque") else UiText.text("Vídeo e conexão")
                v.setPadding((24*s).roundToInt(),0,(24*s).roundToInt(),0);v.gravity=Gravity.CENTER_VERTICAL or Gravity.START;v.maxLines=3
                v.typeface=Typeface.create("sans-serif",Typeface.NORMAL);text(v,16f);v.setLineSpacing(9*s,1f)
                v.setCompoundDrawablesWithIntrinsicBounds(null,null,ConsoleIconDrawable(if(i==0) ConsoleGlyph.CONTROLLER else ConsoleGlyph.IMAGE,(29*s).roundToInt(),android.content.res.ColorStateList.valueOf(Color.WHITE)),null)
                v.background=ConsoleControlPlate(designScene,ConsoleControl.QUICK)
                v.nextFocusUpId=launchButton.id;v.nextFocusRightId=if(i==0) quickActions[3].id else playStationBenefits.id
            }
            pos(playStationBenefits,if(portrait) edge+8f else edge+8f+first+gap+second+gap,if(portrait) quickY+quickHeight+12f else quickY,third,quickHeight)
            playStationBenefits.background=ConsoleControlPlate(designScene,ConsoleControl.QUICK)
            pos(subscription,24f,15f,third-48f,22f);text(subscription,11f)
            pos(remainingTime,24f,40f,if(portrait) third-190f else 156f,49f);text(remainingTime,15f)
            pos(playStationQuotaValue,third-160f,25f,140f,58f);text(playStationQuotaValue,44f);playStationQuotaValue.setTextColor(0xffe4d2a3.toInt())
            if(android.os.Build.VERSION.SDK_INT>=26) playStationQuotaValue.setAutoSizeTextTypeUniformWithConfiguration((20*s).roundToInt().coerceAtLeast(1),(44*s).roundToInt().coerceAtLeast(2),1,TypedValue.COMPLEX_UNIT_PX)
            playStationBenefits.nextFocusLeftId=quickActions[3].id;playStationBenefits.nextFocusUpId=launchButton.id
            launchButton.nextFocusDownId=quickActions[0].id;playStationFavorite.nextFocusDownId=quickActions[3].id
            if(!portrait && quickY+quickHeight>tall-12f) {
                shortcuts.visibility=GONE;playStationBenefits.visibility=GONE
                launchButton.nextFocusDownId=settingsButton.id;playStationFavorite.nextFocusDownId=settingsButton.id
            }
        } else {
            playStationAccountTitle.visibility=if(portrait || compact) GONE else VISIBLE
            playStationAccountTitle.text=UiText.text("Tempo restante de nuvem");playStationAccountTitle.maxLines=2;playStationAccountTitle.gravity=Gravity.END or Gravity.CENTER_VERTICAL
            playStationBenefits.background=rounded(0x14002178,2*s,0x33ffffff)
            pos(playStationBenefits,if(portrait) edge else wide-325f,if(portrait) 210f else 130f,if(portrait) wide-2*edge else 265f,if(portrait || compact) 62f else 44f)
            if(portrait || compact) {
                pos(subscription,10f,0f,220f,26f);pos(remainingTime,10f,29f,if(portrait) wide-2*edge-20f else 245f,26f)
                playStationQuotaValue.visibility=GONE;text(subscription,12f);text(remainingTime,12f)
            } else {
                pos(playStationAccountTitle,-110f,3f,96f,38f);text(playStationAccountTitle,10f)
                pos(subscription,12f,0f,165f,44f);text(subscription,11f)
                pos(playStationQuotaValue,177f,0f,82f,44f);text(playStationQuotaValue,24f);playStationQuotaValue.setTextColor(Color.WHITE)
                if(android.os.Build.VERSION.SDK_INT>=26) playStationQuotaValue.setAutoSizeTextTypeUniformWithConfiguration((12*s).roundToInt().coerceAtLeast(1),(24*s).roundToInt().coerceAtLeast(2),1,TypedValue.COMPLEX_UNIT_PX)
                remainingTime.visibility=GONE
            }
        }
        pos(catalogNote,wide-240f,tall-28f,200f,20f);text(catalogNote,12f)
        footer.visibility=if(ps5 && !portrait) GONE else VISIBLE
        pos(footer,edge,tall-43f,wide-2*edge-210f,24f)
        for(i in 0 until footer.childCount step 2) {
            val glyph=footer.getChildAt(i) as TextView;val label=footer.getChildAt(i+1) as TextView
            glyph.text=glyph.tag.toString();text(glyph,if(portrait) 10f else 12f);text(label,if(portrait) 10f else 12f)
            glyph.layoutParams=LinearLayout.LayoutParams(((if(portrait) 15 else 19)*s).roundToInt(),(19*s).roundToInt()).apply { marginEnd=(4*s).roundToInt() }
            glyph.background=rounded(Color.TRANSPARENT,20*s,0x77ffffff)
            label.layoutParams=LinearLayout.LayoutParams(-2,-2).apply { marginEnd=((if(portrait) 5 else 16)*s).roundToInt() }
        }
        if(portrait) { pos(footer,edge,tall-28f,wide-2*edge,22f);catalogNote.visibility=GONE }
        coverArtSize=ArtworkSize.forDisplay((220*s).roundToInt(),(220*s).roundToInt())
        selectedCard?.let(::loadHeaderArtwork);updateConsoleTitle();updatePlayStationQuota();consoleBackdrop.refreshStructure()
        emptyState.visibility=if(adapter.itemCount==0) VISIBLE else GONE
        adapter.notifyItemRangeChanged(0,adapter.itemCount,"skin")
    }

    private fun layoutPlayStationPhone(w: Int,h: Int) {
        val d=resources.displayMetrics.density;val sp=resources.displayMetrics.scaledDensity
        scale=d;val wide=w/d;val high=h/d;val portrait=h>w;val ps5=designScene==ConsoleScene.PS5
        fun pos(v: View,x: Float,y: Float,vw: Float,vh: Float)=place(v,x*d,y*d,vw*d,vh*d)
        fun label(v: TextView,size: Float) { v.typeface=Typeface.create("sans-serif",Typeface.NORMAL);v.setTextSize(TypedValue.COMPLEX_UNIT_PX,size*sp) }
        fun glyph(v: TextView,icon: ConsoleGlyph,card: Boolean=false) {
            v.text="";v.gravity=Gravity.CENTER;v.setPadding(0,0,0,0)
            v.background=ConsoleControlPlate(designScene,if(card) ConsoleControl.QUICK else ConsoleControl.TAB,icon=if(card) icon else null)
            if(card) v.setCompoundDrawables(null,null,null,null) else {
                val drawable=ConsoleIconDrawable(icon,(24*d).roundToInt(),android.content.res.ColorStateList.valueOf(Color.WHITE))
                v.setCompoundDrawablesWithIntrinsicBounds(null,drawable,null,null);v.gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;v.setPadding(0,(12*d).roundToInt(),0,0)
            }
        }
        listOf(sectionTitle,consoleBack,headerIcon,metadataNote,lb,rb,dashboardClock,footer,catalogNote,playStationFriends,playStationAccountTitle,playStationDescription).forEach { it.visibility=GONE }
        listOf(profilePanel,searchButton,settingsButton,navigation,consoleTabRow,grid,headerTitle,playStationFeature,playStationCaption,launchButton,playStationFavorite,consoleBrand).forEach { it.visibility=VISIBLE }
        accountName.visibility=GONE;gamertag.visibility=GONE;connected.visibility=GONE
        pos(profilePanel,wide-64,4f,48f,48f);pos(avatar,6f,6f,36f,36f);pos(avatarLetter,6f,6f,36f,36f);label(avatarLetter,16f)
        pos(settingsButton,wide-112,4f,48f,48f);glyph(settingsButton,ConsoleGlyph.SETTINGS)
        pos(searchButton,wide-160,4f,48f,48f)
        pos(consoleBrand,if(portrait || !ps5) 16f else wide-252,12f,84f,28f)
        val navX=if(portrait || ps5) 16f else 112f
        val navY=if(portrait) 64f else 4f
        val navW=if(portrait) wide-32 else if(ps5) wide-280 else wide-navX-168
        pos(navigation,navX,navY,navW,48f);pos(consoleTabRow,0f,0f,navW,48f)
        tabs.forEachIndexed { i,v -> v.visibility=if(LibraryTab.entries[i] in playStationTabs) VISIBLE else GONE }
        val controls=listOf(tabs[LibraryTab.RECENT.ordinal],tabs[LibraryTab.ALL.ordinal],playStationBrowseTab,tabs[LibraryTab.FAVORITES.ordinal])
        controls.forEachIndexed { i,v ->
            pos(v,i*navW/4,0f,navW/4-4f,48f);v.gravity=Gravity.CENTER;v.setPadding((2*d).roundToInt(),0,(2*d).roundToInt(),0);label(v,14f)
            v.text=when(i) { 0 -> if(ps5) UiText.text("Jogos") else UiText.text("Voltar a jogar");1 -> titleCase(LibraryTab.ALL.label);2 -> UiText.text("Biblioteca");else -> titleCase(LibraryTab.FAVORITES.label) }
            v.setCompoundDrawables(null,null,null,null)
            fitConsoleText(v,(navW/4*d).toInt(),(48*d).toInt(),12*sp,14*sp,2)
            val selected=if(i==2) activeTab !in playStationTabs else activeTab==when(i) { 0 -> LibraryTab.RECENT;1 -> LibraryTab.ALL;else -> LibraryTab.FAVORITES }
            v.background=ConsoleControlPlate(designScene,ConsoleControl.TAB,selected)
            v.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(Color.WHITE,if(selected || !ps5) Color.WHITE else 0xffa4b5c6.toInt())))
            v.nextFocusLeftId=if(i>0) controls[i-1].id else v.id;v.nextFocusRightId=if(i<3) controls[i+1].id else settingsButton.id
            v.nextFocusUpId=searchButton.id;v.nextFocusDownId=grid.id
        }
        playStationBrowseTab.visibility=VISIBLE
        val railY=if(portrait) 124f else 64f
        val railH=if(ps5) { if(portrait) 104f else 64f } else if(portrait) 156f else 124f
        coverHeight=(railH*d).roundToInt()
        val left=if(ps5) 84f else 8f
        val side=if(portrait) 56f else 48f
        val total=carouselWidth(true)+(adapter.itemCount-1).coerceAtLeast(0)*carouselWidth(false)
        val allX=minOf(left+total+8f,wide-16-side)
        playStationLibrary.visibility=if(ps5) VISIBLE else GONE;playStationAllGames.visibility=playStationLibrary.visibility
        pos(playStationLibrary,16f,railY+8f,side,side);glyph(playStationLibrary,ConsoleGlyph.CLOUD,true)
        pos(playStationAllGames,allX,railY+8f,side,side);glyph(playStationAllGames,ConsoleGlyph.GRID,true)
        val right=if(ps5) allX-8 else wide-8
        pos(grid,left,railY,(right-left).coerceAtLeast(80f),railH)
        pos(emptyState,left+8,railY,(right-left-16).coerceAtLeast(64f),railH);label(emptyState,16f)
        playStationLibrary.nextFocusRightId=grid.id;playStationLibrary.nextFocusLeftId=playStationLibrary.id
        playStationAllGames.nextFocusLeftId=grid.id;playStationAllGames.nextFocusRightId=playStationAllGames.id
        playStationLibrary.nextFocusUpId=categoryFocus().id;playStationAllGames.nextFocusUpId=categoryFocus().id
        val captionY=railY+railH+4f
        playStationCaption.visibility=if(ps5) VISIBLE else GONE
        val captionW=if(portrait) wide-left-24 else (wide-256-left).coerceAtLeast(120f)
        pos(playStationCaption,left+8,captionY,captionW,20f);label(playStationCaption,12f)
        val featureY=if(ps5) captionY+32 else railY+railH+12
        playStationFeature.text="XBOX CLOUD GAMING";playStationFeature.letterSpacing=.14f
        pos(playStationFeature,16f,featureY,wide-32,16f);label(playStationFeature,10f)
        val titleY=featureY+26
        val compactCards=ps5 && !portrait && high>=336f && sp/d<=1.15f
        val titleH=if(portrait) (high-568f).coerceIn(76f,112f) else (high-titleY-if(compactCards) 72 else 16).coerceAtLeast(72f)
        val titleW=if(portrait) wide-32 else wide-256
        pos(headerTitle,16f,titleY,titleW,titleH);headerTitle.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);headerTitle.gravity=Gravity.TOP or Gravity.START
        fitConsoleText(headerTitle,(titleW*d).toInt(),(titleH*d).toInt(),16*sp,(if(portrait) 28 else 26)*sp,4)
        val descH=if(portrait && ps5 && high>=680) 44f else 0f
        if(descH>0) { playStationDescription.visibility=VISIBLE;pos(playStationDescription,16f,titleY+titleH+12,wide-32,descH);label(playStationDescription,14f) }
        val actionY=if(portrait) titleY+titleH+if(descH>0) descH+24 else 16f else titleY
        val actionX=if(portrait) 16f else wide-224
        pos(launchButton,actionX,actionY,if(portrait) wide-104 else 144f,48f);label(launchButton,16f)
        launchButton.text=if(ps5) UiText.text("Jogar na nuvem") else UiText.text("JOGAR")
        launchButton.setTextColor(if(ps5) 0xff15171c.toInt() else Color.WHITE)
        pos(playStationFavorite,wide-72,actionY,48f,48f)
        if(ps5) {
            playStationFavorite.setCompoundDrawables(null,null,null,null);playStationFavorite.text="•••";playStationFavorite.gravity=Gravity.CENTER;playStationFavorite.setPadding(0,0,0,0);label(playStationFavorite,20f)
            playStationFavorite.background=ConsoleControlPlate(designScene,ConsoleControl.QUICK);playStationFavorite.setTextColor(Color.WHITE)
            playStationFavorite.contentDescription=UiText.text("Opções: {0}",selectedCard?.name ?: UiText.text("Jogos"))
        } else { glyph(playStationFavorite,ConsoleGlyph.FAVORITE);playStationFavorite.contentDescription=UiText.text("FAVORITAR") }
        launchButton.nextFocusUpId=grid.id;playStationFavorite.nextFocusUpId=grid.id
        val quickY=actionY+64
        val showQuick=compactCards || portrait && ps5 && quickY+164<=high-12
        shortcuts.visibility=if(showQuick) VISIBLE else GONE
        if(showQuick) {
            pos(shortcuts,16f,if(compactCards) high-64 else quickY,wide-32,if(compactCards) 56f else 76f)
            quickActions.forEachIndexed { i,v -> v.visibility=if(i==0 || i==3) VISIBLE else GONE }
            listOf(quickActions[0],quickActions[3]).forEachIndexed { i,v ->
                val cardWidth=if(compactCards) (wide-64)/3 else (wide-48)/2
                pos(v,i*(cardWidth+16),0f,cardWidth,if(compactCards) 56f else 76f);label(v,14f)
                v.text=if(i==0) UiText.text("Controles e partida") else UiText.text("Veja cada detalhe")
                v.contentDescription=if(i==0) UiText.text("Controles e toque") else UiText.text("Vídeo e conexão")
                v.setPadding((12*d).toInt(),(8*d).toInt(),(12*d).toInt(),(8*d).toInt());v.setCompoundDrawables(null,null,null,null);v.setLineSpacing(0f,1f);v.maxLines=2
                v.background=ConsoleControlPlate(designScene,ConsoleControl.QUICK);v.nextFocusUpId=launchButton.id
            }
            launchButton.nextFocusDownId=quickActions[0].id;playStationFavorite.nextFocusDownId=quickActions[3].id
        } else { launchButton.nextFocusDownId=settingsButton.id;playStationFavorite.nextFocusDownId=settingsButton.id }
        val benefitsY=if(portrait) quickY+if(showQuick) 88 else 0 else actionY+64
        val showBenefits=compactCards || if(portrait) benefitsY+76<=high-12 else benefitsY+76<=high-8
        playStationBenefits.visibility=if(showBenefits) VISIBLE else GONE
        if(showBenefits) {
            val width=if(compactCards) (wide-64)/3 else if(portrait) wide-32 else 208f
            pos(playStationBenefits,if(compactCards) wide-16-width else if(portrait) 16f else wide-224,if(compactCards) high-64 else benefitsY,width,if(compactCards) 56f else 76f)
            playStationBenefits.background=ConsoleControlPlate(designScene,ConsoleControl.QUICK);playStationBenefits.isFocusable=true;playStationBenefits.isClickable=true
            listOf(subscription,remainingTime,playStationQuotaValue).forEach { it.visibility=VISIBLE;it.typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
            subscription.refreshBadge(d);remainingTime.refreshBadge(d)
            pos(subscription,12f,6f,width-24,20f);label(subscription,12f)
            pos(remainingTime,12f,29f,(width-140).coerceAtLeast(80f),42f);label(remainingTime,14f);remainingTime.maxLines=2
            pos(playStationQuotaValue,width-116,28f,104f,42f);label(playStationQuotaValue,28f)
            playStationQuotaValue.setTextColor(if(ps5) 0xffe4d2a3.toInt() else Color.WHITE)
            fitConsoleText(playStationQuotaValue,(104*d).toInt(),(42*d).toInt(),18*sp,28*sp,1)
            if(compactCards) {
                remainingTime.visibility=GONE
                pos(playStationQuotaValue,12f,26f,width-24,26f)
                fitConsoleText(playStationQuotaValue,((width-24)*d).toInt(),(26*d).toInt(),16*sp,20*sp,1)
                quickActions[0].nextFocusRightId=quickActions[3].id;quickActions[3].nextFocusLeftId=quickActions[0].id
                quickActions[3].nextFocusRightId=playStationBenefits.id;playStationBenefits.nextFocusLeftId=quickActions[3].id
            }
            playStationBenefits.nextFocusUpId=if(showQuick) quickActions[0].id else launchButton.id
        }
        playStationLibrary.nextFocusDownId=launchButton.id;playStationAllGames.nextFocusDownId=launchButton.id
        coverArtSize=ArtworkSize.forDisplay((128*d).toInt(),(128*d).toInt())
        selectedCard?.let(::loadHeaderArtwork);updateConsoleTitle();updatePlayStationQuota();consoleBackdrop.refreshStructure()
        emptyState.visibility=if(adapter.itemCount==0) VISIBLE else GONE
        adapter.notifyItemRangeChanged(0,adapter.itemCount,"skin")
    }

    private fun layoutConsole(w: Int,h: Int) {
        if(designScene.playStation) { layoutPlayStation(w,h);return }
        scale=minOf(w/1280f,h/720f)
        val s=scale;val wide=w/s
        fun pos(v: View,x: Float,y: Float,width: Float,height: Float)=place(v,x*s,y*s,width*s,height*s)
        fun text(v: TextView,size: Float)=textSize(v,size)
        val original=designScene==ConsoleScene.XBOX_ORIGINAL
        listOf(headerTitle,profilePanel,searchButton,metadataNote,footer,catalogNote).forEach { it.visibility=VISIBLE }
        grid.visibility=if(original && !originalBrowsing) GONE else VISIBLE
        headerIcon.visibility=if(original && !originalBrowsing) GONE else if(designScene==ConsoleScene.XBOX_SERIES) INVISIBLE else VISIBLE
        consoleBack.visibility=if(original && originalBrowsing) VISIBLE else GONE
        consoleTabRow.visibility=if(original && originalBrowsing) GONE else VISIBLE
        navigation.visibility=if(original && originalBrowsing) GONE else VISIBLE
        shortcuts.visibility=if(designScene==ConsoleScene.XBOX_SERIES || designScene==ConsoleScene.XBOX_360_METRO) VISIBLE else GONE
        sectionTitle.text=activeTab.label
        sectionTitle.visibility=if(designScene==ConsoleScene.XBOX_SERIES || original && originalBrowsing) VISIBLE else GONE
        val era=designScene==ConsoleScene.XBOX_360_NXE || designScene==ConsoleScene.XBOX_360_METRO
        launchButton.visibility=if(original || designScene==ConsoleScene.XBOX_SERIES || era) GONE else VISIBLE
        headerTitle.visibility=if(designScene==ConsoleScene.XBOX_SERIES || era) GONE else VISIBLE
        if(era) headerIcon.visibility=GONE
        consoleBrand.visibility=VISIBLE
        // Compact real account header, independent from the content panels.
        pos(profilePanel,if(designScene==ConsoleScene.XBOX_360) wide-380 else 54f,26f,300f,84f)
        pos(avatar,0f,0f,48f,48f);pos(avatarLetter,0f,0f,48f,48f);text(avatarLetter,25f)
        pos(accountName,61f,0f,235f,25f);pos(gamertag,61f,25f,235f,19f)
        pos(subscription,61f,44f,235f,18f);text(accountName,23f);text(gamertag,16f);text(subscription,13f)
        pos(remainingTime,61f,65f,235f,18f);text(remainingTime,13f)
        subscription.refreshBadge(s);remainingTime.refreshBadge(s)
        pos(connected,38f,39f,10f,10f)
        pos(settingsButton,0f,0f,1f,1f);settingsButton.visibility=GONE
        val searchSize=if(searchButton.phoneTarget) 76f else 56f
        pos(searchButton,wide-searchSize-20f,20f,searchSize,searchSize)
        pos(consoleBrand,if(designScene==ConsoleScene.XBOX_360) 34f else wide-380f,24f,260f,62f)
        pos(footer,50f,680f,wide-310f,28f)
        footer.alpha=if(original) .9f else 1f
        for(i in 0 until footer.childCount step 2) {
            val glyph=footer.getChildAt(i) as TextView;val label=footer.getChildAt(i+1) as TextView
            text(glyph,17f);text(label,16f)
            glyph.layoutParams=LinearLayout.LayoutParams((22*s).roundToInt(),(22*s).roundToInt()).apply { marginEnd=(5*s).roundToInt() }
            glyph.background=rounded(Color.TRANSPARENT,22*s,consoleTextColor())
            label.layoutParams=LinearLayout.LayoutParams(-2,-2).apply { marginEnd=(16*s).roundToInt() }
        }
        pos(catalogNote,wide-265f,682f,215f,22f);text(catalogNote,15f)
        pos(metadataNote,54f,650f,wide-108f,23f);text(metadataNote,15f)
        headerTitle.gravity=Gravity.START
        headerTitle.setTextColor(consoleTextColor());text(headerTitle,30f)
        lb.visibility=GONE;rb.visibility=GONE
        coverVerticalPadding=4*s
        when(designScene) {
            ConsoleScene.XBOX_360_NXE -> {
                pos(consoleBrand,58f,29f,250f,64f)
                pos(profilePanel,wide-414f,24f,294f,84f)
                pos(navigation,48f,159f,294f,410f);pos(consoleTabRow,0f,0f,294f,410f)
                tabs.forEachIndexed { i,v ->
                    pos(v,0f,i*65f,290f,59f);text(v,if(i==activeTab.ordinal) 27f else 23f)
                    v.gravity=Gravity.CENTER_VERTICAL or Gravity.START
                    v.setPadding((16*s).roundToInt(),0,(7*s).roundToInt(),0)
                    v.typeface=Typeface.create("sans-serif",if(i==activeTab.ordinal) Typeface.BOLD else Typeface.NORMAL)
                    v.nextFocusUpId=if(i==0) profilePanel.id else tabs[i-1].id
                    v.nextFocusDownId=if(i==5) tabs[0].id else tabs[i+1].id;v.nextFocusRightId=grid.id
                }
                pos(sectionTitle,370f,134f,wide-430f,51f);sectionTitle.visibility=VISIBLE;text(sectionTitle,36f)
                pos(grid,362f,199f,wide-390f,397f);coverHeight=(397*s).roundToInt()
                pos(emptyState,362f,199f,wide-410f,397f)
                pos(metadataNote,58f,625f,wide-180f,34f);text(metadataNote,15f)
                pos(catalogNote,wide-265f,682f,215f,22f)
                metadataNote.setTextColor(0xff263024.toInt());catalogNote.setTextColor(0xff263024.toInt())
                for(i in 0 until footer.childCount) (footer.getChildAt(i) as TextView).apply {
                    setTextColor(0xff263024.toInt())
                    if(i%2==0) background=rounded(Color.TRANSPARENT,22*s,0xff263024.toInt())
                }
            }
            ConsoleScene.XBOX_360_METRO -> {
                pos(consoleBrand,146f,25f,300f,62f)
                pos(searchButton,52f,22f,76f,76f)
                pos(profilePanel,wide-362f,23f,304f,84f)
                pos(navigation,52f,106f,wide-104f,62f);pos(consoleTabRow,0f,0f,wide-104f,62f)
                val weights=floatArrayOf(1.3f,.9f,1f,1.65f,1.4f,1.4f);var x=0f
                tabs.forEachIndexed { i,v ->
                    val tw=(wide-104f)*weights[i]/weights.sum();pos(v,x,0f,tw,62f);text(v,22f)
                    v.typeface=Typeface.create("sans-serif",if(i==activeTab.ordinal) Typeface.BOLD else Typeface.NORMAL)
                    v.setPadding((6*s).roundToInt(),0,(6*s).roundToInt(),0);x+=tw
                }
                pos(shortcuts,56f,190f,198f,426f)
                quickActions.forEachIndexed { i,v ->
                    v.visibility=if(i<3) VISIBLE else GONE
                    pos(v,0f,i*143f,194f,135f);text(v,18f);v.maxLines=2;v.gravity=Gravity.BOTTOM or Gravity.START
                    v.setPadding((13*s).roundToInt(),(65*s).roundToInt(),(8*s).roundToInt(),(10*s).roundToInt())
                    v.nextFocusRightId=grid.id
                }
                pos(grid,270f,185f,wide-324f,432f);coverHeight=(214*s).roundToInt()
                pos(emptyState,270f,185f,wide-324f,432f)
                pos(metadataNote,56f,635f,wide-112f,30f);text(metadataNote,15f)
            }
            ConsoleScene.XBOX_360 -> {
                val left=activeTab.ordinal*54f+26f;val right=wide-(5-activeTab.ordinal)*54f-26f;val width=right-left
                pos(navigation,0f,0f,wide,658f);pos(consoleTabRow,0f,0f,wide,658f)
                tabs.forEachIndexed { i,v ->
                    v.rotation=0f;v.maxLines=1;text(v,if(i==activeTab.ordinal) 33f else 24f)
                    v.typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD)
                    if(i==activeTab.ordinal) pos(v,left+120,54f,width-162,54f)
                    else {
                        val x=if(i<activeTab.ordinal) i*54f+4f else wide-(6-i)*54f+4f
                        pos(v,x+27f-270f,337f-27f,540f,54f);v.rotation=if(i<activeTab.ordinal) -90f else 90f
                    }
                    v.nextFocusLeftId=tabs[(i+5)%6].id;v.nextFocusRightId=tabs[(i+1)%6].id
                    v.nextFocusDownId=grid.id
                }
                pos(searchButton,left+28,44f,84f,84f)
                pos(profilePanel,left+35,138f,270f,84f)
                pos(headerIcon,left+44,228f,220f,272f);headerArtSize=ArtworkSize.forDisplay((220*s).roundToInt(),(272*s).roundToInt())
                pos(headerTitle,left+303,137f,width-345,70f);text(headerTitle,29f)
                pos(grid,left+302,222f,width-341,377f);coverHeight=(62*s).roundToInt()
                pos(emptyState,left+302,222f,width-341,377f)
                pos(launchButton,left+42,530f,226f,51f);text(launchButton,24f)
                pos(consoleBrand,left+35,590f,240f,60f)
                pos(metadataNote,left+303,610f,width-345,30f);text(metadataNote,13f)
            }
            ConsoleScene.XBOX_ONE -> {
                pos(navigation,52f,107f,wide-104f,62f);pos(consoleTabRow,0f,0f,wide-104f,62f)
                val weights=floatArrayOf(1.25f,.9f,.95f,1.6f,1.3f,1.2f);var x=0f
                tabs.forEachIndexed { i,v -> val tw=(wide-104f)*weights[i]/weights.sum();pos(v,x,0f,tw,62f);v.setPadding((8*s).roundToInt(),0,(8*s).roundToInt(),0);text(v,20f);x+=tw }
                val heroW=(wide-146f)*.46f;val gx=76f+heroW
                pos(headerTitle,54f,183f,heroW,42f);text(headerTitle,30f);headerTitle.maxLines=1
                pos(headerIcon,54f,239f,heroW,290f);headerArtSize=ArtworkSize.forDisplay((heroW*s).roundToInt(),(290*s).roundToInt())
                pos(grid,gx,226f,wide-gx-50f,426f);coverHeight=(212*s).roundToInt()
                pos(emptyState,gx,226f,wide-gx-50f,426f)
                pos(metadataNote,54f,630f,heroW,39f);metadataNote.maxLines=2;text(metadataNote,15f)
                pos(launchButton,54f,543f,heroW,73f);text(launchButton,24f)
            }
            ConsoleScene.XBOX_SERIES -> {
                pos(consoleBrand,58f,151f,220f,72f)
                pos(dashboardClock,wide-250f,43f,130f,35f);text(dashboardClock,22f)
                dashboardClock.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                pos(navigation,350f,26f,wide-650f,94f);pos(consoleTabRow,0f,0f,wide-650f,94f)
                val tw=(wide-650f)/6f
                tabs.forEachIndexed { i,v ->
                    pos(v,i*tw,0f,tw-5f,94f);v.text=if(i==activeTab.ordinal) LibraryTab.entries[i].label else ""
                    v.gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;v.maxLines=2
                    v.setPadding((3*s).roundToInt(),(59*s).roundToInt(),(3*s).roundToInt(),(2*s).roundToInt());text(v,14f)
                }
                pos(headerIcon,wide-420f,139f,320f,174f);headerIcon.visibility=INVISIBLE
                pos(sectionTitle,58f,292f,wide-116f,32f);text(sectionTitle,22f)
                pos(grid,52f,329f,wide-104f,240f);cardWidth=(168*s).roundToInt();coverHeight=(240*s).roundToInt()
                pos(emptyState,58f,329f,wide-116f,240f)
                headerArtSize=ArtworkSize(320,192)
                pos(shortcuts,58f,581f,wide-116f,91f)
                val qw=(wide-116f)/4f
                quickActions.forEachIndexed { i,v ->
                    pos(v,i*qw,0f,qw-12f,90f);text(v,18f);v.gravity=Gravity.BOTTOM or Gravity.START
                    v.setPadding((15*s).roundToInt(),(42*s).roundToInt(),(10*s).roundToInt(),(10*s).roundToInt())
                }
                pos(metadataNote,58f,166f,430f,50f);text(metadataNote,15f);metadataNote.maxLines=2
                metadataNote.setPadding((9*s).roundToInt(),(5*s).roundToInt(),(9*s).roundToInt(),(4*s).roundToInt())
                metadataNote.background=rounded(0xe0131719.toInt(),5*s)
                metadataNote.visibility=GONE
                if(seriesGrid) {
                    pos(consoleBrand,58f,137f,180f,47f)
                    pos(sectionTitle,268f,148f,wide-326f,34f);text(sectionTitle,25f)
                    pos(grid,52f,203f,wide-104f,457f)
                    pos(emptyState,58f,203f,wide-116f,457f)
                    shortcuts.visibility=GONE;cardWidth=0
                    coverHeight=(((wide-104f)/cardColumns-12f)*1.22f*s+39*s).roundToInt()
                }
            }
            ConsoleScene.XBOX_ORIGINAL -> {
                pos(consoleBrand,94f,591f,470f,67f)
                val left=wide*.51f;val content=wide-left-85f
                pos(navigation,left,150f,content,414f);pos(consoleTabRow,0f,0f,content,414f)
                tabs.forEachIndexed { i,v ->
                    pos(v,0f,i*64f,content,53f);text(v,26f);v.gravity=Gravity.CENTER_VERTICAL or Gravity.START
                    v.setPadding((50*s).roundToInt(),0,(18*s).roundToInt(),0);v.letterSpacing=.08f
                    v.nextFocusUpId=if(i==0) profilePanel.id else tabs[i-1].id
                    v.nextFocusDownId=if(i==5) tabs[0].id else tabs[i+1].id
                }
                pos(headerTitle,70f,550f,left-105f,38f);text(headerTitle,24f);headerTitle.maxLines=1;headerTitle.gravity=Gravity.CENTER
                pos(headerIcon,wide*.26f-73f,248f,146f,198f);headerArtSize=ArtworkSize.forDisplay((146*s).roundToInt(),(198*s).roundToInt())
                headerIcon.visibility=if(originalBrowsing) VISIBLE else GONE
                pos(consoleBack,left,106f,175f,43f);text(consoleBack,22f)
                pos(sectionTitle,left+190f,109f,content-190f,40f);text(sectionTitle,24f)
                pos(grid,left,174f,content,416f);coverHeight=(67*s).roundToInt()
                grid.visibility=if(originalBrowsing) VISIBLE else GONE
                pos(emptyState,left,174f,content,416f)
                if(!originalBrowsing) emptyState.visibility=GONE
                pos(metadataNote,60f,650f,wide-120f,22f);text(metadataNote,13f)
            }
            else -> Unit
        }
        val profileTextWidth=(profilePanel.layoutParams.width/s-65f).coerceAtLeast(1f)
        listOf(accountName,gamertag,subscription,remainingTime).forEach { line ->
            line.layoutParams=line.layoutParams.apply { width=(profileTextWidth*s).roundToInt() }
        }
        if(designScene==ConsoleScene.XBOX_ONE) {
            coverArtSize=ArtworkSize.forDisplay(((grid.layoutParams.width/3f)-10*s).roundToInt(),(170*s).roundToInt())
        } else if(designScene==ConsoleScene.XBOX_360_METRO) coverArtSize=ArtworkSize.forDisplay((570*s).roundToInt(),(210*s).roundToInt())
        else if(seriesGrid) coverArtSize=ArtworkSize.forDisplay((grid.layoutParams.width/cardColumns-12*s).roundToInt(),coverHeight)
        else if(cardHorizontal) coverArtSize=ArtworkSize.forDisplay((if(designScene==ConsoleScene.XBOX_360_NXE) 272*s else 220*s).roundToInt(),(272*s).roundToInt())
        else coverArtSize=ArtworkSize.forDisplay((58*s).roundToInt(),(58*s).roundToInt())
        selectedCard?.let(::loadHeaderArtwork)
        emptyState.visibility=if(adapter.itemCount==0 && (!original || originalBrowsing)) VISIBLE else GONE
        metadataNote.visibility=GONE
        updateConsoleTitle();consoleBackdrop.refreshStructure()
        adapter.notifyItemRangeChanged(0,adapter.itemCount,"skin")
    }

    private inner class CoversAdapter : RecyclerView.Adapter<CoverHolder>() {
        var items = emptyList<CatalogCard>()
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = items[position].key.fold(1125899906842597L) { hash, c -> hash*31+c.code }
        override fun getItemCount() = items.size
        fun submit(next: List<CatalogCard>, changes: CatalogChanges) {
            val resized=items.size!=next.size
            items=next
            // The PS5 library tile follows the real shelf. A worker result may arrive
            // after the first measure without changing the viewport dimensions.
            if(resized && designScene==ConsoleScene.PS5) { lastGeometry=0L;this@GameDashboardView.requestLayout() }
            if(changes.replace) notifyDataSetChanged()
            else changes.ranges.forEach { notifyItemRangeChanged(it.first,it.last-it.first+1,"metadata") }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CoverHolder(GameCard(context))
        override fun onBindViewHolder(holder: CoverHolder, position: Int) {
            val game = items[position]
            val card = holder.card
            val width=if(cardHorizontal) (carouselWidth(game.key==selectedCard?.key)*scale).roundToInt() else LayoutParams.MATCH_PARENT
            if(card.layoutParams?.height!=coverHeight || card.layoutParams?.width!=width) card.layoutParams = RecyclerView.LayoutParams(width,coverHeight)
            card.bind(game)
            card.nextFocusUpId = if(cardHorizontal || position<cardColumns) {
                if(designScene==ConsoleScene.XBOX_ORIGINAL) consoleBack.id else categoryFocus().id
            } else View.NO_ID
            card.nextFocusDownId=if(designScene==ConsoleScene.PS4 && launchButton.visibility!=VISIBLE) playStationFavorite.id else if(designScene.playStation) launchButton.id else if(designScene==ConsoleScene.XBOX_SERIES && !seriesGrid) quickActions.first().id else View.NO_ID
            card.nextFocusLeftId=if(cardColumns==1 && !cardHorizontal) {
                if(designScene==ConsoleScene.XBOX_ORIGINAL) consoleBack.id else categoryFocus().id
            } else if(designScene==ConsoleScene.XBOX_360_NXE && position==0) categoryFocus().id
            else if(designScene==ConsoleScene.XBOX_360_METRO && (grid.layoutManager as GridLayoutManager).spanSizeLookup.getSpanIndex(position,3)==0) quickActions.first().id
            else View.NO_ID
            card.setOnFocusChangeListener { _, focused ->
                card.focused = focused
                if (focused) items.getOrNull(holder.bindingAdapterPosition)?.let(::updateSelected)
            }
            // Holders from the previous snapshot can still exist until the next layout.
            // Resolve the current position/binding instead of launching the captured old card.
            card.setOnClickListener {
                items.getOrNull(holder.bindingAdapterPosition)?.let { current -> updateSelected(current); playCard(current) }
            }
            card.setOnLongClickListener {
                val game=items.getOrNull(holder.bindingAdapterPosition)?.playableGame()
                if(game!=null && onGameShortcut!=null) { onGameShortcut.invoke(game);true } else false
            }
        }
        override fun onBindViewHolder(holder: CoverHolder, position: Int, payloads: MutableList<Any>) {
            when {
                payloads.isNotEmpty() && payloads.all { it=="favorite" } -> holder.card.bindFavorite(items[position])
                payloads.isNotEmpty() && payloads.all { it=="artwork" } -> if(holder.card.isAttachedToWindow) holder.card.bindArtwork(items[position])
                else -> onBindViewHolder(holder,position)
            }
        }
        override fun onViewAttachedToWindow(holder: CoverHolder) {
            val position=holder.bindingAdapterPosition
            if(position!=RecyclerView.NO_POSITION) holder.card.bindArtwork(items[position])
        }
        override fun onViewDetachedFromWindow(holder: CoverHolder) { posters.cancel(holder.card.image) }
        override fun onViewRecycled(holder: CoverHolder) { posters.cancel(holder.card.image) }
    }
    private class CoverHolder(val card: GameCard) : RecyclerView.ViewHolder(card)
    private inner class GameCard(context: Context) : FrameLayout(context) {
        private val cover = FrameLayout(context).apply {
            background = DashboardTheme.gradient(GradientDrawable.Orientation.TL_BR, arrayOf(UiColor.COVER_TOP, UiColor.COVER_BOTTOM))
                .apply { cornerRadius = 7*scale }
            clipToOutline = true
        }
        private val fallback = label(29f).apply { gravity = Gravity.CENTER; maxLines = 4; setPadding(12, 12, 12, 12) }
        val image = FixedArtworkView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val badges = GameBadgesView(context)
        private val rowBadges=GameBadgesView(context)
        private val caption=label(22f,false).apply { maxLines=1 }
        private var cardScene=ConsoleScene.NONE
        private var boundCard: CatalogCard?=null
        private var failedTileHero: String?=null
        private val frame=RectF()
        private val reflectionPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val reflectionSrc=Rect()
        private val reflectionDst=RectF()
        private val favorite = FavoriteBadgeView(context)
        var focused = false
            set(value) {
                field=value
                if(designScene!=ConsoleScene.NONE) {
                    caption.setTextColor(when(designScene) {
                        ConsoleScene.XBOX_360 -> if(value) Color.WHITE else 0xff263024.toInt()
                        ConsoleScene.XBOX_ORIGINAL -> if(value) 0xff142400.toInt() else 0xffc2f364.toInt()
                        else -> Color.WHITE
                    })
                }
                invalidate()
            }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG)
        private var geometryScale=-1f
        init {
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            DashboardTheme.customFocus(this)
            clipChildren = false; setWillNotDraw(false)
            cover.addView(fallback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cover.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cover.addView(badges)
            addView(cover);addView(rowBadges);addView(caption); addView(favorite)
        }
        fun bind(game: CatalogCard) {
            boundCard=game
            if(!designScene.playStation) {
                if(android.os.Build.VERSION.SDK_INT>=26) fallback.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                fallback.maxLines=4;fallback.typeface=DashboardTheme.font(context)
            }
            if(designScene!=ConsoleScene.XBOX_360_NXE && favorite.parent!==this) {
                (favorite.parent as? ViewGroup)?.removeView(favorite);addView(favorite)
            }
            favorite.refresh()
            // Every binding starts from a plain caption, including recycled holders from another scene.
            val overlay=(designScene==ConsoleScene.XBOX_SERIES && !seriesGrid) || designScene==ConsoleScene.XBOX_360_METRO
            val captionParent=if(overlay) cover else this
            if(caption.parent!==captionParent) { (caption.parent as? ViewGroup)?.removeView(caption);captionParent.addView(caption) }
            caption.background=null;caption.setPadding(0,0,0,0);caption.maxLines=1;caption.rotationY=0f
            cover.clipToOutline=true
            bindFavorite(game)
            val name=game.name.uppercase(Locale.getDefault())
            if(fallback.text.toString()!=name) fallback.text=name
            if(isAttachedToWindow) bindArtwork(game)
            val s = scale
            badges.bind(game.gamePass, game.seriesOptimized, s)
            if(designScene!=ConsoleScene.NONE) { bindConsole(game);return }
            rowBadges.visibility=GONE;caption.visibility=GONE;caption.background=null;caption.setPadding(0,0,0,0);background=null
            if(cardScene!=ConsoleScene.NONE) {
                geometryScale=-1f
                cover.background=DashboardTheme.gradient(GradientDrawable.Orientation.TL_BR,arrayOf(UiColor.COVER_TOP,UiColor.COVER_BOTTOM)).apply { cornerRadius=7*scale }
            }
            cardScene=ConsoleScene.NONE
            cover.rotationY=0f;caption.rotationY=0f
            if(geometryScale==s) return
            geometryScale=s
            textSize(fallback,27f)
            badges.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (52*s).roundToInt(), Gravity.BOTTOM)
            cover.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins((10*s).roundToInt(), coverVerticalPadding.roundToInt(), (10*s).roundToInt(), coverVerticalPadding.roundToInt())
            }
            favorite.layoutParams = LayoutParams((31*s).roundToInt(), (31*s).roundToInt(), Gravity.TOP or Gravity.END)
                .apply { topMargin = (16*s).roundToInt(); marginEnd = (18*s).roundToInt() }
        }
        private fun bindConsole(game: CatalogCard) {
            val s=scale;val list=designScene==ConsoleScene.XBOX_360 || designScene==ConsoleScene.XBOX_ORIGINAL
            cardScene=designScene;geometryScale=-1f
            background=if(designScene==ConsoleScene.XBOX_SERIES || designScene==ConsoleScene.XBOX_360_NXE || designScene.playStation) null else ConsoleControlPlate(designScene,ConsoleControl.CARD)
            caption.visibility=VISIBLE;caption.text=game.name;caption.typeface=Typeface.create(if(designScene==ConsoleScene.XBOX_ORIGINAL) "sans-serif-condensed" else "sans-serif-medium",Typeface.NORMAL)
            caption.maxLines=1
            caption.letterSpacing=if(designScene==ConsoleScene.XBOX_ORIGINAL) .055f else 0f
            caption.setTextColor(if(designScene==ConsoleScene.XBOX_360) 0xff263024.toInt() else if(designScene==ConsoleScene.XBOX_ORIGINAL) 0xffc2f364.toInt() else Color.WHITE)
            textSize(caption,if(list) 22f else 17f)
            cover.background=rounded(0xff151915.toInt(),if(designScene==ConsoleScene.XBOX_SERIES) 5*s else 0f)
            cover.rotationY=0f;caption.rotationY=0f
            if(designScene.playStation) { layoutPlayStationCard();focused=hasFocus();return }
            if(designScene==ConsoleScene.XBOX_360_NXE) { layoutNxeCard();focused=hasFocus();return }
            if(designScene==ConsoleScene.XBOX_360_METRO) {
                cover.setBackgroundColor(0xff168d07.toInt())
                cover.layoutParams=LayoutParams(-1,-1).apply { setMargins((5*s).roundToInt(),(5*s).roundToInt(),(5*s).roundToInt(),(5*s).roundToInt()) }
                caption.visibility=VISIBLE;caption.gravity=Gravity.CENTER_VERTICAL
                caption.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xe6000000.toInt()))
                caption.setPadding((10*s).roundToInt(),0,(9*s).roundToInt(),0);textSize(caption,20f)
                caption.layoutParams=LayoutParams(-1,(40*s).roundToInt(),Gravity.BOTTOM)
                badges.layoutParams=LayoutParams(-1,(34*s).roundToInt(),Gravity.TOP)
                rowBadges.visibility=GONE
                favorite.layoutParams=LayoutParams((25*s).roundToInt(),(25*s).roundToInt(),Gravity.TOP or Gravity.END).apply { topMargin=(10*s).roundToInt();marginEnd=(10*s).roundToInt() }
                focused=hasFocus();return
            }
            if(list) {
                cover.layoutParams=LayoutParams((52*s).roundToInt(),(52*s).roundToInt()).apply { leftMargin=(8*s).roundToInt();topMargin=(6*s).roundToInt() }
                caption.layoutParams=LayoutParams(-1,-1).apply { leftMargin=(73*s).roundToInt();rightMargin=(145*s).roundToInt() }
                caption.gravity=Gravity.CENTER_VERTICAL
                badges.visibility=GONE
                rowBadges.bind(game.gamePass,game.seriesOptimized,s*.78f)
                rowBadges.layoutParams=LayoutParams((136*s).roundToInt(),(45*s).roundToInt(),Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd=(4*s).roundToInt() }
            } else {
                rowBadges.visibility=GONE
                if(designScene==ConsoleScene.XBOX_SERIES) layoutSeriesCard()
                else {
                    caption.background=null;caption.setPadding(0,0,0,0)
                    cover.layoutParams=LayoutParams(-1,(coverHeight-47*s).roundToInt()).apply { setMargins((9*s).roundToInt(),(8*s).roundToInt(),(9*s).roundToInt(),0) }
                    caption.layoutParams=LayoutParams(-1,(33*s).roundToInt(),Gravity.BOTTOM).apply { leftMargin=(12*s).roundToInt();rightMargin=(10*s).roundToInt();bottomMargin=(6*s).roundToInt() }
                    caption.gravity=Gravity.CENTER_VERTICAL;textSize(caption,18f)
                    badges.layoutParams=LayoutParams(-1,(42*s).roundToInt(),Gravity.BOTTOM)
                }
            }
            favorite.layoutParams=LayoutParams((25*s).roundToInt(),(25*s).roundToInt(),Gravity.TOP or Gravity.END).apply { topMargin=(7*s).roundToInt();marginEnd=(9*s).roundToInt() }
            if(designScene==ConsoleScene.XBOX_SERIES) layoutSeriesCard()
            focused=hasFocus()
        }
        fun refreshSeriesSelection() {
            if(!cardHorizontal || boundCard==null) return
            val desired=(carouselWidth(boundCard?.key==selectedCard?.key)*scale).roundToInt()
            (layoutParams as? RecyclerView.LayoutParams)?.let { if(it.width!=desired) { it.width=desired;layoutParams=it } }
            if(designScene.playStation) layoutPlayStationCard() else if(designScene==ConsoleScene.XBOX_360_NXE) layoutNxeCard() else layoutSeriesCard()
            invalidate()
        }
        private fun layoutNxeCard() {
            val s=scale;val selected=boundCard?.key==selectedCard?.key
            val side=((if(selected) 272f else 190f)*s).roundToInt()
            val top=((if(selected) 19f else 91f)*s).roundToInt()
            cover.layoutParams=LayoutParams(-1,side).apply { setMargins((8*s).roundToInt(),top,(8*s).roundToInt(),0) }
            cover.pivotX=0f;cover.pivotY=side/2f;cover.cameraDistance=1440*s;cover.rotationY=if(selected) 0f else -30f
            caption.visibility=VISIBLE;caption.gravity=Gravity.TOP or Gravity.START;caption.setTextColor(Color.WHITE)
            caption.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xc91b251a.toInt(),0xf0333d2d.toInt()))
            caption.setPadding((10*s).roundToInt(),(8*s).roundToInt(),(10*s).roundToInt(),(6*s).roundToInt())
            caption.maxLines=2;textSize(caption,if(selected) 23f else 18f)
            caption.layoutParams=LayoutParams(-1,((if(selected) 67f else 54f)*s).roundToInt()).apply { setMargins((8*s).roundToInt(),top+side,(8*s).roundToInt(),0) }
            caption.pivotX=0f;caption.pivotY=0f;caption.cameraDistance=1440*s;caption.rotationY=cover.rotationY
            rowBadges.visibility=GONE;badges.bind(boundCard?.gamePass==true,boundCard?.seriesOptimized==true,s*.8f)
            badges.layoutParams=LayoutParams(-1,(38*s).roundToInt(),Gravity.BOTTOM)
            if(favorite.parent!==cover) { (favorite.parent as? ViewGroup)?.removeView(favorite);cover.addView(favorite) }
            favorite.layoutParams=LayoutParams((25*s).roundToInt(),(25*s).roundToInt(),Gravity.TOP or Gravity.END).apply { topMargin=(6*s).roundToInt();marginEnd=(6*s).roundToInt() }
        }
        private fun layoutPlayStationCard() {
            val s=scale;val selected=boundCard?.key==selectedCard?.key
            val margin=8f
            val side=((carouselWidth(selected)-2*margin)*s).roundToInt()
            val top=if(selected) 8f else if(designScene==ConsoleScene.PS4) 21f else 8f
            cover.layoutParams=LayoutParams(-1,side).apply { setMargins((margin*s).roundToInt(),(top*s).roundToInt(),(margin*s).roundToInt(),0) }
            cover.background=rounded(0xff242c40.toInt(),if(designScene==ConsoleScene.PS5) 12*s else 0f)
            cover.clipToOutline=designScene==ConsoleScene.PS5
            caption.rotationY=0f;cover.rotationY=0f;rowBadges.visibility=GONE
            caption.visibility=if(designScene==ConsoleScene.PS4 && selected) VISIBLE else GONE
            caption.text=UiText.text("Iniciar");caption.gravity=Gravity.CENTER;caption.background=android.graphics.drawable.ColorDrawable(0x49002574)
            caption.setPadding(0,0,0,0);textSize(caption,18f)
            caption.layoutParams=LayoutParams(-1,((if(playStationMobile) 28 else 34)*s).roundToInt()).apply { leftMargin=(margin*s).roundToInt();rightMargin=(margin*s).roundToInt();topMargin=(top*s).roundToInt()+side }
            badges.visibility=GONE
            if(playStationMobile) {
                fallback.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                val sp=resources.displayMetrics.scaledDensity
                val lines=((side-fallback.compoundPaddingTop-fallback.compoundPaddingBottom)/(12*sp)).toInt().coerceIn(1,3)
                fitConsoleText(fallback,side,side,10*sp,16*sp,lines)
            }
            favorite.layoutParams=LayoutParams((22*s).roundToInt(),(22*s).roundToInt(),Gravity.TOP or Gravity.END).apply { topMargin=(12*s).roundToInt();marginEnd=(12*s).roundToInt() }
        }
        private fun layoutSeriesCard() {
            if(seriesGrid) {
                val s=scale
                cover.layoutParams=LayoutParams(-1,(coverHeight-39*s).roundToInt()).apply { setMargins((7*s).roundToInt(),(5*s).roundToInt(),(7*s).roundToInt(),0) }
                caption.visibility=VISIBLE;caption.background=null;caption.setPadding(0,0,0,0);caption.gravity=Gravity.CENTER_VERTICAL or Gravity.START
                textSize(caption,17f)
                caption.layoutParams=LayoutParams(-1,(28*s).roundToInt(),Gravity.BOTTOM).apply { leftMargin=(9*s).roundToInt();rightMargin=(9*s).roundToInt();bottomMargin=(6*s).roundToInt() }
                badges.bind(boundCard?.gamePass==true,boundCard?.seriesOptimized==true,s*.85f)
                badges.layoutParams=LayoutParams(-1,(34*s).roundToInt(),Gravity.BOTTOM)
                favorite.layoutParams=LayoutParams((25*s).roundToInt(),(25*s).roundToInt(),Gravity.TOP or Gravity.END).apply { topMargin=(10*s).roundToInt();marginEnd=(11*s).roundToInt() }
                return
            }
            val s=scale;val selected=boundCard?.key==selectedCard?.key
            val margin=(6*s).roundToInt()
            val side=((if(selected) 232f else 168f)*s).roundToInt()-2*margin
            cover.layoutParams=LayoutParams(-1,side,Gravity.BOTTOM).apply { setMargins(margin,0,margin,(5*s).roundToInt()) }
            caption.visibility=if(selected) VISIBLE else GONE
            caption.gravity=Gravity.BOTTOM or Gravity.START;textSize(caption,20f)
            caption.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xed080b0d.toInt()))
            caption.setPadding((8*s).roundToInt(),(4*s).roundToInt(),(8*s).roundToInt(),(8*s).roundToInt())
            caption.layoutParams=LayoutParams(-1,(46*s).roundToInt(),Gravity.BOTTOM)
            badges.bind(boundCard?.gamePass==true,boundCard?.seriesOptimized==true,s*.85f)
            badges.layoutParams=LayoutParams(-1,(34*s).roundToInt(),Gravity.TOP)
            favorite.layoutParams=LayoutParams((25*s).roundToInt(),(25*s).roundToInt(),Gravity.TOP or Gravity.END).apply { topMargin=(coverHeight-side-1*s).roundToInt();marginEnd=(10*s).roundToInt() }
        }
        fun bindFavorite(game: CatalogCard) {
            val starred=game.game?.let { library.isFavorite(it.titleId) } == true
            contentDescription = game.name + if(starred) UiText.text(", favorito") else ""
            favorite.visibility=if(starred) View.VISIBLE else View.GONE
        }
        fun bindArtwork(game: CatalogCard) {
            fallback.visibility=VISIBLE
            val hero=game.product?.heroUrl ?: game.game?.heroUrl
            if(designScene==ConsoleScene.XBOX_360_METRO && hero!=null && hero!=failedTileHero) {
                image.scaleType=ImageView.ScaleType.CENTER_CROP
                posters.load(image,hero,coverArtSize.width,coverArtSize.height,notifyPlaceholder=false) { ready ->
                    artworkReady(game,ready)
                    if(!ready && !artworkPaused && !closed && boundCard?.key==game.key) {
                        failedTileHero=hero;image.scaleType=ImageView.ScaleType.FIT_CENTER
                        posters.load(image,game.tileUrl ?: game.posterUrl,coverArtSize.width,coverArtSize.height) { artworkReady(game,it) }
                    }
                }
                return
            }
            image.scaleType=if(designScene==ConsoleScene.XBOX_360_METRO) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            val url=if(designScene==ConsoleScene.NONE) game.posterUrl ?: game.tileUrl else game.tileUrl ?: game.posterUrl
            posters.load(image,url,coverArtSize.width,coverArtSize.height) { artworkReady(game,it) }
        }
        private fun artworkReady(game: CatalogCard,ready: Boolean) {
            if(boundCard?.key!=game.key) return
            fallback.visibility=if(ready) GONE else VISIBLE
            if(ready) refreshSeriesMosaic()
        }
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if(designScene.playStation && (focused || boundCard?.key==selectedCard?.key)) {
                val s=scale;frame.set(cover.left-3*s,cover.top-3*s,cover.right+3*s,if(designScene==ConsoleScene.PS4) caption.bottom+3*s else cover.bottom+3*s)
                outline.shader=null;outline.style=Paint.Style.STROKE;outline.strokeWidth=(if(focused) 3f else 1.5f)*s;outline.color=Color.WHITE;outline.alpha=if(focused) 255 else 170
                val radius=if(designScene==ConsoleScene.PS5) 15*s else 0f
                canvas.drawRoundRect(frame,radius,radius,outline)
                return
            }
            if(designScene==ConsoleScene.XBOX_360_NXE && boundCard?.key==selectedCard?.key) {
                (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { bitmap ->
                    // A short reflection reuses the decoded bitmap and clips inside this item; no FBO or new bitmap.
                    val y=caption.bottom+3*scale;val height=minOf(32*scale,this.height-y).coerceAtLeast(0f)
                    reflectionSrc.set(0,(bitmap.height*.78f).toInt(),bitmap.width,bitmap.height)
                    reflectionDst.set(cover.left.toFloat(),0f,cover.right.toFloat(),height)
                    val save=canvas.save();canvas.translate(0f,y+height);canvas.scale(1f,-1f)
                    reflectionPaint.alpha=35;canvas.drawBitmap(bitmap,reflectionSrc,reflectionDst,reflectionPaint);canvas.restoreToCount(save)
                }
            }
            if (!focused) return
            val s = scale
            if(designScene==ConsoleScene.XBOX_ONE || designScene==ConsoleScene.XBOX_SERIES || designScene==ConsoleScene.XBOX_360_NXE || designScene==ConsoleScene.XBOX_360_METRO) {
                if(designScene==ConsoleScene.XBOX_SERIES) frame.set(cover.left-2*s,cover.top-2*s,cover.right+2*s,if(seriesGrid) caption.bottom+2*s else cover.bottom+2*s)
                else if(designScene==ConsoleScene.XBOX_360_NXE) frame.set(cover.left-2*s,cover.top-2*s,cover.right+2*s,caption.bottom+2*s)
                else frame.set(3*s,3*s,width-3*s,height-3*s)
                outline.shader=null;outline.style=Paint.Style.STROKE;outline.strokeWidth=3*s
                outline.color=if(designScene==ConsoleScene.XBOX_ONE || designScene==ConsoleScene.XBOX_360_NXE) Color.WHITE else 0xff63b814.toInt();outline.alpha=255
                canvas.drawRoundRect(frame,if(designScene==ConsoleScene.XBOX_SERIES) 7*s else 1*s,if(designScene==ConsoleScene.XBOX_SERIES) 7*s else 1*s,outline)
                return
            }
            if(designScene!=ConsoleScene.NONE) return
            val rect = RectF(5*s, 3*s, width-5*s, height-3*s)
            outline.style = Paint.Style.STROKE
            for (i in 5 downTo 1) {
                outline.strokeWidth = i*3*s; outline.color = DashboardTheme.accent; outline.alpha = 10
                canvas.drawRoundRect(rect, 11*s, 11*s, outline)
            }
            outline.strokeWidth = 3*s; outline.color = DashboardTheme.accent; outline.alpha = 255
            canvas.drawRoundRect(rect, 10*s, 10*s, outline)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();appearancePrefs.registerOnSharedPreferenceChangeListener(appearanceListener);appearanceListening=true;refreshSeriesPreferences() }
    override fun onDetachedFromWindow() { if(appearanceListening) { appearancePrefs.unregisterOnSharedPreferenceChangeListener(appearanceListener);appearanceListening=false };super.onDetachedFromWindow() }
    fun close() { if (!closed) {
        closed = true;if(appearanceListening) { appearancePrefs.unregisterOnSharedPreferenceChangeListener(appearanceListener);appearanceListening=false }
        consoleBackdrop.close();shortcutSkins.forEach { it.clear() };scope.cancel(); posters.close(); grid.adapter = null
    } }
    companion object { const val COLUMNS = 6 }
}
