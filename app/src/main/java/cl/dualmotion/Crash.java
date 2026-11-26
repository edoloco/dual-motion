package cl.dualmotion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Si la app se cae, guarda qué pasó (ultimo-error.txt) antes de cerrarse; la próxima vez que se abre lo muestra
 * con un botón para copiarlo. Así se puede saber dónde falló aunque el teléfono solo diga "se cerró".
 */
final class Crash {
    private static volatile boolean installed;

    static void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("DUAL Motion " + Updater.installed(app) + " · " + new java.util.Date());
                pw.println("Android " + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MANUFACTURER + " "
                        + android.os.Build.MODEL + " · hilo " + t.getName());
                Runtime r = Runtime.getRuntime();
                pw.println("Memoria: " + (r.totalMemory() - r.freeMemory()) / 1048576 + " de " + r.maxMemory() / 1048576 + " MB");
                e.printStackTrace(pw);
                pw.flush();
                try (FileOutputStream o = new FileOutputStream(new File(app.getFilesDir(), "ultimo-error.txt"))) {
                    o.write(sw.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
            else System.exit(2);
        });
    }

    /** Si la vez anterior se cayó, lo muestra (una vez). */
    static void showIfAny(final Activity a) {
        final File f = new File(a.getFilesDir(), "ultimo-error.txt");
        if (!f.isFile()) return;
        String txt;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 20000)];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            txt = new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) { txt = "(no se pudo leer)"; }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        final String all = txt;
        String[] lines = all.split("\n");
        StringBuilder shortTxt = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 14); i++) shortTxt.append(lines[i]).append('\n');
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("La última vez DUAL Motion se cerró por un error")
                .setMessage("Tu trabajo se recupera solo. Copia este detalle y mándalo para arreglarlo:\n\n" + shortTxt)
                .setPositiveButton("Copiar detalle", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Error de DUAL Motion", all));
                    Ui.toast(a, "Copiado: pégalo en el chat");
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private Crash() { }
}
