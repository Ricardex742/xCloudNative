package com.xcloud.tclnative.renderer

import android.content.Context
import android.view.SurfaceHolder
import org.webrtc.SurfaceEglRenderer
import org.webrtc.SurfaceViewRenderer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
    internal suspend fun retireEglSurface() { (holder as OutputSurfaceHolder).retireEglSurface() }

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
    private var eglCallback: SurfaceHolder.Callback? = null
    private var retiring = false

    override fun addCallback(callback: SurfaceHolder.Callback) {
        // SurfaceViewRenderer registers its SurfaceEglRenderer through our holder in M137.
        if (callback !is SurfaceEglRenderer) {
            original.addCallback(callback)
            return
        }
        eglRenderer = callback
        val lifecycle = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!retiring) callback.surfaceCreated(holder)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (!retiring) callback.surfaceChanged(holder, format, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = callback.surfaceDestroyed(holder)
        }
        eglCallback = lifecycle
        original.addCallback(lifecycle)
    }

    override fun removeCallback(callback: SurfaceHolder.Callback) {
        if (callback === eglRenderer) {
            original.removeCallback(checkNotNull(eglCallback))
            eglRenderer = null
            eglCallback = null
        } else original.removeCallback(callback)
    }

    /** Stop reopening the Surface while waiting for EGL to finish with its native window. */
    suspend fun retireEglSurface() {
        val callback = eglRenderer ?: return
        retiring = true
        suspendCoroutine<Unit> { done -> callback.releaseEglSurface { done.resume(Unit) } }
        // Android can still destroy the Surface during the wait. Keep the stock destroy callback
        // installed until releasing the EGL window is acknowledged on the render thread.
        removeCallback(callback)
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
