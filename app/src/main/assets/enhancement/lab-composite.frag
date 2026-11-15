// xCloudNative image laboratory, MIT. Independent implementation; no vendor models.
precision highp float;
varying vec2 tc;
uniform sampler2D source,processed;
uniform vec2 frame_size,stage_size,output_size;
uniform float saturation,brightness,contrast_adjust;
uniform int protection,comparison;
float y(vec3 c){return dot(c,vec3(.299,.587,.114));}
vec3 at(vec2 p){return texture2D(source,clamp(p,.5/frame_size,1.-.5/frame_size)).rgb;}
float box(vec2 p,vec2 lo,vec2 hi){
 vec2 a=smoothstep(lo-.015,lo+.015,p),b=1.-smoothstep(hi-.015,hi+.015,p);
 return a.x*a.y*b.x*b.y;
}
vec3 linearLight(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
vec3 encodedLight(vec3 c){return mix(c*12.92,1.055*pow(max(c,vec3(0)),vec3(1./2.4))-.055,step(vec3(.0031308),c));}
vec3 colors(vec3 c){float l=y(c);c=mix(vec3(l),c,saturation);return clamp((c-.5)*(1.+contrast_adjust)+.5+brightness,0.,1.);}
void main(){
 vec3 raw=at(tc),base=colors(raw);
 if(comparison==1||(comparison==2&&tc.x<.5)){gl_FragColor=vec4(base,1.);return;}
 vec2 d=.25/output_size;
 // Four bilinear samples resolve the intermediate image; at 2x this is the
 // exact 2x2 linear-light average. Never sharpen again after reducing the image.
 vec3 enhanced;
 if(stage_size.x>output_size.x*1.01||stage_size.y>output_size.y*1.01)
  enhanced=encodedLight((linearLight(texture2D(processed,tc+vec2(-d.x,-d.y)).rgb)+linearLight(texture2D(processed,tc+vec2(d.x,-d.y)).rgb)+
            linearLight(texture2D(processed,tc+vec2(-d.x,d.y)).rgb)+linearLight(texture2D(processed,tc+d).rgb))*.25);
 else enhanced=texture2D(processed,tc).rgb;
 vec2 px=1./frame_size;
 float c=y(raw),w=y(at(tc-vec2(px.x,0))),e=y(at(tc+vec2(px.x,0))),n=y(at(tc+vec2(0,px.y))),s=y(at(tc-vec2(0,px.y)));
 float range=max(c,max(max(w,e),max(n,s)))-min(c,min(min(w,e),min(n,s)));
 float mask=0.;
 if(protection==1){
  // Conservative thin-feature heuristic, not OCR or semantic HUD detection.
  float thin=max(abs(w+e-2.*c),abs(n+s-2.*c));
  mask=smoothstep(.18,.45,range)*smoothstep(.12,.35,thin);
 }else if(protection==2){
  mask=max(max(box(tc,vec2(0,.74),vec2(.30,1)),box(tc,vec2(.70,.74),vec2(1))),
           max(box(tc,vec2(0),vec2(.35,.25)),box(tc,vec2(.65,0),vec2(1,.25))));
 }
 // Change-only composition preserves the base. Bound luma and chroma changes
 // independently so codec noise, neural residuals and edges cannot explode.
 vec3 delta=enhanced-base;
 float dl=y(delta),limit=mix(.025,.10,smoothstep(.02,.20,range));
 vec3 chroma=delta-vec3(dl);
 delta=vec3(clamp(dl,-limit,limit))+clamp(chroma,vec3(-.025),vec3(.025));
 gl_FragColor=vec4(clamp(base+delta*(1.-mask),0.,1.),1.);
}
