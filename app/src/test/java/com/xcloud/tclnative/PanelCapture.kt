package com.xcloud.tclnative

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.xcloud.tclnative.ui.dashboard.ConsoleDialog
import java.io.File

internal fun capturePanel(dialog: ConsoleDialog,name: String) {
    val view=dialog.window!!.decorView
    view.measure(View.MeasureSpec.makeMeasureSpec(dialog.window!!.attributes.width,View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.AT_MOST))
    view.layout(0,0,view.measuredWidth,view.measuredHeight)
    val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    File("build/reports/dashboard/$name.png").apply {parentFile!!.mkdirs()}.outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
    }
    bitmap.recycle()
}
