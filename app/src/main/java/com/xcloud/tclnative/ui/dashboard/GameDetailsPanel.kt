package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xcloud.tclnative.catalog.CatalogCard
import com.xcloud.tclnative.catalog.StoreGameDetails
import com.xcloud.tclnative.catalog.XboxGameProgress
import com.xcloud.tclnative.i18n.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class GameDetailsPanel(
    context: Context,
    card: CatalogCard,
    private val scope: CoroutineScope,
    loadDetails: (suspend (String) -> StoreGameDetails?)?,
    onPlay: () -> Unit,
    onShortcut: (() -> Unit)?,
    onSettings: (() -> Unit)? = null,
    onStore: (() -> Unit)? = null,
    onFavorite: (suspend () -> Boolean?)? = null,
    initialFavorite: Boolean = false,
    loadProgress: (suspend () -> XboxGameProgress?)? = null
) {
    val dialog = ConsoleDialog(context, card.name, ConsolePanelPurpose.GAME_DETAILS)
    private val artwork = PosterLoader(context, scope, maxConcurrent = 2)
    private val wide = dialog.wideDetails
    private val hero = card.product?.heroUrl ?: card.game?.heroUrl
    private val coverArt = card.posterUrl ?: card.tileUrl ?: hero
    private val backgroundSource = ImageView(context)
    private var backgroundUrl: String? = null
    private var backgroundJob: Job? = null
    private val gallery = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        tag = "game-details-gallery"
        visibility = View.GONE
    }
    private val galleryImages = mutableListOf<ImageView>()
    private val description = dialog.text("", if (wide) 18 else 15).apply {
        tag = "game-details-description"
        typeface = Typeface.DEFAULT
        setLineSpacing(0f, 1.15f)
    }
    private val infoTitle = dialog.text(UiText.text("Descrição"), if (wide) 24 else 19, true)
    private var chosePanel = false
    private var progressVisible = false
    private var progressButton: TextView? = null
    private val progress = loadProgress?.let { loader -> GameProgressPanel(dialog, scope, artwork, loader) {
        if (!chosePanel) showProgress(true)
    } }

    init {
        loadBackdrop(hero ?: coverArt)
        val columns = LinearLayout(context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            isBaselineAligned = false
        }
        dialog.body.addView(columns, LinearLayout.LayoutParams(-1, if (wide) -1 else -2))

        if (coverArt != null) {
            val cover = image(coverArt, 384, 576, ImageView.ScaleType.FIT_START).apply { tag = "game-details-cover" }
            columns.addView(cover, column(1.05f, compactHeight = dialog.px(210), compactWidth = dialog.px(140)))
        }

        val information = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = tile(0xe21a2334.toInt())
            val inset = dialog.px(if (wide) 16 else 14)
            setPadding(inset, inset, inset, inset)
        }
        information.addView(infoTitle,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dialog.px(12) })
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(description, -1, -2)
            progress?.body?.let {
                it.visibility = View.GONE
                addView(it, -1, -2)
            }
        }
        val readable = if (wide) ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            addView(content, -1, -2)
            dialog.shortcut = { event ->
                val key = event.keyCode
                val direction = if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                if ((key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) &&
                    dialog.window?.currentFocus?.tag != "close-settings" && canScrollVertically(direction)) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        smoothScrollBy(0, direction * maxOf(height / 2, dialog.touchSize()))
                    }
                    true
                } else false
            }
        } else content
        information.addView(readable, LinearLayout.LayoutParams(-1, if (wide) 0 else -2, if (wide) 1f else 0f))
        progress?.refreshButton?.let {
            it.visibility = View.GONE
            information.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dialog.px(8) })
        }
        columns.addView(information, column(1.45f))
        columns.addView(gallery, column(2.5f, gapAfter = false))
        renderGallery(listOfNotNull(hero))

        val playable = card.game != null || card.product?.cloudSupported != false
        if (playable) dialog.addAction(UiText.text("Jogar na nuvem"), onPlay).apply { tag = "game-details-play" }
        onSettings?.let { dialog.addDetailsIcon(UiText.text("Opções das partidas"), ConsoleGlyph.SETTINGS, it).tag = "game-details-settings" }
        onStore?.let { dialog.addDetailsIcon(UiText.text("Ver na loja"), ConsoleGlyph.PURCHASED, it).tag = "game-details-store" }
        onFavorite?.let { action ->
            val favorite = dialog.addDetailsIcon(UiText.text("Favoritos"), ConsoleGlyph.FAVORITE) {}
            favorite.tag = "game-details-favorite"
            fun display(active: Boolean) {
                favorite.isSelected = active
                favorite.contentDescription = if (active) UiText.text("Remover dos favoritos") else UiText.text("Adicionar aos favoritos")
                if (android.os.Build.VERSION.SDK_INT >= 26) favorite.tooltipText = favorite.contentDescription
            }
            display(initialFavorite)
            favorite.setOnClickListener {
                favorite.isEnabled = false
                scope.launch {
                    try {
                        action()?.let(::display)
                    } finally {
                        favorite.isEnabled = true
                    }
                }
            }
        }
        if (onShortcut != null) {
            dialog.addDetailsIcon(UiText.text("Atalho na tela inicial"), ConsoleGlyph.SHORTCUT, onShortcut)
                .apply { tag = "game-details-shortcut" }
        }
        if (progress != null) {
            progressButton = dialog.addDetailsIcon(UiText.text("Seu progresso"), ConsoleGlyph.TROPHY) {
                chosePanel = true
                showProgress(!progressVisible)
            }.apply { tag = "game-details-progress" }
        }
        if (!playable) dialog.addAction(UiText.text("Voltar")) {}

        val availability = when {
            card.game?.freeToPlay == true -> UiText.text("Grátis na nuvem")
            !playable -> UiText.text("Indisponível no Xbox Cloud Gaming")
            card.gamePass -> UiText.text("No catálogo do Game Pass")
            card.purchased -> UiText.text("Na sua biblioteca")
            card.game != null || card.product?.cloudSupported == true -> UiText.text("Disponível na nuvem")
            else -> null
        }
        availability?.let { status -> dialog.detailsAvailability?.apply {
            text = status
            visibility = View.VISIBLE
        } }

        val product = card.product?.productId ?: card.game?.productId
        val canLoad = product != null && loadDetails != null
        description.text = if (canLoad) UiText.text("Carregando detalhes do jogo…") else unavailableDescription
        val job = if (product != null && loadDetails != null) scope.launch {
            render(loadDetails(product))
        } else null
        dialog.addCleanup {
            job?.cancel()
            backgroundJob?.cancel()
            artwork.cancel(backgroundSource)
            artwork.close()
        }
        progress?.refresh()
    }

    private val unavailableDescription get() = UiText.text("Detalhes da loja indisponíveis.")

    private fun column(weight: Float, gapAfter: Boolean = true, compactHeight: Int = -2, compactWidth: Int = -1) =
        if (wide) LinearLayout.LayoutParams(0, -1, weight).apply {
            if (gapAfter) marginEnd = dialog.px(14)
        } else LinearLayout.LayoutParams(compactWidth, compactHeight).apply {
            if (gapAfter) bottomMargin = dialog.px(12)
        }

    private fun tile(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dialog.px(12).toFloat()
    }

    private fun image(url: String, width: Int, height: Int, mode: ImageView.ScaleType) =
        FixedArtworkView(dialog.context).apply {
            scaleType = mode
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            artwork.load(this, url, width, height)
        }

    private fun render(details: StoreGameDetails?) {
        val publisher = listOfNotNull(details?.publisher, details?.releaseYear?.toString()).joinToString(" · ")
        if (publisher.isNotEmpty()) dialog.detailsPublisher?.apply {
            text = publisher
            visibility = View.VISIBLE
        }
        details?.rating?.let { rating -> dialog.detailsRating?.apply {
            text = UiText.text("Classificação indicativa: {0}", UiText.nested(rating))
            visibility = View.VISIBLE
        } }
        val notices = (details?.contentNotices.orEmpty() + details?.interactiveNotices.orEmpty()).distinct()
        if (notices.isNotEmpty()) dialog.detailsRatingNotes?.apply {
            text = notices.joinToString(" · ") { it.label }
            visibility = View.VISIBLE
        }
        description.text = details?.description ?: unavailableDescription
        val screenshots = details?.screenshots.orEmpty()
        if (screenshots.isNotEmpty()) {
            renderGallery(screenshots)
            if (hero == null) loadBackdrop(screenshots.first())
        }
    }

    private fun showProgress(show: Boolean) {
        val panel = progress ?: return
        progressVisible = show
        infoTitle.text = if (show) UiText.text("Seu progresso") else UiText.text("Descrição")
        description.visibility = if (show) View.GONE else View.VISIBLE
        panel.body.visibility = if (show) View.VISIBLE else View.GONE
        if (!show && panel.refreshButton.hasFocus()) progressButton?.requestFocus()
        panel.refreshButton.visibility = if (show) View.VISIBLE else View.GONE
        progressButton?.apply {
            isSelected = show
            contentDescription = if (show) UiText.text("Descrição") else UiText.text("Seu progresso")
            if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = contentDescription
        }
    }

    private fun loadBackdrop(url: String?) {
        val target = dialog.detailsBackdrop ?: return
        if (url == null || url == backgroundUrl) return
        backgroundUrl = url
        backgroundJob?.cancel()
        artwork.load(backgroundSource, url, 256, 160, notifyPlaceholder = false) { ready ->
            val source = (backgroundSource.drawable as? BitmapDrawable)?.bitmap
            backgroundSource.setImageDrawable(null)
            if (!ready || source == null) {
                if (url != coverArt) loadBackdrop(coverArt)
                return@load
            }
            backgroundJob = scope.launch {
                val blurred = withContext(Dispatchers.Default) { ArtworkBackdropBlur.apply(source) }
                target.setImageBitmap(blurred)
            }
        }
    }

    private fun renderGallery(urls: List<String>) {
        galleryImages.forEach(artwork::cancel)
        galleryImages.clear()
        gallery.removeAllViews()
        gallery.visibility = if (urls.isEmpty()) View.GONE else View.VISIBLE
        val rows = urls.chunked(2)
        for ((rowIndex, urlsInRow) in rows.withIndex()) {
            val row = LinearLayout(dialog.context)
            for ((index, url) in urlsInRow.withIndex()) {
                val preview = image(url, 640, 360, ImageView.ScaleType.CENTER_CROP).apply {
                    background = tile(0xff273044.toInt())
                    clipToOutline = true
                }
                galleryImages += preview
                row.addView(preview, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (index > 0) marginStart = dialog.px(10)
                })
            }
            gallery.addView(row, LinearLayout.LayoutParams(-1, if (wide) 0 else dialog.px(123), if (wide) 1f else 0f).apply {
                if (rowIndex > 0) topMargin = dialog.px(10)
            })
        }
    }
}
