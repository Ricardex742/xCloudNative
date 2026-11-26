package com.xcloud.tclnative.ui.dashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/** Decode from disk with sampling before allocating pixels; never keep a compressed image copy in heap. */
internal object ArtworkImageDecoder {
    fun decode(file: File, size: ArtworkSize, constrainedMemory: Boolean = false): Bitmap? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,bounds)
        val width=bounds.outWidth;val height=bounds.outHeight
        if(width !in 1..32768 || height !in 1..32768 || width.toLong()*height>128_000_000L) return null
        val target=size.fit(width,height)
        if (constrainedMemory && Build.VERSION.SDK_INT >= 28) {
            try {
                return decodeToSize(file, size)
            } catch (_: IOException) {
                // Keep the fallback for images using a format accepted only by BitmapFactory.
            }
        }
        var sample=1
        while(width/(sample*2)>=target.width && height/(sample*2)>=target.height) sample*=2
        val decoded=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {
            inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565
        }) ?: return null
        var transferred=false
        try {
            val fitted=if(decoded.width>size.width || decoded.height>size.height) {
                val fit=size.fit(decoded.width,decoded.height)
                Bitmap.createScaledBitmap(decoded,fit.width,fit.height,true)
            } else decoded
            transferred=fitted===decoded
            return fitted
        } finally {
            if(!transferred) decoded.recycle()
        }
    }

    @RequiresApi(28)
    private fun decodeToSize(file: File, size: ArtworkSize): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val fit = size.fit(info.size.width, info.size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setTargetSize(fit.width, fit.height)
        }
}
