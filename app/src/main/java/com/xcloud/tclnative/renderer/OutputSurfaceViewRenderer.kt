package com.xcloud.tclnative.renderer

import android.content.Context
import android.view.SurfaceHolder
import org.webrtc.SurfaceEglRenderer
import org.webrtc.SurfaceViewRenderer

/** Keep stock WebRTC/EGL rendering; override only holder sizing while an FSR target is selected. */
open class OutputSurfaceViewRenderer(context: Context): SurfaceViewRenderer(context) {
    // The Java superclass calls getHolder() in its constructor. lateinit avoids replacing that proxy.
    private lateinit var sizedHolder: OutputSurfaceHolder
    var onBufferFailure: (() -> Unit)?=null
    override fun getHolder(): SurfaceHolder {
        if(!::sizedHolder.isInitialized) sizedHolder=OutputSurfaceHolder(super.getHolder()) { onBufferFailure?.invoke() }
        return sizedHolder
    }
    fun setOutputBuffer(size: VideoViewport?) { (holder as OutputSurfaceHolder).setOutput(size) }

    /** Register before attaching the view, while the EGL thread cannot be submitting frames. */
    internal fun addFrameSubmissionListener(submitted: () -> Unit) {
        val egl = checkNotNull((holder as OutputSurfaceHolder).eglRenderer) {
            "The pinned WebRTC renderer did not register its EGL surface callback"
        }
        egl.addRenderListener { submitted() }
    }
}

/** WebRTC re-applies setSizeFromLayout on layout/frame changes; retain the requested buffer size. */
internal class OutputSurfaceHolder(private val original: SurfaceHolder,private val failed: () -> Unit): SurfaceHolder by original {
    internal var eglRenderer: SurfaceEglRenderer? = null
        private set

    override fun addCallback(callback: SurfaceHolder.Callback) {
        // SurfaceViewRenderer registers its SurfaceEglRenderer through our holder in M137.
        if (callback is SurfaceEglRenderer) eglRenderer = callback
        original.addCallback(callback)
    }

    override fun removeCallback(callback: SurfaceHolder.Callback) {
        original.removeCallback(callback)
        if (callback === eglRenderer) eglRenderer = null
    }

    private var output: VideoViewport?=null
    private var applied: VideoViewport?=null
    private var layoutApplied=false
    fun setOutput(size: VideoViewport?) {
        if(output==size) return
        output=size
        setSizeFromLayout()
    }
    override fun setFixedSize(width: Int,height: Int) {
        val wanted=output ?: VideoViewport(width,height)
        if(!layoutApplied && applied==wanted) return
        try {
            original.setFixedSize(wanted.width,wanted.height)
            applied=wanted;layoutApplied=false
        } catch(_: RuntimeException) {
            output=null;applied=null;layoutApplied=false
            try { original.setSizeFromLayout();layoutApplied=true } catch(_: RuntimeException) { /* Notify once below. */ }
            failed()
        }
    }
    override fun setSizeFromLayout() {
        val wanted=output
        if(wanted!=null) { setFixedSize(wanted.width,wanted.height);return }
        if(!layoutApplied) { original.setSizeFromLayout();applied=null;layoutApplied=true }
    }
}
