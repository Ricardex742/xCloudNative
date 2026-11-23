package com.xcloud.tclnative.ui.dashboard

import android.graphics.Bitmap
import android.graphics.Color

/** Blur a small copy once, including on platform versions without RenderEffect. */
internal object ArtworkBackdropBlur {
    fun apply(source: Bitmap): Bitmap {
        val size = ArtworkSize(144, 96).fit(source.width, source.height)
        val reduced = Bitmap.createScaledBitmap(source, size.width, size.height, true)
        val width = reduced.width
        val height = reduced.height
        val pixels = IntArray(width * height)
        try {
            reduced.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            if (reduced !== source) reduced.recycle()
        }
        val radius = (minOf(width, height) / 10).coerceIn(2, 8)
        val horizontal = blur(pixels, width, height, radius, horizontal = true)
        val blurred = blur(horizontal, width, height, radius, horizontal = false)
        return Bitmap.createBitmap(blurred, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean): IntArray {
        val output = IntArray(pixels.size)
        val samples = (radius + 1) * (radius + 1)
        for (y in 0 until height) for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val sx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                val sy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                val color = pixels[sy * width + sx]
                val weight = radius + 1 - kotlin.math.abs(offset)
                alpha += Color.alpha(color) * weight
                red += Color.red(color) * weight
                green += Color.green(color) * weight
                blue += Color.blue(color) * weight
            }
            output[y * width + x] = Color.argb(alpha / samples, red / samples, green / samples, blue / samples)
        }
        return output
    }
}
