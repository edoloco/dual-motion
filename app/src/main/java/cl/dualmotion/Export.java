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
        long[] written = {0};
        boolean[] started = {false};
        try {
            for (int f = 0; f < frames && !cancel; f++) {
                Canvas c = Build.VERSION.SDK_INT >= 23 ? surface.lockHardwareCanvas() : surface.lockCanvas(null);
                try {
                    c.drawColor(0xFFFFFFFF);
                    c.save();
                    c.scale(W / (float) d.w, H / (float) d.h);
                    Render.frame(c, d, f, true, false, 1);
                    c.restore();
                } finally {
                    surface.unlockCanvasAndPost(c);
                }
                drain(enc, mux, tracks, started, audioFmt, audio, written, fps, false);
                if (l != null && (f % 4 == 0 || f == frames - 1)) l.progress(f + 1, frames);
            }
            enc.signalEndOfInputStream();
            drain(enc, mux, tracks, started, audioFmt, audio, written, fps, true);
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            surface.release();
            try { if (started[0]) mux.stop(); } catch (Exception ignored) { }
            mux.release();
        }
        if (!started[0]) throw new Exception("El codificador de video no entregó nada");
    }

    /** Saca lo codificado y lo escribe; el tiempo de cada fotograma se pone exacto (n / fps). */
    private static void drain(MediaCodec enc, MediaMuxer mux, int[] tracks, boolean[] started, MediaFormat audioFmt,
                              ArrayList<Sample> audio, long[] written, int fps, boolean end) {
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
            if (buf != null && info.size > 0 && !config && started[0]) {
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                info.presentationTimeUs = written[0] * 1_000_000L / fps;
                mux.writeSampleData(tracks[0], buf, info);
                written[0]++;
            }
            enc.releaseOutputBuffer(ob, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
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
