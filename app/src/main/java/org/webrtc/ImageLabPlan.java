package org.webrtc;

/** Conservative total texture budget, including the selected upscaler's intermediate copy. */
public final class ImageLabPlan {
  public static final long LIMIT=64L*1024*1024;
  public final int width,height;
  public final float scale;
  public final long bytes;
  private ImageLabPlan(int w,int h,float scale,long bytes){width=w;height=h;this.scale=scale;this.bytes=bytes;}
  public static ImageLabPlan choose(int iw,int ih,int ow,int oh,float wanted,int maxTexture,long otherBytes,int stageCopies){
    if(iw<=0||ih<=0||ow<=0||oh<=0||iw>maxTexture||ih>maxTexture||otherBytes<0||!Float.isFinite(wanted))return null;
    wanted=Math.max(1,Math.min(2,wanted));
    for(float scale:new float[]{wanted,1.5f,1.25f,1f}){
      if(scale>wanted)continue;
      int w=Math.max(2,Math.round(ow*scale)),h=Math.max(2,Math.round(oh*scale));
      long bytes=otherBytes+4L*iw*ih+4L*w*h*Math.max(1,stageCopies);
      if(w<=maxTexture&&h<=maxTexture&&(long)w*h<=3840L*2160&&bytes<=LIMIT)
        return new ImageLabPlan(w,h,scale,bytes);
    }
    return null;
  }
}
