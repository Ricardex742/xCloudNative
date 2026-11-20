package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.signaling.RemoteConsole
import kotlin.math.roundToInt

internal class RemotePlayPanel(private val context: Context, private val openPage: (String) -> Unit) {
    val dialog = ConsoleDialog(context, UiText.text("Reprodução remota"), ConsolePanelPurpose.REMOTE_PLAY)
    private val description = dialog.text(UiText.text("Consultando seus consoles Xbox…"), 22).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val content = LinearLayout(dialog.context).apply { orientation = LinearLayout.VERTICAL }
    private val refresh = dialog.addPersistentAction(UiText.text("Atualizar")) { reload?.invoke() }.apply {
        visibility = View.GONE
    }
    private val setup = dialog.addPersistentAction(UiText.text("Configurar console")) { showSetup() }.apply {
        visibility = View.GONE
    }
    private val help = dialog.addPersistentAction(UiText.text("Obter ajuda")) { openPage(HELP) }.apply {
        visibility = View.GONE
    }
    private var reload: (() -> Unit)? = null
    private var setupPage: ConsoleDialog? = null

    init {
        dialog.body.addView(description, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dialog.px(14) })
        dialog.body.addView(content, LinearLayout.LayoutParams(-1, -2))
        dialog.focusFirst(dialog.addAction(UiText.text("Voltar")) {})
        dialog.addCleanup { setupPage?.dismiss(); setupPage = null }
    }

    fun loading() {
        content.removeAllViews()
        description.visibility = View.VISIBLE
        description.text = UiText.text("Consultando seus consoles Xbox…")
        refresh.visibility = View.GONE
        setup.visibility = View.GONE
        help.visibility = View.GONE
        reload = null
    }

    fun failure(message: String, retry: () -> Unit) {
        description.visibility = View.VISIBLE
        description.text = message
        refresh.visibility = View.VISIBLE
        setup.visibility = View.VISIBLE
        help.visibility = View.GONE
        reload = retry
        refresh.requestFocus()
    }

    fun showConsoles(consoles: List<RemoteConsole>, selectedId: String?, retry: () -> Unit,
                     connect: (RemoteConsole) -> Unit) {
        refresh.visibility = View.VISIBLE
        reload = retry
        if (consoles.isEmpty()) {
            showPreparation()
            return
        }
        setup.visibility = View.VISIBLE
        help.visibility = View.GONE
        description.visibility = View.VISIBLE
        description.text = UiText.text("Meus consoles")
        val density = dialog.context.resources.displayMetrics.density
        val minTouch = (48 * density).roundToInt()
        val split = LinearLayout(dialog.context).apply {
            orientation = if (resources.displayMetrics.widthPixels >= 480 * density) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val choices = LinearLayout(dialog.context).apply { orientation = LinearLayout.VERTICAL }
        val details = LinearLayout(dialog.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dialog.px(14), dialog.px(10), dialog.px(14), dialog.px(10))
        }
        val horizontal = split.orientation == LinearLayout.HORIZONTAL
        listOf(choices, details).forEach {
            split.addView(it, if (horizontal) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(split, LinearLayout.LayoutParams(-1, -2))
        var selected = consoles.firstOrNull { it.serverId == selectedId } ?: consoles.first()
        val name = dialog.text(selected.name, 27, true)
        val status = dialog.text("", 19).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val action = dialog.keyButton(UiText.text("Conectar")) {
            if (selected.canConnect) {
                connect(selected)
            }
        }.apply { id = View.generateViewId(); minimumHeight = minTouch }
        details.addView(name, LinearLayout.LayoutParams(-1, -2))
        details.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dialog.px(10); bottomMargin = dialog.px(16) })
        details.addView(action, LinearLayout.LayoutParams(-1, -2))
        val selectedMarker = ConsoleIconDrawable(ConsoleGlyph.CONTROLLER, dialog.px(22).coerceAtLeast(1),
            ColorStateList.valueOf(DashboardTheme.accent))
        val rows = mutableListOf<android.widget.TextView>()
        fun select(console: RemoteConsole) {
            selected = console
            name.text = console.name
            status.text = if (console.canConnect) UiText.join(console.powerState.label, "\n", UiText.text("Abra a tela do seu Xbox.")) else
                UiText.join(console.powerState.label, "\n", UiText.text("Ligue o Xbox e confira os recursos remotos."))
            action.visibility = if (console.canConnect) View.VISIBLE else View.GONE
            rows.forEachIndexed { i, row ->
                row.isSelected = consoles[i] == console
                row.setCompoundDrawablesWithIntrinsicBounds(null, null, selectedMarker.takeIf { row.isSelected }, null)
                row.nextFocusRightId = if (action.visibility == View.VISIBLE) action.id else View.NO_ID
            }
            action.nextFocusLeftId = rows[consoles.indexOf(console)].id
        }
        consoles.forEach { console ->
            val label = UiText.join(console.name, "\n", listOfNotNull(console.model, console.powerState.label).joinToString(" • "))
            rows += dialog.keyButton(label) { select(console) }.apply {
                id = View.generateViewId()
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minimumHeight = maxOf(minTouch, dialog.px(66))
                maxLines = 3
                setPadding(dialog.px(12), dialog.px(4), dialog.px(12), dialog.px(4))
                choices.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dialog.px(6) })
            }
        }
        rows.forEachIndexed { i, row ->
            row.setOnFocusChangeListener { _, focused -> if (focused) select(consoles[i]) }
        }
        select(selected)
        rows[consoles.indexOf(selected)].requestFocus()
    }

    private fun showPreparation() {
        description.visibility = View.GONE
        setup.visibility = View.VISIBLE
        help.visibility = View.VISIBLE
        refresh.visibility = View.GONE
        val density = context.resources.displayMetrics.density
        val wide = context.resources.displayMetrics.widthPixels / density >= 600
        val row = LinearLayout(dialog.context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            isBaselineAligned = false
        }
        val intro = LinearLayout(dialog.context).apply { orientation = LinearLayout.VERTICAL }
        intro.addArticle(dialog.text(UiText.text("Prepare-se para jogar"), 40, true))
        intro.addArticle(dialog.text(UiText.text("Jogue os títulos instalados no seu Xbox."), 23))
        val art = RemotePlayArtwork(dialog.context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = DashboardTheme.panel(dialog.px(16).toFloat())
        }
        intro.addView(art, LinearLayout.LayoutParams(-1, dialog.px(204)).apply { topMargin = dialog.px(14) })
        val checklist = LinearLayout(dialog.context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dialog.px(22)
            setPadding(padding, padding, padding, padding)
            background = DashboardTheme.panel(dialog.px(12).toFloat())
        }
        checklist.addArticle(dialog.text(UiText.text("O que você precisa"), 27, true))
        checklist.addArticle(requirement(ConsoleGlyph.NETWORK, UiText.text("Internet de pelo menos 10 Mbps")))
        checklist.addArticle(requirement(ConsoleGlyph.ACCOUNT, UiText.text("Conta Microsoft com perfil Xbox")))
        checklist.addArticle(requirement(ConsoleGlyph.DISPLAY, UiText.text("Xbox Series X|S ou Xbox One com recursos remotos ativos")))
        checklist.addArticle(requirement(ConsoleGlyph.CONTROLLER, UiText.text("Controle Bluetooth recomendado")))
        row.addView(intro, if(wide) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2))
        row.addView(checklist, if(wide) LinearLayout.LayoutParams(0,-2,1.35f).apply { marginStart=dialog.px(30) }
            else LinearLayout.LayoutParams(-1,-2).apply { topMargin=dialog.px(20) })
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
        setup.requestFocus()
    }

    private fun requirement(glyph: ConsoleGlyph, message: String) = dialog.text(message, 22).apply {
        minHeight = dialog.px(40)
        gravity = Gravity.CENTER_VERTICAL
        setCompoundDrawablesRelativeWithIntrinsicBounds(ConsoleIconDrawable(glyph, dialog.px(25), textColors), null, null, null)
        compoundDrawablePadding = dialog.px(12)
    }

    private fun LinearLayout.addArticle(view: View) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dialog.px(12) })
    }

    private fun showSetup() {
        if (setupPage?.isShowing == true) return
        val guide = ConsoleDialog(context, UiText.text("Configurar console"), ConsolePanelPurpose.REMOTE_PLAY)
        setupPage = guide
        fun line(message: String, size: Int, heavy: Boolean = false, highlight: Boolean = false) {
            val view = guide.text(message, size, heavy)
            if (highlight) {
                view.background = DashboardTheme.panel(guide.px(9).toFloat())
                val padding = guide.px(14)
                view.setPadding(padding, padding, padding, padding)
            }
            guide.body.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = guide.px(14)
            })
        }
        line(UiText.text("Habilite os recursos remotos"), 36, true)
        line(UiText.text("No Xbox, entre com a mesma conta Microsoft e abra o caminho abaixo."), 23)
        line(UiText.text("Configurações › Dispositivos e conexões › Recursos remotos"), 25, true, highlight = true)
        line(UiText.text("Ative os recursos remotos. Depois, volte ao app e atualize a lista de consoles."), 23)
        line(UiText.text("Você também pode concluir a configuração no site oficial do Xbox."), 22)
        guide.addAction(UiText.text("Voltar")) {}
        guide.addPersistentAction(UiText.text("Obter ajuda")) { openPage(HELP) }
        guide.focusFirst(guide.addPersistentAction(UiText.text("Configurar no Xbox.com")) { openPage(OFFICIAL_PLAY) })
        guide.setOnDismissListener {
            if (setupPage === guide) setupPage = null
            if (dialog.isShowing) {
                refresh.visibility = View.VISIBLE
                help.visibility = View.GONE
                refresh.requestFocus()
            }
        }
        guide.show()
    }

    companion object {
        private const val OFFICIAL_PLAY = "https://www.xbox.com/pt-br/play/consoles"
        private const val HELP = "https://support.xbox.com/pt-BR/help/games-apps/game-setup-and-play/how-to-set-up-remote-play"
    }
}

