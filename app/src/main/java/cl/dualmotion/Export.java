package cl.dualmotion;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Surface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Exporta la animación a MP4: video H.264 dibujado fuera de pantalla (cada fotograma en la superficie del
 * codificador) y sonido AAC con la mezcla de las capas de audio. Corre en otro hilo con una copia del documento.
 * El archivo queda en Películas › DUAL Motion.
 */
final class Export {
    private Export() { }

    interface Listener {
        void progress(int done, int total);
        void finished(Uri uri, String error);
    }

    static volatile boolean cancel;

    static void start(final Context c, MDoc src, final String name, final Listener l) {
        cancel = false;
        final JSONObject json = src.toJson();
        final MDoc d = new MDoc();
        d.load(json);
        final Sounds.Plan plan = Sounds.plan(c, d, false); // solo lo ya cargado; abajo se completa
        Images.init(c);
        new Thread(() -> {
            File tmp = new File(c.getCacheDir(), "exportar.mp4");
            try {
                Sounds.Plan p = d.hasAudio() ? Sounds.plan(c, d, true) : plan;
                for (MDoc.Image im : d.images) Images.getNow(d, im.id); // las imágenes, listas antes de dibujar
                encode(d, p, tmp, l);
                if (cancel) throw new Exception("Cancelado");
                Uri u = publish(c, tmp, name);
                l.finished(u, null);
            } catch (Exception e) {
                l.finished(null, e.getMessage() != null ? e.getMessage() : "error al codificar");
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }, "dm-exportar").start();
    }

    // ---------------------------------------------------------------- codificar
    private static final class Sample { byte[] data; long pts; int flags; }

    private static void encode(MDoc d, Sounds.Plan plan, File out, Listener l) throws Exception {
        // tamaño par y como mucho 1920 de ancho (los codificadores lo piden así)
        float sc = Math.min(1f, 1920f / Math.max(d.w, d.h));
        int W = Math.max(16, Math.round(d.w * sc / 2) * 2), H = Math.max(16, Math.round(d.h * sc / 2) * 2);
        int frames = d.length(), fps = d.fps;
        long durUs = frames * 1_000_000L / fps;

        // 1) sonido completo a AAC (en memoria: es poco)
        ArrayList<Sample> audio = new ArrayList<>();
        MediaFormat audioFmt = plan.isEmpty() ? null : encodeAudio(plan, durUs, audio);

        // 2) video
        MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1_000_000, (int) (W * (long) H * fps * 0.18)));
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        enc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = enc.createInputSurface();
        enc.start();
        MediaMuxer mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int[] tracks = {-1, -1};
        long[] last = {-1};
        boolean[] started = {false};
        Gl gl = null;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        try {
            gl = new Gl(surface, W, H);
            for (int f = 0; f < frames && !cancel; f++) {
                c.drawColor(0xFFFFFFFF);
                c.save();
                c.scale(W / (float) d.w, H / (float) d.h);
                Render.frame(c, d, f, true, false, 1);
                c.restore();
                // cada fotograma lleva su tiempo exacto (f / fps): el codificador no se salta ninguno ni corre
                gl.draw(bmp, f * 1_000_000_000L / fps);
                drain(enc, mux, tracks, started, audioFmt, audio, last, false);
                if (l != null && (f % 4 == 0 || f == frames - 1)) l.progress(f + 1, frames);
            }
            enc.signalEndOfInputStream();
            drain(enc, mux, tracks, started, audioFmt, audio, last, true);
        } finally {
            if (gl != null) gl.release();
            bmp.recycle();
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            surface.release();
            try { if (started[0]) mux.stop(); } catch (Exception ignored) { }
            mux.release();
        }
        if (!started[0]) throw new Exception("El codificador de video no entregó nada");
    }

    /** Saca lo codificado y lo escribe con el tiempo que trae (el que se le puso a cada fotograma). */
    private static void drain(MediaCodec enc, MediaMuxer mux, int[] tracks, boolean[] started, MediaFormat audioFmt,
                              ArrayList<Sample> audio, long[] last, boolean end) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long until = System.currentTimeMillis() + (end ? 5000 : 0);
        while (true) {
            int ob = enc.dequeueOutputBuffer(info, end ? 10000 : 0);
            if (ob == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || System.currentTimeMillis() > until) return;
                continue;
            }
            if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                tracks[0] = mux.addTrack(enc.getOutputFormat());
                if (audioFmt != null) tracks[1] = mux.addTrack(audioFmt);
                mux.start();
                started[0] = true;
                if (tracks[1] >= 0) { // el sonido ya está listo: va todo de una vez
                    MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
                    for (Sample s : audio) {
                        ai.set(0, s.data.length, s.pts, s.flags);
                        mux.writeSampleData(tracks[1], ByteBuffer.wrap(s.data), ai);
                    }
                }
                continue;
            }
            if (ob < 0) continue;
            ByteBuffer buf = enc.getOutputBuffer(ob);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (buf != null && info.size > 0 && !config && started[0] && info.presentationTimeUs > last[0]) {
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                mux.writeSampleData(tracks[0], buf, info);
                last[0] = info.presentationTimeUs;
            }
            enc.releaseOutputBuffer(ob, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    /**
     * Pasa cada fotograma (un Bitmap) a la superficie del codificador con OpenGL, para poder ponerle su tiempo
     * exacto (eglPresentationTimeANDROID). Con lockCanvas el tiempo es el del reloj y el codificador, al ver
     * cientos de fotogramas por segundo, se salta algunos: el video queda corto y el sonido se descalza.
     */
    private static final class Gl {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        private final android.opengl.EGLDisplay dpy;
        private final android.opengl.EGLContext ctx;
        private final android.opengl.EGLSurface srf;
        private final int prog, tex, aPos, aTex;
        private final java.nio.FloatBuffer quad;
        private boolean first = true;

        Gl(Surface s, int w, int h) throws Exception {
            dpy = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!android.opengl.EGL14.eglInitialize(dpy, v, 0, v, 1)) throw new Exception("No se pudo iniciar OpenGL");
            int[] attr = {android.opengl.EGL14.EGL_RED_SIZE, 8, android.opengl.EGL14.EGL_GREEN_SIZE, 8,
                    android.opengl.EGL14.EGL_BLUE_SIZE, 8,
                    android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1, android.opengl.EGL14.EGL_NONE};
            android.opengl.EGLConfig[] cf = new android.opengl.EGLConfig[1];
            int[] n = new int[1];
            if (!android.opengl.EGL14.eglChooseConfig(dpy, attr, 0, cf, 0, 1, n, 0) || n[0] < 1)
                throw new Exception("OpenGL sin configuración para video");
            ctx = android.opengl.EGL14.eglCreateContext(dpy, cf[0], android.opengl.EGL14.EGL_NO_CONTEXT,
                    new int[]{android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE}, 0);
            srf = android.opengl.EGL14.eglCreateWindowSurface(dpy, cf[0], s, new int[]{android.opengl.EGL14.EGL_NONE}, 0);
            if (srf == null || srf == android.opengl.EGL14.EGL_NO_SURFACE) throw new Exception("OpenGL sin superficie");
            android.opengl.EGL14.eglMakeCurrent(dpy, srf, srf, ctx);
            android.opengl.GLES20.glViewport(0, 0, w, h);
            prog = program(
                    "attribute vec2 p; attribute vec2 t; varying vec2 v; void main(){ v = t; gl_Position = vec4(p, 0.0, 1.0); }",
                    "precision mediump float; varying vec2 v; uniform sampler2D s; void main(){ gl_FragColor = texture2D(s, v); }");
            aPos = android.opengl.GLES20.glGetAttribLocation(prog, "p");
            aTex = android.opengl.GLES20.glGetAttribLocation(prog, "t");
            // la fila 0 del Bitmap (arriba) queda en t = 0
            float[] q = {-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0};
            quad = java.nio.ByteBuffer.allocateDirect(q.length * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(q).position(0);
            int[] t = new int[1];
            android.opengl.GLES20.glGenTextures(1, t, 0);
            tex = t[0];
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, tex);
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR);
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR);
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE);
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE);
        }

        void draw(android.graphics.Bitmap b, long nanos) {
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, tex);
            if (first) { android.opengl.GLUtils.texImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, b, 0); first = false; }
            else android.opengl.GLUtils.texSubImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, 0, 0, b);
            android.opengl.GLES20.glUseProgram(prog);
            quad.position(0);
            android.opengl.GLES20.glVertexAttribPointer(aPos, 2, android.opengl.GLES20.GL_FLOAT, false, 16, quad);
            android.opengl.GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2);
            android.opengl.GLES20.glVertexAttribPointer(aTex, 2, android.opengl.GLES20.GL_FLOAT, false, 16, quad);
            android.opengl.GLES20.glEnableVertexAttribArray(aTex);
            android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4);
            android.opengl.EGLExt.eglPresentationTimeANDROID(dpy, srf, nanos);
            android.opengl.EGL14.eglSwapBuffers(dpy, srf);
        }

        void release() {
            android.opengl.EGL14.eglMakeCurrent(dpy, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
            android.opengl.EGL14.eglDestroySurface(dpy, srf);
            android.opengl.EGL14.eglDestroyContext(dpy, ctx);
            android.opengl.EGL14.eglTerminate(dpy);
        }

        private static int program(String vs, String fs) {
            int p = android.opengl.GLES20.glCreateProgram();
            android.opengl.GLES20.glAttachShader(p, shader(android.opengl.GLES20.GL_VERTEX_SHADER, vs));
            android.opengl.GLES20.glAttachShader(p, shader(android.opengl.GLES20.GL_FRAGMENT_SHADER, fs));
            android.opengl.GLES20.glLinkProgram(p);
            return p;
        }

        private static int shader(int type, String src) {
            int sh = android.opengl.GLES20.glCreateShader(type);
            android.opengl.GLES20.glShaderSource(sh, src);
            android.opengl.GLES20.glCompileShader(sh);
            return sh;
        }
    }

    /** Mezcla y codifica todo el sonido a AAC; devuelve su formato (con los datos de configuración). */
    private static MediaFormat encodeAudio(Sounds.Plan plan, long durUs, ArrayList<Sample> out) throws Exception {
        MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Sounds.RATE, 2);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        long total = durUs * Sounds.RATE / 1_000_000L;
        long pos = 0;
        short[] buf = new short[2048 * 2];
        MediaFormat fmt = null;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inDone = false;
        try {
            while (true) {
                if (!inDone) {
                    int ib = enc.dequeueInputBuffer(10000);
                    if (ib >= 0) {
                        ByteBuffer in = enc.getInputBuffer(ib);
                        long pts = pos * 1_000_000L / Sounds.RATE;
                        if (pos >= total || in == null) {
                            enc.queueInputBuffer(ib, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            int n = (int) Math.min(Math.min(2048, total - pos), in.capacity() / 4);
                            Sounds.mix(plan, pos, buf, n);
                            in.clear();
                            in.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, 0, n * 2);
                            enc.queueInputBuffer(ib, 0, n * 4, pts, 0);
                            pos += n;
                        }
                    }
                }
                int ob = enc.dequeueOutputBuffer(info, 10000);
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) fmt = enc.getOutputFormat();
                else if (ob >= 0) {
                    ByteBuffer b = enc.getOutputBuffer(ob);
                    if (b != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        Sample s = new Sample();
                        s.data = new byte[info.size];
                        b.position(info.offset);
                        b.get(s.data);
                        s.pts = info.presentationTimeUs;
                        s.flags = info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                        out.add(s);
                    }
                    enc.releaseOutputBuffer(ob, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
                if (cancel) break;
            }
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
        }
        return fmt;
    }

    // ---------------------------------------------------------------- guardar en Películas › DUAL Motion
    private static Uri publish(Context c, File tmp, String name) throws Exception {
        String file = name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            v.put("relative_path", Environment.DIRECTORY_MOVIES + "/DUAL Motion");
            v.put("is_pending", 1);
            Uri u = c.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("No se pudo crear el video en Películas");
            try (InputStream in = new FileInputStream(tmp); OutputStream o = c.getContentResolver().openOutputStream(u)) {
                if (o == null) throw new Exception("No se pudo escribir el video");
                copy(in, o);
            }
            v.clear();
            v.put("is_pending", 0);
            c.getContentResolver().update(u, v, null, null);
            return u;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DUAL Motion");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, file);
        try (InputStream in = new FileInputStream(tmp); OutputStream o = new java.io.FileOutputStream(f)) { copy(in, o); }
        MediaScannerConnection.scanFile(c, new String[]{f.getPath()}, new String[]{"video/mp4"}, null);
        return Uri.fromFile(f);
    }

    private static void copy(InputStream in, OutputStream o) throws Exception {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
    }
}
