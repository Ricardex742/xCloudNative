package com.xcloud.tclnative.touch

import android.graphics.Bitmap

data class OfficialTouchBackground(val color: Int? = null, val image: Bitmap? = null, val opacity: Float = 1f)
data class OfficialTouchStroke(val color: Int, val opacity: Float = 1f)
data class OfficialTouchFace(val image: Bitmap? = null, val icon: HudIcon? = null)

data class OfficialTouchStyle(
    val opacity: Float = 1f,
    val background: OfficialTouchBackground? = null,
    val face: OfficialTouchFace? = null,
    val fill: Int? = null,
    val stroke: OfficialTouchStroke? = null,
    val knobOpacity: Float = 1f,
    val knobBackground: OfficialTouchBackground? = null,
    val knobStroke: OfficialTouchStroke? = null,
    val outlineOpacity: Float = 1f,
    val outlineStroke: OfficialTouchStroke? = null
)

data class OfficialTouchAppearance(
    val idle: OfficialTouchStyle,
    val active: OfficialTouchStyle,
    val enabled: Boolean,
    val touchpad: Boolean = false
)
