package cl.dualmotion;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * Sonidos: el archivo original vive en cada teléfono (files/sonidos/id.ext) y se decodifica una vez a PCM
 * (16 bits, estéreo, 44.1 kHz; caché id.pcm). Aquí también: importar, forma de onda, mezcla de las capas de audio,
 * reproducción y traspaso de archivos entre los teléfonos (moSnd en trozos, moSndReq para pedir uno que falta).
 */
final class Sounds {
    private Sounds() { }

    static final int RATE = 44100;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final HashMap<String, short[]> pcmCache = new HashMap<>();
    private static final HashMap<String, float[]> peakCache = new HashMap<>();
    /** Aviso para refrescar la pantalla cuando un sonido termina de cargarse o de llegar. */
    static volatile Runnable onChange;

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "sonidos");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File file(Context c, String id, String ext) { return new File(dir(c), id + "." + ext); }

    static boolean has(Context c, MDoc.Sound s) { return file(c, s.id, s.ext).isFile(); }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) main.post(r);
    }

    // ---------------------------------------------------------------- PCM y forma de onda
    static synchronized short[] cached(String id) { return pcmCache.get(id); }

    static synchronized float[] peaks(String id) { return peakCache.get(id); }

    /** PCM del sonido (lo decodifica la primera vez). Puede tardar: no llamar desde la pantalla. */
    static short[] pcm(Context c, MDoc.Sound s) {
        short[] p = cached(s.id);
        if (p != null) return p;
        File pf = new File(dir(c), s.id + ".pcm");
        try {
            if (pf.isFile()) p = readPcm(pf);
            else {
                File f = file(c, s.id, s.ext);
                if (!f.isFile()) return null;
                p = decode(f);
                writePcm(pf, p);
            }
        } catch (Exception e) { return null; }
        float[] pk = computePeaks(p);
        synchronized (Sounds.class) { pcmCache.put(s.id, p); peakCache.put(s.id, pk); }
        changed();
        return p;
    }

    /** Carga en segundo plano todos los sonidos del documento que estén en este teléfono. */
    static void warm(final Context c, MDoc d) {
        final ArrayList<MDoc.Sound> list = new ArrayList<>();
        for (MDoc.Sound s : d.sounds) if (cached(s.id) == null && has(c, s)) list.add(s);
        if (list.isEmpty()) return;
        new Thread(() -> { for (MDoc.Sound s : list) pcm(c, s); }, "dm-sonidos").start();
    }

    /** Un valor por cada 10 ms: el pico (0..1). */
    static final int PEAK_MS = 10;

    private static float[] computePeaks(short[] p) {
        int per = RATE * PEAK_MS / 1000;
        int frames = p.length / 2;
        float[] out = new float[frames / per + 1];
        for (int i = 0; i < frames; i++) {
            int a = Math.max(Math.abs(p[i * 2]), Math.abs(p[i * 2 + 1]));
            int k = i / per;
            if (a > out[k] * 32768) out[k] = a / 32768f;
        }
        return out;
    }

    private static short[] readPcm(File f) throws Exception {
        int n = (int) (f.length() / 2);
        short[] p = new short[n];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            byte[] b = new byte[1 << 16];
            int i = 0;
            while (i < n) {
                int want = Math.min(b.length, (n - i) * 2);
                in.readFully(b, 0, want);
                ByteBuffer.wrap(b, 0, want).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(p, i, want / 2);
                i += want / 2;
            }
        }
        return p;
    }

    private static void writePcm(File f, short[] p) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            ByteBuffer bb = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
            for (short v : p) {
                if (bb.remaining() < 2) { out.write(bb.array(), 0, bb.position()); bb.clear(); }
                bb.putShort(v);
            }
            out.write(bb.array(), 0, bb.position());
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    // ---------------------------------------------------------------- decodificar (MediaCodec) → estéreo 44.1 kHz
    static short[] decode(File f) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(f.getPath());
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat m = ex.getTrackFormat(i);
            String mime = m.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; fmt = m; break; }
        }
        if (track < 0) { ex.release(); throw new Exception("sin audio"); }
        ex.selectTrack(track);
        int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        boolean isFloat = false;
        MediaCodec dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        dec.configure(fmt, null, null, 0);
        dec.start();
        ShortList out = new ShortList();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inDone = false, outDone = false;
        try {
            while (!outDone) {
                if (!inDone) {
                    int ib = dec.dequeueInputBuffer(5000);
                    if (ib >= 0) {
                        ByteBuffer buf = dec.getInputBuffer(ib);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) { dec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(ib, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int ob = dec.dequeueOutputBuffer(info, 5000);
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey("pcm-encoding")) isFloat = of.getInteger("pcm-encoding") == 4;
                } else if (ob >= 0) {
                    ByteBuffer buf = dec.getOutputBuffer(ob);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        ByteBuffer le = buf.slice().order(ByteOrder.nativeOrder());
                        if (isFloat) { while (le.remaining() >= 4) out.add((short) Math.max(-32768, Math.min(32767, le.getFloat() * 32767f))); }
                        else { while (le.remaining() >= 2) out.add(le.getShort()); }
                    }
                    dec.releaseOutputBuffer(ob, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
        } finally {
            try { dec.stop(); } catch (Exception ignored) { }
            dec.release();
            ex.release();
        }
        return toStereo44(out.a, out.n, Math.max(1, ch), rate);
    }

    /** Lleva cualquier cantidad de canales y frecuencia a estéreo 44.1 kHz (interpolación lineal). */
    static short[] toStereo44(short[] src, int n, int ch, int rate) {
        int frames = n / ch;
        long outFrames = (long) frames * RATE / Math.max(1, rate);
        short[] out = new short[(int) outFrames * 2];
        double step = rate / (double) RATE;
        for (int i = 0; i < outFrames; i++) {
            double pos = i * step;
            int a = (int) pos;
            float t = (float) (pos - a);
            int b = Math.min(frames - 1, a + 1);
            for (int k = 0; k < 2; k++) {
                int cc = ch == 1 ? 0 : k;
                float va = src[a * ch + cc], vb = src[b * ch + cc];
                out[i * 2 + k] = (short) (va + (vb - va) * t);
            }
        }
        return out;
    }

    private static final class ShortList {
        short[] a = new short[1 << 16];
        int n;
        void add(short v) {
            if (n == a.length) { short[] b = new short[a.length * 2]; System.arraycopy(a, 0, b, 0, n); a = b; }
            a[n++] = v;
        }
    }

    // ---------------------------------------------------------------- importar
    interface Done { void done(MDoc.Sound s, String error); }

    /** Copia el archivo elegido a la carpeta de sonidos, lo decodifica y avisa en la pantalla. */
    static void importUri(final Context c, final Uri u, final Done done) {
        new Thread(() -> {
            MDoc.Sound s = new MDoc.Sound();
            try {
                String name = Files.displayName(c, u);
                int dot = name.lastIndexOf('.');
                s.ext = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") : "";
                if (s.ext.isEmpty() || s.ext.length() > 5) s.ext = "audio";
                s.name = dot > 0 ? name.substring(0, dot) : name;
                s.id = "s" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
                File f = file(c, s.id, s.ext);
                try (InputStream in = c.getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(f)) {
                    if (in == null) throw new Exception("no se pudo leer");
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                short[] p = pcm(c, s);
                if (p == null) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    throw new Exception("este formato de audio no se puede leer");
                }
                s.dur = (int) ((long) (p.length / 2) * 1000 / RATE);
                main.post(() -> done.done(s, null));
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : "error";
                main.post(() -> done.done(null, msg));
            }
        }, "dm-importar").start();
    }

    // ---------------------------------------------------------------- traspaso entre teléfonos
    private static final int CHUNK = 180 * 1024;
    private static final HashSet<String> asked = new HashSet<>();

    /** Manda el archivo al otro teléfono, en trozos (la misma fila que las órdenes: llega antes que ellas). */
    static void sendFile(Context c, String id, String ext) {
        File f = file(c, id, ext);
        if (!f.isFile()) return;
        try (InputStream in = new FileInputStream(f)) {
            long len = f.length();
            int n = (int) Math.max(1, (len + CHUNK - 1) / CHUNK);
            byte[] b = new byte[CHUNK];
            for (int i = 0; i < n; i++) {
                int got = 0, r;
                while (got < CHUNK && (r = in.read(b, got, CHUNK - got)) > 0) got += r;
                JSONObject m = new JSONObject();
                m.put("t", "moSnd"); m.put("id", id); m.put("ext", ext); m.put("i", i); m.put("n", n);
                m.put("d", Base64.encodeToString(b, 0, got, Base64.NO_WRAP));
                LinkService.send(m);
            }
        } catch (Exception ignored) { }
    }

    /** Llegó un trozo de archivo. */
    static void onChunk(Context c, JSONObject m) {
        String id = m.optString("id"), ext = m.optString("ext");
        if (!id.matches("[a-z0-9]+") || !ext.matches("[a-z0-9]{1,5}")) return;
        int i = m.optInt("i"), n = m.optInt("n");
        File part = new File(dir(c), id + "." + ext + ".part");
        try (OutputStream out = new FileOutputStream(part, i > 0)) {
            out.write(Base64.decode(m.optString("d"), Base64.NO_WRAP));
        } catch (Exception e) { return; }
        if (i == n - 1) {
            //noinspection ResultOfMethodCallIgnored
            part.renameTo(file(c, id, ext));
            synchronized (asked) { asked.remove(id); }
            new Thread(() -> {
                MDoc.Sound s = new MDoc.Sound();
                s.id = id; s.ext = ext;
                pcm(c, s);
            }, "dm-sonido-llego").start();
            changed();
        }
    }

    /** El otro teléfono pide un archivo que le falta. */
    static void onRequest(Context c, JSONObject m) {
        String id = m.optString("id");
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String nm = f.getName();
            if (nm.startsWith(id + ".") && !nm.endsWith(".pcm") && !nm.endsWith(".part") && !nm.endsWith(".tmp")) {
                sendFile(c, id, nm.substring(id.length() + 1));
                return;
            }
        }
    }

    /** Pide al otro teléfono los sonidos del documento que no están aquí. */
    static void need(Context c, MDoc d) {
        for (MDoc.Sound s : d.sounds) {
            if (has(c, s)) continue;
            synchronized (asked) { if (!asked.add(s.id)) continue; }
            JSONObject m = new JSONObject();
            try { m.put("t", "moSndReq"); m.put("id", s.id); } catch (JSONException ignored) { }
            LinkService.send(m);
        }
    }

    // ---------------------------------------------------------------- mezcla
    /** Foto de los trozos a sonar (se toma en la pantalla; la mezcla corre en otro hilo sin tocar el documento). */
    static final class Plan {
        final ArrayList<short[]> pcm = new ArrayList<>();
        final ArrayList<long[]> pos = new ArrayList<>(); // inicio en la línea de tiempo, inicio en el sonido, largo (muestras)
        final ArrayList<Float> vol = new ArrayList<>();
        boolean isEmpty() { return pcm.isEmpty(); }
    }

    /** Arma el plan con los sonidos ya cargados (los que aún no cargan, no suenan). */
    static Plan plan(Context c, MDoc d, boolean load) {
        Plan p = new Plan();
        for (MDoc.Layer L : d.layers) {
            if (!L.audio || !L.vis) continue;
            for (MDoc.Clip k : L.clips) {
                MDoc.Sound s = d.sound(k.s);
                if (s == null) continue;
                short[] data = load ? pcm(c, s) : cached(s.id);
                if (data == null) continue;
                p.pcm.add(data);
                p.pos.add(new long[]{(long) k.at * RATE / 1000, (long) k.in * RATE / 1000, (long) k.len * RATE / 1000});
                p.vol.add(k.vol);
            }
        }
        return p;
    }

    /** Mezcla "frames" muestras desde la posición start (en muestras) en out (estéreo intercalado). */
    static void mix(Plan p, long start, short[] out, int frames) {
        int[] acc = new int[frames * 2];
        for (int k = 0; k < p.pcm.size(); k++) {
            short[] src = p.pcm.get(k);
            long[] q = p.pos.get(k);
            float v = p.vol.get(k);
            long a = Math.max(start, q[0]), b = Math.min(start + frames, q[0] + q[2]);
            for (long t = a; t < b; t++) {
                long si = (q[1] + (t - q[0])) * 2;
                if (si + 1 >= src.length) break;
                int o = (int) (t - start) * 2;
                acc[o] += (int) (src[(int) si] * v);
                acc[o + 1] += (int) (src[(int) si + 1] * v);
            }
        }
        for (int i = 0; i < frames * 2; i++) out[i] = (short) Math.max(-32768, Math.min(32767, acc[i]));
    }

    // ---------------------------------------------------------------- reproducción
    /** Reproduce la mezcla en un hilo; la imagen se guía por lo que realmente ya sonó (positionMs). */
    static final class Player {
        private volatile boolean run;
        private AudioTrack track;
        private Thread th;
        private long from, loop;

        void start(final Plan p, long fromMs, long loopMs) {
            stop();
            if (p.isEmpty()) return;
            from = fromMs * RATE / 1000;
            loop = loopMs * RATE / 1000;
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            try {
                track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192) * 2, AudioTrack.MODE_STREAM);
                track.play();
            } catch (Exception e) { track = null; return; }
            run = true;
            final AudioTrack t = track;
            th = new Thread(() -> {
                short[] buf = new short[1024 * 2];
                long pos = from;
                while (run) {
                    int n = 1024;
                    if (loop > 0) {
                        if (pos >= loop) pos %= loop;
                        n = (int) Math.min(n, loop - pos);
                    }
                    mix(p, pos, buf, n);
                    if (t.write(buf, 0, n * 2) < 0) break;
                    pos += n;
                }
            }, "dm-audio");
            th.start();
        }

        boolean active() { return run && track != null; }

        /** Lo que ya sonó, en ms de la línea de tiempo (o -1). */
        long positionMs() {
            AudioTrack t = track;
            if (!run || t == null) return -1;
            long s = from + (t.getPlaybackHeadPosition() & 0xFFFFFFFFL);
            if (loop > 0) s %= loop;
            return s * 1000 / RATE;
        }

        void stop() {
            run = false;
            Thread x = th;
            th = null;
            if (x != null) try { x.join(300); } catch (InterruptedException ignored) { }
            AudioTrack t = track;
            track = null;
            if (t != null) { try { t.pause(); t.flush(); t.stop(); } catch (Exception ignored) { } t.release(); }
        }
    }
}
