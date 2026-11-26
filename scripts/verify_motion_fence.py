#!/usr/bin/env python3
"""Verify that a shared texture has identical pixels after a producer fence or glFinish in Mesa."""
import ctypes as C
import gles_test_context as gles

GL_TEXTURE_2D, GL_RGBA, GL_UNSIGNED_BYTE = 0x0DE1, 0x1908, 0x1401
GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 = 0x8D40, 0x8CE0
GL_WAIT_OK, GL_TIMEOUT_EXPIRED = (0x911A, 0x911C), 0x911B

gl = gles.gl
u, i, p = gles.uint, gles.integer, gles.ptr
gen_fbos = gl("glGenFramebuffers", None, i, C.POINTER(u))
bind_fbo = gl("glBindFramebuffer", None, u, u)
attach = gl("glFramebufferTexture2D", None, u, u, u, u, i)
check_fbo = gl("glCheckFramebufferStatus", u, u)
clear_color = gl("glClearColor", None, C.c_float, C.c_float, C.c_float, C.c_float)
clear = gl("glClear", None, u)
finish = gl("glFinish", None)
error = gl("glGetError", u)
new_fence = gl("glFenceSync", p, u, u)
wait = gl("glClientWaitSync", u, p, u, C.c_uint64)
delete = gl("glDeleteSync", None, p)

attrs = (i * 5)(0x3033, 1, 0x3040, 0x0040, 0x3038)
config, count = p(), i()
assert gles.choose_config(gles.display, attrs, C.byref(config), 1, C.byref(count)) and count.value
surface = gles.create_surface(gles.display, config, (i * 5)(0x3057, 8, 0x3056, 8, 0x3038))
version = (i * 3)(0x3098, 3, 0x3038)
producer = gles.create_context(gles.display, config, None, version)
consumer = gles.create_context(gles.display, config, producer, version)
assert surface and producer and consumer
texture = u()


def use(context):
    assert gles.make_current(gles.display, surface, surface, context)


def connect():
    target = u()
    gen_fbos(1, C.byref(target))
    bind_fbo(GL_FRAMEBUFFER, target)
    attach(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assert check_fbo(GL_FRAMEBUFFER) == 0x8CD5


def pixels():
    use(consumer)
    rgba = (C.c_ubyte * (8 * 8 * 4))()
    gles.read_pixels(0, 0, 8, 8, GL_RGBA, GL_UNSIGNED_BYTE, rgba)
    assert error() == 0
    return bytes(rgba)


use(producer)
gles.gen_textures(1, C.byref(texture))
gles.bind_texture(GL_TEXTURE_2D, texture)
gles.tex_image(GL_TEXTURE_2D, 0, GL_RGBA, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, None)
connect()
use(consumer)
connect()
completed = 0
skips = 0

for _ in range(16):
    use(producer)
    clear_color(0.25, 0.5, 0.75, 1.0)
    clear(0x4000)
    finish()
    reference = pixels()
    assert reference[:4] == bytes((64, 128, 191, 255))
    use(producer)
    clear_color(0.0, 0.0, 0.0, 1.0)
    clear(0x4000)
    clear_color(0.25, 0.5, 0.75, 1.0)
    clear(0x4000)
    fence = new_fence(0x9117, 0)
    assert fence
    status = wait(fence, 1, 1_000_000)
    delete(fence)
    assert error() == 0
    if status == GL_TIMEOUT_EXPIRED:
        skips += 1
        continue
    assert status in GL_WAIT_OK
    assert pixels() == reference
    completed += 1

assert completed > 0, "Software GLES never completed any fence in the test budget"
gles.terminate(gles.display)
print(f"GLES shared texture: {completed} pixel matches, {skips} expired fences")
