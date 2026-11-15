precision highp float;
varying vec2 tc;
uniform sampler2D source;
uniform vec2 frame_size;
void main(){
 vec2 d=.22/vec2(32.,18.);
 vec3 c=(texture2D(source,tc-d).rgb+texture2D(source,tc+d).rgb+
         texture2D(source,tc+vec2(-d.x,d.y)).rgb+texture2D(source,tc+vec2(d.x,-d.y)).rgb)*.25;
 float y=dot(c,vec3(.299,.587,.114));gl_FragColor=vec4(y,y,y,1.);
}
