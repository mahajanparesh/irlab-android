package com.irlab.camera;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Real-time GPU processing of the camera stream. The camera writes into a SurfaceTexture (external OES
 * texture). Each frame is drawn through one fragment shader that implements every mode. The same shader,
 * compiled for a plain 2D texture, renders captures: frames are stacked on the CPU and then processed
 * once at full preview resolution.
 */
final class IrRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "IrRenderer";

    interface Host {
        void onSurfaceTextureReady(SurfaceTexture st);
        void onFrameStats(FrameStats stats);
        void onCaptureProgress(int done, int total);
        void onCaptureReady(Bitmap bitmap, ProcessingParams used, int frames);
    }

    static final class FrameStats {
        final int[] histogram = new int[64];
        float meanR, meanG, meanB, maxLuma;
        /** Largest single-cell luminance change vs the previous sampled frame (0..1). */
        float maxDelta;
        /** Average absolute luminance change (0..1): low when the scene and camera are still. */
        float meanAbsDelta;
        float fps;
        boolean consecutive;
        /** Cells (96x128 grid) whose luminance jumped by more than SPOT_DELTA, and their bounding box. */
        int spotCells;
        int spotMinX, spotMinY, spotMaxX, spotMaxY;
    }

    static final int GRID_W = 96, GRID_H = 128;
    static final float SPOT_DELTA = 0.16f;

    private static final String VERTEX =
            "attribute vec4 aPos;\n" +
            "attribute vec4 aTex;\n" +
            "uniform mat4 uTexM;\n" +
            "varying vec2 vTex;\n" +
            "void main(){ gl_Position = aPos; vTex = (uTexM * aTex).xy; }\n";

    private static final String FRAG_BODY =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "varying vec2 vTex;\n" +
            "uniform SAMPLER uTex;\n" +
            "uniform float uMode;\n" +
            "uniform float uPalette;\n" +
            "uniform float uBrightness;\n" +
            "uniform float uContrast;\n" +
            "uniform float uGamma;\n" +
            "uniform float uEnhance;\n" +
            "uniform vec2 uTexel;\n" +
            "uniform vec3 uIrW;\n" +
            "vec3 fetch(vec2 p){ return texture2D(uTex, p).rgb; }\n" +
            "vec3 hsv2rgb(vec3 c){ vec4 K=vec4(1.0,2.0/3.0,1.0/3.0,3.0);" +
            "  vec3 p=abs(fract(c.xxx+K.xyz)*6.0-K.www); return c.z*mix(K.xxx,clamp(p-K.xxx,0.0,1.0),c.y); }\n" +
            "vec3 ironbow(float t){ t=clamp(t,0.0,1.0)*5.0;\n" +
            "  vec3 c0=vec3(0.0); vec3 c1=vec3(0.20,0.0,0.45); vec3 c2=vec3(0.75,0.05,0.45);\n" +
            "  vec3 c3=vec3(0.98,0.40,0.0); vec3 c4=vec3(1.0,0.85,0.10); vec3 c5=vec3(1.0,1.0,0.95);\n" +
            "  if(t<1.0) return mix(c0,c1,t); if(t<2.0) return mix(c1,c2,t-1.0);\n" +
            "  if(t<3.0) return mix(c2,c3,t-2.0); if(t<4.0) return mix(c3,c4,t-3.0); return mix(c4,c5,t-4.0); }\n" +
            "vec3 nightGreen(float t){ return vec3(0.10*t, pow(t,0.8), 0.22*t); }\n" +
            "void main(){\n" +
            "  vec3 c = fetch(vTex);\n" +
            "  if (uMode < -0.5) { gl_FragColor = vec4(c, 1.0); return; }\n" +
            "  if (uEnhance > 0.001) {\n" +
            "    vec3 b = (fetch(vTex+vec2(uTexel.x,0.0)) + fetch(vTex-vec2(uTexel.x,0.0))\n" +
            "            + fetch(vTex+vec2(0.0,uTexel.y)) + fetch(vTex-vec2(0.0,uTexel.y))) * 0.25;\n" +
            "    c = c + (c - b) * uEnhance * 2.0;\n" +
            "  }\n" +
            "  c = clamp((c - 0.5) * uContrast + 0.5 + uBrightness, 0.0, 1.0);\n" +
            "  c = pow(c, vec3(1.0 / uGamma));\n" +
            "  vec3 o;\n" +
            "  float ir = clamp(dot(c, uIrW), 0.0, 1.0);\n" +
            "  if (uMode < 0.5) { o = c; }\n" +
            "  else if (uMode < 1.5) { o = vec3(ir); }\n" +
            "  else if (uMode < 2.5) {\n" +
            "    if (uPalette < 0.5) o = ironbow(ir);\n" +
            "    else if (uPalette < 1.5) o = hsv2rgb(vec3((1.0 - ir) * 0.72, 1.0, 0.15 + 0.85 * ir));\n" +
            "    else o = nightGreen(ir);\n" +
            "  }\n" +
            "  else if (uMode < 3.5) { o = vec3(c.b, c.g, c.r); }\n" +
            "  else { float l = dot(c, vec3(0.299,0.587,0.114));\n" +
            "         float hot = max(max(c.r, c.g), c.b);\n" +
            "         o = hot > 0.985 ? vec3(1.0, 0.1, 1.0) : vec3(l * 0.55); }\n" +
            "  gl_FragColor = vec4(clamp(o, 0.0, 1.0), 1.0);\n" +
            "}\n";

    private static final class Program {
        int id, aPos, aTex, uTexM, uTex, uMode, uPalette, uBrightness, uContrast, uGamma, uEnhance, uTexel, uIrW;
    }

    private static final int STATS_W = GRID_W, STATS_H = GRID_H;

    private final GLSurfaceView view;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile ProcessingParams params = new ProcessingParams();
    private volatile boolean fastStats;

    private SurfaceTexture surfaceTexture;
    private int oesTex;
    private Program progOes, prog2D;
    private final float[] texM = new float[16];
    private final float[] identity = new float[16];
    private FloatBuffer quadPos, quadTex;
    private int surfW, surfH;
    private int bufW = 1920, bufH = 1440, contentW = 1440, contentH = 1920;

    private int statsFbo, statsTex;
    private ByteBuffer statsBuf;
    private byte[] statsBytes;
    private final float[] prevLuma = new float[STATS_W * STATS_H];
    private boolean havePrev;
    private int frameNo;
    private int lastStatsFrame = -10;

    // capture state (GL thread only)
    private boolean capActive;
    private int capTotal, capDone;
    private ProcessingParams capParams;
    private int capFbo, capTex, capW, capH, stackTex;
    private ByteBuffer capBuf;
    private byte[] capBytes;
    private int[] acc;
    private long lastCapTimestamp = -1;

    private long fpsT0;
    private int fpsFrames;
    private float fps;

    IrRenderer(GLSurfaceView view, Host host) {
        this.view = view;
        this.host = host;
        android.opengl.Matrix.setIdentityM(identity, 0);
    }

    void setParams(ProcessingParams p) {
        params = p;
    }

    void setFastStats(boolean on) {
        fastStats = on;
    }

    /** Camera buffer size and sensor orientation, used for aspect ratio and capture resolution. */
    void setBufferGeometry(final int w, final int h, final int sensorOrientation) {
        view.queueEvent(() -> {
            bufW = w;
            bufH = h;
            boolean swap = sensorOrientation % 180 != 0;
            contentW = swap ? h : w;
            contentH = swap ? w : h;
            havePrev = false;
        });
    }

    void requestCapture(final int frames, final ProcessingParams p) {
        view.queueEvent(() -> {
            capTotal = Math.max(1, frames);
            capDone = 0;
            capParams = p;
            capActive = true;
            lastCapTimestamp = -1;
            ensureCaptureTargets();
            java.util.Arrays.fill(acc, 0);
        });
    }

    // ------------------------------------------------------------------ GL callbacks

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        progOes = buildProgram(true);
        prog2D = buildProgram(false);

        quadPos = floatBuffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        quadTex = floatBuffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1});

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        oesTex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        setTexParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);

        GLES20.glGenTextures(1, t, 0);
        stackTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stackTex);
        setTexParams(GLES20.GL_TEXTURE_2D);

        int[] fb = createTarget(STATS_W, STATS_H);
        statsFbo = fb[0];
        statsTex = fb[1];
        statsBuf = ByteBuffer.allocateDirect(STATS_W * STATS_H * 4).order(ByteOrder.nativeOrder());
        statsBytes = new byte[STATS_W * STATS_H * 4];
        capW = capH = 0; // targets belong to the old context, rebuild lazily
        capActive = false;

        final SurfaceTexture old = surfaceTexture;
        surfaceTexture = new SurfaceTexture(oesTex);
        surfaceTexture.setOnFrameAvailableListener(this);
        final SurfaceTexture st = surfaceTexture;
        main.post(() -> {
            if (old != null) old.release();
            host.onSurfaceTextureReady(st);
        });
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        surfW = width;
        surfH = height;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        view.requestRender();
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (surfaceTexture == null) return;
        try {
            surfaceTexture.updateTexImage();
        } catch (RuntimeException e) {
            return; // abandoned texture during teardown
        }
        surfaceTexture.getTransformMatrix(texM);
        long ts = surfaceTexture.getTimestamp();
        frameNo++;
        countFps();
        ProcessingParams p = params;

        // 1. statistics on the unprocessed frame (histogram, IR-flash detector)
        if (fastStats || frameNo % 3 == 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, statsFbo);
            GLES20.glViewport(0, 0, STATS_W, STATS_H);
            draw(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, texM, null, 0, 0);
            statsBuf.rewind();
            GLES20.glReadPixels(0, 0, STATS_W, STATS_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, statsBuf);
            computeStats(frameNo - lastStatsFrame == 1);
            lastStatsFrame = frameNo;
        }

        // 2. capture: accumulate unprocessed frames at preview resolution
        if (capActive && ts != lastCapTimestamp) {
            lastCapTimestamp = ts;
            ensureCaptureTargets();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, capFbo);
            GLES20.glViewport(0, 0, capW, capH);
            draw(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, texM, null, 0, 0);
            capBuf.rewind();
            GLES20.glReadPixels(0, 0, capW, capH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, capBuf);
            capBuf.rewind();
            capBuf.get(capBytes);
            int n = capW * capH;
            for (int i = 0, j = 0, k = 0; i < n; i++, j += 4, k += 3) {
                acc[k] += capBytes[j] & 0xFF;
                acc[k + 1] += capBytes[j + 1] & 0xFF;
                acc[k + 2] += capBytes[j + 2] & 0xFF;
            }
            capDone++;
            final int done = capDone, total = capTotal;
            main.post(() -> host.onCaptureProgress(done, total));
            if (capDone >= capTotal) finishCapture();
        }

        // 3. on-screen preview with the live processing parameters
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfW, surfH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        float contentAspect = contentW / (float) contentH;
        int vw = surfW, vh = Math.round(surfW / contentAspect);
        if (vh > surfH) {
            vh = surfH;
            vw = Math.round(surfH * contentAspect);
        }
        GLES20.glViewport((surfW - vw) / 2, (surfH - vh) / 2, vw, vh);
        draw(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, texM, p, 1.5f / bufW, 1.5f / bufH);
    }

    // ------------------------------------------------------------------ capture

    private void ensureCaptureTargets() {
        if (capW == contentW && capH == contentH && capFbo != 0) return;
        if (capFbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{capFbo}, 0);
            GLES20.glDeleteTextures(1, new int[]{capTex}, 0);
        }
        capW = contentW;
        capH = contentH;
        int[] fb = createTarget(capW, capH);
        capFbo = fb[0];
        capTex = fb[1];
        capBuf = ByteBuffer.allocateDirect(capW * capH * 4).order(ByteOrder.nativeOrder());
        capBytes = new byte[capW * capH * 4];
        acc = new int[capW * capH * 3];
    }

    private void finishCapture() {
        capActive = false;
        int n = capW * capH;
        int total = capTotal;
        for (int i = 0, j = 0, k = 0; i < n; i++, j += 4, k += 3) {
            capBytes[j] = (byte) (acc[k] / total);
            capBytes[j + 1] = (byte) (acc[k + 1] / total);
            capBytes[j + 2] = (byte) (acc[k + 2] / total);
            capBytes[j + 3] = (byte) 0xFF;
        }
        capBuf.clear();
        capBuf.put(capBytes);
        capBuf.position(0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stackTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, capW, capH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, capBuf);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, capFbo);
        GLES20.glViewport(0, 0, capW, capH);
        draw(prog2D, GLES20.GL_TEXTURE_2D, stackTex, identity, capParams, 1.5f / capW, 1.5f / capH);
        capBuf.rewind();
        GLES20.glReadPixels(0, 0, capW, capH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, capBuf);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        Bitmap raw = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888);
        capBuf.rewind();
        raw.copyPixelsFromBuffer(capBuf);
        Matrix flip = new Matrix();
        flip.preScale(1f, -1f); // GL rows are bottom-up
        final Bitmap out = Bitmap.createBitmap(raw, 0, 0, capW, capH, flip, false);
        raw.recycle();
        final ProcessingParams used = capParams;
        main.post(() -> host.onCaptureReady(out, used, total));
    }

    // ------------------------------------------------------------------ stats

    private void computeStats(boolean consecutive) {
        statsBuf.rewind();
        statsBuf.get(statsBytes);
        FrameStats s = new FrameStats();
        int n = STATS_W * STATS_H;
        double sr = 0, sg = 0, sb = 0, sumAbs = 0;
        float maxL = 0, maxD = 0;
        for (int i = 0, j = 0; i < n; i++, j += 4) {
            float r = (statsBytes[j] & 0xFF) / 255f;
            float g = (statsBytes[j + 1] & 0xFF) / 255f;
            float b = (statsBytes[j + 2] & 0xFF) / 255f;
            float l = 0.299f * r + 0.587f * g + 0.114f * b;
            sr += r;
            sg += g;
            sb += b;
            if (l > maxL) maxL = l;
            s.histogram[Math.min(63, (int) (l * 64f))]++;
            if (havePrev) {
                float d = Math.abs(l - prevLuma[i]);
                sumAbs += d;
                if (d > maxD) maxD = d;
                if (d > SPOT_DELTA) {
                    int x = i % STATS_W, y = i / STATS_W;
                    if (s.spotCells == 0) {
                        s.spotMinX = s.spotMaxX = x;
                        s.spotMinY = s.spotMaxY = y;
                    } else {
                        s.spotMinX = Math.min(s.spotMinX, x);
                        s.spotMaxX = Math.max(s.spotMaxX, x);
                        s.spotMinY = Math.min(s.spotMinY, y);
                        s.spotMaxY = Math.max(s.spotMaxY, y);
                    }
                    s.spotCells++;
                }
            }
            prevLuma[i] = l;
        }
        s.meanR = (float) (sr / n);
        s.meanG = (float) (sg / n);
        s.meanB = (float) (sb / n);
        s.maxLuma = maxL;
        s.maxDelta = havePrev ? maxD : 0f;
        s.meanAbsDelta = havePrev ? (float) (sumAbs / n) : 0f;
        s.fps = fps;
        s.consecutive = consecutive && havePrev;
        havePrev = true;
        main.post(() -> host.onFrameStats(s));
    }

    private void countFps() {
        long now = System.nanoTime();
        if (fpsT0 == 0) fpsT0 = now;
        fpsFrames++;
        if (now - fpsT0 >= 1_000_000_000L) {
            fps = fpsFrames * 1e9f / (now - fpsT0);
            fpsFrames = 0;
            fpsT0 = now;
        }
    }

    // ------------------------------------------------------------------ GL helpers

    private void draw(Program pr, int target, int tex, float[] m, ProcessingParams p, float tx, float ty) {
        GLES20.glUseProgram(pr.id);
        GLES20.glVertexAttribPointer(pr.aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos);
        GLES20.glEnableVertexAttribArray(pr.aPos);
        GLES20.glVertexAttribPointer(pr.aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex);
        GLES20.glEnableVertexAttribArray(pr.aTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(target, tex);
        GLES20.glUniform1i(pr.uTex, 0);
        GLES20.glUniformMatrix4fv(pr.uTexM, 1, false, m, 0);
        if (p == null) {
            GLES20.glUniform1f(pr.uMode, ProcessingParams.MODE_PASSTHROUGH);
            GLES20.glUniform1f(pr.uPalette, 0f);
            GLES20.glUniform1f(pr.uBrightness, 0f);
            GLES20.glUniform1f(pr.uContrast, 1f);
            GLES20.glUniform1f(pr.uGamma, 1f);
            GLES20.glUniform1f(pr.uEnhance, 0f);
            GLES20.glUniform3f(pr.uIrW, 0.6f, 0.3f, 0.1f);
        } else {
            GLES20.glUniform1f(pr.uMode, p.mode);
            GLES20.glUniform1f(pr.uPalette, p.palette);
            GLES20.glUniform1f(pr.uBrightness, p.brightness);
            GLES20.glUniform1f(pr.uContrast, p.contrast);
            GLES20.glUniform1f(pr.uGamma, Math.max(0.05f, p.gamma));
            GLES20.glUniform1f(pr.uEnhance, p.enhance);
            GLES20.glUniform3f(pr.uIrW, p.irWeights[0], p.irWeights[1], p.irWeights[2]);
        }
        GLES20.glUniform2f(pr.uTexel, tx, ty);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static Program buildProgram(boolean external) {
        String frag = external
                ? "#extension GL_OES_EGL_image_external : require\n" + FRAG_BODY.replace("SAMPLER", "samplerExternalOES")
                : FRAG_BODY.replace("SAMPLER", "sampler2D");
        int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, frag);
        int id = GLES20.glCreateProgram();
        GLES20.glAttachShader(id, vs);
        GLES20.glAttachShader(id, fs);
        GLES20.glLinkProgram(id);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(id);
            throw new RuntimeException("Program link failed: " + log);
        }
        Program p = new Program();
        p.id = id;
        p.aPos = GLES20.glGetAttribLocation(id, "aPos");
        p.aTex = GLES20.glGetAttribLocation(id, "aTex");
        p.uTexM = GLES20.glGetUniformLocation(id, "uTexM");
        p.uTex = GLES20.glGetUniformLocation(id, "uTex");
        p.uMode = GLES20.glGetUniformLocation(id, "uMode");
        p.uPalette = GLES20.glGetUniformLocation(id, "uPalette");
        p.uBrightness = GLES20.glGetUniformLocation(id, "uBrightness");
        p.uContrast = GLES20.glGetUniformLocation(id, "uContrast");
        p.uGamma = GLES20.glGetUniformLocation(id, "uGamma");
        p.uEnhance = GLES20.glGetUniformLocation(id, "uEnhance");
        p.uTexel = GLES20.glGetUniformLocation(id, "uTexel");
        p.uIrW = GLES20.glGetUniformLocation(id, "uIrW");
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            Log.e(TAG, "Shader compile failed: " + log + "\n" + src);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return s;
    }

    private static int[] createTarget(int w, int h) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null);
        setTexParams(GLES20.GL_TEXTURE_2D);
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "FBO incomplete: " + status);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return new int[]{f[0], t[0]};
    }

    private static void setTexParams(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static FloatBuffer floatBuffer(float[] v) {
        FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v).position(0);
        return b;
    }
}
