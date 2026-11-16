package com.xcloud.tclnative.renderer

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.Locale

interface MotionInterpolationBackend {
    /** Copies into bounded private GPU storage; only a 32x18 luma signature leaves GPU. */
    fun prepare(frame: VideoFrame): ByteArray
    fun interpolate(timestampNs: Long): VideoFrame?
    fun commit()
    fun reset()
    fun diagnostics(): String
    fun updateProtection(value: Int) {}
    /** Keep shared output textures alive until the renderer returns every retained buffer. */
    fun close(released: () -> Unit)
}
internal class ImageLabGlScheduler: FramePacingScheduler {
    private val thread=HandlerThread("image-lab-motion",Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    val handler=Handler(thread.looper)
    override fun nowNs()=System.nanoTime()
    override fun post(task: Runnable,delayMs: Long){handler.postDelayed(task,delayMs)}
    override fun remove(task: Runnable){handler.removeCallbacks(task)}
    override fun close(){handler.removeCallbacksAndMessages(null);thread.quitSafely()}
}

/** One latest input, one delayed original, two GPU history textures, two leased output slots. */
class MotionFrameSink(private val target: VideoSink,private val backendFactory: () -> MotionInterpolationBackend,
    private val scheduler: FramePacingScheduler,private val eligible: Boolean=true): VideoSink,AutoCloseable {
    private val lock=Any()
    private var pending: VideoFrame?=null
    private var pendingAt=0L
    private var queued=false
    private var held: VideoFrame?=null
    private var heldDue=0L
    private var backend: MotionInterpolationBackend?=null
    private var protectionOverride: Int?=null
    private var retiring=0
    private var schedulerClosed=false
    private val cadence=ContentFrameCadence()
    @Volatile private var closed=false
    @Volatile private var enabled=eligible
    @Volatile private var phase=if(eligible) FrameGenerationPhase.WAITING else FrameGenerationPhase.UNSUPPORTED
    @Volatile private var submittedRate: Double?=null
    @Volatile private var drawRate: Double?=null
    @Volatile private var generatedRate: Double?=null
    @Volatile private var reason=if(eligible) "waiting for stable 30 fps content" else "device/memory policy"
    @Volatile private var incoming=0L
    @Volatile private var real=0L
    @Volatile private var generated=0L
    @Volatile private var duplicates=0L
    @Volatile private var replaced=0L
    @Volatile private var expired=0L
    @Volatile private var errors=0L
    @Volatile private var workMeanMs=0.0
    @Volatile private var workP95Ms=0.0
    @Volatile private var backendInfo=""
    private val work=DoubleArray(32)
    private var samples=0;private var at=0;private var warmup=0
    private var lastTimestamp=Long.MIN_VALUE
    private var lastObservation=0L;private var observedDraws=0L;private var observedSent=0L;private var observedGenerated=0L;private var slowMs=0L
    private val present=Runnable {
        val frame=held;held=null;heldDue=0
        if(frame!=null)try { deliver(frame,false) }finally { frame.release() }
    }
    private val drain=Runnable { drainLatest() }
    override fun onFrame(frame: VideoFrame){
        synchronized(lock){
            if(closed)return
            frame.retain();pending?.also { replaced++;it.release() };pending=frame;pendingAt=scheduler.nowNs();incoming++
            if(!queued){queued=true;scheduler.post(drain,0)}
        }
    }
    fun updateProtection(value: Int){scheduler.post(Runnable {protectionOverride=value;backend?.updateProtection(value)},0)}
    fun setEnabled(value: Boolean,why: FrameGenerationPhase=FrameGenerationPhase.OFF){
        if(value||closed)return // Enabling generation requires a fresh session/clock/history.
        scheduler.post(Runnable { disable("disabled or comparison selected",why) },0)
    }
    private fun disable(message: String,why: FrameGenerationPhase=FrameGenerationPhase.PROTECTED){
        enabled=false;reason=message;phase=why;cadence.reset()
        flushHeld(true);retireBackend()
    }
    private fun flushHeld(show: Boolean){
        scheduler.remove(present);val old=held;held=null;heldDue=0
        if(old!=null)try { if(show)deliver(old,false) else expired++ }finally { old.release() }
    }
    private fun deliver(frame: VideoFrame,synthetic: Boolean){
        if(closed||(synthetic&&frame.timestampNs>0&&lastTimestamp>0&&frame.timestampNs<=lastTimestamp))return
        target.onFrame(frame);lastTimestamp=frame.timestampNs
        if(synthetic)generated++ else real++
    }
    private fun drainLatest(){
        val frame: VideoFrame;val arrival: Long
        synchronized(lock){queued=false;frame=pending ?: return;pending=null;arrival=pendingAt}
        try {
            if(closed)return
            if(held!=null&&scheduler.nowNs()>=heldDue)present.run()
            if(!enabled||frame.timestampNs<=0){flushHeld(true);deliver(frame,false);return}
            if(scheduler.nowNs()-arrival>50_000_000L){flushHeld(false);backend?.reset();cadence.reset();expired++;phase=FrameGenerationPhase.LATE;deliver(frame,false);return}
            val start=scheduler.nowNs()
            val engine=backend ?: backendFactory().also { backend=it;protectionOverride?.let(it::updateProtection) }
            val decision=cadence.observe(frame.timestampNs,engine.prepare(frame))
            when(decision.action){
                ContentFrameCadence.Action.RESET -> {flushHeld(false);lastTimestamp=Long.MIN_VALUE;engine.commit();deliver(frame,false);phase=FrameGenerationPhase.WAITING;reason="history reset/cut"}
                ContentFrameCadence.Action.PASS -> {flushHeld(true);engine.commit();deliver(frame,false);phase=FrameGenerationPhase.WAITING;reason="source not confirmed as moving 30 fps"}
                ContentFrameCadence.Action.DUPLICATE -> {duplicates++;reason="alternating duplicate pattern; estimated 30 fps content"}
                ContentFrameCadence.Action.INTERPOLATE -> {
                    if(held!=null){
                        flushHeld(false);engine.commit();cadence.reset();deliver(frame,false);reason="arrival too early; reset"
                    }else{
                        val middle=frame.timestampNs-decision.intervalNs/2
                        val deadline=arrival+decision.intervalNs/2
                        val synthesized=engine.interpolate(middle)
                        engine.commit()
                        if(synthesized==null||scheduler.nowNs()>deadline-1_000_000L){
                            synthesized?.release();expired++;deliver(frame,false);phase=FrameGenerationPhase.LATE;reason="generated frame unavailable/late"
                        }else{
                            try {deliver(synthesized,true)}finally{synthesized.release()}
                            frame.retain();held=frame;heldDue=deadline
                            scheduler.post(present,((deadline-scheduler.nowNs()+999_999L)/1_000_000L).coerceAtLeast(0))
                            phase=FrameGenerationPhase.ACTIVE;reason="motion-compensated 2x; target up to 60 submitted fps"
                        }
                    }
                }
            }
            backendInfo=engine.diagnostics()
            val ms=(scheduler.nowNs()-start)/1e6
            if(++warmup>24){
                work[at]=ms;at=(at+1)%work.size;samples=minOf(samples+1,work.size)
                val current=work.copyOf(samples).sorted();workMeanMs=current.average();workP95Ms=current[((samples-1)*.95).toInt()]
                if(samples>=12&&(workP95Ms>8.0||ms>100))disable("processing budget exceeded; original frames preserved")
            }
        }catch(error: Exception){
            errors++;enabled=false;phase=FrameGenerationPhase.ERROR;reason="fallback: ${error.javaClass.simpleName}";flushHeld(false)
            runCatching {deliver(frame,false)}.onFailure {close()};retireBackend()
        }finally{frame.release()}
    }
    /** Actual renderer draws, not generated submissions, detect downstream saturation. */
    fun observePresentation(draws: Long,nowMs: Long){
        scheduler.post(Runnable {
            if(closed)return@Runnable
            val sent=real+generated;val elapsed=nowMs-lastObservation
            if(lastObservation>0&&elapsed in 500..2000){
                val expected=sent-observedSent;val shown=draws-observedDraws
                submittedRate=expected.coerceAtLeast(0)*1000.0/elapsed
                drawRate=shown.takeIf { it>=0 }?.let { it*1000.0/elapsed }
                generatedRate=(generated-observedGenerated).coerceAtLeast(0)*1000.0/elapsed
                slowMs=if(enabled&&expected>=10&&shown>=0&&shown<expected*.85)slowMs+elapsed else 0
                if(slowMs>=2000)disable("renderer cadence budget exceeded; generation stopped")
            }else { submittedRate=null;drawRate=null;generatedRate=null }
            lastObservation=nowMs;observedDraws=draws;observedSent=sent;observedGenerated=generated
        },0)
    }
    fun snapshot()=FrameGenerationSnapshot(when {
        !enabled -> phase
        (generatedRate ?: 0.0)>0 -> FrameGenerationPhase.ACTIVE
        phase==FrameGenerationPhase.ACTIVE && generatedRate==0.0 -> FrameGenerationPhase.WAITING
        else -> phase
    },generated,submittedRate,drawRate,generatedRate)
    fun diagnostics()=String.format(Locale.US,"Frame generation: %s | enabled=%s | input=%d real=%d generated=%d duplicate=%d replaced=%d expired=%d errors=%d | GL work incl. waits mean/P95=%.2f/%.2f ms | counts are submissions, not physical FPS/input latency\n%s",
        reason,enabled,incoming,real,generated,duplicates,replaced,expired,errors,workMeanMs,workP95Ms,backendInfo)
    private fun retireBackend(){
        val old=backend ?: return;backend=null;retiring++
        old.close {retiring--;finishShutdown()}
    }
    private fun finishShutdown(){if(closed&&retiring==0&&!schedulerClosed){schedulerClosed=true;scheduler.close()}}
    override fun close(){
        synchronized(lock){if(closed)return;closed=true;pending?.release();pending=null;scheduler.remove(drain)}
        scheduler.post(Runnable {
            flushHeld(false)
            retireBackend()
            finishShutdown()
        },0)
    }
}
