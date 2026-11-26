package org.webrtc;

import android.content.Context;
import android.opengl.GLES20;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Optional source-resolution restoration. All objects/GL work belong to EglRenderer's thread. */
public final class ImageTreatmentPipeline {
  public enum Input { OES,RGB,YUV }
  private static final long MAX_BYTES=64L*1024*1024,NEURAL_PIXELS=1920L*1080;
  private static final float[] IDENTITY=new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
  private static final String VERTEX="attribute vec2 in_pos;varying vec2 tc;void main(){gl_Position=vec4(in_pos,0.,1.);tc=in_pos*.5+.5;}";
  private static final FloatBuffer QUAD=GlUtil.createFloatBuffer(new float[]{-1,-1,1,-1,-1,1,1,1});
  private final Context context;
  private final ImageLabPipeline lab;
  private ReconstructionDrawer labRestorer;
  private volatile int usedNeuralAmount;
  private final int[] framebuffer=new int[1],viewport=new int[4],activeTexture=new int[1],scissorBox=new int[4];
  private int reconstructionLevel,reconstructionStrength=50,comparison,globalComparison;
  private int finalX,finalY,finalWidth,finalHeight;
  private final ReconstructionDrawer[] reconstruction=new ReconstructionDrawer[3];
  private boolean scissor,blend,closed,broken,historyValid,neuralRejected;
  private TreatmentOptions options=TreatmentOptions.OFF;
  private TreatmentOptions finishingOptions=TreatmentOptions.OFF;
  private int sharpness,saturation=100,brightness,contrast=100,lastW,lastH;
  private long lastFrameNs;
  private final float[] lastTransform=new float[16];
  private boolean transformKnown;
  private GlTextureFrameBuffer a,b,history,motion;
  private Pass motionPass;
  private boolean motionAllowed=true,motionRejected;
  private volatile boolean usedMotion,mergedRestoration;
  private volatile String motionStatus="off";
  private HalfTarget featureA,featureB;
  private GlRectDrawer copy;
  private TreatmentShaderDrawer spatial;
  private Pass featurePassA,featurePassB,neuralApply,temporal;
  private volatile String failure,neuralStatus="off";
  private volatile int passes;
  private volatile boolean usedTemporal,usedNeural;
  private volatile boolean usedScaling;
  private volatile long frames,totalNs,maxNs,workingBytes;

