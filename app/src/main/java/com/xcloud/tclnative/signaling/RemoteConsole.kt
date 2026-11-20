package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.i18n.UiText
import org.json.JSONObject

internal class RemoteConsole private constructor(
    val serverId: String,
    val name: String,
    val model: String?,
    val powerState: PowerState,
    val streamingEnabled: Boolean
) {
    enum class PowerState {
        ON, STANDBY, OFF, UNKNOWN;

        val label: String get() = when (this) {
            ON -> UiText.text("Ligado")
            STANDBY -> UiText.text("Em espera")
            OFF -> UiText.text("Desligado")
            UNKNOWN -> UiText.text("Estado desconhecido")
        }
    }

    val canConnect: Boolean get() = streamingEnabled && powerState != PowerState.OFF

    companion object {
        fun parse(response: String): List<RemoteConsole> {
            val results = JSONObject(response).getJSONArray("results")
            return (0 until results.length()).mapNotNull { index ->
                val entry = results.optJSONObject(index) ?: return@mapNotNull null
                val id = entry.optString("serverId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) && it != "null" }
                    ?: return@mapNotNull null
                val name = entry.optString("deviceName").trim().take(160).takeIf { it.isNotBlank() && it != "null" }
                val model = when (entry.optString("consoleType")) {
                    "XboxSeriesX" -> "Xbox Series X"
                    "XboxSeriesS" -> "Xbox Series S"
                    "XboxOne" -> "Xbox One"
                    "XboxOneS" -> "Xbox One S"
                    "XboxOneX" -> "Xbox One X"
                    else -> null
                }
                val power = when (entry.optString("powerState")) {
                    "On" -> PowerState.ON
                    "ConnectedStandby" -> PowerState.STANDBY
                    "Off", "Shutdown" -> PowerState.OFF
                    else -> PowerState.UNKNOWN
                }
                RemoteConsole(id, name ?: model ?: "Xbox", model, power, entry.optBoolean("isStreamingEnabled", true))
            }.distinctBy { it.serverId }
        }
    }
}
