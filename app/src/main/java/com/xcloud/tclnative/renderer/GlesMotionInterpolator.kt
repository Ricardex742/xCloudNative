package com.xcloud.tclnative.renderer

import android.content.Context
import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.Locale

/** Original GLES implementation. Source pixels stay on GPU except a fixed 32x18 luma signature. */
internal class GlesMotionInterpolator(context: Context,shared: EglBase.Context,private val handler: Handler,
    private var protection: Int): MotionInterpolationBackend {
    private var egl: EglBase?=null
    private val frameDrawer=VideoFrameDrawer()
    private val copy=GlRectDrawer()
    private val yuv=YuvConverter()
    private var previous: GlTextureFrameBuffer?=null
    private var current: GlTextureFrameBuffer?=null
    private var signature: GlTextureFrameBuffer?=null
    private var forward: GlTextureFrameBuffer?=null
    private var backward: GlTextureFrameBuffer?=null
    private var flow: LabGlPass?=null
    private var midpoint: LabGlPass?=null
    private var thumbnail: LabGlPass?=null
    private val assets=context.applicationContext
    private var copyTimer: LabGpuTimer?=null
    private var flowTimer: LabGpuTimer?=null
    private val pixels=ByteBuffer.allocateDirect(32*18*4)
    private val luma=ByteArray(32*18)
    private var width=0;private var height=0;private var rotation=0
    private var valid=false;private var closing=false;private var released=false
    private var closeCallback: (() -> Unit)?=null
    private data class Slot(var target: GlTextureFrameBuffer?=null,var busy: Boolean=false)
    private val slots=Array(2){Slot()}
    init {
        val created=EglBase.create(shared,EglBase.configBuilder().setOpenGlesVersion(3).setSupportsPixelBuffer(true).createConfigAttributes())
        try {
            created.createDummyPbufferSurface();created.makeCurrent();egl=created
            copyTimer=LabGpuTimer();flowTimer=LabGpuTimer()
        }catch(error: Exception){runCatching {created.release()};throw error}
    }
    override fun prepare(frame: VideoFrame): ByteArray {
        check(!closing)
        val w=frame.rotatedWidth;val h=frame.rotatedHeight
        require(w>0&&h>0&&w<=4096&&h<=4096&&w.toLong()*h<=2_500_000L){"frame generation requires at most 2.5 MP"}
        if(width!=w||height!=h||rotation!=frame.rotation){valid=false;width=w;height=h;rotation=frame.rotation}
        if(previous==null)previous=GlTextureFrameBuffer(GLES20.GL_RGBA)
        if(current==null)current=GlTextureFrameBuffer(GLES20.GL_RGBA)
        previous!!.setSize(w,h);current!!.setSize(w,h)
        if(signature==null)signature=GlTextureFrameBuffer(GLES20.GL_RGBA).apply {setSize(32,18)}
        if(thumbnail==null)thumbnail=LabGlPass(assets,"fg-signature.frag")
        copyTimer?.begin()
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,current!!.frameBufferId)
            frameDrawer.drawFrame(frame,copy,null,0,0,w,h)
            thumbnail!!.draw(signature!!,w,h,mapOf("source" to current!!.textureId))
            pixels.clear();GLES20.glReadPixels(0,0,32,18,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels)
            repeat(luma.size){luma[it]=pixels.get(it*4)}
            GlUtil.checkNoGLES2Error("motion signature")
        }finally{copyTimer?.end()}
        return luma
    }
    override fun interpolate(timestampNs: Long): VideoFrame? {
        if(!valid||closing)return null
        val slot=slots.firstOrNull {!it.busy} ?: return null
        if(slot.target==null)slot.target=GlTextureFrameBuffer(GLES20.GL_RGBA)
        slot.target!!.setSize(width,height)
        if(forward==null)forward=GlTextureFrameBuffer(GLES20.GL_RGBA).apply {setSize(64,36)}
        if(backward==null)backward=GlTextureFrameBuffer(GLES20.GL_RGBA).apply {setSize(64,36)}
        if(flow==null)flow=LabGlPass(assets,"fg-flow.frag")
        if(midpoint==null)midpoint=LabGlPass(assets,"fg-interpolate.frag")
        flowTimer?.begin()
        try {
            flow!!.draw(forward!!,width,height,mapOf("history_tex" to previous!!.textureId,"source" to current!!.textureId))
            flow!!.draw(backward!!,width,height,mapOf("history_tex" to current!!.textureId,"source" to previous!!.textureId))
            midpoint!!.draw(slot.target!!,width,height,mapOf("source" to current!!.textureId,"history_tex" to previous!!.textureId,
                "flow_forward" to forward!!.textureId,"flow_backward" to backward!!.textureId),protection)
            // Finish the producer before another EGL context samples the output texture.
            GLES20.glFinish();GlUtil.checkNoGLES2Error("motion interpolation handoff")
        }finally{flowTimer?.end()}
        slot.busy=true
        val buffer=TextureBufferImpl(width,height,VideoFrame.TextureBuffer.Type.RGB,slot.target!!.textureId,
            Matrix(),handler,yuv){
            // A rendered buffer is released on the renderer's current EGL context.
            // A dropped, never-drawn pending frame may be released without a context.
            if(EGL14.eglGetCurrentContext()!=EGL14.EGL_NO_CONTEXT)GLES20.glFinish()
            handler.post {slot.busy=false;finishClose()}
        }
        return VideoFrame(buffer,0,timestampNs)
    }
    override fun commit(){val old=previous;previous=current;current=old;valid=true}
    override fun updateProtection(value: Int){protection=value}
    override fun reset(){valid=false}
    override fun diagnostics()=String.format(Locale.US,
        "GLES motion: %dx%d | flow=64x36 bidirectional | pool=%d/2 | GPU copy/flow+warp=%s/%s ms | buffers <=48 MiB | signature readback=32x18",
        width,height,slots.count {it.busy},copyTimer?.milliseconds()?.let {"%.2f".format(Locale.US,it)} ?: "n/a",
        flowTimer?.milliseconds()?.let {"%.2f".format(Locale.US,it)} ?: "n/a")
    override fun close(released: () -> Unit){
        if(this.released){released();return}
        if(closing)return
        closing=true;valid=false;closeCallback=released;finishClose()
    }
    private fun finishClose(){
        if(!closing||released||slots.any {it.busy})return
        released=true
        runCatching {
            egl?.makeCurrent()
            listOf(previous,current,signature,forward,backward).forEach {it?.release()}
            slots.forEach {it.target?.release();it.target=null}
            flow?.release();midpoint?.release();thumbnail?.release();copy.release();frameDrawer.release();yuv.release()
            copyTimer?.release();flowTimer?.release()
        }
        runCatching {egl?.release()};egl=null
        closeCallback?.invoke();closeCallback=null
    }
}

