package com.xcloud.tclnative.settings

/** Independent opt-in; no vendor runtime or game-engine data is implied. */
enum class HudProtection { OFF, AUTO, CORNERS }
enum class ImageSupersampling(val scale: Float) { OFF(1f), X125(1.25f), X150(1.5f), X200(2f) }
enum class ExperimentalFrameGeneration { OFF, AUTO_30_TO_60 }
data class ImageLabSettings(val enabled: Boolean=false,val restoration: Int=35,
    val protection: HudProtection=HudProtection.AUTO,val supersampling: ImageSupersampling=ImageSupersampling.OFF,
    val frameGeneration: ExperimentalFrameGeneration=ExperimentalFrameGeneration.OFF,
    val comparison: ImageComparison=ImageComparison.PROCESSED,val neural: Boolean=false) {
    fun bounded()=copy(restoration=restoration.coerceIn(0,100))
    fun forDevice(constrainedMemory: Boolean)=if(!constrainedMemory) bounded() else bounded().copy(
        restoration=restoration.coerceIn(0,25),neural=false,supersampling=ImageSupersampling.OFF,
        frameGeneration=ExperimentalFrameGeneration.OFF)
}
