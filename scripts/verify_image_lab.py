#!/usr/bin/env python3
"""Execute the shipped original GLES shaders: residuals/HUD, supersampling and motion midpoint.

Synthetic fixtures only. Mesa correctness/quality checks do not measure Android performance.
"""
from gles_test_context import *
import math

assets=root/'app/src/main/assets/enhancement'
vertex='attribute vec2 pos;varying vec2 tc;void main(){gl_Position=vec4(pos,0.,1.);tc=pos*.5+.5;}'
active=gl('glActiveTexture',None,uint)
gen_fbo=gl('glGenFramebuffers',None,integer,C.POINTER(uint))
bind_fbo=gl('glBindFramebuffer',None,uint,uint)
attach=gl('glFramebufferTexture2D',None,uint,uint,uint,uint,integer)
status=gl('glCheckFramebufferStatus',uint,uint)
get_error=gl('glGetError',uint)
delete_tex=gl('glDeleteTextures',None,integer,C.POINTER(uint))
delete_fbo=gl('glDeleteFramebuffers',None,integer,C.POINTER(uint))
vertices=(C.c_float*8)(-1,-1,1,-1,-1,1,1,1)
checks=0
def verify(ok,message):
    global checks
    assert ok,message
    checks+=1
def program(name):
    p=create_program();attach_shader(p,compile_source(0x8B31,vertex));attach_shader(p,compile_source(0x8B30,(assets/name).read_text()))
    bind_attribute(p,0,b'pos');link_program(p);ok=integer();program_status(p,0x8B82,C.byref(ok))
    if not ok.value:
        log=C.create_string_buffer(8192);program_log(p,len(log),None,log);raise AssertionError(log.value.decode())
    return p
def texture(data,w,h):
    t=uint();gen_textures(1,C.byref(t));active(0x84C0);bind_texture(0x0DE1,t)
    for key,value in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:tex_parameter(0x0DE1,key,value)
    raw=(C.c_ubyte*len(data))(*data) if data is not None else None
    tex_image(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,raw)
    return t.value
def draw(p,textures,w,h,params=None):
    target=texture(None,w,h);f=uint();gen_fbo(1,C.byref(f));bind_fbo(0x8D40,f);attach(0x8D40,0x8CE0,0x0DE1,target,0)
    assert status(0x8D40)==0x8CD5
    use_program(p);viewport(0,0,w,h)
    params={'saturation':1.,'brightness':0.,'contrast_adjust':0.,'protection':0,'comparison':0,
            'frame_size':(w,h),'stage_size':(w,h),'output_size':(w,h)}|(params or {})
    for key,value in params.items():
        u=uniform_location(p,key.encode())
        if isinstance(value,tuple):uniform2f(u,*value)
        elif isinstance(value,int):uniform1i(u,value)
        else:uniform1f(u,value)
    for i,(name,t) in enumerate(textures.items()):
        active(0x84C0+i);bind_texture(0x0DE1,t);uniform1i(uniform_location(p,name.encode()),i)
    vertex_pointer(0,2,0x1406,0,0,vertices);enable_attribute(0);draw_arrays(5,0,4)
    assert get_error()==0,'GL error'
    result=(C.c_ubyte*(w*h*4))();read_pixels(0,0,w,h,0x1908,0x1401,result)
    delete_fbo(1,C.byref(f))
    return target,list(result)
def rgba(values):return [channel for v in values for channel in (v,v,v,255)]
def mse(a,b,margin,w,h):
    ids=[4*(y*w+x)+c for y in range(margin,h-margin) for x in range(margin,w-margin) for c in range(3)]
    return sum((a[i]-b[i])**2 for i in ids)/len(ids)

