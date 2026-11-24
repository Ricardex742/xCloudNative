package com.xcloud.tclnative.ui.dashboard

import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.XboxAchievement
import com.xcloud.tclnative.catalog.XboxGameProgress
import com.xcloud.tclnative.i18n.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat

/** Scrollable title achievements, with at most one expanded text row. */
internal class GameProgressPanel(
    private val dialog: ConsoleDialog,
    private val scope: CoroutineScope,
    private val load: suspend () -> XboxGameProgress?,
    private val onAvailable: () -> Unit
) {
    private val context = dialog.context
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "game-progress" }
    private val status = dialog.text("", 17)
    private val summary = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val score = dialog.text("", 19, true).apply { tag = "game-progress-score" }
    private val count = dialog.text("", 17).apply { tag = "game-progress-count" }
    private val rows = AchievementRows()
    private val list = RecyclerView(context).apply {
        tag = "game-progress-list"
        layoutManager = LinearLayoutManager(context)
        adapter = rows
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        visibility = View.GONE
    }
    private var request: Job? = null
    val refreshButton: TextView = dialog.keyButton(UiText.text("Atualizar"), ::refresh).apply {
        tag = "game-progress-refresh"
        minimumHeight = dialog.touchSize()
    }

    init {
        body.addView(status, -1, -2)
        body.addView(summary, -1, -2)
        summary.addView(score, -1, -2)
        summary.addView(count, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dialog.px(4) })
        body.addView(list, LinearLayout.LayoutParams(-1,
            if (dialog.wideDetails) 0 else dialog.px(310), if (dialog.wideDetails) 1f else 0f).apply { topMargin = dialog.px(9) })
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
        val percent = if (result.totalGamerscore > 0) (100L * result.gamerscore / result.totalGamerscore).toInt() else null
        score.text = "${result.gamerscore} / ${result.totalGamerscore}" +
            if (percent != null) "   ·   $percent%" else ""
        count.text = UiText.text("Conquistas: {0} de {1}", UiText.nested(result.achievements), UiText.nested(result.totalAchievements))
        rows.submit(result.entries)
        list.visibility = if (result.entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class AchievementRows : RecyclerView.Adapter<AchievementRow>() {
        private var entries = emptyList<XboxAchievement>()
        private var expanded = RecyclerView.NO_POSITION

        fun submit(next: List<XboxAchievement>) {
            val previous = entries.getOrNull(expanded)
            expanded = previous?.let { entry -> next.indexOfFirst { it.name == entry.name && it.gamerscore == entry.gamerscore } }
                ?: RecyclerView.NO_POSITION
            entries = next
            notifyDataSetChanged()
        }

        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = AchievementRow()
        override fun onBindViewHolder(holder: AchievementRow, position: Int) {
            holder.bind(entries[position], position, position == expanded)
            holder.itemView.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                val previous = expanded
                expanded = if (previous == index) RecyclerView.NO_POSITION else index
                if (previous != RecyclerView.NO_POSITION && previous != index) notifyItemChanged(previous)
                notifyItemChanged(index)
                if (expanded == index) holder.itemView.post {
                    val row = holder.itemView
                    row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), false)
                }
            }
        }
    }

    private inner class AchievementRow : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val panel = itemView as LinearLayout
        private val name = dialog.text("", 17, true)
        private val gamerscore = dialog.text("", 16)
        private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(0xff79dfb6.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0xff4a5669.toInt())
        }
        private val details = dialog.text("", 16).apply { tag = "game-progress-description" }

        init {
            panel.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dialog.px(3) }
            panel.orientation = LinearLayout.VERTICAL
            panel.isFocusable = true
            panel.minimumHeight = dialog.touchSize()
            val horizontal = dialog.px(9)
            val vertical = dialog.px(7)
            panel.setPadding(horizontal, vertical, horizontal, vertical)
            panel.background = StateListDrawable().apply {
                fun plate(fill: Int, focus: Boolean) = GradientDrawable().apply {
                    cornerRadius = dialog.px(4).toFloat()
                    setColor(fill)
                    if (focus) setStroke(dialog.px(2).coerceAtLeast(1), 0xff79dfb6.toInt())
                }
                addState(intArrayOf(android.R.attr.state_focused), plate(0xff28423f.toInt(), true))
                addState(intArrayOf(android.R.attr.state_selected), plate(0xff25384a.toInt(), false))
                addState(intArrayOf(), plate(0xff182333.toInt(), false))
            }
            panel.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                addView(gamerscore, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dialog.px(8) })
            }, -1, -2)
            panel.addView(bar, LinearLayout.LayoutParams(-1, dialog.px(6)).apply { topMargin = dialog.px(6) })
            panel.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dialog.px(5) })
        }

        fun bind(entry: XboxAchievement, position: Int, expanded: Boolean) {
            panel.tag = "game-progress-row-$position"
            panel.isSelected = expanded
            name.text = entry.name.ifBlank { UiText.text("Conquista") }
            gamerscore.text = "${entry.gamerscore} G"
            bar.visibility = if (entry.progress != null) View.VISIBLE else View.GONE
            bar.progress = entry.progress ?: 0
            details.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) {
                val state = if (entry.unlocked) UiText.text("Desbloqueada") else UiText.text("Bloqueada")
                val progress = entry.progress?.let { "$state · $it%" } ?: state
                val rarity = entry.unlockRate?.let { rate ->
                    val percent = NumberFormat.getPercentInstance(UiText.locale).apply { maximumFractionDigits = 2 }
                    UiText.text("{0} desbloquearam", UiText.nested(percent.format(rate / 100)))
                }
                details.text = listOfNotNull(progress, entry.description ?: UiText.text("Descrição indisponível."), rarity).joinToString("\n")
            }
        }
    }
}
