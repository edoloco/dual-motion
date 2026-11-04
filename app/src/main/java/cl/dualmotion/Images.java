package cl.dualmotion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * Imágenes (como "Importar a escenario" de Flash): se eligen SIEMPRE en el principal, se achican si son enormes
 * (lado máximo 2048 px; PNG si tienen transparencia, si no JPEG), y el archivo viaja al extendido por la misma
 * fila que las órdenes (moImg en trozos, moImgReq para pedir uno que falta). Cada teléfono las guarda en
 * files/imagenes/id.ext; el documento solo las nombra (MDoc.Image) y las pone como objetos (Shape.img).
 */
final class Images {
    private Images() { }

    static final int MAX = 2048;
    private static final int CHUNK = 180 * 1024;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final HashMap<String, Bitmap> cache = new HashMap<>();
    private static final HashSet<String> loading = new HashSet<>(), asked = new HashSet<>();
    private static volatile Context app;
    /** Aviso para redibujar cuando una imagen termina de cargar o de llegar. */
    static volatile Runnable onChange;

    static void init(Context c) { app = c.getApplicationContext(); }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "imagenes");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File file(Context c, String id, String ext) { return new File(dir(c), id + "." + ext); }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) main.post(r);
    }

    /** La imagen lista para dibujar, o null mientras carga (se carga en otro hilo y avisa). */
    static Bitmap get(MDoc d, String id) {
        synchronized (cache) {
            Bitmap b = cache.get(id);
            if (b != null) return b;
        }
        MDoc.Image im = d == null ? null : d.image(id);
        Context c = app;
        if (im == null || c == null) return null;
        File f = file(c, id, im.ext);
        if (!f.isFile()) return null;
        synchronized (cache) { if (!loading.add(id)) return null; }
        new Thread(() -> {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            synchronized (cache) { loading.remove(id); if (b != null) cache.put(id, b); }
            if (b != null) changed();
        }, "dm-imagen").start();
        return null;
    }

    /** Para exportar a video: espera a que la imagen cargue (no se llama desde la pantalla). */
    static Bitmap getNow(MDoc d, String id) {
        synchronized (cache) { Bitmap b = cache.get(id); if (b != null) return b; }
        MDoc.Image im = d.image(id);
        Context c = app;
        if (im == null || c == null) return null;
        Bitmap b = BitmapFactory.decodeFile(file(c, id, im.ext).getPath());
        if (b != null) synchronized (cache) { cache.put(id, b); }
        return b;
    }

    // ---------------------------------------------------------------- importar (en el principal)
    interface Done { void done(MDoc.Image im, String error); }

    static void importUri(final Context c, final Uri u, final Done done) {
        new Thread(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = c.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
                if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("no es una imagen");
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap b;
                try (InputStream in = c.getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o2); }
                if (b == null) throw new Exception("no se pudo leer la imagen");
                float k = Math.min(1f, MAX / (float) Math.max(b.getWidth(), b.getHeight()));
                if (k < 1f) {
                    Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
                    if (s != b) b.recycle();
                    b = s;
                }
                MDoc.Image im = new MDoc.Image();
                im.id = "i" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
                String name = Files.displayName(c, u);
                int dot = name.lastIndexOf('.');
                im.name = dot > 0 ? name.substring(0, dot) : name;
                boolean alpha = b.hasAlpha();
                im.ext = alpha ? "png" : "jpg";
                im.w = b.getWidth();
                im.h = b.getHeight();
                try (OutputStream out = new FileOutputStream(file(c, im.id, im.ext))) {
                    b.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, out);
                }
                synchronized (cache) { cache.put(im.id, b); }
                main.post(() -> done.done(im, null));
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : "error";
                main.post(() -> done.done(null, msg));
            }
        }, "dm-importar-imagen").start();
    }

    // ---------------------------------------------------------------- traspaso entre teléfonos
    static void sendFile(Context c, String id, String ext) {
        File f = file(c, id, ext);
        if (!f.isFile()) return;
        try (InputStream in = new FileInputStream(f)) {
            int n = (int) Math.max(1, (f.length() + CHUNK - 1) / CHUNK);
            byte[] b = new byte[CHUNK];
            for (int i = 0; i < n; i++) {
                int got = 0, r;
                while (got < CHUNK && (r = in.read(b, got, CHUNK - got)) > 0) got += r;
                JSONObject m = new JSONObject();
                m.put("t", "moImg"); m.put("id", id); m.put("ext", ext); m.put("i", i); m.put("n", n);
                m.put("d", Base64.encodeToString(b, 0, got, Base64.NO_WRAP));
                LinkService.send(m);
            }
        } catch (Exception ignored) { }
    }

    static void onChunk(Context c, JSONObject m) {
        String id = m.optString("id"), ext = m.optString("ext").toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9]+") || !ext.matches("png|jpg")) return;
        int i = m.optInt("i"), n = m.optInt("n");
        File part = new File(dir(c), id + "." + ext + ".part");
        try (OutputStream out = new FileOutputStream(part, i > 0)) {
            out.write(Base64.decode(m.optString("d"), Base64.NO_WRAP));
        } catch (Exception e) { return; }
        if (i == n - 1) {
            //noinspection ResultOfMethodCallIgnored
            part.renameTo(file(c, id, ext));
            synchronized (asked) { asked.remove(id); }
            changed();
        }
    }

    static void onRequest(Context c, JSONObject m) {
        String id = m.optString("id");
        for (String ext : new String[]{"png", "jpg"}) if (file(c, id, ext).isFile()) { sendFile(c, id, ext); return; }
    }

    /** Pide al otro teléfono las imágenes del documento que no están aquí. */
    static void need(Context c, MDoc d) {
        for (MDoc.Image im : d.images) {
            if (file(c, im.id, im.ext).isFile()) continue;
            synchronized (asked) { if (!asked.add(im.id)) continue; }
            JSONObject m = new JSONObject();
            try { m.put("t", "moImgReq"); m.put("id", im.id); } catch (JSONException ignored) { }
            LinkService.send(m);
        }
    }
}