composite=program('lab-composite.frag');flow=program('fg-flow.frag');interpolate=program('fg-interpolate.frag');signature=program('fg-signature.frag')
checks+=4
w,h=128,96
flat=[82,117,149,255]*(w*h);base=texture(flat,w,h)
_,out=draw(composite,{'source':base,'processed':base},w,h)
verify(max(abs(a-b) for a,b in zip(out,flat))<=1,'Flat field/color changed')
damaged=texture([0,255,0,255]*(w*h),w,h)
_,original=draw(composite,{'source':base,'processed':damaged},w,h,{'comparison':1})
verify(max(abs(a-b) for a,b in zip(original,flat))<=1,'Original comparison not exact')
_,split=draw(composite,{'source':base,'processed':damaged},w,h,{'comparison':2})
verify(all(abs(split[4*(y*w+x)+c]-flat[4*(y*w+x)+c])<=1 for y in range(h) for x in range(w//2) for c in range(3)),'Split reference half changed')
_,bounded=draw(composite,{'source':base,'processed':damaged},w,h)
verify(max(abs(bounded[i]-flat[i]) for i in range(len(flat)) if i%4!=3)<=14,'Residual escaped luma/chroma bounds')
_,protected=draw(composite,{'source':base,'processed':damaged},w,h,{'protection':2})
verify(all(abs(protected[4*(y*w+x)+c]-flat[4*(y*w+x)+c])<=1 for y in range(3,15) for x in range(3,18) for c in range(3)),'Corner HUD was modified')
_,sample=draw(signature,{'source':base},32,18,{'frame_size':(w,h)})
verify(max(sample[::4])-min(sample[::4])<=1,'Luma signature not stable')

# Exact 2x resolve against a manually averaged high-resolution checker pattern.
source=texture(rgba([100]*(32*24)),32,24)
high=rgba([80 if (x+y)%2 else 120 for y in range(48) for x in range(64)])
hi=texture(high,64,48)
_,reduced=draw(composite,{'source':source,'processed':hi},32,24,{'frame_size':(32,24),'stage_size':(64,48)})
linear=lambda v: ((v/255+.055)/1.055)**2.4
expected=round((1.055*((linear(80)+linear(120))*.5)**(1/2.4)-.055)*255)
verify(max(abs(x-expected) for x in reduced[::4])<=1,'Supersampling resolve is not the linear-light 2x2 average')

# Textured pan of eight pixels: the midpoint must match a four-pixel pan,
# outperforming a ghosted A/B blend in the interior. Source images are synthetic.
def image(shift):
    values=[]
    for y in range(h):
        for x in range(w):
            u=x-shift
            values.append(round(125+35*math.sin(u*.21)+24*math.cos(y*.19)+18*math.sin((u+y)*.33)))
    return rgba(values)
a=image(0);b=image(8);reference=image(4)
ta=texture(a,w,h);tb=texture(b,w,h)
ff,ffdata=draw(flow,{'history_tex':ta,'source':tb},64,36,{'frame_size':(w,h)})
bf,bfdata=draw(flow,{'history_tex':tb,'source':ta},64,36,{'frame_size':(w,h)})
_,middle=draw(interpolate,{'history_tex':ta,'source':tb,'flow_forward':ff,'flow_backward':bf},w,h)
blend=[round((x+y)/2) for x,y in zip(a,b)]
motion_error=mse(middle,reference,20,w,h);blend_error=mse(blend,reference,20,w,h)
verify(motion_error<blend_error*.35,f'Midpoint worse than a blend: motion={motion_error}, blend={blend_error}')
verify(motion_error<25,f'Motion mismatch: {motion_error}')
_,hud=draw(interpolate,{'history_tex':ta,'source':tb,'flow_forward':ff,'flow_backward':bf},w,h,{'protection':2})
verify(all(abs(hud[4*(y*w+x)+c]-b[4*(y*w+x)+c])<=1 for y in range(3,15) for x in range(3,18) for c in range(3)),'Generated frame damaged protected HUD')
# Invalid/inconsistent vectors retain the newest image instead of ghosting.
bad=texture([255,255,0,255]*(64*36),64,36)
_,fallback=draw(interpolate,{'history_tex':ta,'source':tb,'flow_forward':bad,'flow_backward':bad},w,h)
verify(max(abs(x-y) for x,y in zip(fallback,b))<=1,'Uncertain flow did not preserve latest source')
report={'checks':checks,'failures':0,'renderer':get_string(0x1F01).decode(),'motion_midpoint_mse':motion_error,'naive_blend_mse':blend_error,
        'scope':'synthetic GLES correctness and quality; not Android device FPS or latency'}
out=root/'app/build/reports/image-lab';out.mkdir(parents=True,exist_ok=True)
(out/'quality.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
