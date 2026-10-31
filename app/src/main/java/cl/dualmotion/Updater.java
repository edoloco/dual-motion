package cl.dualmotion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Actualización como en ChronOS: busca la versión más nueva en GitHub (Releases de edoloco/dual-motion),
 * descarga DualMotion.apk y lo instala con el instalador de paquetes de Android. "Actualizar los dos teléfonos"
 * le manda moUpdate al otro, que hace lo mismo solo (y muestra el instalador o un aviso para tocar).
 */
final class Updater {
    private Updater() { }

    static final String REPO = "edoloco/dual-motion", APK = "DualMotion.apk";
    private static final Handler main = new Handler(Looper.getMainLooper());

    static final class Release { String tag, url; long size; }

    interface Progress { void on(long done, long total); }

    static String installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    /** "v0.261031.1912" → "0.261031.1912". */
    static String clean(String v) {
        if (v == null) return "";
        if (v.startsWith("v")) v = v.substring(1);
        int d = v.indexOf('-');
        return d > 0 ? v.substring(0, d) : v;
    }

    static boolean newer(String latest, String inst) {
        String[] a = clean(latest).split("\\."), b = clean(inst).split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? num(a[i]) : 0, y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static long num(String s) { try { return Long.parseLong(s); } catch (Exception e) { return 0; } }

    /** La versión publicada de número más alto que trae DualMotion.apk. */
    static Release latest() throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases?per_page=20").openConnection();
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "DualMotion");
        h.setConnectTimeout(10000);
        h.setReadTimeout(15000);
        int code = h.getResponseCode();
        if (code != 200) throw new IOException("GitHub respondió " + code + ". Prueba en un rato.");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = h.getInputStream()) { byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
        try {
            JSONArray rels = new JSONArray(new String(b.toByteArray(), StandardCharsets.UTF_8));
            Release best = null;
            for (int k = 0; k < rels.length(); k++) {
                JSONObject o = rels.getJSONObject(k);
                if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue;
                JSONArray as = o.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    JSONObject a = as.getJSONObject(i);
                    if (!APK.equals(a.optString("name"))) continue;
                    Release r = new Release();
                    r.tag = o.optString("tag_name");
                    r.url = a.optString("browser_download_url");
                    r.size = a.optLong("size");
                    if (best == null || newer(r.tag, best.tag)) best = r;
                }
            }
            if (best == null) throw new IOException("No encontré versiones publicadas");
            return best;
        } catch (org.json.JSONException e) { throw new IOException("Respuesta inválida de GitHub"); }
    }

    static File download(Context c, Release r, Progress p) throws IOException {
        File dir = new File(c.getCacheDir(), "actualizar");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "DualMotion-" + clean(r.tag) + ".apk");
        URL u = new URL(r.url);
        for (int hop = 0; hop < 5; hop++) { // GitHub redirige al servidor de descargas
            HttpURLConnection h = (HttpURLConnection) u.openConnection();
            h.setInstanceFollowRedirects(false);
            h.setRequestProperty("User-Agent", "DualMotion");
            h.setConnectTimeout(10000);
            h.setReadTimeout(30000);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) { u = new URL(u, h.getHeaderField("Location")); continue; }
            if (code != 200) throw new IOException("La descarga respondió " + code);
            long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : r.size;
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                long done = 0, last = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (p != null && now - last > 150) { last = now; p.on(done, total); }
                }
            }
            String bad = check(c, f, r.size);
            if (bad != null) throw new IOException(bad);
            return f;
        }
        throw new IOException("Demasiadas redirecciones");
    }

    static String check(Context c, File apk, long expected) {
        if (!apk.isFile() || apk.length() == 0) return "La descarga quedó vacía";
        if (expected > 0 && apk.length() != expected) return "La descarga quedó incompleta";
        android.content.pm.PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (pi == null) return "El archivo descargado no es un APK válido";
        if (!c.getPackageName().equals(pi.packageName)) return "El archivo no es de DUAL Motion";
        return null;
    }

    static boolean canInstall(Context c) { return c.getPackageManager().canRequestPackageInstalls(); }

    static Intent allowInstall(Context c) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Instala con el instalador de paquetes (Android pide confirmar con "Actualizar"). */
    static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent cb = new Intent(c, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
            s.commit(PendingIntent.getBroadcast(c, 77, cb, flags).getIntentSender());
        }
    }

    /** Respuesta del instalador: mostrar la confirmación o avisar si falló. */
    public static final class InstallReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) LinkService.startOrNotify(c, confirm, 78, "Actualizar DUAL Motion", "Toca para confirmar la actualización");
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "No se pudo actualizar" + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------------------------------------------------------------- aviso al iniciar
    /** Revisa en silencio (cada 3 h como mucho); si hay versión nueva, ofrece actualizar. */
    static void checkQuiet(MotionActivity a) {
        SharedPreferences p = a.prefs();
        if (System.currentTimeMillis() - p.getLong("updAt", 0) < 3 * 3600_000L) return;
        p.edit().putLong("updAt", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                Release r = latest();
                if (newer(r.tag, installed(a))) main.post(() -> { if (!a.isFinishing()) offer(a, r); });
            } catch (Exception ignored) { }
        }, "dm-buscar-version").start();
    }

    /** Menú › Buscar actualización. */
    static void check(MotionActivity a) {
        Toast.makeText(a, "Buscando la última versión…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Release r = null;
            String err = null;
            try { r = latest(); } catch (IOException e) { err = e.getMessage(); }
            final Release fr = r;
            final String fe = err;
            main.post(() -> {
                if (a.isFinishing()) return;
                if (fe != null) {
                    new AlertDialog.Builder(a, android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("Actualizar")
                            .setMessage("Instalada: " + clean(installed(a)) + "\n\n" + fe).setPositiveButton("Cerrar", null).show();
                } else offer(a, fr);
            });
        }, "dm-buscar-version").start();
    }

    private static void offer(MotionActivity a, Release r) {
        boolean nw = newer(r.tag, installed(a));
        boolean both = LinkService.isConnected();
        AlertDialog.Builder b = new AlertDialog.Builder(a, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(nw ? "Hay una versión nueva" : "Ya tienes la última versión")
                .setMessage("Instalada: " + clean(installed(a)) + "\nÚltima: " + clean(r.tag)
                        + (both ? "\n\nSe actualizan los dos teléfonos: en el otro aparece el instalador (o un aviso para tocar)."
                        : "\n\nEl otro teléfono no está conectado: actualízalo después."))
                .setNegativeButton("Ahora no", null);
        if (both) {
            b.setPositiveButton(nw ? "Actualizar los dos" : "Reinstalar en los dos", (d, w) -> {
                JSONObject m = new JSONObject();
                try { m.put("t", "moUpdate"); } catch (Exception ignored) { }
                LinkService.send(m);
                installHere(a, r);
            });
            b.setNeutralButton("Solo este", (d, w) -> installHere(a, r));
        } else b.setPositiveButton(nw ? "Actualizar" : "Reinstalar", (d, w) -> installHere(a, r));
        b.show();
    }

    private static void installHere(Activity a, Release r) {
        if (!canInstall(a)) {
            new AlertDialog.Builder(a, android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("Permitir instalar")
                    .setMessage("Android pide permitir que DUAL Motion instale sus propias actualizaciones. Actívalo, vuelve y toca Actualizar otra vez.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Abrir ajustes", (d, w) -> { try { a.startActivity(allowInstall(a)); } catch (Exception ignored) { } })
                    .show();
            return;
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 10), Ui.dp(a, 20), 0);
        TextView t = new TextView(a);
        t.setText("Descargando " + clean(r.tag) + "…");
        box.addView(t);
        ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        box.addView(pb);
        AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Actualizando").setView(box).setCancelable(false).show();
        new Thread(() -> {
            try {
                File f = download(a, r, (done, total) -> main.post(() -> { if (total > 0) pb.setProgress((int) (done * 1000 / total)); }));
                install(a, f);
                main.post(() -> { try { d.dismiss(); } catch (Exception ignored) { } });
            } catch (IOException e) {
                main.post(() -> {
                    try { d.dismiss(); } catch (Exception ignored) { }
                    Toast.makeText(a, "No se pudo actualizar: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "dm-descarga").start();
    }

    /** Pedido desde el otro teléfono: descarga en segundo plano y deja el instalador (o un aviso) listo. */
    static void fromRemote(Context c) {
        new Thread(() -> {
            try {
                if (!canInstall(c)) {
                    LinkService.startOrNotify(c, allowInstall(c), 31, "DUAL Motion quiere actualizarse",
                            "Toca y permite instalar apps; después busca la actualización otra vez");
                    tell("Este teléfono necesita permiso para instalar: toca el aviso allá");
                    return;
                }
                tell("Descargando la actualización en el otro teléfono…");
                Release r = latest();
                File f = download(c, r, null);
                install(c, f);
                tell("Confirma la actualización en el otro teléfono (\"Actualizar\")");
            } catch (IOException e) {
                tell("El otro teléfono no pudo actualizarse: " + e.getMessage());
            }
        }, "dm-actualizar-remoto").start();
    }

    private static void tell(String msg) {
        JSONObject m = new JSONObject();
        try { m.put("t", "moMsg"); m.put("msg", msg); } catch (Exception ignored) { }
        LinkService.send(m);
    }
}
