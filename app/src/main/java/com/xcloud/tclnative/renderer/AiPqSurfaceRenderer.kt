package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.i18n.UiText

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import com.xcloud.tclnative.settings.VideoAspect
import com.xcloud.tclnative.settings.DevicePerformancePolicy
import com.xcloud.tclnative.settings.DevicePlatform
import com.xcloud.tclnative.settings.FsrOutput
import com.xcloud.tclnative.settings.FsrMode
import com.xcloud.tclnative.settings.ImageTreatmentSettings
import com.xcloud.tclnative.settings.PresentationPacing
import com.xcloud.tclnative.settings.ReconstructionSettings
import com.xcloud.tclnative.settings.ImageComparison
import com.xcloud.tclnative.settings.ImageLabSettings
import com.xcloud.tclnative.webrtc.ClientDisplayDimensions
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import org.webrtc.VideoSink
import com.xcloud.tclnative.webrtc.StreamMetrics
import com.xcloud.tclnative.webrtc.MediaCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class AiPqSurfaceRenderer internal constructor(context: Context, private val platform: DevicePlatform) : FrameLayout(context) {
    constructor(context: Context) : this(context, DevicePlatform.read(context))

    internal var onOutputModesChanged: (() -> Unit)? = null
    internal var onVideoAspectChanged: (() -> Unit)? = null
    private val devicePolicy=DevicePerformancePolicy.read(context)
    private val egl = lazy { EglBase.create() }
    val eglBase: EglBase get()=egl.value
    var onFirstFrameSubmitted: ((Long) -> Unit)? = null
    var onRenderError: ((Exception) -> Unit)? = null
    var onDisplayDimensionsChanged: ((ClientDisplayDimensions) -> Unit)? = null
    private var lastDisplayDimensions: ClientDisplayDimensions? = null
    private val displayManager=context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
    private var watchingDisplay=false
    private val displayListener=object: android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) { onOutputModesChanged?.invoke() }
        override fun onDisplayChanged(id: Int) {
            if (!released && display?.displayId == id) {
                updateVideoLayout()
                onOutputModesChanged?.invoke()
            }
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if(!watchingDisplay && !released) {
            displayManager.registerDisplayListener(displayListener,android.os.Handler(android.os.Looper.getMainLooper()))
            watchingDisplay=true
        }
        if(fsrEnabled) updateOutputBuffer()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if(visibility!=android.view.View.VISIBLE)frameInterpolation?.setEnabled(false)
    }
    override fun onDetachedFromWindow() {
        stopWatchingDisplay();cancelBufferVerification();super.onDetachedFromWindow()
    }
    private fun stopWatchingDisplay() { if(watchingDisplay) { displayManager.unregisterDisplayListener(displayListener);watchingDisplay=false } }
    private var currentTrack: VideoTrack? = null
    private var renderer: OutputSurfaceViewRenderer? = null
    private val rendererChanges = Mutex()
    private var aspectDrawer: AspectVideoDrawer? = null
    private var trackSink: VideoSink? = null
    private var pacedSink: FramePacedVideoSink?=null
    private var frameInterpolation: MotionFrameSink?=null
    private var interpolationSession=false
    private var pacingMode=PresentationPacing.OFF
    fun setPresentationPacing(mode: PresentationPacing) { pacingMode=mode }
    @Volatile private var generation = 0
    @Volatile private var lastDeliveryMs = AtomicLong(Long.MIN_VALUE)
    private val submittedToken = AtomicLong()
    private var submissionCadence = EglSubmissionCadence()
    private val fsrDraws=AtomicLong()
    private val allDraws=AtomicLong()
    private val fsrPerformance=FsrPerformanceGuard()
    data class VideoHealth(val submittedToken: Long, val deliveredRecently: Boolean)

    /** Counts buffers submitted to EGL, independently of frames delivered by the track. */
    fun probeVideoHealth(): VideoHealth {
        val delivered = lastDeliveryMs.get()
        return VideoHealth(submittedToken.get(), delivered != Long.MIN_VALUE &&
            android.os.SystemClock.elapsedRealtime() - delivered < 2000)
    }

    /** Keep the same remote track, peer connection, audio and Xbox allocation. Called from Main. */
    suspend fun rebuildSurface() = rendererChanges.withLock {
        if (released) return@withLock
        val track = currentTrack
        val firstDelivery = trackFirstDelivery
        retireRenderer()
        currentCoroutineContext().ensureActive()
        createRenderer()
        if (track != null && firstDelivery != null) attachTrackNow(track, firstDelivery)
    }
    @Volatile private var released = false
    var minimalPostProcessingRequested = false
    private var matchFrameRate = true

    internal fun setFrameRateMatching(enabled: Boolean) {
        if (matchFrameRate == enabled) return
        matchFrameRate = enabled
        renderer?.holder?.let(::applyFrameRateHint)
    }

    private fun applyFrameRateHint(holder: android.view.SurfaceHolder) {
        frameRateHintRequested = false
        if (Build.VERSION.SDK_INT < 30 || !holder.surface.isValid) return
        val rate = if (!matchFrameRate) 0f else if (compatibility) 30f else 60f
        val type = if (matchFrameRate) android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            else android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
        val applied = runCatching { holder.surface.setFrameRate(rate, type) }.isSuccess
        frameRateHintRequested = matchFrameRate && applied
    }

    private var frameRateHintRequested = false
    private var videoWidth = 1920
    private var videoHeight = 1080
    private var sharpness=0
    private var saturation=100
    private var brightness=0
    private var contrast=100
    private var fsrRequested=false
    private var fsrMode=FsrMode.OFF
    private var reconstructionSettings=ReconstructionSettings()
    private var imageLab=ImageLabSettings()
    private var imageLabLevel=0
    private var imageComparison=ImageComparison.PROCESSED
    fun setImageComparison(value: ImageComparison) {
        if(imageComparison==value)return
        imageComparison=value;aspectDrawer?.globalImageComparison=value.ordinal;fsrPerformance.reset()
        if(value!=ImageComparison.PROCESSED)frameInterpolation?.setEnabled(false,FrameGenerationPhase.COMPARISON)
    }
    private fun requestedLabLevel()=when {
        !imageLab.hasImageEffects || compatibility -> 0
        constrainOptionalEffects -> 1
        imageLab.supersampling!=com.xcloud.tclnative.settings.ImageSupersampling.OFF -> 3
        imageLab.neural -> 2
        else -> 1
    }
    fun setImageLab(value: ImageLabSettings) {
        if(imageLab==value.bounded())return
        imageLab=value.bounded();imageLabLevel=requestedLabLevel()
        fsrPerformance.reset();applyTreatmentBudget()
        frameInterpolation?.updateProtection(value.protection.ordinal)
        if(!value.enabled || value.comparison!=ImageComparison.PROCESSED || value.frameGeneration==com.xcloud.tclnative.settings.ExperimentalFrameGeneration.OFF) frameInterpolation?.setEnabled(false,if(value.comparison!=ImageComparison.PROCESSED) FrameGenerationPhase.COMPARISON else FrameGenerationPhase.OFF)
    }
    private val reconstructionController=ReconstructionController()
    private var requestedTreatment=ImageTreatmentSettings()
    private var treatmentBudget=TreatmentBudget(ImageTreatmentSettings(),FsrOutput.AUTO,false)
    private var treatmentCuts=mutableListOf<String>()
    private var treatmentFailure: String?=null
    private var fsrSharpness=50
    private var fsrOutput=FsrOutput.AUTO
    private var fsrFailure: String?=null
    private var requestedBuffer: VideoViewport?=null
    private var largeMobileSurface=false
    private var constrainedTvSurface = false
    private var systemMemoryPressure = false
    private val constrainOptionalEffects get() = devicePolicy.constrainedMemory || systemMemoryPressure
    private var totalRamMiB: Long? = null
    private var bufferVerification: Runnable?=null
    private val fsrEnabled get()=fsrRequested && treatmentBudget.scaling && !compatibility && fsrFailure==null
    private val fsrDetailLevel get()=if(fsrEnabled && treatmentFailure==null && !constrainOptionalEffects &&
        reconstructionSettings.strength>0 && videoWidth.toLong()*videoHeight<=1920L*1080) reconstructionController.level else 0
    var compatibility = false
    var tryMotionFence = false
    fun setFsr(requested: Boolean,sharpness: Int,output: FsrOutput=FsrOutput.AUTO) {
        setFsr(if(requested) FsrMode.FSR1 else FsrMode.OFF,sharpness,output)
    }
    fun setFsr(mode: FsrMode,sharpness: Int,output: FsrOutput=FsrOutput.AUTO) {
        if(fsrMode==mode && fsrOutput==output && fsrRequested==(mode!=FsrMode.OFF)) { setFsrSharpness(sharpness);return }
        fsrPerformance.reset()
        fsrMode=mode;fsrFailure=null;fsrOutput=output;fsrRequested=mode!=FsrMode.OFF;setFsrSharpness(sharpness)
        resetTreatmentBudget()
        updateOutputBuffer()
    }
    fun setImageTreatment(value: ImageTreatmentSettings) {
        if(requestedTreatment==value.bounded()) return
        requestedTreatment=value.bounded();treatmentFailure=null;resetTreatmentBudget();updateOutputBuffer()
    }
    fun setReconstruction(value: ReconstructionSettings) {
        val bounded=value.bounded()
        if(reconstructionSettings==bounded) return
        val qualityChanged=bounded.quality!=reconstructionSettings.quality
        reconstructionSettings=bounded
        if(qualityChanged) resetReconstructionController()
        fsrPerformance.reset();applyTreatmentBudget()
    }
    private fun resetReconstructionController() {
        reconstructionController.reset(reconstructionSettings.quality, constrainOptionalEffects)
    }
    private fun resetTreatmentBudget() {
        imageLabLevel=requestedLabLevel()
        treatmentCuts.clear()
        val requested = TreatmentBudget(if (compatibility) ImageTreatmentSettings() else requestedTreatment, fsrOutput, fsrRequested)
        treatmentBudget = if (systemMemoryPressure) requested.underMemoryPressure() else requested
        if (treatmentBudget != requested) treatmentCuts.add("memory pressure")
        resetReconstructionController()
        fsrPerformance.reset();applyTreatmentBudget()
    }
    private fun applyTreatmentBudget() {
        aspectDrawer?.let { drawer ->
            val effective=if(compatibility || treatmentFailure!=null) ImageTreatmentSettings() else treatmentBudget.image
            // Never accumulate generated pictures as new temporal source evidence.
            drawer.treatment=if(interpolationSession)effective.copy(temporal=0) else effective
            drawer.motionTemporalAllowed=!constrainOptionalEffects
            drawer.fsrEnabled=fsrEnabled
            drawer.fsrDetailLevel=fsrDetailLevel
            drawer.fsrDetailStrength=reconstructionSettings.strength
            drawer.globalImageComparison=if(compatibility)0 else imageComparison.ordinal
            drawer.imageLab=if(compatibility || treatmentFailure!=null) ImageLabSettings() else imageLab.forDevice(constrainOptionalEffects)
            drawer.imageLabLevel=imageLabLevel
        }
    }
    fun setFsrSharpness(sharpness: Int) { fsrSharpness=sharpness.coerceIn(0,100);aspectDrawer?.fsrSharpness=fsrSharpness }
    fun setImageAdjustments(sharpness: Int,saturation: Int,brightness: Int=0,contrast: Int=100) {
        this.brightness=brightness.coerceIn(-50,50);this.contrast=contrast.coerceIn(50,150)
        this.sharpness=sharpness.coerceIn(0,100); this.saturation=saturation.coerceIn(0,200)
        aspectDrawer?.sharpness=this.sharpness; aspectDrawer?.saturation=this.saturation
        aspectDrawer?.brightness=this.brightness;aspectDrawer?.contrast=this.contrast
    }
    fun touchCoordinates(x: Float,y: Float): Pair<Float,Float>? = VideoScalingPolicy.touchCoordinates(
        width,height,videoWidth,videoHeight,aspect,x,y)
    fun displayDimensions(): ClientDisplayDimensions? {
        if (width <= 0 || height <= 0) return null
        val viewport = VideoScalingPolicy.viewport(width,height,videoWidth,videoHeight,aspect)
        val metrics = resources.displayMetrics
        return ClientDisplayDimensions.fromPixels(width,height,viewport.width,viewport.height,
            metrics.xdpi,metrics.ydpi,metrics.densityDpi.toFloat())
    }
    var aspect = VideoAspect.RATIO_16_9
        set(value) {
            field=value
            aspectDrawer?.stretch=value.stretch
            updateVideoLayout()
        }
    var nativeUpscaling = true
        set(value) {
            field = value
            updateOutputBuffer()
            updateVideoLayout()
        }

    init { setBackgroundColor(Color.BLACK) }

    /** A new renderer has a new first-frame event for every session, including retries. */
    fun prepareForSession(expectedWidth: Int = 1920, expectedHeight: Int = 1080) {
        check(!released && renderer == null && !rendererChanges.isLocked)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = activityManager?.let { manager -> ActivityManager.MemoryInfo().also(manager::getMemoryInfo) }
        systemMemoryPressure = memory?.lowMemory == true
        totalRamMiB = memory?.totalMem?.takeIf { it > 0 }?.div(1024 * 1024)
        fsrFailure=null;treatmentFailure=null;resetTreatmentBudget()
        videoWidth = expectedWidth; videoHeight = expectedHeight
        submittedToken.set(0)
        createRenderer()
    }
    private fun createRenderer() {
        cancelBufferVerification();requestedBuffer=null
        generation++
        fsrDraws.set(0);allDraws.set(0);fsrPerformance.reset()
        frameRateHintRequested = false
        val id = generation
        val drawer=AspectVideoDrawer(aspect.stretch,{ org.webrtc.Fsr1Drawer(context) },onFsrFailure={ reason ->
            post { if(!released && generation==id) fallbackFsr(reason) }
        },treatmentFactory={ org.webrtc.ImageTreatmentPipeline(context) },onTreatmentFailure={ reason ->
            post { if(!released && generation==id) {
                treatmentFailure=reason;applyTreatmentBudget()
            } }
        }).also {
            it.onFsrDraw={ if(!released && generation==id) fsrDraws.incrementAndGet();Unit }
            it.onDraw={ if(!released && generation==id) allDraws.incrementAndGet();Unit }
            it.sharpness=sharpness;it.saturation=saturation;it.brightness=brightness;it.contrast=contrast;it.fsrSharpness=fsrSharpness;aspectDrawer=it
            applyTreatmentBudget()
        }
        val newView = object : OutputSurfaceViewRenderer(context) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                drawer.layoutAspect=(right-left).toFloat()/(bottom-top).coerceAtLeast(1)
                super.onLayout(changed,left,top,right,bottom)
                updateOutputBuffer()
            }
        }
        renderer=newView
        try {
            val view=newView
            val firstSubmission = FirstSurfaceSubmission()
            val cadence = EglSubmissionCadence().also { submissionCadence = it }
            view.onBufferFailure={ post { if(!released && generation==id) fallbackFsr("Surface recusou o tamanho solicitado") } }
            view.init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() = Unit // WebRTC emits this on delivery, before EGL draws.
                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                    post { if (!released && generation == id) {
                        val nextWidth = if (rotation % 180 == 0) width else height
                        val nextHeight = if (rotation % 180 == 0) height else width
                        if (videoWidth.toLong() * nextHeight != videoHeight.toLong() * nextWidth) {
                            onVideoAspectChanged?.invoke()
                        }
                        videoWidth = nextWidth
                        videoHeight = nextHeight
                        applyTreatmentBudget()
                        updateVideoLayout()
                    } }
                }
            },EglBase.CONFIG_PLAIN,drawer)
            view.addFrameSubmissionListener {
                if (!released && generation == id) {
                    val submittedAtNs = SystemClock.elapsedRealtimeNanos()
                    cadence.observe(submittedAtNs)
                    submittedToken.incrementAndGet()
                    firstSubmission.begin()?.let { epoch ->
                        val submittedAt = submittedAtNs / 1_000_000
                        post {
                            if (!released && generation == id && firstSubmission.confirm(epoch, view.holder.surface.isValid)) {
                                onFirstFrameSubmitted?.invoke(submittedAt)
                            }
                        }
                    }
                }
            }
            view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            view.setEnableHardwareScaler(nativeUpscaling && !fsrEnabled)
            if(compatibility) view.setFpsReduction(30f) else view.disableFpsReduction()
            view.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    firstSubmission.surfaceChanged()
                    cadence.surfaceChanged()
                    if (!released && generation == id) applyFrameRateHint(holder)
                    if(!released && generation==id) updateOutputBuffer()
                }
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (!released && generation == id) applyFrameRateHint(holder)
                    if(!released && generation==id) updateOutputBuffer()
                }
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                    firstSubmission.surfaceChanged()
                    cadence.surfaceChanged()
                    if (generation == id) { frameRateHintRequested = false;cancelBufferVerification() }
                }
            })
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } catch(error: Exception) {
            renderer=null;aspectDrawer=null;requestedBuffer=null
            runCatching { removeView(newView);newView.release() }
            throw error
        }
        updateVideoLayout()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        updateVideoLayout()
    }
    private fun updateVideoLayout() {
        displayDimensions()?.let { dimensions ->
            if (dimensions != lastDisplayDimensions) {
                lastDisplayDimensions = dimensions
                onDisplayDimensionsChanged?.invoke(dimensions)
            }
        }
        val view = renderer ?: return
        val viewport = VideoScalingPolicy.viewport(width,height,videoWidth,videoHeight,aspect)
        val targetWidth = viewport.width
        val targetHeight = viewport.height
        val old = view.layoutParams as? LayoutParams
        if (old == null || old.width != targetWidth || old.height != targetHeight || old.gravity != Gravity.CENTER) {
            view.layoutParams = LayoutParams(targetWidth,targetHeight,Gravity.CENTER)
        }
        updateOutputBuffer()
    }
    private fun updateOutputBuffer() {
        val view=renderer ?: return
        val mode=display?.mode
        val tv = platform.television
        val activity=context as? android.app.Activity
        val fullScreen=activity?.let { !it.isInMultiWindowMode && !it.isInPictureInPictureMode } ?: true
        val viewport=VideoScalingPolicy.viewport(width,height,videoWidth,videoHeight,aspect)
        largeMobileSurface = SurfaceMemoryBudget.applyLimit(width, height, tv)
        val constrained = constrainOptionalEffects
        constrainedTvSurface = tv && (SurfaceMemoryBudget.applyLimit(width, height, tv, constrained) ||
            (fullScreen && SurfaceMemoryBudget.applyLimit(mode?.physicalWidth ?: 0, mode?.physicalHeight ?: 0, tv, constrained)))
        val upscaleTarget=if(fsrEnabled) FsrOutputPolicy.target(treatmentBudget.output,width,height,viewport,
            mode?.physicalWidth ?: 0,mode?.physicalHeight ?: 0,tv,fullScreen)?.let { selected ->
            val scale=(treatmentBudget.maxOutputHeight.toDouble()/minOf(selected.width,selected.height)).coerceAtMost(1.0)
            val limited = VideoViewport((selected.width*scale).toInt().coerceAtLeast(1),(selected.height*scale).toInt().coerceAtLeast(1))
            if (largeMobileSurface || constrainedTvSurface) SurfaceMemoryBudget.cap(limited) else limited
        } else null
        val target = upscaleTarget ?: viewport.takeIf { constrainedTvSurface }?.let(SurfaceMemoryBudget::cap)
        if(target!=requestedBuffer) { cancelBufferVerification();fsrPerformance.reset() }
        requestedBuffer=target
        view.setOutputBuffer(target)
        view.setEnableHardwareScaler((nativeUpscaling || largeMobileSurface || constrainedTvSurface) && !fsrEnabled)
        verifyOutputBuffer()
    }
    private fun cancelBufferVerification() { bufferVerification?.let(::removeCallbacks);bufferVerification=null }
    private fun verifyOutputBuffer() {
        val target=requestedBuffer ?: return
        val view=renderer ?: return
        val actual=view.holder.surfaceFrame
        if(actual.width()==target.width && actual.height()==target.height) { cancelBufferVerification();return }
        if(bufferVerification!=null) return
        val id=generation
        bufferVerification=Runnable {
            bufferVerification=null
            if(released || generation!=id || requestedBuffer!=target || !view.isAttachedToWindow || !view.holder.surface.isValid) return@Runnable
            val frame=view.holder.surfaceFrame
            if(frame.width()!=target.width || frame.height()!=target.height)
                fallbackFsr(UiText.text("Surface não confirmou {0}x{1}; mantendo escala padrão",UiText.nested(target.width),UiText.nested(target.height)))
        }.also { postDelayed(it,2000) }
    }
    private fun fallbackFsr(reason: String) {
        if(!fsrEnabled) return
        fsrFailure=reason
        applyTreatmentBudget()
        updateOutputBuffer()
    }
    /** UI thread; fed independently of HUD visibility by the existing once-per-second stats. */
    fun observeFsrPerformance(sampledAtMs: Long,metrics: StreamMetrics,foreground: Boolean) {
        val now=android.os.SystemClock.elapsedRealtime()
        val fresh=now>=sampledAtMs && now-sampledAtMs<=500
        if(fsrDetailLevel>0) {
            val eligible=foreground && fresh && requestedTreatment.autoProtect && imageComparison==ImageComparison.PROCESSED
            if(reconstructionController.observe(now,allDraws.get(),metrics,eligible)) {
                applyTreatmentBudget();fsrPerformance.reset()
            }
        }
        frameInterpolation?.observePresentation(allDraws.get(),now)
        if((context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode==true)
            frameInterpolation?.setEnabled(false,FrameGenerationPhase.PROTECTED)
        if(imageComparison!=ImageComparison.PROCESSED || compatibility || (!requestedTreatment.autoProtect && imageLabLevel==0) || (!fsrEnabled && !treatmentBudget.image.active && imageLabLevel==0) ||
            (fsrEnabled && fsrDetailLevel==0 && aspectDrawer?.isFsrBypassed()==true && !treatmentBudget.image.active && imageLabLevel==0)) { fsrPerformance.reset();return }
        val slow=fsrPerformance.observe(now,allDraws.get(),metrics,foreground && fresh) ?: return
        val rate=String.format(java.util.Locale.US,"%.1f",slow.drawsPerSecond)
        if(imageLabLevel>0) {
            imageLabLevel--;treatmentCuts.add("image lab level $imageLabLevel ($rate draws/s)")
            if(imageLabLevel==0)frameInterpolation?.setEnabled(false,FrameGenerationPhase.PROTECTED)
            applyTreatmentBudget();fsrPerformance.reset();return
        }
        val outputHeight = requestedBuffer?.let { minOf(it.width,it.height) } ?: minOf(width,height)
        val temporalCut = if (!interpolationSession && treatmentBudget.image.temporal > 0) treatmentBudget.reduce(outputHeight) else null
        if(temporalCut==null && fsrDetailLevel>0 && reconstructionController.reduce()) {
            treatmentCuts.add("FSR detail level ${reconstructionController.level} ($rate draws/s)")
            applyTreatmentBudget();fsrPerformance.reset();return
        }
        val cut=temporalCut ?: treatmentBudget.reduce(outputHeight)
        if(cut!=null) {
            treatmentBudget=cut.effective;treatmentCuts.add("${cut.stage} ($rate draws/s)")
            applyTreatmentBudget();updateOutputBuffer();fsrPerformance.reset()
        } else fallbackFsr(UiText.text("proteção de fluidez: {0} desenhos FSR/s por {1}s; escala padrão nesta partida",UiText.nested(rate),UiText.nested(slow.durationMs/1000)))
    }
    private var trackFirstDelivery: ((Long) -> Unit)? = null

    fun attachVideoTrack(track: VideoTrack, firstDelivery: (Long) -> Unit) {
        val id = generation
        post {
            if (!released && generation == id) attachTrackNow(track, firstDelivery)
        }
    }
    private fun attachTrackNow(track: VideoTrack, firstDelivery: (Long) -> Unit) {
        if (currentTrack === track) return
        detachTrack()
        val view = renderer ?: return
        val drawer = aspectDrawer
        val id = generation
        val delivery=VideoSink { frame ->
            if (generation == id && !released) {
                drawer?.sourceAspect=frame.rotatedWidth.toFloat()/frame.rotatedHeight.coerceAtLeast(1)
                drawer?.sourceWidth=frame.rotatedWidth; drawer?.sourceHeight=frame.rotatedHeight
                view.onFrame(frame)
            }
        }
        val lab=imageLab.forDevice(constrainOptionalEffects)
        val requestedGeneration=lab.enabled && lab.frameGeneration==com.xcloud.tclnative.settings.ExperimentalFrameGeneration.AUTO_30_TO_60 &&
            lab.comparison==ImageComparison.PROCESSED && imageComparison==ImageComparison.PROCESSED && !compatibility
        val glVersion=(context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val power=context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val motion=if(requestedGeneration) {
            val scheduler=ImageLabGlScheduler()
            val useFence = tryMotionFence
            MotionFrameSink(delivery,{GlesMotionInterpolator(context,eglBase.eglBaseContext,scheduler.handler,lab.protection.ordinal,useFence=useFence)},scheduler,
                eligible=glVersion>=0x30000 && !constrainOptionalEffects && power?.isPowerSaveMode!=true)
        }else null
        frameInterpolation=motion
        interpolationSession=motion!=null && glVersion>=0x30000 && !constrainOptionalEffects && power?.isPowerSaveMode!=true
        applyTreatmentBudget()
        val paced=if(motion==null && pacingMode==PresentationPacing.SMOOTH && !compatibility) FramePacedVideoSink(delivery,failed={ error ->
            post { if(generation==id && !released) onRenderError?.invoke(error) }
        }) else null
        pacedSink=paced
        val deliveryClock = AtomicLong(Long.MIN_VALUE)
        lastDeliveryMs = deliveryClock
        val sink = VideoSink { frame ->
            if(generation==id && !released) {
                val now = SystemClock.elapsedRealtime()
                if (deliveryClock.getAndSet(now) == Long.MIN_VALUE) firstDelivery(now)
                (motion ?: paced ?: delivery).onFrame(frame)
            }
        }
        try {
            track.addSink(sink)
            trackSink = sink
            currentTrack = track
            trackFirstDelivery = firstDelivery
        } catch (e: Exception) { motion?.close();frameInterpolation=null;paced?.close();pacedSink=null;onRenderError?.invoke(e) }
    }
    suspend fun detachTransportTrack() = rendererChanges.withLock { detachTrack() }

    private fun detachTrack() {
        val track = currentTrack
        val sink = trackSink
        val motion = frameInterpolation
        val paced = pacedSink
        frameInterpolation = null
        pacedSink = null
        interpolationSession=false
        trackSink = null
        currentTrack = null
        trackFirstDelivery = null
        try {
            if (sink != null) track?.removeSink(sink)
        } finally {
            try { motion?.close() } finally { paced?.close() }
        }
    }

    suspend fun clearSession() = rendererChanges.withLock {
        if (!released) clearSessionLocked()
    }

    private suspend fun clearSessionLocked() {
        cancelBufferVerification();fsrPerformance.reset()
        fsrRequested=false
        onFirstFrameSubmitted = null
        onRenderError = null
        retireRenderer()
    }

    private suspend fun retireRenderer() = withContext(NonCancellable) {
        generation++
        cancelBufferVerification()
        val previous = renderer
        renderer = null; aspectDrawer = null; requestedBuffer = null
        val cleanup = MediaCleanup()
        cleanup.step("detach") { detachTrack() }
        if (previous != null) {
            cleanup.step("egl-surface") { previous.retireEglSurface() }
            cleanup.step("view") { removeView(previous) }
            cleanup.step("egl") { withContext(Dispatchers.IO) { previous.release() } }
            Log.i("xCloudMedia", "RENDERER_RELEASE/ ${cleanup.diagnostics()}")
        }
        cleanup.throwIfFailed()
    }
    fun frameGenerationSummary(): String? {
        frameInterpolation?.let {
            val state=it.snapshot()
            val requested=imageLab.enabled && imageLab.frameGeneration!=com.xcloud.tclnative.settings.ExperimentalFrameGeneration.OFF
            val resumedComparison=state.phase==FrameGenerationPhase.COMPARISON && imageComparison==ImageComparison.PROCESSED && imageLab.comparison==ImageComparison.PROCESSED
            return (if(requested && (state.phase==FrameGenerationPhase.OFF || resumedComparison)) state.copy(phase=FrameGenerationPhase.NEXT_SESSION) else state).text()
        }
        if(!imageLab.enabled || imageLab.frameGeneration==com.xcloud.tclnative.settings.ExperimentalFrameGeneration.OFF) return null
        val gl=(context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        return FrameGenerationSnapshot(when {
            compatibility || constrainOptionalEffects || gl<0x30000 -> FrameGenerationPhase.UNSUPPORTED
            imageComparison!=ImageComparison.PROCESSED || imageLab.comparison!=ImageComparison.PROCESSED -> FrameGenerationPhase.COMPARISON
            else -> FrameGenerationPhase.NEXT_SESSION
        }).text()
    }
    private fun imageDiagnostics(): String {
        val active=if(compatibility || treatmentFailure!=null || imageComparison==ImageComparison.ORIGINAL) ImageTreatmentSettings() else treatmentBudget.image.copy(
            neural=aspectDrawer?.effectiveNeural() ?: 0,temporal=aspectDrawer?.effectiveTemporal() ?: 0)
        fun describe(value: ImageTreatmentSettings)="${value.sharpening.name}; deband=${value.deband}%; cleanup=${value.cleanup}%; chroma=${value.chroma}%; AA=${value.antialias}%; temporal=${value.temporal}%; neural=${value.neural}%"
        return (frameGenerationSummary()?.let { "$it\n" } ?: "")+UiText.join(UiText.text("Tratamento solicitado: {0}",UiText.nested(describe(requestedTreatment))),"\n",
            UiText.text("Tratamento aplicado: {0}",UiText.nested(describe(active))),"\n",
            "Cuts: ${treatmentCuts.joinToString()}${treatmentFailure?.let { "; $it" } ?: ""}\n",
            aspectDrawer?.treatmentDiagnostics() ?: "Treatment: off","\n",
            if(fsrRequested) "FSR detail requested=${reconstructionSettings.quality}; applied level=$fsrDetailLevel; ${reconstructionController.decision}\n" else "",
            pacedSink?.diagnostics() ?: "Pacing: immediate","\n",
            submissionCadence.diagnostics(),"\n",
            "Image lab requested=$imageLab | effective level=$imageLabLevel | constrainedMemory=${devicePolicy.constrainedMemory}\n",
            "Comparison=$imageComparison | temporal history suspended for interpolation=$interpolationSession\n",
            frameInterpolation?.diagnostics() ?: "Frame generation: off (applies on next session)")
    }
    fun memoryDiagnostics(): String =
        "VIDEO_DEVICE/SDK=${Build.VERSION.SDK_INT}; RAM_MIB=${totalRamMiB ?: "n/d"}; FIRMWARE=${Build.DISPLAY.take(96)}\n" +
            "VIDEO_MEMORY/LARGE_MOBILE_SURFACE=$largeMobileSurface; CONSTRAINED_TV_SURFACE=$constrainedTvSurface; " +
            "SYSTEM_PRESSURE=$systemMemoryPressure; " +
            "HARDWARE_SCALER=${(nativeUpscaling || largeMobileSurface || constrainedTvSurface) && !fsrEnabled}; " +
            "BUFFER_REQUEST=${requestedBuffer?.let { "${it.width}x${it.height}" } ?: "AUTO"}"

    /** Android exposes surface/display geometry, but no public API reports TCL AiPQ activation. */
    fun diagnostics(): String {
        val surface = renderer?.holder?.surfaceFrame
        val size = if (surface != null && surface.width() > 0 && surface.height() > 0)
            "${surface.width()}x${surface.height()}" else "n/d"
        val mode = display?.mode
        val output = mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${kotlin.math.round(it.refreshRate).toInt()} Hz" } ?: "n/d"
        val layout=renderer?.let { "${it.width}x${it.height}" } ?: "n/d"
        val expected=VideoScalingPolicy.viewport(width,height,videoWidth,videoHeight,aspect)
        val minimalSupport=if(android.os.Build.VERSION.SDK_INT>=30) runCatching { display?.isMinimalPostProcessingSupported }.getOrNull() else null
        val minimalLabel=when(minimalSupport) { true -> UiText.text("sim");false -> UiText.text("não");null -> "n/d" }
        return UiText.join(UiText.text("Surface {0} → janela {1}x{2} | Saída Android: {3}\n",UiText.nested(size),UiText.nested(width),UiText.nested(height),UiText.nested(output)),UiText.text("Layout do vídeo: {0} | Área calculada: {1}x{2} | Quadro do renderer: {3}x{4}\n",UiText.nested(layout),UiText.nested(expected.width),UiText.nested(expected.height),UiText.nested(videoWidth),UiText.nested(videoHeight)),UiText.text("Escala da Surface: {0}; imagem ajustada à janela | Conteúdo {1} FPS sinalizado à Surface: {2} | Pós-processamento mínimo: {3}; suporte Android declarado: {4}\n",UiText.nested(if(fsrEnabled) UiText.text("renderer na resolução de saída do escalador") else if (nativeUpscaling) "HW solicitada" else "HW desativada"),UiText.nested(if(compatibility) 30 else 60),UiText.nested(if (frameRateHintRequested) UiText.text("sim") else UiText.text("não confirmado")),UiText.nested(if (minimalPostProcessingRequested) "solicitado" else UiText.text("padrão")),UiText.nested(minimalLabel)),UiText.text("Proporção: {0} | Nitidez padrão: {1}% | Saturação: {2}% | Brilho: {3}% | Contraste: {4}% | Apresentação: até {5} FPS | AiPQ: não verificável pelo app\n",UiText.nested(aspect.label),UiText.nested(sharpness),UiText.nested(saturation),UiText.nested(brightness),UiText.nested(contrast),UiText.nested(if(compatibility) 30 else 60)),(if(fsrRequested) UiText.text("Saída do escalador solicitada: {0} | buffer solicitado: {1}\n",UiText.nested(fsrOutput.label),UiText.nested(requestedBuffer?.let { "${it.width}x${it.height}" } ?: UiText.text("escala padrão"))) else ""),(if(fsrEnabled) UiText.text("Cadência local do renderer: {0} (não mede GPU nem FPS do jogo/tela)\n",UiText.nested(fsrPerformance.lastRate?.let { String.format(java.util.Locale.US,"%.1f desenhos/s",it) } ?: UiText.text("aguardando amostras"))) else ""),(if(fsrFailure!=null) UiText.text("Escalador: retorno à escala padrão — {0}",UiText.nested(fsrFailure)) else if(fsrRequested && compatibility) UiText.text("AMD FSR 1.0.2: inativo no modo Compatibilidade") else aspectDrawer?.fsrDiagnostics() ?: UiText.text("AMD FSR 1.0.2: desligado"))) + "\n" + imageDiagnostics()
    }

    fun compactDiagnostics(): String {
        val surface = renderer?.holder?.surfaceFrame
        val mode = display?.mode
        return UiText.join(UiText.text("Surface {0}x{1} → saída Android ",UiText.nested(surface?.width() ?: 0),UiText.nested(surface?.height() ?: 0)),(mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${kotlin.math.round(it.refreshRate).toInt()}Hz" } ?: "n/d"))
    }

    suspend fun release() = rendererChanges.withLock {
        if (released) return@withLock
        val cleanup = MediaCleanup()
        cleanup.step("renderer") { clearSessionLocked() }
        released = true
        stopWatchingDisplay()
        cleanup.step("shared-egl") {
            if (egl.isInitialized()) withContext(NonCancellable + Dispatchers.IO) { eglBase.release() }
        }
        cleanup.throwIfFailed()
    }
}
