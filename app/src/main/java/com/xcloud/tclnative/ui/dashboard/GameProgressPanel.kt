package com.xcloud.tclnative.ui.dashboard

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.xcloud.tclnative.catalog.XboxGameProgress
import com.xcloud.tclnative.i18n.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** An account-specific view mounted in the same column as the Store description. */
internal class GameProgressPanel(
    private val dialog: ConsoleDialog,
    private val scope: CoroutineScope,
    private val artwork: PosterLoader,
    private val load: suspend () -> XboxGameProgress?,
    private val onAvailable: () -> Unit
) {
    private val context = dialog.context
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "game-progress" }
    private val status = dialog.text("", 17)
    private val summary = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val score = dialog.text("", 21, true).apply { tag = "game-progress-score" }
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(0xff79dfb6.toInt())
        progressBackgroundTintList = ColorStateList.valueOf(0xff425265.toInt())
    }
    private val count = dialog.text("", 19).apply { tag = "game-progress-count" }
    private val recentTitle = dialog.text(UiText.text("Conquistas recentes"), 17, true)
    private val recent = LinearLayout(context).apply { weightSum = 4f }
    private val images = mutableListOf<ImageView>()
    private val selectedName = dialog.text("", 17, true)
    private val selectedDescription = dialog.text("", 16).apply {
        tag = "game-progress-description"
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val descriptionScroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = dialog.px(64)
            val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit
                else minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        }
    }.apply {
        tag = "game-progress-description-scroll"
        addView(selectedDescription, -1, -2)
    }
    private val recentSection = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(recentTitle)
        addView(recent, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dialog.px(5) })
        addView(selectedName, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dialog.px(6) })
        addView(descriptionScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dialog.px(3) })
    }
    private var request: Job? = null
    val refreshButton: TextView = dialog.keyButton(UiText.text("Atualizar"), ::refresh).apply {
        tag = "game-progress-refresh"
        minimumHeight = dialog.touchSize()
    }
    val actions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(recentSection, -1, -2)
        addView(refreshButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dialog.px(6) })
    }

    init {
        body.addView(status, LinearLayout.LayoutParams(-1,-2))
        body.addView(summary, LinearLayout.LayoutParams(-1,-2))
        summary.visibility = View.GONE
        summary.addView(dialog.text(UiText.text("Gamerscore"), 17))
        summary.addView(score, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dialog.px(3) })
        summary.addView(bar, LinearLayout.LayoutParams(-1,dialog.px(9)).apply { topMargin = dialog.px(6) })
        summary.addView(count, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dialog.px(12) })
        dialog.addCleanup { request?.cancel() }
    }

    fun refresh() {
        if (request?.isActive == true) return
        status.text = UiText.text("Consultando conquistas…")
        status.visibility = View.VISIBLE
        refreshButton.isEnabled = false
        request = scope.launch {
            try {
                val result = withTimeoutOrNull(20_000) { load() }
                if (result == null) unavailable()
                else {
                    render(result)
                    onAvailable()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailable()
            } finally {
                refreshButton.isEnabled = true
            }
        }
    }

    private fun unavailable() {
        status.text = UiText.text("Progresso indisponível para este jogo.")
        status.visibility = View.VISIBLE
    }

    private fun render(result: XboxGameProgress) {
        status.visibility = View.GONE
        summary.visibility = View.VISIBLE
        bar.visibility = if (result.totalGamerscore > 0) View.VISIBLE else View.GONE
        val percent = if (result.totalGamerscore > 0) (100L * result.gamerscore / result.totalGamerscore).toInt() else 0
        score.text = "${result.gamerscore} / ${result.totalGamerscore}" +
            if (result.totalGamerscore > 0) "   ·   $percent%" else ""
        bar.progress = percent.coerceIn(0,100)
        count.text = UiText.text("Conquistas: {0} de {1}", UiText.nested(result.achievements), UiText.nested(result.totalAchievements))
        recentSection.visibility = if (result.recent.isEmpty()) View.GONE else View.VISIBLE
        images.forEach(artwork::cancel)
        images.clear()
        recent.removeAllViews()
        fun select(index: Int) {
            val entry = result.recent[index]
            val label = entry.name.ifBlank { UiText.text("Conquista") }
            selectedName.text = "$label · +${entry.gamerscore} G"
            selectedDescription.text = entry.description ?: UiText.text("Descrição indisponível.")
            descriptionScroll.scrollTo(0, 0)
            for (i in 0 until recent.childCount) recent.getChildAt(i).isSelected = i == index
        }
        for ((index, entry) in result.recent.withIndex()) {
            val label = entry.name.ifBlank { UiText.text("Conquista") }
            val badge = FrameLayout(context).apply {
                tag = "game-progress-badge-$index"
                val inset = dialog.px(2).coerceAtLeast(1)
                setPadding(inset, inset, inset, inset)
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_selected), android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dialog.px(4).toFloat()
                        setColor(0xff254c44.toInt())
                        setStroke(dialog.px(1).coerceAtLeast(1), 0xff79dfb6.toInt())
                    })
                    addState(intArrayOf(), DashboardTheme.panel(dialog.px(4).toFloat()))
                }
                contentDescription = label
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = label
                isFocusable = true
                setOnClickListener { select(index) }
                setOnFocusChangeListener { _, focused -> if (focused) select(index) }
            }
            badge.addView(dialog.text("+${entry.gamerscore} G", 16, true).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1,-1))
            entry.iconUrl?.let { url ->
                val icon = FixedArtworkView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                badge.addView(icon, FrameLayout.LayoutParams(-1,-1))
                images += icon
                artwork.load(icon, url, 112, 112)
            }
            recent.addView(badge, LinearLayout.LayoutParams(0, dialog.px(48).coerceAtLeast(32), 1f).apply {
                if (index > 0) marginStart = dialog.px(6)
            })
        }
        if (result.recent.isNotEmpty()) select(0)
    }
}