/** Shader pass with stable sampler bindings. All assets are original xCloudNative GLES code. */
internal class LabGlPass(context: Context,asset: String){
    private val shader=GlShader("attribute vec2 pos;varying vec2 tc;void main(){gl_Position=vec4(pos,0.,1.);tc=pos*.5+.5;}",TreatmentShaderDrawer.asset(context,asset))
    private val quad=GlUtil.createFloatBuffer(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f))
    private val uniforms=HashMap<String,Int>()
    private fun u(name: String)=uniforms.getOrPut(name){runCatching {shader.getUniformLocation(name)}.getOrDefault(-1)}
    fun draw(target: GlTextureFrameBuffer,w: Int,h: Int,textures: Map<String,Int>,protection: Int=0){
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,target.frameBufferId);GLES20.glViewport(0,0,target.width,target.height)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glDisable(GLES20.GL_BLEND);shader.useProgram()
        GLES20.glUniform2f(u("frame_size"),w.toFloat(),h.toFloat());GLES20.glUniform1i(u("protection"),protection)
        textures.entries.forEachIndexed {index,(name,texture)->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0+index);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);GLES20.glUniform1i(u(name),index)
        }
        val p=shader.getAttribLocation("pos");quad.position(0);GLES20.glEnableVertexAttribArray(p);GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,0,quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkNoGLES2Error("image lab pass")
    }
    fun release(){shader.release()}
}

/** Non-blocking GPU timestamps only where EXT_disjoint_timer_query is advertised. */
internal class LabGpuTimer {
    private val ids=IntArray(2)
    private val pending=BooleanArray(2)
    private var supported=GLES20.glGetString(GLES20.GL_EXTENSIONS)?.split(' ')?.contains("GL_EXT_disjoint_timer_query")==true
    private var active=-1
    private var latest: Double?=null
    init {if(supported){GLES30.glGenQueries(2,ids,0);if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)supported=false}}
    fun begin(){
        if(!supported)return
        val disjoint=IntArray(1);GLES20.glGetIntegerv(0x8FBB,disjoint,0)
        if(disjoint[0]!=0)latest=null
        val result=IntArray(1)
        repeat(2){i->
            if(pending[i]){
                GLES30.glGetQueryObjectuiv(ids[i],0x8867,result,0)
                if(result[0]!=0){
                    GLES30.glGetQueryObjectuiv(ids[i],0x8866,result,0)
                    if(disjoint[0]==0)latest=(result[0].toLong() and 0xffffffffL)/1e6
                    pending[i]=false
                }
            }
        }
        active=pending.indexOfFirst {!it}
        if(active>=0)GLES30.glBeginQuery(0x88BF,ids[active])
        if(GLES20.glGetError()!=GLES20.GL_NO_ERROR){supported=false;active=-1;latest=null}
    }
    fun end(){if(supported&&active>=0){GLES30.glEndQuery(0x88BF);pending[active]=true;active=-1}}
    fun milliseconds()=latest
    fun release(){if(ids.any {it!=0})GLES30.glDeleteQueries(2,ids,0)}
}