  public ImageTreatmentPipeline(Context context) { this.context=context.getApplicationContext();lab=new ImageLabPipeline(this.context); }
  public void configureLab(boolean enabled,int restoration,int protection,float scale,int comparison,int level,boolean neural) {
    if(lab.configure(enabled,restoration,protection,scale,comparison,level,neural))historyValid=false;
  }
  public void configureFsrDetails(int level,int strength) {
    if(reconstructionLevel!=level || reconstructionStrength!=strength) historyValid=false;
    reconstructionLevel=Math.max(0,Math.min(3,level));reconstructionStrength=Math.max(0,Math.min(100,strength));
  }
  public void configureComparison(int value) {
    value=Math.max(0,Math.min(2,value));if(globalComparison!=value)historyValid=false;globalComparison=value;
  }
  public void configureMotionHistory(boolean allowed) {
    if(motionAllowed!=allowed)historyValid=false;motionAllowed=allowed;
  }
  public void configure(TreatmentOptions requested,int sharpness,int saturation,int brightness,int contrast) {
    if(options!=requested) {
      historyValid=false;finishingOptions=new TreatmentOptions(requested.sharpening,0,0,0,0,0,0);
    }
    options=requested;this.sharpness=sharpness;
    this.saturation=saturation;this.brightness=brightness;this.contrast=contrast;
  }
  private TreatmentShaderDrawer spatial() {
    if(spatial==null) spatial=new TreatmentShaderDrawer(context);
    return spatial;
  }
  private static void input(RendererCommon.GlDrawer drawer,Input kind,int texture,int[] textures,float[] matrix,
      int fw,int fh,int x,int y,int w,int h) {
    if(kind==Input.OES) drawer.drawOes(texture,matrix,fw,fh,x,y,w,h);
    else if(kind==Input.RGB) drawer.drawRgb(texture,matrix,fw,fh,x,y,w,h);
    else drawer.drawYuv(textures,matrix,fw,fh,x,y,w,h);
  }
  private GlTextureFrameBuffer target(boolean second,int w,int h) {
    GlTextureFrameBuffer out=second?b:a;
    if(out==null) { out=new GlTextureFrameBuffer(GLES20.GL_RGBA);if(second)b=out;else a=out; }
    out.setSize(w,h);return out;
  }
  private static void bind(GlTextureFrameBuffer target) { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,target.getFrameBufferId()); }
  private void original() {
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0]);
    if(comparison==2 && !lab.active()) clip(finalX+finalWidth/2,finalY,finalWidth-finalWidth/2,finalHeight);
    else if(scissor) { GLES20.glEnable(GLES20.GL_SCISSOR_TEST);GLES20.glScissor(scissorBox[0],scissorBox[1],scissorBox[2],scissorBox[3]); }
    else GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
  }
  private void clip(int x,int y,int w,int h) {
    int right=x+w,top=y+h;
    if(scissor) { x=Math.max(x,scissorBox[0]);y=Math.max(y,scissorBox[1]);right=Math.min(right,scissorBox[0]+scissorBox[2]);top=Math.min(top,scissorBox[1]+scissorBox[3]); }
    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);GLES20.glScissor(x,y,Math.max(0,right-x),Math.max(0,top-y));
  }
  public boolean draw(Input kind,int texture,int[] textures,float[] matrix,int fw,int fh,int iw,int ih,
      int x,int y,int width,int height,FsrUpscaler fsr) {
    if(closed || broken || iw<=0 || ih<=0) return false;
    Input rawKind=kind;long started=System.nanoTime();
    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING,framebuffer,0);
    GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0);
    GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE,activeTexture,0);
    GLES20.glGetIntegerv(GLES20.GL_SCISSOR_BOX,scissorBox,0);
    scissor=GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);blend=GLES20.glIsEnabled(GLES20.GL_BLEND);
    finalX=x;finalY=y;finalWidth=width;finalHeight=height;
    try {
      passes=0;usedTemporal=false;usedNeural=false;usedNeuralAmount=0;usedScaling=false;usedMotion=false;mergedRestoration=false;
      comparison=globalComparison!=0?globalComparison:lab.enabled()?lab.comparison():0;
      if(comparison==1) {
        if(copy==null)copy=new GlRectDrawer();
        if(sharpness==0 && saturation==100 && brightness==0 && contrast==100)
          input(copy,kind,texture,textures,matrix,fw,fh,x,y,width,height);
        else {
          spatial().configure(TreatmentOptions.OFF,0,saturation,brightness,contrast,true);
          spatial().setFinalDither(false);input(spatial(),kind,texture,textures,matrix,fw,fh,x,y,width,height);
        }
        passes=1;historyValid=false;return true;
      }
      long pixels=(long)iw*ih;
      if(pixels>3840L*2160 || pixels*12>MAX_BYTES) throw new IllegalStateException("source exceeds treatment memory limit");
      boolean restoreFsr=fsr!=null && reconstructionLevel>0 && reconstructionStrength>0 && pixels<=NEURAL_PIXELS;
      boolean changed=iw!=lastW || ih!=lastH;
      if(changed || started-lastFrameNs>200_000_000L) historyValid=false;
      if(!transformKnown || !java.util.Arrays.equals(matrix,lastTransform)) {
        historyValid=false;System.arraycopy(matrix,0,lastTransform,0,16);transformKnown=true;
      }
      lastW=iw;lastH=ih;lastFrameNs=started;
      boolean wantsHistory=options.temporal>0;
      boolean labNeural=lab.neural();
      long motionReserve=wantsHistory&&motionAllowed&&pixels<=NEURAL_PIXELS?64L*36*4:0;
      long reserve=pixels*(8+(wantsHistory?4:0)+((options.neural>0||labNeural)?16:0)+(fsr!=null?4:0))+motionReserve;
      boolean labActive=lab.begin(iw,ih,width,height,reserve,fsr!=null?2:1);
      if(!labActive && labNeural) {
        labNeural=false;reserve=pixels*(8+(wantsHistory?4:0)+(options.neural>0?16:0)+(fsr!=null?4:0))+motionReserve;
        labActive=lab.begin(iw,ih,width,height,reserve,fsr!=null?2:1);
      }
      int neuralAmount=Math.max(options.neural,labActive&&labNeural?lab.restoration():0);
      boolean wantsNeural=neuralAmount>0 && neuralAvailable(iw,ih);
      if(comparison==2 && !labActive) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0]);
        clip(x,y,width/2,height);
        spatial().configure(TreatmentOptions.OFF,0,saturation,brightness,contrast,true);
        spatial().setFinalDither(false);
        input(spatial(),kind,texture,textures,matrix,fw,fh,x,y,width,height);passes++;
      }
      if(labActive) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glDisable(GLES20.GL_BLEND);
        if(copy==null)copy=new GlRectDrawer();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,lab.baseFramebuffer());
        input(copy,kind,texture,textures,matrix,fw,fh,0,0,iw,ih);passes++;
        kind=Input.RGB;texture=lab.baseTexture();textures=null;matrix=IDENTITY;fw=iw;fh=ih;
      }
      if(!wantsHistory && history!=null) { history.release();history=null;historyValid=false; }
      if(!wantsHistory || !motionAllowed)releaseMotionTarget();
      if(!wantsNeural) releaseFeatures();
      if(!wantsHistory && !wantsNeural) {
        if(b!=null && !labActive && !restoreFsr) { b.release();b=null; }
        if((!options.spatial() || fsr==null) && a!=null && !labActive && !restoreFsr) { a.release();a=null; }
      }
      if(!labActive && !wantsHistory && !wantsNeural && !options.spatial() && sharpness==0 && fsr==null) {
        workingBytes=0;return false;
      }
      if(!labActive && !wantsHistory && !wantsNeural && fsr==null) {
        // Common 1080p case: selective cleanup + colors + sharpening in the existing input pass.
        original();spatial().configure(options,sharpness,saturation,brightness,contrast,true);
        input(spatial(),kind,texture,textures,matrix,fw,fh,x,y,width,height);passes++;
      } else {
        Input finalKind=kind;int finalTexture=texture;int[] finalTextures=textures;
        float[] finalMatrix=matrix;int finalW=fw,finalH=fh;
        if(wantsHistory || wantsNeural || options.spatial()) {
          GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glDisable(GLES20.GL_BLEND);
          GlTextureFrameBuffer current=target(false,iw,ih);
          if(copy==null) copy=new GlRectDrawer();
          bind(current);
          if(!wantsHistory && !wantsNeural) {
            spatial().configure(options,0,100,0,100,false);
            input(spatial(),kind,texture,textures,matrix,fw,fh,0,0,iw,ih);
          } else input(copy,kind,texture,textures,matrix,fw,fh,0,0,iw,ih);
          passes++;
          if(wantsNeural) {
            try {
              if(featurePassA==null) featurePassA=new Pass(TreatmentShaderDrawer.asset(context,"neural-0.frag"));
              if(featurePassB==null) featurePassB=new Pass(TreatmentShaderDrawer.asset(context,"neural-1.frag"));
              if(neuralApply==null) neuralApply=new Pass(TreatmentShaderDrawer.asset(context,"neural-apply.frag"));
              featurePassA.draw(featureA.framebuffer,iw,ih,current.getTextureId(),0,0,0);
              featurePassB.draw(featureB.framebuffer,iw,ih,current.getTextureId(),0,0,0);
              GlTextureFrameBuffer out=target(true,iw,ih);
              neuralApply.draw(out.getFrameBufferId(),iw,ih,current.getTextureId(),featureA.texture,featureB.texture,neuralAmount/100f);
              current=out;passes+=3;usedNeural=true;usedNeuralAmount=neuralAmount;neuralStatus="active (3 GLES2 passes, half-float features)";
            } catch(RuntimeException rejected) {
              neuralRejected=true;neuralStatus="unavailable: "+rejected.getClass().getSimpleName();releaseFeatures();clearErrors();
            }
          }
          if(options.spatial() && (wantsHistory || wantsNeural)) {
            GlTextureFrameBuffer out=target(current==a,iw,ih);bind(out);
            spatial().configure(options,0,100,0,100,false);
            spatial().drawRgb(current.getTextureId(),IDENTITY,iw,ih,0,0,iw,ih);current=out;passes++;
          }
          if(wantsHistory) {
            if(history==null) history=new GlTextureFrameBuffer(GLES20.GL_RGBA);
            history.setSize(iw,ih);
            GlTextureFrameBuffer next=current;
            if(historyValid) {
              int motionTexture=0;
              if(motionAllowed && !motionRejected && pixels<=NEURAL_PIXELS) {
                try {
                  if(motion==null)motion=new GlTextureFrameBuffer(GLES20.GL_RGBA);
                  motion.setSize(64,36);
                  if(motionPass==null)motionPass=new Pass(TreatmentShaderDrawer.asset(context,"fg-flow.frag"));
                  // Match each current pixel to the previous source. Share the estimator
                  // implementation with interpolation, without adding another full-size history.
                  motionPass.flow(motion.getFrameBufferId(),iw,ih,history.getTextureId(),current.getTextureId());
                  motionTexture=motion.getTextureId();passes++;usedMotion=true;motionStatus="64x36 backward alignment";
                } catch(RuntimeException unsupported) {
                  motionRejected=true;motionStatus="local search fallback: "+unsupported.getClass().getSimpleName();
                  releaseMotionTarget();clearErrors();
                }
              } else motionStatus=motionAllowed?"local search (source/driver budget)":"local search (device policy)";
              if(temporal==null) temporal=new Pass(TreatmentShaderDrawer.asset(context,"temporal.frag"));
              next=target(current==a,iw,ih);
              temporal.draw(next.getFrameBufferId(),iw,ih,current.getTextureId(),history.getTextureId(),motionTexture,options.temporal/100f);
              passes++;usedTemporal=true;
            }
            // The old source becomes history by ownership rotation, avoiding a full-frame copy.
            // Keep history distinct from both scratch slots and from the temporal output.
            GlTextureFrameBuffer retiredHistory=history;
            history=current;
            if(current==a)a=retiredHistory;else b=retiredHistory;
            historyValid=true;current=next;
          }
          finalKind=Input.RGB;finalTexture=current.getTextureId();finalTextures=null;finalMatrix=IDENTITY;finalW=iw;finalH=ih;
        }
        boolean restoreLab=labActive && lab.restoration()>0 && !usedNeural;
        if(restoreFsr || restoreLab) {
          GlTextureFrameBuffer restored=target(a!=null && finalTexture==a.getTextureId(),iw,ih);
          GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glDisable(GLES20.GL_BLEND);bind(restored);
          ReconstructionDrawer restorer;
          if(restoreFsr) {
            int index=reconstructionLevel-1;
            if(reconstruction[index]==null) reconstruction[index]=new ReconstructionDrawer(context,reconstructionLevel);
            restorer=reconstruction[index];
          } else {
            if(labRestorer==null) labRestorer=new ReconstructionDrawer(context,1);
            restorer=labRestorer;
          }
          int strength=Math.max(restoreFsr?reconstructionStrength:0,restoreLab?lab.restoration():0);
          mergedRestoration=restoreFsr && restoreLab;
          restorer.configure(strength,100,0,100,false);
          // Restore the decoded-size image once. EASU alone resamples it to the presentation size.
          input(restorer,finalKind,finalTexture,finalTextures,finalMatrix,finalW,finalH,0,0,iw,ih);passes++;
          finalKind=Input.RGB;finalTexture=restored.getTextureId();finalTextures=null;finalMatrix=IDENTITY;finalW=iw;finalH=ih;
        }
        int drawX=x,drawY=y,drawWidth=width,drawHeight=height;
        if(labActive) {
          GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glDisable(GLES20.GL_BLEND);
          GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,lab.stageFramebuffer());
          drawX=0;drawY=0;drawWidth=lab.width();drawHeight=lab.height();
        } else original();
        if(fsr!=null && drawFsr(fsr,finalKind,finalTexture,finalTextures,finalMatrix,finalW,finalH,drawX,drawY,drawWidth,drawHeight)) {
          passes+=fsr.lastPassCount();usedScaling=true;
        } else {
          spatial().configure(finishingOptions,sharpness,saturation,brightness,contrast,true);
          spatial().setFinalDither(options.deband>0);
          input(spatial(),finalKind,finalTexture,finalTextures,finalMatrix,finalW,finalH,drawX,drawY,drawWidth,drawHeight);passes++;
        }
        if(labActive) {
          original();lab.composite(framebuffer[0],x,y,width,height,iw,ih,saturation,brightness,contrast,comparison);passes++;
        }
      }
      GlUtil.checkNoGLES2Error("image treatment");
      long elapsed=System.nanoTime()-started;totalNs+=elapsed;maxNs=Math.max(maxNs,elapsed);frames++;
      workingBytes=pixels*4*((a!=null?1:0)+(b!=null?1:0)+(history!=null?1:0))+(featureA!=null?pixels*16:0)+lab.bytes()+(motion!=null?64L*36*4:0);
      return true;
    } catch(RuntimeException error) {
      broken=true;failure="image treatment rejected: "+error.getClass().getSimpleName();clearErrors();return false;
    } finally {
      for(int unit=0;unit<3;unit++) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0+unit);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,0); }
      if(rawKind==Input.OES) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,0); }
      GLES20.glActiveTexture(activeTexture[0]);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0]);
      GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
      if(scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST);else GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
      GLES20.glScissor(scissorBox[0],scissorBox[1],scissorBox[2],scissorBox[3]);
      if(blend) GLES20.glEnable(GLES20.GL_BLEND);else GLES20.glDisable(GLES20.GL_BLEND);
    }
  }
  private static boolean drawFsr(FsrUpscaler f,Input kind,int texture,int[] textures,float[] matrix,int fw,int fh,int x,int y,int w,int h) {
    if(kind==Input.OES) return f.drawOes(texture,matrix,fw,fh,x,y,w,h);
    if(kind==Input.RGB) return f.drawRgb(texture,matrix,fw,fh,x,y,w,h);
    return f.drawYuv(textures,matrix,fw,fh,x,y,w,h);
  }
  private boolean neuralAvailable(int w,int h) {
    if(neuralRejected) return false;
    if((long)w*h>NEURAL_PIXELS || (long)w*h*28>MAX_BYTES) { neuralStatus="source/memory limit (up to 1080p)";return false; }
    if(featureA!=null && featureB!=null && featureA.width==w && featureA.height==h && featureB.width==w && featureB.height==h) return true;
    try {
      int[] range=new int[2],precision=new int[1];
      GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER,GLES20.GL_HIGH_FLOAT,range,0,precision,0);
      if(precision[0]<23) { neuralRejected=true;neuralStatus="full-precision fragment math unavailable";return false; }
      String ext=GLES20.glGetString(GLES20.GL_EXTENSIONS);
      String version=GLES20.glGetString(GLES20.GL_VERSION);
      boolean core=version!=null && version.matches(".*OpenGL ES [3-9].*");
      boolean oes=ext!=null && ext.contains("GL_OES_texture_half_float");
      if(!oes && !core) {
        neuralRejected=true;neuralStatus="half-float rendering not declared";return false;
      }
      if(featureA==null) featureA=new HalfTarget(!oes);if(featureB==null) featureB=new HalfTarget(!oes);
      featureA.resize(w,h);featureB.resize(w,h);return true;
    } catch(RuntimeException rejected) {
      neuralRejected=true;neuralStatus="half-float framebuffer rejected";releaseFeatures();clearErrors();return false;
    }
  }
  private static void clearErrors() { for(int i=0;i<8 && GLES20.glGetError()!=GLES20.GL_NO_ERROR;i++) {} }
  private void releaseFeatures() {
    if(featureA!=null) featureA.release();if(featureB!=null) featureB.release();featureA=null;featureB=null;
  }
  public String failureReason() { return failure; }
  public boolean didScaleThisFrame() { return usedScaling; }
  public int effectiveTemporal() { return usedTemporal && !broken ? options.temporal : 0; }
  private void releaseMotionTarget(){if(motion!=null)motion.release();motion=null;usedMotion=false;}
  public int effectiveNeural() { return usedNeural && !broken ? usedNeuralAmount : 0; }
  public String diagnostics() {
    return lab.diagnostics()+"\n"+(reconstructionLevel>0?"FSR detail: level="+reconstructionLevel+" | strength="+reconstructionStrength+"% | comparison="+comparison+" | ":"")+String.format(Locale.US,"Treatment: spatial=%s | sharpen=%d | temporal=%s | neural=%s | passes=%d | storage=%.1f MiB | CPU/GL submission=%.2f/%.2f ms (not GPU/display)",
        options.spatial(),options.sharpening,usedTemporal,(options.neural>0||lab.neural())?neuralStatus:"off",passes,
        workingBytes/1048576.,frames==0?0:totalNs/1e6/frames,maxNs/1e6)+
        " | motion="+(usedMotion?motionStatus:"off/local")+" | merged restoration="+mergedRestoration+(failure==null?"":" | "+failure);
  }
  public void release() {
    if(closed)return;closed=true;
    if(a!=null)a.release();if(b!=null)b.release();if(history!=null)history.release();releaseFeatures();
    lab.release();if(labRestorer!=null)labRestorer.release();
    releaseMotionTarget();if(motionPass!=null)motionPass.shader.release();
    if(copy!=null)copy.release();if(spatial!=null)spatial.release();
    for(ReconstructionDrawer drawer:reconstruction) if(drawer!=null) drawer.release();
    for(Pass pass:new Pass[]{featurePassA,featurePassB,neuralApply,temporal}) if(pass!=null)pass.shader.release();
  }
  private static final class Pass {
    final GlShader shader;final int position;final Map<String,Integer> uniforms=new HashMap<>();
    Pass(String source) { shader=new GlShader(VERTEX,source);position=shader.getAttribLocation("in_pos"); }
    int u(String name) { Integer old=uniforms.get(name);if(old!=null)return old;int value;try { value=shader.getUniformLocation(name); } catch(RuntimeException optimizedOut) { value=-1; } uniforms.put(name,value);return value; }
    void draw(int target,int w,int h,int source,int second,int third,float strength) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,target);GLES20.glViewport(0,0,w,h);shader.useProgram();
      GLES20.glUniform2f(u("frame_size"),w,h);GLES20.glUniform1f(u("strength"),strength);
      GLES20.glUniform1i(u("use_motion"),third!=0?1:0);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,source);GLES20.glUniform1i(u("source"),0);
      if(second!=0) { GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,second);GLES20.glUniform1i(u("features_a"),1);GLES20.glUniform1i(u("history_tex"),1); }
      if(third!=0) { GLES20.glActiveTexture(GLES20.GL_TEXTURE2);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,third);GLES20.glUniform1i(u("features_b"),2);GLES20.glUniform1i(u("motion_tex"),2); }
      QUAD.position(0);GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,QUAD);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkNoGLES2Error("treatment pass");
    }
    void flow(int target,int sourceWidth,int sourceHeight,int previous,int current) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,target);GLES20.glViewport(0,0,64,36);shader.useProgram();
      GLES20.glUniform2f(u("frame_size"),sourceWidth,sourceHeight);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,previous);GLES20.glUniform1i(u("source"),0);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,current);GLES20.glUniform1i(u("history_tex"),1);
      QUAD.position(0);GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,QUAD);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkNoGLES2Error("temporal alignment");
    }
  }
  private static final class HalfTarget {
    int texture,framebuffer,width,height;
    final boolean core;
    HalfTarget(boolean core) {
      this.core=core;
      int[] id=new int[1];GLES20.glGenTextures(1,id,0);texture=id[0];GLES20.glGenFramebuffers(1,id,0);framebuffer=id[0];
    }
    void resize(int w,int h) {
      if(width==w && height==h)return;
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,core?0x881A:GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,core?0x140B:0x8D61,null);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);
      GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,texture,0);
      if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)
        throw new IllegalStateException("Half-float framebuffer unavailable");
      GlUtil.checkNoGLES2Error("neural feature storage");width=w;height=h;
    }
    void release() { GLES20.glDeleteTextures(1,new int[]{texture},0);GLES20.glDeleteFramebuffers(1,new int[]{framebuffer},0); }
  }
}
