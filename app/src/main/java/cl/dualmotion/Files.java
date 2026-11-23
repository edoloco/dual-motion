package cl.dualmotion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Formato propio .dmo (el equivalente al .fla): un ZIP con
 *   documento.json — el documento completo (capas, claves, formas, interpolaciones)
 *   dmo.txt        — marca de formato y versión
 *   sonidos/id.ext — los archivos de sonido de la biblioteca (tal como se importaron)
 *   imagenes/id.png|jpg — las imágenes importadas
 * Más adelante irán aquí los bitmaps (PNG).
 * Se guarda y se abre con el selector de archivos de Android (el usuario elige la carpeta).
 */
final class Files {
    private Files() { }

    static final int REQ_SAVE = 41, REQ_OPEN = 42;
    static final String DOC_ENTRY = "documento.json", MARK_ENTRY = "dmo.txt";

    /** Guardar (en el mismo archivo si ya tiene uno) o Guardar como… */
    static void save(MotionActivity a, boolean as) {
        String uri = a.prefs().getString("docUri", null);
        if (!as && uri != null && write(a, Uri.parse(uri), true)) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        String name = a.prefs().getString("docName", "Animación.dmo");
        if (!name.endsWith(".dmo")) name = "Animación.dmo";
        i.putExtra(Intent.EXTRA_TITLE, name);
        try { a.startActivityForResult(i, REQ_SAVE); }
        catch (Exception e) { Ui.toast(a, "Este teléfono no tiene selector de archivos"); }
    }

    static void open(MotionActivity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { a.startActivityForResult(i, REQ_OPEN); }
        catch (Exception e) { Ui.toast(a, "Este teléfono no tiene selector de archivos"); }
    }

    static final int REQ_AUDIO = 43, REQ_IMAGE = 44;

    /** Elegir una imagen (PNG, JPG, WebP…). */
    static void pickImage(MotionActivity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try { a.startActivityForResult(i, REQ_IMAGE); }
        catch (Exception e) { Ui.toast(a, "Este teléfono no tiene selector de archivos"); }
    }

    /** Elegir un archivo de sonido (mp3, m4a, wav, ogg…). */
    static void pickAudio(MotionActivity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        try { a.startActivityForResult(i, REQ_AUDIO); }
        catch (Exception e) { Ui.toast(a, "Este teléfono no tiene selector de archivos"); }
    }

