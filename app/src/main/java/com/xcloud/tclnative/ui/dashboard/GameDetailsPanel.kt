package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.xcloud.tclnative.catalog.CatalogCard
import com.xcloud.tclnative.catalog.StoreGameDetails
import com.xcloud.tclnative.i18n.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class GameDetailsPanel(
    context: Context,
    card: CatalogCard,
    scope: CoroutineScope,
    loadDetails: (suspend (String) -> StoreGameDetails?)?,
    onPlay: () -> Unit,
    onShortcut: (() -> Unit)?
) {
    val dialog = ConsoleDialog(context, card.name, ConsolePanelPurpose.GAME_DETAILS)
    private val artwork = PosterLoader(context, scope, maxConcurrent = 2)
    private val wide = dialog.wideDetails
    private val hero = card.product?.heroUrl ?: card.game?.heroUrl
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

    init {
        dialog.detailsBackdrop?.let { artwork.load(it, hero, 800, 450) }
        val columns = LinearLayout(context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            isBaselineAligned = false
        }
        dialog.body.addView(columns, LinearLayout.LayoutParams(-1, if (wide) -1 else -2))

        val poster = card.posterUrl ?: card.tileUrl ?: hero
        if (poster != null) {
            val cover = image(poster, 384, 576, ImageView.ScaleType.FIT_START).apply { tag = "game-details-cover" }
            columns.addView(cover, column(1.05f, compactHeight = dialog.px(210), compactWidth = dialog.px(140)))
        }

        val information = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = tile(0xe21a2334.toInt())
            val inset = dialog.px(if (wide) 16 else 14)
            setPadding(inset, inset, inset, inset)
        }
        information.addView(dialog.text(UiText.text("Descrição"), if (wide) 24 else 19, true),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dialog.px(12) })
        val readable = if (wide) ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            addView(description, -1, -2)
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
        } else description
        information.addView(readable, LinearLayout.LayoutParams(-1, if (wide) 0 else -2, if (wide) 1f else 0f))
        columns.addView(information, column(1.45f))
        columns.addView(gallery, column(2.5f, gapAfter = false))
        renderGallery(listOfNotNull(hero))

        val playable = card.game != null || card.product?.cloudSupported != false
        if (playable) dialog.addAction(UiText.text("Jogar na nuvem"), onPlay).apply { tag = "game-details-play" }
        if (onShortcut != null) {
            dialog.addPersistentAction(UiText.text("Atalho na tela inicial"), onShortcut).apply { tag = "game-details-shortcut" }
        }
        if (!playable && onShortcut == null) dialog.addAction(UiText.text("Voltar")) {}

        val product = card.product?.productId ?: card.game?.productId
        val canLoad = product != null && loadDetails != null
        description.text = if (canLoad) UiText.text("Carregando detalhes do jogo…") else unavailableDescription
        if (!playable) information.addView(dialog.text(UiText.text("Indisponível no Xbox Cloud Gaming"), 17, true), 1)
        val job = if (product != null && loadDetails != null) scope.launch {
            render(loadDetails(product))
        } else null
        dialog.addCleanup {
            job?.cancel()
            artwork.close()
        }
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
        details?.publisher?.let { publisher -> dialog.detailsPublisher?.apply {
            text = publisher
            visibility = View.VISIBLE
        } }
        details?.rating?.let { rating -> dialog.detailsRating?.apply {
            text = UiText.text("Classificação indicativa: {0}", UiText.nested(rating))
            visibility = View.VISIBLE
        } }
        description.text = details?.description ?: unavailableDescription
        val screenshots = details?.screenshots.orEmpty()
        if (screenshots.isNotEmpty()) renderGallery(screenshots)
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
