package org.webrtc;

import android.content.Context;
import android.opengl.GLES20;
import java.nio.FloatBuffer;
import java.util.Locale;

/** GL-thread-only residual composition and bounded supersampling. No pixel readback. */
public final class ImageLabPipeline {
  private static final FloatBuffer QUAD=GlUtil.createFloatBuffer(new float[]{-1,-1,1,-1,-1,1,1,1});
  private static final String VERTEX="attribute vec2 pos;varying vec2 tc;void main(){gl_Position=vec4(pos,0.,1.);tc=pos*.5+.5;}";
  private final Context context;
  private GlTextureFrameBuffer base,stage;
  private GlShader shader;
  private boolean enabled,active,neural;
  private int restoration,protection,comparison,level=3,stageW,stageH;
  private float requestedScale=1,appliedScale=1;
  private volatile String reason="off";
  public ImageLabPipeline(Context context){this.context=context;}
  public boolean configure(boolean enabled,int restoration,int protection,float scale,int comparison,int level,boolean neural){
    boolean changed=this.enabled!=enabled||this.restoration!=restoration||this.protection!=protection||this.requestedScale!=scale||this.comparison!=comparison||this.level!=level||this.neural!=neural;
    this.enabled=enabled;this.neural=neural;this.restoration=Math.max(0,Math.min(100,restoration));
    this.protection=protection;this.requestedScale=Math.max(1,Math.min(2,scale));this.comparison=comparison;this.level=level;return changed;
  }
  public boolean enabled(){return enabled&&level>0;}
  public int restoration(){return enabled()?restoration:0;}
  public boolean neural(){return enabled()&&level>=2&&restoration>0&&neural;}
  public boolean begin(int iw,int ih,int ow,int oh,long otherBytes,int stageCopies){
    active=false;
    if(!enabled()){releaseTargets();reason="off";return false;}
    int[] max=new int[1];GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,max,0);
    float wanted=level>=3?requestedScale:1;
    ImageLabPlan plan=ImageLabPlan.choose(iw,ih,ow,oh,wanted,max[0],otherBytes,stageCopies);
    if(plan!=null){
      if(base==null)base=new GlTextureFrameBuffer(GLES20.GL_RGBA);
      if(stage==null)stage=new GlTextureFrameBuffer(GLES20.GL_RGBA);
      base.setSize(iw,ih);stage.setSize(plan.width,plan.height);stageW=plan.width;stageH=plan.height;appliedScale=plan.scale;active=true;
      reason=plan.scale<wanted?"supersampling reduced by texture/memory budget":"active";
      return true;
    }
    releaseTargets();reason="source/output exceeds 64 MiB image-lab budget";return false;
  }
  public int baseFramebuffer(){return base.getFrameBufferId();}
  public int baseTexture(){return base.getTextureId();}
  public int stageFramebuffer(){return stage.getFrameBufferId();}
  public int width(){return stageW;}
  public int height(){return stageH;}
  public int comparison(){return comparison;}
  public boolean active(){return active;}
  public long bytes(){return base==null?0:4L*base.getWidth()*base.getHeight()+4L*stageW*stageH;}
  public void composite(int framebuffer,int x,int y,int width,int height,int iw,int ih,int saturation,int brightness,int contrast,int frameComparison){
    if(shader==null)shader=new GlShader(VERTEX,TreatmentShaderDrawer.asset(context,"lab-composite.frag"));
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);GLES20.glViewport(x,y,width,height);shader.useProgram();
    pair("frame_size",iw,ih);pair("stage_size",stageW,stageH);pair("output_size",width,height);
    GLES20.glUniform1f(u("saturation"),saturation/100f);GLES20.glUniform1f(u("brightness"),brightness/100f);
    GLES20.glUniform1f(u("contrast_adjust"),contrast/100f-1);
    GLES20.glUniform1i(u("protection"),protection);GLES20.glUniform1i(u("comparison"),frameComparison);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,base.getTextureId());GLES20.glUniform1i(u("source"),0);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,stage.getTextureId());GLES20.glUniform1i(u("processed"),1);
    int p=shader.getAttribLocation("pos");QUAD.position(0);GLES20.glEnableVertexAttribArray(p);GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,0,QUAD);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkNoGLES2Error("image lab composite");
  }
  private int u(String name){return shader.getUniformLocation(name);}
  private void pair(String name,float a,float b){GLES20.glUniform2f(u(name),a,b);}
  private void releaseTargets(){if(base!=null)base.release();if(stage!=null)stage.release();base=null;stage=null;active=false;}
  public String diagnostics(){return String.format(Locale.US,"Image lab: %s | level=%d | restoration=%d%% | protection=%d | supersampling requested/applied=%.2fx/%.2fx | stage=%dx%d | storage=%.1f MiB",reason,level,restoration,protection,requestedScale,appliedScale,stageW,stageH,bytes()/1048576.);}
  public void release(){releaseTargets();if(shader!=null)shader.release();shader=null;}
}