    static void onResult(MotionActivity a, int req, int res, Intent data) {
        if (req == REQ_IMAGE) {
            if (res == Activity.RESULT_OK && data != null && data.getData() != null) a.importImage(data.getData());
            return;
        }
        if (req == REQ_AUDIO) {
            if (res == Activity.RESULT_OK && data != null && data.getData() != null) a.importSound(data.getData());
            return;
        }
        if (req != REQ_SAVE && req != REQ_OPEN) return;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            a.getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) { }
        if (req == REQ_SAVE) {
            write(a, u, false);
        } else {
            JSONObject d = read(a, u);
            if (d == null) { Ui.toast(a, "No se pudo abrir: no es un archivo de DUAL Motion"); return; }
            remember(a, u);
            a.loadDoc(d);
            Ui.toast(a, "Abierto: " + a.prefs().getString("docName", ""));
        }
    }

    private static void remember(MotionActivity a, Uri u) {
        a.prefs().edit().putString("docUri", u.toString()).putString("docName", displayName(a, u)).apply();
    }

    /** Escribe el documento en el archivo. quiet: si falla no avisa (luego se ofrece "Guardar como"). */
    private static boolean write(MotionActivity a, Uri u, boolean quiet) {
        byte[] json = a.doc().toJson().toString().getBytes(StandardCharsets.UTF_8);
        try {
            zip(a, u, json, parts(a));
            remember(a, u);
            a.savedToFile();
            Ui.toast(a, "Guardado: " + a.prefs().getString("docName", ""));
            return true;
        } catch (Exception e) {
            if (!quiet) Ui.toast(a, "No se pudo guardar");
            return false;
        }
    }

    /**
     * Guardado automático en el mismo .dmo: el documento se toma aquí (rápido) y el archivo se escribe en otro
     * hilo, sin avisos. done(true) si quedó guardado.
     */
    static void autoWrite(MotionActivity a, java.util.function.Consumer<Boolean> done) {
        String uri = a.prefs().getString("docUri", null);
        if (uri == null) { done.accept(false); return; }
        final byte[] json = a.doc().toJson().toString().getBytes(StandardCharsets.UTF_8);
        final java.util.List<String[]> parts = parts(a);
        final Context app = a.getApplicationContext();
        final Uri u = Uri.parse(uri);
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            boolean ok;
            try { zip(app, u, json, parts); ok = true; } catch (Exception e) { ok = false; }
            final boolean r = ok;
            main.post(() -> done.accept(r));
        }, "dm-guardar").start();
    }

    /** Archivos que van dentro del .dmo: {nombre en el zip, ruta}. */
    private static java.util.List<String[]> parts(MotionActivity a) {
        java.util.ArrayList<String[]> l = new java.util.ArrayList<>();
        for (MDoc.Sound snd : a.doc().sounds) {
            java.io.File f = Sounds.file(a, snd.id, snd.ext);
            if (f.isFile()) l.add(new String[]{"sonidos/" + snd.id + "." + snd.ext, f.getPath()});
        }
        for (MDoc.Image im : a.doc().images) {
            java.io.File f = Images.file(a, im.id, im.ext);
            if (f.isFile()) l.add(new String[]{"imagenes/" + im.id + "." + im.ext, f.getPath()});
        }
        return l;
    }

    /** Uno a la vez (el guardado automático y el de la mano no se pisan). */
    private static synchronized void zip(Context c, Uri u, byte[] json, java.util.List<String[]> parts) throws Exception {
        OutputStream os;
        try { os = c.getContentResolver().openOutputStream(u, "wt"); }
        catch (Exception e) { os = c.getContentResolver().openOutputStream(u, "w"); }
        if (os == null) throw new Exception("sin salida");
        try (ZipOutputStream z = new ZipOutputStream(new java.io.BufferedOutputStream(os, 1 << 16))) {
            z.putNextEntry(new ZipEntry(MARK_ENTRY));
            z.write("DUAL Motion\nformato 1\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry(DOC_ENTRY));
            z.write(json);
            z.closeEntry();
            byte[] buf = new byte[65536];
            for (String[] p : parts) {
                z.putNextEntry(new ZipEntry(p[0]));
                try (InputStream in = new java.io.FileInputStream(p[1])) {
                    int n;
                    while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                }
                z.closeEntry();
            }
        }
    }

    /** Lee un .dmo (o un JSON suelto, por si acaso); los sonidos que trae quedan en la carpeta de sonidos. */
    static JSONObject read(Context a, Uri u) {
        try (InputStream raw = a.getContentResolver().openInputStream(u)) {
            if (raw == null) return null;
            // se lee de corrido (sin cargar el archivo entero): un .dmo con sonidos largos puede pesar mucho
            java.io.BufferedInputStream in = new java.io.BufferedInputStream(raw, 1 << 16);
            in.mark(4);
            int first = in.read();
            in.reset();
            if (first == '{') return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            JSONObject doc = null;
            try (ZipInputStream z = new ZipInputStream(in)) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    String nm = e.getName();
                    if (DOC_ENTRY.equals(nm)) {
                        JSONObject d = new JSONObject(new String(readAll(z), StandardCharsets.UTF_8));
                        if (d.has("layers")) doc = d;
                    } else if (nm.startsWith("imagenes/") && nm.substring(9).matches("[a-z0-9]+\\.(png|jpg)")) {
                        java.io.File f = new java.io.File(Images.dir(a), nm.substring(9));
                        if (!f.isFile()) copyTo(z, f);
                    } else if (nm.startsWith("sonidos/") && nm.substring(8).matches("[a-z0-9]+\\.[a-z0-9]{1,5}")) {
                        java.io.File f = new java.io.File(Sounds.dir(a), nm.substring(8));
                        if (!f.isFile()) copyTo(z, f);
                    }
                }
            }
            return doc;
        } catch (Exception ignored) { }
        return null;
    }

    private static void copyTo(InputStream in, java.io.File f) throws java.io.IOException {
        java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
        try (OutputStream o = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String displayName(Context a, Uri u) {
        try (Cursor c = a.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null) return s;
            }
        } catch (Exception ignored) { }
        String s = u.getLastPathSegment();
        return s == null ? "Animación.dmo" : s;
    }
}
