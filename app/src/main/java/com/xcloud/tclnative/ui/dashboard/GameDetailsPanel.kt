package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    private val information = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val status: TextView

    init {
        val metrics = context.resources.displayMetrics
        val vertical = metrics.widthPixels < metrics.heightPixels
        val header = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        dialog.body.addView(header, LinearLayout.LayoutParams(-1, -2))
        val poster = card.posterUrl ?: card.tileUrl ?: card.product?.heroUrl ?: card.game?.heroUrl
        if (poster != null) header.addView(image(poster, 360, 540, ImageView.ScaleType.FIT_CENTER),
            LinearLayout.LayoutParams(dialog.px(190), dialog.px(240)).apply {
                if (vertical) bottomMargin = dialog.px(14) else marginEnd = dialog.px(24)
            })
        header.addView(information, if (vertical) LinearLayout.LayoutParams(-1, -2)
            else LinearLayout.LayoutParams(0, -2, 1f))
        val playable = card.game != null || card.product?.cloudSupported != false
        if (playable) dialog.addAction(UiText.text("Jogar na nuvem"), onPlay).apply { tag = "game-details-play" }
        else information.addView(dialog.text(UiText.text("Indisponível no Xbox Cloud Gaming"), 20, true))
        if (onShortcut != null) {
            dialog.addPersistentAction(UiText.text("Atalho na tela inicial"), onShortcut).apply { tag = "game-details-shortcut" }
        }
        if (!playable && onShortcut == null) dialog.addAction(UiText.text("Voltar")) {}

        val product = card.product?.productId ?: card.game?.productId
        val canLoad = product != null && loadDetails != null
        status = dialog.text(if (canLoad) UiText.text("Carregando detalhes do jogo…")
            else UiText.text("Detalhes da loja indisponíveis."), 20)
        information.addView(status)
        val job = if (product != null && loadDetails != null) scope.launch {
            render(loadDetails(product))
        } else null
        dialog.addCleanup {
            job?.cancel()
            artwork.close()
        }
    }

    private fun image(url: String, width: Int, height: Int, mode: ImageView.ScaleType) =
        FixedArtworkView(dialog.context).apply {
            scaleType = mode
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            artwork.load(this, url, width, height)
        }

    private fun render(details: StoreGameDetails?) {
        if (details == null || details.publisher == null && details.description == null &&
            details.rating == null && details.screenshots.isEmpty()) {
            status.text = UiText.text("Detalhes da loja indisponíveis.")
            return
        }
        information.removeView(status)
        details.publisher?.let { line(it, 25, true) }
        details.rating?.let { line(UiText.text("Classificação indicativa: {0}", UiText.nested(it)), 19) }
        details.description?.let { line(it, 21) }
        if (details.screenshots.isEmpty()) return
        dialog.addSection(UiText.text("Capturas de tela"))
        details.screenshots.chunked(2).forEach { group ->
            val row = LinearLayout(dialog.context)
            group.forEachIndexed { index, url ->
                row.addView(image(url, 720, 405, ImageView.ScaleType.FIT_CENTER),
                    LinearLayout.LayoutParams(0, dialog.px(200), 1f).apply {
                        if (index > 0) marginStart = dialog.px(12)
                    })
            }
            dialog.body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dialog.px(12) })
        }
    }

    private fun line(text: String, size: Int, heavy: Boolean = false) {
        information.addView(dialog.text(text, size, heavy), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dialog.px(12)
        })
    }
}
