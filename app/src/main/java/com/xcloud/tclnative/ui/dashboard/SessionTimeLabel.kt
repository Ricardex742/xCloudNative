package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import com.xcloud.tclnative.i18n.LocalizedTextView
import com.xcloud.tclnative.i18n.UiText
import java.util.Locale

/** Observes the current session deadline only while the game menu is open. */
internal class SessionTimeLabel(context: Context, private val remainingSeconds: () -> Long?) : LocalizedTextView(context) {
    private val refresh = object : Runnable {
        override fun run() {
            val seconds = remainingSeconds()
            val label = if (seconds == null) UiText.text("Tempo da sessão não informado") else {
                val value = if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                UiText.text("Tempo restante na sessão: {0}", UiText.nested(value))
            }
            if (label != text.toString()) text = label
            postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }
}
