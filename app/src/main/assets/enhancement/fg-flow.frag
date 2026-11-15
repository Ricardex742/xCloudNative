// Original xCloudNative block matching, MIT. Image motion, not engine vectors.
precision highp float;
varying vec2 tc;
uniform sampler2D source,history_tex;
uniform vec2 frame_size;
float luma(vec3 c){return dot(c,vec3(.299,.587,.114));}
float a(vec2 uv){return luma(texture2D(history_tex,clamp(uv,.5/frame_size,1.-.5/frame_size)).rgb);}
float b(vec2 uv){return luma(texture2D(source,clamp(uv,.5/frame_size,1.-.5/frame_size)).rgb);}
float cost(vec2 v){
 vec2 q=v/frame_size,d=2./frame_size;
 return (abs(a(tc)-b(tc+q))+abs(a(tc+vec2(d.x,0))-b(tc+q+vec2(d.x,0)))+
  abs(a(tc-vec2(d.x,0))-b(tc+q-vec2(d.x,0)))+abs(a(tc+vec2(0,d.y))-b(tc+q+vec2(0,d.y)))+
  abs(a(tc-vec2(0,d.y))-b(tc+q-vec2(0,d.y))))*.2;
}
void main(){
 vec2 best=vec2(0);float score=cost(best),bestCost=score;
 for(int y=-4;y<=4;y++)for(int x=-4;x<=4;x++){
  vec2 v=vec2(float(x),float(y))*8.;float c=cost(v),rank=c+length(v)*.000025;
  if(rank<score){score=rank;bestCost=c;best=v;}
 }
 for(int step=0;step<3;step++){
  float s=step==0?4.:step==1?2.:1.;vec2 center=best;
  for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
   vec2 v=clamp(center+vec2(float(x),float(y))*s,vec2(-32),vec2(32));
   float c=cost(v),rank=c+length(v)*.000025;
   if(rank<score){score=rank;bestCost=c;best=v;}
  }
 }
 gl_FragColor=vec4((vec2(128.)+best*(127./32.))/255.,1.-smoothstep(.025,.10,bestCost),1.);
}
