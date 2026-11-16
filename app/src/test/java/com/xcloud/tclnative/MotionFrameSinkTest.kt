package com.xcloud.tclnative

import com.xcloud.tclnative.renderer.*
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class MotionFrameSinkTest {
    private class Buffer(private val done: ()->Unit={}): VideoFrame.Buffer {
        var refs=1
        override fun getWidth()=128
        override fun getHeight()=96
        override fun retain(){refs++}
        override fun release(){refs--;check(refs>=0);if(refs==0)done()}
        override fun toI420(): VideoFrame.I420Buffer=throw UnsupportedOperationException()
        override fun cropAndScale(x: Int,y: Int,w: Int,h: Int,sw: Int,sh: Int): VideoFrame.Buffer=throw UnsupportedOperationException()
    }
    private class Clock: FramePacingScheduler {
        var now=1_000_000_000L;var closes=0
        val tasks=linkedMapOf<Runnable,Long>()
        override fun nowNs()=now
        override fun post(task: Runnable,delayMs: Long){check(closes==0);tasks[task]=now+delayMs*1_000_000L}
        override fun remove(task: Runnable){tasks.remove(task)}
        override fun close(){closes++;tasks.clear()}
        fun advance(ns: Long){
            val target=now+ns
            while(true){
                val next=tasks.minByOrNull {it.value} ?: break
                if(next.value>target)break
                tasks.remove(next.key);now=maxOf(now,next.value);next.key.run()
            }
            now=maxOf(now,target)
        }
        fun flush(){advance(0)}
    }
    private class Backend(private val clock: Clock): MotionInterpolationBackend {
        var signature=20;var costNs=0L;var fail=false;var prepared=0;var committed=0;var leases=0;var closes=0
        var callback: (()->Unit)?=null
        val buffers=mutableListOf<Buffer>()
        override fun prepare(frame: VideoFrame): ByteArray {
            prepared++;clock.now+=costNs
            if(fail)throw IllegalStateException("injected EGL failure")
            return ByteArray(32){signature.toByte()}
        }
        override fun interpolate(timestampNs: Long): VideoFrame? {
            if(leases>=2)return null
            leases++
            val b=Buffer {leases--;if(leases==0)callback?.also {callback=null;it()}}
            buffers+=b;return VideoFrame(b,0,timestampNs)
        }
        override fun commit(){committed++}
        override fun reset()=Unit
        override fun diagnostics()="synthetic test backend"
        override fun close(released: ()->Unit){closes++;if(leases==0)released()else callback=released}
    }
    private fun push(sink: MotionFrameSink,timestamp: Long): Buffer {
        val b=Buffer();val f=VideoFrame(b,0,timestamp);sink.onFrame(f);f.release();return b
    }
    @Test fun effectiveStatusAndRatesCountSubmissionsAndDrawsSeparatelyAndClearStaleRates() {
        val clock=Clock();val backend=Backend(clock);var draws=0L
        val sink=MotionFrameSink(VideoSink { draws++ },{backend},clock)
        assertEquals(FrameGenerationPhase.WAITING,sink.snapshot().phase)
        sink.observePresentation(draws,1000);clock.flush()
        repeat(30) { i -> backend.signature=20+i*4;push(sink,1_000_000_000L+i*33_333_333L);clock.flush();clock.advance(33_333_333L) }
        sink.observePresentation(draws,2000);clock.flush()
        val active=sink.snapshot()
        assertEquals(FrameGenerationPhase.ACTIVE,active.phase);assertTrue(active.generated>0)
        assertTrue(active.submittedPerSecond!!>45);assertEquals(draws.toDouble(),active.drawsPerSecond!!,0.0)
        sink.setEnabled(false,FrameGenerationPhase.COMPARISON);clock.flush()
        assertEquals(FrameGenerationPhase.COMPARISON,sink.snapshot().phase)
        sink.observePresentation(draws,6000);clock.flush()
        assertNull(sink.snapshot().drawsPerSecond)
        sink.close();clock.flush()
    }
    @Test fun movingNativeThirtyFpsProducesOrderedMidpointsWithOneBoundedOriginalWait(){
        val clock=Clock();val backend=Backend(clock);val seen=mutableListOf<Long>();val buffers=mutableListOf<Buffer>()
        val sink=MotionFrameSink(VideoSink {seen+=it.timestampNs},{backend},clock)
        repeat(30){i->backend.signature=20+i*4;buffers+=push(sink,1_000_000_000L+i*33_333_333L);clock.flush();clock.advance(33_333_333L)}
        assertTrue(seen.zipWithNext().all {(a,b)->b>a});assertTrue(seen.size>45)
        assertTrue(sink.diagnostics().contains("generated=25"));assertTrue(buffers.all {it.refs==0})
        sink.close();clock.flush();assertEquals(1,clock.closes);assertEquals(1,backend.closes)
        assertTrue(backend.buffers.all {it.refs==0})
    }
    @Test fun sixtyFpsContentIsNeverReducedToThirtyOrClaimedAsGenerated(){
        val clock=Clock();val backend=Backend(clock);val seen=mutableListOf<Long>()
        val sink=MotionFrameSink(VideoSink {seen+=it.timestampNs},{backend},clock)
        repeat(50){i->backend.signature=20+i*4;val b=push(sink,1_000_000_000L+i*16_666_667L);clock.flush();assertEquals(0,b.refs);clock.advance(16_666_667L)}
        assertEquals(50,seen.size);assertTrue(backend.buffers.isEmpty())
        sink.close();clock.flush();assertEquals(1,clock.closes)
    }
    @Test fun duplicatedSixtyHzNeedsConfirmedAlternationAndKeepsTheFirstContentSample(){
        val probe=ContentFrameCadence();val decisions=(0 until 50).map {i->probe.observe(1_000_000_000L+i*16_666_667L,ByteArray(16){(20+i/2*4).toByte()})}
        assertTrue(decisions.take(12).none {it.action==ContentFrameCadence.Action.DUPLICATE})
        assertTrue(decisions.count {it.action==ContentFrameCadence.Action.INTERPOLATE}>12)
        val cut=probe.observe(2_000_000_000L,ByteArray(16){240.toByte()});assertEquals(ContentFrameCadence.Action.RESET,cut.action)
        assertFalse(probe.duplicateCadence)
        val noisy=ContentFrameCadence()
        repeat(100){i->assertNotEquals(ContentFrameCadence.Action.INTERPOLATE,noisy.observe(1_000_000_000L+i*16_666_667L,ByteArray(16){(100+i%2).toByte()}).action)}
    }
    @Test fun replacementAndCloseReleaseEveryInputWithoutAllocatingGpuForIneligibleDevices(){
        val clock=Clock();var opens=0;var real=0
        val sink=MotionFrameSink(VideoSink {real++},{opens++;Backend(clock)},clock,eligible=false)
        val first=push(sink,1);val second=push(sink,2)
        assertEquals(0,first.refs);assertEquals(1,second.refs);clock.flush();assertEquals(1,real);assertEquals(0,second.refs)
        val third=push(sink,3);sink.close();sink.close();clock.flush()
        assertEquals(0,third.refs);assertEquals(0,opens);assertEquals(1,clock.closes)
    }
    @Test fun eglFailureAndExcessProcessingCostFallBackToRealFramesInsteadOfEndingTheGame(){
        for(fails in listOf(true,false)){
            val clock=Clock();val backend=Backend(clock).apply {fail=fails;costNs=if(fails)0 else 12_000_000L}
            var opens=0;var received=0
            val sink=MotionFrameSink(VideoSink {received++},{opens++;backend},clock)
            val all=mutableListOf<Buffer>()
            repeat(60){i->backend.signature=20+i%20*4;all+=push(sink,1_000_000_000L+i*33_333_333L);clock.flush();clock.advance(33_333_333L)}
            assertEquals(1,opens);assertTrue(received>=60);assertTrue(sink.diagnostics().contains("enabled=false"));assertTrue(all.all {it.refs==0})
            sink.close();clock.flush();assertEquals(1,backend.closes);assertEquals(1,clock.closes)
        }
    }
    @Test fun shutdownWaitsForRetainedOutputTexturesAndNeverReusesALeasedBuffer(){
        val clock=Clock();val backend=Backend(clock);val retained=mutableListOf<VideoFrame>()
        val sink=MotionFrameSink(VideoSink {f->if(f.timestampNs%33_333_333L!=1_000_000_000L%33_333_333L){f.retain();retained+=f}},{backend},clock)
        repeat(10){i->backend.signature=20+i*4;push(sink,1_000_000_000L+i*33_333_333L);clock.flush();clock.advance(33_333_333L)}
        assertEquals(2,retained.size);assertEquals(2,backend.leases)
        sink.close();clock.flush();assertEquals(0,clock.closes)
        retained.forEach {it.release()};assertEquals(0,backend.leases);assertEquals(1,clock.closes)
    }
    @Test fun aTimestampEpochResetAndZeroTimestampsCannotFreezeTheOriginalPath(){
        val clock=Clock();val backend=Backend(clock);val seen=mutableListOf<Long>()
        val sink=MotionFrameSink(VideoSink {seen+=it.timestampNs},{backend},clock)
        for(ts in listOf(1_000_000_000L,1_033_333_333L,10L,0L,0L,33_333_343L)){
            push(sink,ts);clock.flush();clock.advance(33_333_333L)
        }
        assertTrue(seen.contains(10));assertEquals(2,seen.count {it==0L})
        sink.close();clock.flush();assertEquals(1,clock.closes)
    }
}
