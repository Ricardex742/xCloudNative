package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.catalog.CloudPlaytime
import java.util.Locale

/** Actual receive/decoder counters; no resolution request or quality score is substituted. */
object CustomStatistics {
    fun format(metrics: StreamMetrics,input: InputMetrics,settings: StatisticsSettings,playtime: CloudPlaytime=CloudPlaytime()): String {
        fun number(value: Double?,decimals: Int=1)=value?.takeIf { it.isFinite() && it>=0 }
            ?.let { String.format(Locale.US,"%.${decimals}f",it) } ?: "n/d"
        val selected=settings.normalized().fields
        val measurements=StatisticField.entries.filter { it in selected && it!=StatisticField.PLAYTIME }.map { field ->
            val value=when(field) {
                StatisticField.FPS -> number(metrics.fps,0)
                StatisticField.RTT -> number(metrics.rttMs)+" ms"
                StatisticField.BITRATE -> number(metrics.bitrateMbps,2)+" Mbps"
                StatisticField.RESOLUTION -> if((metrics.width ?: 0)>0 && (metrics.height ?: 0)>0) "${metrics.width}×${metrics.height}" else "n/d"
                StatisticField.CODEC -> metrics.codec?.takeIf { it.isNotBlank() } ?: "n/d"
                StatisticField.DECODE -> number(metrics.decodeMs,2)+" ms"
                StatisticField.JITTER -> number(metrics.jitterMs)+" ms"
                StatisticField.LOSS -> number(metrics.packetLossPercent,2)+"%"
                StatisticField.BUFFER -> number(metrics.jitterBufferMs)+" ms"
                StatisticField.DROPPED -> metrics.droppedFrames?.takeIf { it>=0 }?.toString() ?: "n/d"
                StatisticField.INPUT -> number(input.maxSchedulerDelayMs)+" ms"
                StatisticField.PLAYTIME -> error("Playtime has its own line")
            }
            UiText.text("{0}: {1}",UiText.nested(field.label),UiText.nested(value))
        }.chunked(3).map { it.joinToString("  ·  ") }
        return ((if(StatisticField.PLAYTIME in selected) listOf(playtime.label) else emptyList())+measurements).joinToString("\n")
    }
}
