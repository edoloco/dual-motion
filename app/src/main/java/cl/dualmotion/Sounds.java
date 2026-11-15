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
    /**
     * PCM ya decodificado (16 bits, estéreo intercalado, 44.1 kHz) leído directo del archivo id.pcm con memoria
     * mapeada: no ocupa la memoria de la app, así que un sonido de muchos minutos (17 min ≈ 180 MB) no la hace caer.
     * Solo lectura con índices absolutos: se puede leer desde varios hilos a la vez.
     */
    static final class Pcm {
        private final java.nio.ShortBuffer b;
        final int length; // muestras (2 por instante)
        Pcm(java.nio.ShortBuffer b) { this.b = b; this.length = b.limit(); }
        short get(int i) { return b.get(i); }
        int frames() { return length / 2; }
    }

    private static final HashMap<String, Pcm> pcmCache = new HashMap<>();

    static synchronized Pcm cached(String id) { return pcmCache.get(id); }

    static synchronized float[] peaks(String id) { return peakCache.get(id); }

    /** PCM del sonido (lo decodifica la primera vez a id.pcm). Puede tardar: no llamar desde la pantalla. */
    static Pcm pcm(Context c, MDoc.Sound s) {
        Pcm p = cached(s.id);
        if (p != null) return p;
        synchronized (decoding) { // el mismo sonido no se decodifica dos veces a la vez
            p = cached(s.id);
            if (p != null) return p;
            File pf = new File(dir(c), s.id + ".pcm");
            try {
                if (!pf.isFile() || pf.length() < 4) {
                    File f = file(c, s.id, s.ext);
                    if (!f.isFile()) return null;
                    decodeTo(f, pf);
                }
                p = map(pf);
            } catch (Throwable e) { return null; }
            if (p.length < 2) return null;
            float[] pk = computePeaks(p);
            synchronized (Sounds.class) { pcmCache.put(s.id, p); peakCache.put(s.id, pk); }
        }
        changed();
        return p;
    }

    private static final Object decoding = new Object();

    private static Pcm map(File f) throws Exception {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
             java.nio.channels.FileChannel ch = raf.getChannel()) {
            long n = ch.size() & ~1L;
            java.nio.MappedByteBuffer mb = ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, n);
            return new Pcm(mb.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()); // sigue válido al cerrar el archivo
        }
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

    private static float[] computePeaks(Pcm p) {
        int per = RATE * PEAK_MS / 1000;
        int frames = p.frames();
        float[] out = new float[frames / per + 1];
        for (int i = 0; i < frames; i++) {
            int a = Math.max(Math.abs(p.get(i * 2)), Math.abs(p.get(i * 2 + 1)));
            int k = i / per;
            if (a > out[k] * 32768) out[k] = a / 32768f;
        }
        return out;
    }

    // ---------------------------------------------------------------- decodificar (MediaCodec) → estéreo 44.1 kHz
    /**
     * Decodifica el archivo y escribe el PCM estéreo 44.1 kHz directo al disco mientras avanza (nunca tiene el
     * sonido entero en memoria). La frecuencia se cambia con interpolación lineal sobre la marcha.
     */
    static void decodeTo(File f, File pcmFile) throws Exception {
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
        File tmp = new File(pcmFile.getPath() + ".tmp");
        Resampler rs = null;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inDone = false, outDone = false;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
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
                        if (rs == null) rs = new Resampler(Math.max(1, ch), Math.max(1, rate), out);
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        ByteBuffer le = buf.slice().order(ByteOrder.nativeOrder());
                        if (isFloat) { while (le.remaining() >= 4) rs.add((short) Math.max(-32768, Math.min(32767, le.getFloat() * 32767f))); }
                        else { while (le.remaining() >= 2) rs.add(le.getShort()); }
                    }
                    dec.releaseOutputBuffer(ob, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            if (rs != null) rs.finish();
        } finally {
            try { dec.stop(); } catch (Exception ignored) { }
            dec.release();
            ex.release();
        }
        if (rs == null) { //noinspection ResultOfMethodCallIgnored
            tmp.delete(); throw new Exception("sin muestras"); }
        if (!tmp.renameTo(pcmFile)) throw new Exception("no se pudo guardar");
    }

    /** Lleva cualquier cantidad de canales y frecuencia a estéreo 44.1 kHz, muestra por muestra, al disco. */
    static final class Resampler {
        private final int ch;
        private final double step;
        private final OutputStream out;
        private final ByteBuffer bb = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
        private final short[] frame;
        private int got;
        private long inFrames, outFrames;
        private double pos; // instante de salida, medido en instantes de entrada
        private float pl, pr; // instante de entrada anterior

        Resampler(int ch, int rate, OutputStream out) {
            this.ch = ch;
            this.step = rate / (double) RATE;
            this.out = out;
            this.frame = new short[ch];
        }

        void add(short v) throws java.io.IOException {
            frame[got++] = v;
            if (got < ch) return;
            got = 0;
            float l = frame[0], r = ch == 1 ? frame[0] : frame[1];
            if (inFrames > 0) { // salidas entre el instante anterior (inFrames-1) y este
                while (pos < inFrames) {
                    float t = (float) (pos - (inFrames - 1));
                    put(pl + (l - pl) * t, pr + (r - pr) * t);
                    pos = ++outFrames * step;
                }
            }
            pl = l; pr = r;
            inFrames++;
        }

        void finish() throws java.io.IOException {
            long total = (long) (inFrames / step); // como antes: instantes de entrada × 44100 / frecuencia
            while (outFrames < total) { put(pl, pr); outFrames++; }
            out.write(bb.array(), 0, bb.position());
            bb.clear();
        }

        private void put(float l, float r) throws java.io.IOException {
            if (bb.remaining() < 4) { out.write(bb.array(), 0, bb.position()); bb.clear(); }
            bb.putShort((short) l);
            bb.putShort((short) r);
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
                Pcm p = pcm(c, s);
                if (p == null) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    throw new Exception("este formato de audio no se puede leer");
                }
                s.dur = (int) ((long) p.frames() * 1000 / RATE);
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
    private static final HashSet<String> sending = new HashSet<>();

    /**
     * Manda el archivo en un hilo aparte, de a poco (espera que salgan los trozos anteriores): así un sonido largo
     * no llena la memoria ni congela la pantalla. Si el otro lo pide mientras va en camino, no se manda dos veces.
     */
    static void sendFile(Context c, String id, String ext) {
        final File f = file(c, id, ext);
        if (!f.isFile()) return;
        synchronized (sending) { if (!sending.add(id)) return; }
        new Thread(() -> {
            try (InputStream in = new FileInputStream(f)) {
                long len = f.length();
                int n = (int) Math.max(1, (len + CHUNK - 1) / CHUNK);
                byte[] b = new byte[CHUNK];
                for (int i = 0; i < n; i++) {
                    long t0 = System.currentTimeMillis();
                    while (LinkService.queued() > 6) {
                        if (System.currentTimeMillis() - t0 > 60000) return; // la conexión no avanza
                        Thread.sleep(15);
                    }
                    int got = 0, r;
                    while (got < CHUNK && (r = in.read(b, got, CHUNK - got)) > 0) got += r;
                    JSONObject m = new JSONObject();
                    m.put("t", "moSnd"); m.put("id", id); m.put("ext", ext); m.put("i", i); m.put("n", n);
                    m.put("d", Base64.encodeToString(b, 0, got, Base64.NO_WRAP));
                    LinkService.send(m);
                }
            } catch (Exception ignored) {
            } finally {
                synchronized (sending) { sending.remove(id); }
            }
        }, "dm-mandar-sonido").start();
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
        final ArrayList<Pcm> pcm = new ArrayList<>();
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
                Pcm data = load ? pcm(c, s) : cached(s.id);
                if (data == null) continue;
                p.pcm.add(data);
                p.pos.add(new long[]{(long) k.at * RATE / 1000, (long) k.in * RATE / 1000, (long) k.len * RATE / 1000});
                p.vol.add(k.vol * L.vol);
            }
        }
        return p;
    }

    /** Mezcla "frames" muestras desde la posición start (en muestras) en out (estéreo intercalado). */
    static void mix(Plan p, long start, short[] out, int frames) {
        int[] acc = new int[frames * 2];
        for (int k = 0; k < p.pcm.size(); k++) {
            Pcm src = p.pcm.get(k);
            long[] q = p.pos.get(k);
            float v = p.vol.get(k);
            long a = Math.max(start, q[0]), b = Math.min(start + frames, q[0] + q[2]);
            for (long t = a; t < b; t++) {
                long si = (q[1] + (t - q[0])) * 2;
                if (si + 1 >= src.length) break;
                int o = (int) (t - start) * 2;
                acc[o] += (int) (src.get((int) si) * v);
                acc[o + 1] += (int) (src.get((int) si + 1) * v);
            }
        }
        for (int i = 0; i < frames * 2; i++) out[i] = (short) Math.max(-32768, Math.min(32767, acc[i]));
    }

    // ---------------------------------------------------------------- reproducción
    /** Reproduce la mezcla en un hilo; la imagen se guía por lo que realmente ya sonó (positionMs). */
    /**
     * Sonido al pasar la aguja (como Flash con sonidos "Flujo"): cada vez que el cabezal cambia de fotograma suena
     * un trocito de la mezcla en ese punto. Si se arrastra rápido, se salta a lo último pedido (no se encola).
     */
    static final class Scrub {
        private final Object lock = new Object();
        private Plan plan;
        private long reqAt = -1;
        private int reqLen;
        private long lastReq;
        private Thread th;

        void play(Plan p, long atMs, int durMs) {
            if (p == null || p.isEmpty()) return;
            synchronized (lock) {
                plan = p;
                reqAt = atMs * RATE / 1000;
                reqLen = Math.max(256, durMs * RATE / 1000);
                lastReq = System.currentTimeMillis();
                if (th == null) {
                    th = new Thread(this::loop, "dm-scrub");
                    th.start();
                }
                lock.notifyAll();
            }
        }

        private void loop() {
            AudioTrack t = null;
            try {
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096), AudioTrack.MODE_STREAM);
                t.play();
                while (true) {
                    Plan p; long at; int n;
                    synchronized (lock) {
                        while (reqAt < 0) {
                            long idle = System.currentTimeMillis() - lastReq;
                            if (idle > 2500) { th = null; return; }
                            try { lock.wait(500); } catch (InterruptedException e) { th = null; return; }
                        }
                        p = plan; at = reqAt; n = reqLen;
                        reqAt = -1;
                    }
                    short[] buf = new short[n * 2];
                    mix(p, at, buf, n);
                    int fade = Math.min(n / 4, RATE / 200); // 5 ms de entrada y salida: sin chasquidos
                    for (int i = 0; i < fade; i++) {
                        float g = i / (float) fade;
                        buf[i * 2] = (short) (buf[i * 2] * g); buf[i * 2 + 1] = (short) (buf[i * 2 + 1] * g);
                        int j = n - 1 - i;
                        buf[j * 2] = (short) (buf[j * 2] * g); buf[j * 2 + 1] = (short) (buf[j * 2 + 1] * g);
                    }
                    if (t.write(buf, 0, n * 2) < 0) break;
                }
            } catch (Exception ignored) {
            } finally {
                synchronized (lock) { if (th == Thread.currentThread()) th = null; }
                if (t != null) { try { t.stop(); } catch (Exception ignored) { } t.release(); }
            }
        }
    }

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
