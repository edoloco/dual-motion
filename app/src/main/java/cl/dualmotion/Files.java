package cl.dualmotion;

import android.app.Activity;
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
 * Más adelante irán aquí los bitmaps (PNG) y los sonidos.
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

    static void onResult(MotionActivity a, int req, int res, Intent data) {
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
        OutputStream os = null;
        try {
            try { os = a.getContentResolver().openOutputStream(u, "wt"); }
            catch (Exception e) { os = a.getContentResolver().openOutputStream(u, "w"); }
            if (os == null) throw new Exception("sin salida");
            try (ZipOutputStream z = new ZipOutputStream(os)) {
                z.putNextEntry(new ZipEntry(MARK_ENTRY));
                z.write("DUAL Motion\nformato 1\n".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
                z.putNextEntry(new ZipEntry(DOC_ENTRY));
                z.write(json);
                z.closeEntry();
            }
            remember(a, u);
            Ui.toast(a, "Guardado: " + a.prefs().getString("docName", ""));
            return true;
        } catch (Exception e) {
            if (!quiet) Ui.toast(a, "No se pudo guardar");
            try { if (os != null) os.close(); } catch (Exception ignored) { }
            return false;
        }
    }

    /** Lee un .dmo (o un JSON suelto, por si acaso). */
    static JSONObject read(Activity a, Uri u) {
        try (InputStream in = a.getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            byte[] all = readAll(in);
            if (all.length > 0 && all[0] == '{') return new JSONObject(new String(all, StandardCharsets.UTF_8));
            try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(all))) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (DOC_ENTRY.equals(e.getName())) {
                        JSONObject d = new JSONObject(new String(readAll(z), StandardCharsets.UTF_8));
                        return d.has("layers") ? d : null;
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String displayName(Activity a, Uri u) {
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
