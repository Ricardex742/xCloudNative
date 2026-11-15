// Original xCloudNative bidirectional midpoint reconstruction, MIT.
precision highp float;
varying vec2 tc;
uniform sampler2D source,history_tex,flow_forward,flow_backward;
uniform vec2 frame_size;
uniform int protection;
float y(vec3 c){return dot(c,vec3(.299,.587,.114));}
vec2 decode(vec2 v){return (v*255.-vec2(128.))*(32./127.);}
vec3 current(vec2 uv){return texture2D(source,clamp(uv,.5/frame_size,1.-.5/frame_size)).rgb;}
float box(vec2 p,vec2 lo,vec2 hi){vec2 a=smoothstep(lo-.015,lo+.015,p),b=1.-smoothstep(hi-.015,hi+.015,p);return a.x*a.y*b.x*b.y;}
void main(){
 vec4 f=texture2D(flow_forward,tc),b=texture2D(flow_backward,tc);
 vec2 forward=decode(f.xy),backward=decode(b.xy);
 vec2 uvA=clamp(tc-.5*forward/frame_size,.5/frame_size,1.-.5/frame_size);
 vec2 uvB=clamp(tc-.5*backward/frame_size,.5/frame_size,1.-.5/frame_size);
 vec3 aColor=texture2D(history_tex,uvA).rgb,bColor=current(uvB),base=current(tc);
 float confidence=min(f.z,b.z)*(1.-smoothstep(1.5,5.,length(forward+backward)))*(1.-smoothstep(.04,.12,abs(y(aColor)-y(bColor))));
 float mask=0.;vec2 px=1./frame_size;
 if(protection==1){
  float c=y(base),w=y(current(tc-vec2(px.x,0))),e=y(current(tc+vec2(px.x,0))),n=y(current(tc+vec2(0,px.y))),s=y(current(tc-vec2(0,px.y)));
  float range=max(c,max(max(w,e),max(n,s)))-min(c,min(min(w,e),min(n,s)));
  mask=smoothstep(.18,.45,range)*smoothstep(.12,.35,max(abs(w+e-2.*c),abs(n+s-2.*c)));
 }else if(protection==2){
  mask=max(max(box(tc,vec2(0,.74),vec2(.30,1)),box(tc,vec2(.70,.74),vec2(1))),
           max(box(tc,vec2(0),vec2(.35,.25)),box(tc,vec2(.65,0),vec2(1,.25))));
 }
 // Uncertain/occluded pixels keep the latest source; no blind full-frame blend.
 vec3 result=confidence>=.45?mix(aColor,bColor,.5):base;
 gl_FragColor=vec4(mix(result,base,mask),1.);
}
