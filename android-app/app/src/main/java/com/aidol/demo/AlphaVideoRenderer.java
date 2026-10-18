package com.aidol.demo;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Decodes a left-RGB/right-alpha video into a premultiplied, transparent TextureView surface. */
final class AlphaVideoRenderer {
    interface Listener {
        void surfaceReady(Surface decoderSurface);
        void firstFrame();
        void failed();
        void released();
    }

    private static final String VERTEX =
        "attribute vec2 aPosition;\n"
        + "attribute vec2 aTexture;\n"
        + "varying vec2 vTexture;\n"
        + "void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexture=aTexture; }\n";
    private static final String FRAGMENT =
        "#extension GL_OES_EGL_image_external : require\n"
        + "precision mediump float;\n"
        + "uniform samplerExternalOES uVideo;\n"
        + "uniform mat4 uTransform;\n"
        + "varying vec2 vTexture;\n"
        + "void main(){\n"
        // Keep bilinear sampling inside each 480-pixel half, including codec texture crops.
        + "  float x=clamp(vTexture.x*0.5,0.0005208333,0.4994791667);\n"
        + "  float y=clamp(vTexture.y,0.0010416667,0.9989583333);\n"
        + "  vec2 colorUv=(uTransform*vec4(x,y,0.0,1.0)).xy;\n"
        + "  vec2 alphaUv=(uTransform*vec4(x+0.5,y,0.0,1.0)).xy;\n"
        + "  vec3 color=texture2D(uVideo,colorUv).rgb;\n"
        + "  float alpha=clamp(texture2D(uVideo,alphaUv).r,0.0,1.0);\n"
        // Android's compositor expects premultiplied RGBA for a nonopaque TextureView.
        + "  gl_FragColor=vec4(color*alpha,alpha);\n"
        + "}\n";

    private final HandlerThread thread = new HandlerThread("aidol-avatar-gl");
    private final Handler render;
    private final Handler main;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final FloatBuffer vertices = floats(new float[]{-1,-1, 1,-1, -1,1, 1,1});
    private final FloatBuffer coordinates = floats(new float[]{0,0, 1,0, 0,1, 1,1});
    private final float[] transform = new float[16];
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private Surface outputSurface, inputSurface;
    private SurfaceTexture inputTexture;
    private int program, videoTexture, width, height;
    private int positionAttribute, textureAttribute, transformUniform, videoUniform;
    private boolean firstFrame, released;

    AlphaVideoRenderer(SurfaceTexture output, int width, int height, Handler main, Listener listener) {
        this.width = Math.max(1, width); this.height = Math.max(1, height);
        this.main = main; this.listener = listener;
        thread.start(); render = new Handler(thread.getLooper());
        render.post(() -> initialize(output));
    }

    void resize(int width, int height) {
        if (closed.get()) return;
        render.post(() -> { this.width = Math.max(1, width); this.height = Math.max(1, height); });
    }

    /** Release asynchronously; the caller releases MediaPlayer before closing this surface owner. */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        render.post(this::release);
    }

    private void initialize(SurfaceTexture output) {
        if (closed.get()) return;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("No EGL display");
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("EGL initialize");
            int[] attributes = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0)
                throw new IllegalStateException("No RGBA EGL configuration");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("EGL context");
            outputSurface = new Surface(output);
            window = EGL14.eglCreateWindowSurface(display, configs[0], outputSurface, new int[]{EGL14.EGL_NONE}, 0);
            if (window == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, window, window, context))
                throw new IllegalStateException("EGL surface");
            int vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX);
            int fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
            int[] linked = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) throw new IllegalStateException("Alpha shader link");
            positionAttribute = GLES20.glGetAttribLocation(program, "aPosition");
            textureAttribute = GLES20.glGetAttribLocation(program, "aTexture");
            transformUniform = GLES20.glGetUniformLocation(program, "uTransform");
            videoUniform = GLES20.glGetUniformLocation(program, "uVideo");
            int[] textures = new int[1]; GLES20.glGenTextures(1, textures, 0); videoTexture = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            inputTexture = new SurfaceTexture(videoTexture);
            inputTexture.setDefaultBufferSize(960, 480);
            inputTexture.setOnFrameAvailableListener(ignored -> draw(), render);
            inputSurface = new Surface(inputTexture);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glClearColor(0, 0, 0, 0); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (!EGL14.eglSwapBuffers(display, window)) throw new IllegalStateException("EGL initial frame");
            main.post(() -> { if (!closed.get()) listener.surfaceReady(inputSurface); });
        } catch (RuntimeException problem) { fail(problem); }
    }

    private void draw() {
        if (closed.get()) return;
        try {
            inputTexture.updateTexImage(); inputTexture.getTransformMatrix(transform);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture);
            GLES20.glUniform1i(videoUniform, 0);
            GLES20.glUniformMatrix4fv(transformUniform, 1, false, transform, 0);
            vertices.position(0); coordinates.position(0);
            GLES20.glEnableVertexAttribArray(positionAttribute); GLES20.glEnableVertexAttribArray(textureAttribute);
            GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glVertexAttribPointer(textureAttribute, 2, GLES20.GL_FLOAT, false, 0, coordinates);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR || !EGL14.eglSwapBuffers(display, window))
                throw new IllegalStateException("Alpha frame rendering");
            if (!firstFrame) {
                firstFrame = true;
                main.post(() -> { if (!closed.get()) listener.firstFrame(); });
            }
        } catch (RuntimeException problem) { fail(problem); }
    }

    private void fail(RuntimeException problem) {
        if (!closed.compareAndSet(false, true)) return;
        android.util.Log.w("CompanionAlpha", "Transparent video unavailable", problem);
        main.post(listener::failed);
        release();
    }

    private void release() {
        if (released) return;
        released = true;
        if (inputTexture != null) inputTexture.setOnFrameAvailableListener(null);
        if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
        if (inputTexture != null) { inputTexture.release(); inputTexture = null; }
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE)
                EGL14.eglMakeCurrent(display, window, window, context);
            if (videoTexture != 0) GLES20.glDeleteTextures(1, new int[]{videoTexture}, 0);
            if (program != 0) GLES20.glDeleteProgram(program);
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
        }
        if (outputSurface != null) { outputSurface.release(); outputSurface = null; }
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; window = EGL14.EGL_NO_SURFACE;
        program = videoTexture = 0;
        thread.quitSafely();
        main.post(listener::released);
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] compiled = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] != 0) return shader;
        GLES20.glDeleteShader(shader);
        throw new IllegalStateException("Alpha shader compile");
    }

    private static FloatBuffer floats(float[] values) {
        FloatBuffer result = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        result.put(values).position(0); return result;
    }
}