private class RemotePlayArtwork(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons = ConsoleIcons()

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width / 312f, height / 180f)
        val saved = canvas.save()
        canvas.translate((width - 312f * scale) / 2, (height - 180f * scale) / 2)
        canvas.scale(scale, scale)
        val accent = DashboardTheme.accent
        paint.color = DashboardTheme.color(UiColor.LOGO_BACKGROUND)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(22f, 24f, 214f, 127f, 9f, 9f, paint)
        canvas.drawRoundRect(232f, 37f, 289f, 151f, 9f, 9f, paint)
        paint.color = accent
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        canvas.drawRoundRect(22f, 24f, 214f, 127f, 9f, 9f, paint)
        canvas.drawRoundRect(232f, 37f, 289f, 151f, 9f, 9f, paint)
        canvas.drawCircle(260.5f, 69f, 17f, paint)
        canvas.drawLine(39f, 148f, 121f, 148f, paint)
        canvas.drawLine(79f, 127f, 79f, 148f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(260.5f, 133f, 2.7f, paint)
        icons.draw(canvas, ConsoleGlyph.DISPLAY, 91f, 48f, 55f, accent)
        canvas.drawRoundRect(137f, 103f, 225f, 165f, 14f, 14f,
            paint.apply { color = DashboardTheme.surface })
        icons.draw(canvas, ConsoleGlyph.CONTROLLER, 153f, 109f, 57f, accent)
        canvas.restoreToCount(saved)
    }
}
