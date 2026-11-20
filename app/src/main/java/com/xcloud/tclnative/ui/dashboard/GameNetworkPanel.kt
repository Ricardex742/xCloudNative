package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.StatisticField
import com.xcloud.tclnative.webrtc.StreamMetrics
import java.text.NumberFormat

internal class GameNetworkPanel(context: Context, back: () -> Unit) {
    val dialog = ConsoleDialog(context, UiText.text("Estatísticas da rede"), ConsolePanelPurpose.GAME_SESSION)
    private val formatter = NumberFormat.getNumberInstance(UiText.locale).apply {
        maximumFractionDigits = 2
        isGroupingUsed = false
    }
    private val warning = dialog.text(UiText.text("Rede instável"), 20, true).apply { visibility = View.GONE }
    private val rtt: TextView
    private val jitter: TextView
    private val loss: TextView
    private val bitrate: TextView

    init {
        dialog.body.addView(warning)
        rtt = addMetric(StatisticField.RTT.label)
        jitter = addMetric(StatisticField.JITTER.label)
        loss = addMetric(StatisticField.LOSS.label)
        bitrate = addMetric(UiText.text("Bitrate recebido"))
        dialog.addAction(UiText.text("Voltar"), back)
        dialog.setOnCancelListener { back() }
    }

    fun update(metrics: StreamMetrics?, unstable: Boolean) {
        warning.visibility = if (unstable) View.VISIBLE else View.GONE
        setValue(rtt, metrics?.rttMs, " ms")
        setValue(jitter, metrics?.jitterMs, " ms")
        setValue(loss, metrics?.packetLossPercent, "%")
        setValue(bitrate, metrics?.bitrateMbps, " Mbps")
    }

    private fun addMetric(label: String): TextView {
        val value = dialog.text("—", 22, true)
        val row = LinearLayout(dialog.context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dialog.px(8)
            setPadding(0, pad, 0, pad)
            addView(dialog.text(label, 22), LinearLayout.LayoutParams(0, -2, 1f))
            addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginStart = pad })
        }
        dialog.body.addView(row)
        return value
    }

    private fun setValue(view: TextView, number: Double?, unit: String) {
        val text = number?.takeIf { it.isFinite() && it >= 0 }?.let { formatter.format(it) + unit } ?: "—"
        if (view.text.toString() != text) view.text = text
    }
}
