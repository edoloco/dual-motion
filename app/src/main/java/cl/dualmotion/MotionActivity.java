package cl.dualmotion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * DUAL Motion: animación en dos pantallas.
 *  - "view"  (principal, arriba): herramientas, línea de tiempo y propiedades; la vista previa se ve aquí a
 *    pantalla completa.
 *  - "edit"  (extendido, abajo): la mesa de trabajo. Es el dueño del documento: aplica cada orden y la reenvía.
 *  - "solo"  (un teléfono): todo junto.
 * Mensajes: mo {op…} (orden aplicada), moFull, moReq (el principal pide una orden), moState (herramienta,
 * colores, capa, fotograma), moPlay / moTick (reproducción), moCmd, moPick, moSel.
 */
public final class MotionActivity extends Activity
        implements StageView.Host, TimelineView.Host, PlayerBar.Host, Net.Listener {

    public static final String VIEW = "view", EDIT = "edit", SOLO = "solo";
    static final String RELEASES = "https://github.com/edoloco/dual-motion/releases";

    private SharedPreferences prefs;
    private String role;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private MDoc doc = new MDoc();
    private final State st = new State();
    /** Pantalla de DUAL Motion a la vista (el servicio lo mira antes de traer la mesa al frente). */
    static volatile boolean visible;
    /** Principal: ¿la mesa del extendido está a la vista? */
    private boolean stageShown;
    /** Principal: no ocultar la mesa al salir (se abrió un selector de archivos o un permiso). */
    private boolean keepStage;
    private boolean connected;
    private int selN;
    /** Símbolo elegido en la mesa (si es lo único seleccionado): nombre y cómo se repite. */
    private String selSym;
    private int selLoop;

    /** Capas de la línea de tiempo en que se trabaja (la escena o el símbolo que se edita). */
    private java.util.List<MDoc.Layer> T() { return doc.timeline(st.sym); }

    private MDoc.Layer lay(int l) {
        java.util.List<MDoc.Layer> t = T();
        return l >= 0 && l < t.size() ? t.get(l) : null;
    }

    @Override public java.util.List<MDoc.Layer> layers() { return T(); }

    private FrameLayout root;
    private StageView stage;
    private TimelineView tl;
    private View mini;
    private TextView connLabel, infoLabel, selLabel, fpsLabel, widthLabel, onionBtn, docLabel;
    private TextView tweenBtn, spinLabel, alphaLabel;
    private SeekBar alphaBar;
    private CurveView curve;
    private SeekBar widthBar;
    private Swatch strokeSw, fillSw;
    private boolean targetFill = true;
    private Ui.ToolButton[] toolBtns;
    private FrameLayout previewLayer;
    private View previewView, previewBar;
    private TextView previewPlay, previewLoop, previewInfo;

    // ================================================================ ciclo de vida
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("dm", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(wlp);
        String r = getIntent().getStringExtra("role");
        if (r == null) r = prefs.getString("role", null);
        if (r == null) { setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); chooseRole(); return; }
        start(r);
    }

    private void start(String r) {
        role = r;
        if (EDIT.equals(r)) { setShowWhenLocked(true); setTurnScreenOn(true); }
        if (!isView()) loadAutosave();
        if (!isEdit()) restoreState();
        if (isEdit()) buildEdit(); else buildMain();
        keepClearOfCutout(root);
        if (!isSolo()) { LinkService.start(this, role); LinkService.attach(this); }
        Sounds.onChange = () -> { if (tl != null) tl.invalidate(); };
        Sounds.warm(this, doc);
        Images.init(this);
        Images.onChange = () -> { if (stage != null) stage.invalidate(); if (mini != null) mini.invalidate();
            if (previewView != null) previewView.invalidate(); };
        askPermissions();
        if (!isEdit()) Updater.checkQuiet(this);
        refreshAll();
    }

    /** Avisos (Android 13+) y, en el extendido, "mostrar sobre otras apps" para que la mesa aparezca sola. */
    private void askPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !isSolo()
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5);
        if (isEdit() && xiaomi() && !prefs.getBoolean("miuiAsked", false)) {
            prefs.edit().putBoolean("miuiAsked", true).putBoolean("overlayAsked", true).apply();
            permsDialog();
            return;
        }
        if (isEdit() && !android.provider.Settings.canDrawOverlays(this) && !prefs.getBoolean("overlayAsked", false)) {
            prefs.edit().putBoolean("overlayAsked", true).apply();
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("Mesa automática")
                    .setMessage("Para que la mesa de trabajo aparezca sola cuando abras DUAL Motion en el principal, "
                            + "Android pide permitir \"Mostrar sobre otras apps\" para DUAL Motion en este teléfono.")
                    .setNegativeButton("Ahora no", null)
                    .setPositiveButton("Abrir ajustes", (d, w) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) { }
                    }).show();
        }
    }

    static boolean xiaomi() {
        String m = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).toLowerCase(Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    /** Lo que hace falta en este teléfono para que la mesa aparezca sola (y no se corte la conexión). */
    private void permsDialog() {
        boolean mi = xiaomi();
        String msg = (isView() ? "Esto se revisa en el EXTENDIDO (el de la mesa). En este teléfono basta con permitir los avisos.\n\n" : "")
                + "Para que la mesa aparezca sola al abrir DUAL Motion en el principal:\n\n"
                + "• Mostrar sobre otras apps: " + (android.provider.Settings.canDrawOverlays(this) ? "listo ✓" : "falta") + "\n"
                + (mi ? "• Xiaomi: en Otros permisos, activa \"Mostrar ventanas emergentes mientras se ejecuta en segundo plano\" "
                        + "y \"Mostrar en pantalla de bloqueo\".\n• Xiaomi: activa el Inicio automático.\n"
                        + "• Ahorro de batería de la app: Sin restricciones.\n" : "")
                + "\nSi aun así no aparece, en el extendido sale un aviso de DUAL Motion para tocar.";
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Permisos para la mesa").setMessage(msg)
                .setNegativeButton("Cerrar", null);
        b.setPositiveButton(mi ? "Otros permisos" : "Mostrar sobre otras apps", (d, w) -> {
            Intent i;
            if (mi) i = new Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", getPackageName());
            else i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            openOr(i);
        });
        b.setNeutralButton(mi ? "Inicio automático" : "Ajustes de la app", (d, w) -> {
            Intent i = mi ? new Intent().setComponent(new android.content.ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")) : null;
            openOr(i);
        });
        b.show();
    }

    /** Abre ese ajuste; si el teléfono no lo tiene, la ficha de la app. */
    private void openOr(Intent i) {
        try { if (i != null) { startActivity(i); return; } } catch (Exception ignored) { }
        try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        catch (Exception ignored) { }
    }

    private boolean isView() { return VIEW.equals(role); }
    private boolean isEdit() { return EDIT.equals(role); }
    private boolean isSolo() { return SOLO.equals(role); }

    /** Un solo teléfono: siempre parado, mitad y mitad (arriba lo del principal, abajo la mesa). */
    private boolean half() { return isSolo(); }

    @Override protected void onResume() { super.onResume(); hideBars(); keepStage = false; }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        if (role == null) return;
        if (isEdit()) { send("moVis", "on", true); LinkService.cancelStageNotice(this); }
        else if (isView() && connected) askStage();
    }

    /**
     * Principal: pide la mesa al extendido y lo vuelve a pedir si no aparece (en algunos teléfonos la primera vez
     * se bloquea); si tampoco, avisa.
     */
    private void askStage() {
        send("moShow");
        ui.removeCallbacks(stageRetry);
        stageTries = 0;
        ui.postDelayed(stageRetry, 2500);
    }

    /** Botón "⇪ Mesa": trae la mesa al frente en el extendido aunque se haya quedado en el escritorio. */
    private void bringStage() {
        if (!connected) { Ui.toast(this, "Sin conexión con el extendido"); return; }
        send("moShow", "force", true);
        ui.removeCallbacks(stageRetry);
        stageTries = 0;
        stageShown = false;
        ui.postDelayed(stageRetry, 2500);
        Ui.toast(this, "Trayendo la mesa al frente…");
    }

    private int stageTries;
    private final Runnable stageRetry = new Runnable() {
        @Override public void run() {
            if (!visible || !connected || stageShown || !isView()) return;
            if (++stageTries <= 2) { send("moShow"); ui.postDelayed(this, 2500); }
            else Ui.toast(MotionActivity.this, "La mesa no se abrió sola: en el extendido toca el aviso de DUAL Motion (☰ › Permisos en Xiaomi)");
        }
    };

    @Override protected void onStop() {
        super.onStop();
        visible = false;
        if (role == null) return;
        if (!isEdit()) saveFileNow(true); // a segundo plano: queda guardado en el archivo
        if (isEdit()) send("moVis", "on", false);
        // el principal se fue a segundo plano: la mesa se esconde (no si solo se abrió un selector o un permiso)
        else if (isView() && connected && !keepStage && !isChangingConfigurations()) send("moHide");
    }

    @Override public void startActivityForResult(Intent i, int code, Bundle opts) {
        keepStage = true;
        super.startActivityForResult(i, code, opts);
    }

    @Override public void startActivity(Intent i) {
        keepStage = true;
        super.startActivity(i);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }

    @Override protected void onPause() {
        super.onPause();
        if (role == null) return;
        if (!isView()) snapshot(false);
        if (!isEdit()) saveState();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopPlay(false);
        LinkService.detach(this);
    }

    /** Teclado físico: Enter o espacio prueban aquí; "," y "." retroceden y avanzan un fotograma (como Flash). */
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int k = e.getKeyCode();
        boolean typing = getCurrentFocus() instanceof EditText;
        if (!typing && role != null && e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            switch (k) {
                case android.view.KeyEvent.KEYCODE_ENTER:
                case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER:
                case android.view.KeyEvent.KEYCODE_SPACE:
                    if (previewOpen()) { if (playing) pausePreview(); else resumePreview(); } else playHere();
                    return true;
                case android.view.KeyEvent.KEYCODE_COMMA:
                    if (!isEdit()) { select(st.layer, Math.max(0, st.frame - 1)); return true; }
                    break;
                case android.view.KeyEvent.KEYCODE_PERIOD:
                    if (!isEdit()) { select(st.layer, st.frame + 1); return true; }
                    break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onBackPressed() {
        if (playing && !previewOpen() && !isEdit()) { stopHere(); return; }
        if (previewLayer != null && previewLayer.getVisibility() == View.VISIBLE) { closePreview(); return; }
        super.onBackPressed();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(float v) { return Ui.dp(this, v); }

    // ================================================================ elegir el papel de este teléfono
    private void chooseRole() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Ui.WORK);
        box.setPadding(dp(24), dp(16), dp(24), dp(16));
        TextView t = Ui.label(this, "DUAL Motion", 30, 0xFFFFFFFF);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        TextView q = Ui.label(this, "¿Qué es este teléfono?", 16, 0xFFFFFFFF);
        q.setGravity(Gravity.CENTER);
        q.setPadding(0, dp(4), 0, dp(16));
        box.addView(q);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.addView(roleCard("Principal", "Arriba: herramientas, línea de tiempo y vista previa", VIEW, true));
        row.addView(roleCard("Extendido", "Abajo: la mesa de trabajo para dibujar", EDIT, false));
        row.addView(roleCard("Un solo teléfono", "Parado: arriba herramientas, abajo la mesa", SOLO, false));
        box.addView(row);
        setContentView(box);
    }

    private View roleCard(String title, String sub, final String r, boolean orange) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(16), dp(18), dp(16), dp(18));
        Ui.panel(c, orange ? Ui.orange(this) : Ui.glass(this));
        TextView a = Ui.label(this, title, 20, orange ? 0xFFFFFFFF : Ui.INK);
        a.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        a.setGravity(Gravity.CENTER);
        TextView b = Ui.label(this, sub, 13, orange ? 0xFFFFFFFF : 0xFF555555);
        b.setGravity(Gravity.CENTER);
        c.addView(a);
        c.addView(b);
        c.setOnClickListener(v -> { prefs.edit().putString("role", r).apply(); recreate(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(130));
        lp.setMargins(dp(8), 0, dp(8), 0);
        c.setLayoutParams(lp);
        return c;
    }

    // ================================================================ armar pantallas
    /** Extendido: solo la mesa de trabajo. */
    private void buildEdit() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.WORK);
        stage = new StageView(this, this);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        addStageBar(root);
        setContentView(root);
    }

    /**
     * Botonera chica sobre la mesa (a la derecha): deshacer, rehacer, probar aquí, copiar y pegar fotogramas.
     * Así esas acciones están a mano mientras se dibuja, y la barra del principal no se llena.
     */
    private void addStageBar(FrameLayout box) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setPadding(dp(4), dp(4), dp(4), dp(4));
        Ui.panel(b, Ui.glass(this));
        int s = isSolo() ? dp(34) : dp(42);
        String[][] items = {
                {"↶", "Deshacer"}, {"↷", "Rehacer"},
                {"⏯", "Probar aquí: reproduce en la mesa con sonido; toca otra vez para parar"},
                {"⧉", "Copiar el fotograma elegido (para varios: mantén presionado un fotograma en la línea de tiempo)"},
                {"📋", "Pegar fotogramas en el fotograma elegido"}};
        Runnable[] acts = {
                () -> request(op("undo")), () -> request(op("redo")), this::playHere,
                () -> copyFrames(st.layer, st.frame, 1), () -> pasteFrames(st.layer, st.frame)};
        for (int i = 0; i < items.length; i++) {
            TextView t = Ui.sym(this, items[i][0], items[i][1], acts[i]);
            t.setTextSize(isSolo() ? 16 : 20);
            b.addView(t, lpMargins(s, s, 0, i == 0 ? 0 : dp(4), 0, 0));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = dp(8);
        box.addView(b, lp);
    }

    /** Principal y modo solo: herramientas | línea de tiempo (+ mesa en solo) | propiedades. */
    private void buildMain() {
        // la orientación va con el modo: "mitad y mitad" parado; lo demás, acostado
        setRequestedOrientation(half() ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if (half()) { buildHalf(); return; }
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.WORK);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));

        row.addView(buildTools(), new LinearLayout.LayoutParams(dp(104), -1));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, 1);
        clp.setMargins(dp(8), 0, dp(8), 0);
        row.addView(center, clp);

        // encabezado: escena, conexión
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), 0, dp(6), dp(4));
        docLabel = Ui.label(this, "", 14, 0xFFFFFFFF);
        docLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        docLabel.setSingleLine(true);
        docLabel.setOnClickListener(v -> { if (st.inSymbol()) exitSymbol(); });
        head.addView(docLabel, new LinearLayout.LayoutParams(0, -2, 1));
        connLabel = Ui.label(this, isSolo() ? "Un solo teléfono" : "Buscando el extendido…", 13, 0xFFFFFFFF);
        connLabel.setOnClickListener(v -> {
            if (isView() && connected && !stageShown) { askStage(); Ui.toast(this, "Mostrando la mesa en el extendido…"); }
            else connectionDialog();
        });
        head.addView(connLabel);
        if (isView()) {
            TextView front = Ui.label(this, "⇪ Mesa", 13, 0xFFFFFFFF);
            front.setGravity(Gravity.CENTER);
            front.setPadding(dp(8), 0, dp(8), 0);
            GradientDrawable fb = new GradientDrawable();
            fb.setColor(0x33FFFFFF);
            fb.setStroke(dp(1), 0x66FFFFFF);
            fb.setCornerRadius(dp(8));
            front.setBackground(fb);
            front.setOnClickListener(v -> bringStage());
            head.addView(front, lpMargins(-2, dp(30), dp(8), 0, 0, 0));
        }
        head.addView(new Ui.AeroClose(this, this::askQuit), lpMargins(dp(34), dp(34), dp(8), 0, 0, 0));
        center.addView(head);

        // línea de tiempo
        tl = new TimelineView(this, this);
        FrameLayout tlBox = new FrameLayout(this);
        Ui.panel(tlBox, Ui.glass(this));
        tlBox.setPadding(dp(2), dp(2), dp(2), dp(2));
        tlBox.addView(tl, new FrameLayout.LayoutParams(-1, -1));
        center.addView(tlBox, new LinearLayout.LayoutParams(-1, 0, isSolo() ? 0.42f : 1));

        // reproducción (como el control de video de ChronOS), encima de las herramientas de fotograma
        player = new PlayerBar(this, this);
        center.addView(player, lpMargins(-1, dp(52), 0, dp(6), 0, 0));
        center.addView(buildControls(), lpMargins(-1, dp(46), 0, dp(6), 0, 0));

        if (isSolo()) {
            stage = new StageView(this, this);
            FrameLayout sBox = new FrameLayout(this);
            Ui.panel(sBox, Ui.glass(this));
            sBox.addView(stage, new FrameLayout.LayoutParams(-1, -1));
            addStageBar(sBox);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 0.58f);
            slp.setMargins(0, dp(6), 0, 0);
            center.addView(sBox, slp);
        }

        row.addView(buildProps(), new LinearLayout.LayoutParams(dp(206), -1));

        buildPreview();
        setContentView(root);
    }

    /**
     * Mitad y mitad (un teléfono parado): la mitad de arriba es el principal en angosto (herramientas en una tira,
     * pestañas Línea de tiempo / Propiedades, reproducción y herramientas de fotograma) y la de abajo, la mesa.
     */
    private void buildHalf() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.WORK);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        col.addView(top, new LinearLayout.LayoutParams(-1, 0, 1));

        // encabezado: escena (tocar = volver de un símbolo), pestañas y cerrar
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), 0, dp(4), dp(4));
        docLabel = Ui.label(this, "", 13, 0xFFFFFFFF);
        docLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        docLabel.setSingleLine(true);
        docLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        docLabel.setOnClickListener(v -> { if (st.inSymbol()) exitSymbol(); });
        head.addView(docLabel, new LinearLayout.LayoutParams(0, -2, 1));
        final TextView tabT = tab("Línea de tiempo"), tabP = tab("Propiedades");
        head.addView(tabT, lpMargins(-2, dp(30), dp(4), 0, 0, 0));
        head.addView(tabP, lpMargins(-2, dp(30), dp(4), 0, 0, 0));
        head.addView(new Ui.AeroClose(this, this::askQuit), lpMargins(dp(34), dp(34), dp(8), 0, 0, 0));
        top.addView(head);

        // herramientas en una tira que se desliza
        android.widget.HorizontalScrollView ts = new android.widget.HorizontalScrollView(this);
        ts.setHorizontalScrollBarEnabled(false);
        Ui.panel(ts, Ui.orange(this));
        LinearLayout strip = new LinearLayout(this);
        strip.setPadding(dp(4), dp(2), dp(4), dp(2));
        ts.addView(strip);
        toolBtns = new Ui.ToolButton[State.TOOLS];
        for (int i = 0; i < State.TOOLS; i++) {
            final int tool = i;
            Ui.ToolButton tb = new Ui.ToolButton(this, i);
            tb.setOnClickListener(v -> setTool(tool));
            if (i == State.BUCKET) tb.setOnLongClickListener(v -> { setTool(State.BUCKET); showProps(true); return true; });
            toolBtns[i] = tb;
            strip.addView(tb, new LinearLayout.LayoutParams(dp(46), dp(44)));
        }
        top.addView(ts, new LinearLayout.LayoutParams(-1, dp(50)));

        // contenido: línea de tiempo o propiedades
        FrameLayout content = new FrameLayout(this);
        tl = new TimelineView(this, this);
        tlBox = new FrameLayout(this);
        Ui.panel(tlBox, Ui.glass(this));
        tlBox.setPadding(dp(2), dp(2), dp(2), dp(2));
        tlBox.addView(tl, new FrameLayout.LayoutParams(-1, -1));
        content.addView(tlBox, new FrameLayout.LayoutParams(-1, -1));
        propsView = buildProps();
        content.addView(propsView, new FrameLayout.LayoutParams(-1, -1));
        top.addView(content, lpMargins(-1, 0, 0, dp(6), 0, 0));
        ((LinearLayout.LayoutParams) content.getLayoutParams()).weight = 1;
        tabT.setOnClickListener(v -> showProps(false));
        tabP.setOnClickListener(v -> showProps(true));
        tabs = new TextView[]{tabT, tabP};

        player = new PlayerBar(this, this);
        top.addView(player, lpMargins(-1, dp(52), 0, dp(6), 0, 0));
        android.widget.HorizontalScrollView cs = new android.widget.HorizontalScrollView(this);
        cs.setHorizontalScrollBarEnabled(false);
        cs.addView(buildControls());
        top.addView(cs, lpMargins(-1, dp(46), 0, dp(6), 0, 0));

        // mitad de abajo: la mesa
        stage = new StageView(this, this);
        FrameLayout sBox = new FrameLayout(this);
        Ui.panel(sBox, Ui.glass(this));
        sBox.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        addStageBar(sBox);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1);
        slp.setMargins(0, dp(6), 0, 0);
        col.addView(sBox, slp);

        showProps(false);
        buildPreview();
        setContentView(root);
    }

    private FrameLayout tlBox;
    private View propsView;
    private TextView[] tabs;

    private TextView tab(String name) {
        TextView t = Ui.label(this, name, 12, Ui.INK);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        return t;
    }

    /** Mitad y mitad: muestra las propiedades (colores, grosor, opciones) o la línea de tiempo. */
    private void showProps(boolean on) {
        if (tabs == null) return;
        propsView.setVisibility(on ? View.VISIBLE : View.GONE);
        tlBox.setVisibility(on ? View.GONE : View.VISIBLE);
        for (int i = 0; i < 2; i++) {
            boolean sel = (i == 1) == on;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(15));
            g.setColor(sel ? 0xFFFFFFFF : 0x33FFFFFF);
            tabs[i].setBackground(g);
            tabs[i].setTextColor(sel ? Ui.INK : 0xFFFFFFFF);
        }
    }

    /**
     * Notch y cámara: el contenido se corre para no quedar debajo; ese espacio queda vacío (solo el fondo).
     * En el modo de un solo teléfono (parado) siempre se deja al menos una franja arriba, porque algunos
     * teléfonos no informan la cámara perforada.
     */
    private void keepClearOfCutout(final View v) {
        if (v == null) return;
        final int bl = v.getPaddingLeft(), bt = v.getPaddingTop(), br = v.getPaddingRight(), bb = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener((view, in) -> {
            int l = 0, t = 0, r = 0, b = 0;
            android.view.DisplayCutout dc = in.getDisplayCutout();
            if (dc != null) { l = dc.getSafeInsetLeft(); t = dc.getSafeInsetTop(); r = dc.getSafeInsetRight(); b = dc.getSafeInsetBottom(); }
            if (half()) t = Math.max(t, dp(28));
            view.setPadding(bl + l, bt + t, br + r, bb + b);
            return in;
        });
        v.requestApplyInsets();
    }

    private static LinearLayout.LayoutParams lpMargins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private View buildTools() {
        ScrollView sv = new ScrollView(this);
        Ui.panel(sv, Ui.orange(this));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(4), dp(6), dp(4), dp(6));
        sv.addView(col);
        toolBtns = new Ui.ToolButton[State.TOOLS];
        LinearLayout line = null;
        for (int i = 0; i < State.TOOLS; i++) {
            if (i % 2 == 0) {
                line = new LinearLayout(this);
                col.addView(line, new LinearLayout.LayoutParams(-1, dp(46)));
            }
            final int tool = i;
            Ui.ToolButton tb = new Ui.ToolButton(this, i);
            tb.setOnClickListener(v -> setTool(tool));
            if (i == State.BUCKET) tb.setOnLongClickListener(v -> { setTool(State.BUCKET); Ui.toast(this, "Opciones del cubo en el panel de la derecha"); return true; });
            toolBtns[i] = tb;
            line.addView(tb, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return sv;
    }

    /** Cubo de pintura: cerrar huecos (como en Flash). */
    // ---- opciones del cubo (como el balde de DUAL-Draw): tolerancia, huecos y qué capas mira
    private LinearLayout bucketBox;
    private TextView tolL, gapL, allL;

    private View buildBucketBox() {
        bucketBox = new LinearLayout(this);
        bucketBox.setOrientation(LinearLayout.VERTICAL);
        bucketBox.setPadding(0, dp(8), 0, 0);
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        tolL = Ui.label(this, "", 12, 0xFF555555);
        r1.addView(tolL, new LinearLayout.LayoutParams(0, -2, 1));
        addBtn(r1, "−", "Menos tolerancia: solo el mismo color exacto", () -> setFillOpts(st.tol - 8, st.gap, st.fillAll));
        addBtn(r1, "+", "Más tolerancia: acepta colores parecidos", () -> setFillOpts(st.tol + 8, st.gap, st.fillAll));
        bucketBox.addView(r1);
        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        r2.setPadding(0, dp(4), 0, 0);
        gapL = Ui.label(this, "", 12, 0xFF555555);
        r2.addView(gapL, new LinearLayout.LayoutParams(0, -2, 1));
        addBtn(r2, "−", "Cerrar huecos más chicos", () -> setFillOpts(st.tol, st.gap - 1, st.fillAll));
        addBtn(r2, "+", "Cerrar huecos más grandes entre las líneas", () -> setFillOpts(st.tol, st.gap + 1, st.fillAll));
        bucketBox.addView(r2);
        allL = Ui.sym(this, "", "Si el cubo mira solo esta capa o todo lo que se ve", () -> setFillOpts(st.tol, st.gap, !st.fillAll));
        allL.setTextSize(12);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, dp(36));
        alp.topMargin = dp(4);
        bucketBox.addView(allL, alp);
        return bucketBox;
    }

    // ---- modos del pincel y del borrador (como en Flash)
    private LinearLayout brushBox, eraseBox;
    private TextView[] brushBtns, eraseBtns;

    private LinearLayout modeBox(String title, String[] names, final boolean brush) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);
        box.addView(Ui.label(this, title, 12, 0xFF555555));
        TextView[] btns = new TextView[names.length];
        LinearLayout row = null;
        for (int i = 0; i < names.length; i++) {
            if (i % 2 == 0) { row = new LinearLayout(this); box.addView(row); }
            final int mode = i;
            TextView t = Ui.sym(this, names[i], names[i], () -> {
                if (brush) st.brushMode = mode; else st.eraseMode = mode;
                prefs.edit().putInt(brush ? "brushMode" : "eraseMode", mode).apply();
                stateChanged(null);
            });
            t.setTextSize(11);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
            lp.setMargins(dp(1), dp(3), dp(1), 0);
            row.addView(t, lp);
            btns[i] = t;
        }
        if (brush) brushBtns = btns; else eraseBtns = btns;
        return box;
    }

    private void setFillOpts(int tol, int gap, boolean all) {
        st.tol = Math.max(0, Math.min(120, tol));
        st.gap = Math.max(0, Math.min(8, gap));
        st.fillAll = all;
        prefs.edit().putInt("fillTol", st.tol).putInt("fillGap", st.gap).putBoolean("fillAll", all).apply();
        stateChanged(null);
    }

    private View buildControls() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(6), dp(4), dp(6), dp(4));
        Ui.panel(c, Ui.glass(this));
        // con sus teclas de Flash, para reconocerlos: F6 clave, F7 clave vacío, F5 fotograma, Mayús+F5 quitar
        keyBtn = wide(keyButton(c, false));
        blankBtn = wide(keyButton(c, true));
        wide(addBtn(c, "F5 ＋", "Insertar fotograma (F5): alarga el tramo; más allá del final, extiende la capa hasta ahí",
                () -> frameOp("frame", false)));
        wide(addBtn(c, "F5 －", "Quitar fotograma (Mayús+F5)", () -> frameOp("delFrame", false)));
        addBtn(c, "⏯", "Probar aquí (Enter): reproduce en la mesa con sonido; toca otra vez para parar", this::playHere);
        addBtn(c, "↝", "Crear / quitar interpolación de movimiento", this::toggleTween);
        addBtn(c, "✂", "Cortar el sonido en el cabezal", this::cutClip);
        addBtn(c, "🖼", "Importar imagen", this::addImage);
        // copiar y pegar fotogramas van en la mesa y en el menú del fotograma (mantener presionado)
        if (!half()) { // acostado: todos a la vista, repartiendo el ancho (los anchos valen 1,5)
            for (int i = 0; i < c.getChildCount(); i++) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getChildAt(i).getLayoutParams();
                boolean w = lp.width >= dp(60);
                lp.weight = w ? 1.5f : 1f;
                if (w) ((TextView) c.getChildAt(i)).setTextSize(13);
                lp.width = 0;
            }
        }
        return c;
    }

    /** Botón ancho con texto chico (para "F5 ＋" y parecidos). */
    private TextView wide(TextView t) {
        t.setTextSize(14);
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
        lp.width = dp(64);
        t.setLayoutParams(lp);
        return t;
    }

    // ================================================================ PlayerBar.Host
    private PlayerBar player;

    @Override public void play() {
        if (playing && !previewOpen()) { stopHere(); return; }
        openPreview();
    }

    private boolean previewOpen() { return previewLayer != null && previewLayer.getVisibility() == View.VISIBLE; }

    /**
     * Probar aquí (Enter en Flash): reproduce en la mesa y en la línea de tiempo, con sonido, sin abrir la vista
     * previa. Otra vez (o tocar la línea de tiempo) lo detiene y el cabezal queda donde iba.
     */
    @Override public void playHere() {
        if (isEdit()) { send("moCmd", "c", "playHere"); return; }
        if (playing) { if (previewOpen()) pausePreview(); else stopHere(); return; }
        int at = st.inSymbol() ? st.pf : st.frame;
        int from = at >= doc.length() - 1 ? 0 : at;
        startPlay(from);
        sendPlay(true, from);
        refreshAll();
    }

    private void stopHere() {
        stopPlay(true);
        sendPlay(false, st.frame);
        stateChanged(null);
    }
    @Override public boolean loop() { return loop; }
    @Override public void toggleLoop() { loop = !loop; refreshAll(); refreshPreviewBar(); }
    @Override public void toggleOnion() { st.onion = !st.onion; stateChanged(null); }

    @Override public void fpsMenu() {
        final int[] opts = {6, 8, 10, 12, 15, 24, 25, 30, 48, 60};
        String[] items = new String[opts.length];
        for (int i = 0; i < opts.length; i++)
            items[i] = opts[i] + " fotogramas por segundo" + (opts[i] == doc.fps ? "   ✓" : "")
                    + (opts[i] == 12 ? "  (dibujo animado clásico)" : opts[i] == 24 ? "  (cine)" : "");
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Velocidad")
                .setItems(items, (d, w) -> setFps(opts[w]))
                .show();
    }

    private TextView keyBtn, blankBtn;

    /**
     * Botón de fotograma clave (● lleno) o clave vacío (○), con dos variantes como las herramientas agrupadas:
     * en este fotograma, o "→" en el fotograma siguiente (y el cabezal avanza ahí). Mantener presionado elige.
     */
    private TextView keyButton(LinearLayout c, final boolean blank) {
        final String pref = blank ? "blankNext" : "keyNext";
        final TextView t = addBtn(c, "", "", null);
        t.setOnClickListener(v -> {
            boolean next = prefs.getBoolean(pref, false);
            int f = next ? st.frame + 1 : st.frame;
            JSONObject o = op("key", "l", st.layer, "f", f);
            if (blank) try { o.put("blank", 1); } catch (JSONException ignored) { }
            request(o);
            if (next) select(st.layer, f);
        });
        t.setOnLongClickListener(v -> {
            String a = blank ? "○" : "●", name = blank ? "clave vacío" : "fotograma clave";
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle(blank ? "Fotograma clave vacío" : "Fotograma clave")
                    .setItems(new String[]{a + "   Insertar " + name + " aquí", a + "→  Insertar " + name + " en el fotograma siguiente"},
                            (d, w) -> { prefs.edit().putBoolean(pref, w == 1).apply(); refreshKeyButtons(); })
                    .show();
            return true;
        });
        return t;
    }

    private void refreshKeyButtons() {
        if (keyBtn == null) return;
        boolean k = prefs.getBoolean("keyNext", false), b = prefs.getBoolean("blankNext", false);
        keyBtn.setText(k ? "●→ F6" : "● F6");
        keyBtn.setTextSize(14);
        keyBtn.setContentDescription(k ? "Insertar fotograma clave en el siguiente" : "Insertar fotograma clave");
        blankBtn.setText(b ? "○→ F7" : "○ F7");
        blankBtn.setTextSize(14);
        blankBtn.setContentDescription(b ? "Insertar clave vacío en el siguiente" : "Insertar fotograma clave vacío");
    }

    private TextView addBtn(LinearLayout parent, String sym, String name, Runnable r) {
        TextView t = Ui.sym(this, sym, name, r);
        parent.addView(t, lpMargins(dp(38), dp(36), dp(2), 0, dp(2), 0));
        return t;
    }

    private void gap(LinearLayout parent) { parent.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1)); }

    private View buildProps() {
        ScrollView sv = new ScrollView(this);
        Ui.panel(sv, Ui.glass(this));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(10), dp(10), dp(10));
        sv.addView(col);

        mini = new View(this) {
            final Paint stageP = new Paint();
            final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            final RectF box = new RectF();
            @Override protected void onDraw(Canvas c) {
                stageP.setColor(0xFFFFFFFF);
                bg.setColor(0xFFB0B0B0);
                box.set(0, 0, getWidth(), getHeight());
                c.drawRoundRect(box, dp(8), dp(8), bg);
                box.inset(dp(4), dp(4));
                Render.fitted(c, doc, shownFrame(), box, stageP);
            }
        };
        mini.setOnClickListener(v -> openPreview());
        col.addView(mini, new LinearLayout.LayoutParams(-1, dp(104)));
        col.addView(buildSoundBox());

        selLabel = Ui.label(this, "", 12, 0xFF555555);
        selLabel.setPadding(0, dp(4), 0, dp(6));
        col.addView(selLabel);

        // trazo y relleno
        LinearLayout colors = new LinearLayout(this);
        colors.setGravity(Gravity.CENTER_VERTICAL);
        strokeSw = new Swatch(this, State.PENCIL);
        fillSw = new Swatch(this, State.BUCKET);
        strokeSw.setOnClickListener(v -> { if (!targetFill) pickColor(false); targetFill = false; refreshProps(); });
        fillSw.setOnClickListener(v -> { if (targetFill) pickColor(true); targetFill = true; refreshProps(); });
        strokeSw.setOnLongClickListener(v -> { Ui.toast(this, "Color del trazo (toca dos veces para elegir)"); return true; });
        fillSw.setOnLongClickListener(v -> { Ui.toast(this, "Color del relleno (toca dos veces para elegir)"); return true; });
        colors.addView(strokeSw, new LinearLayout.LayoutParams(dp(48), dp(48)));
        colors.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        colors.addView(fillSw, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView swap = Ui.sym(this, "⇄", "Intercambiar trazo y relleno", () -> {
            int a = st.stroke; st.stroke = st.fill; st.fill = a;
            refreshProps();
            stateChanged(null);
        });
        colors.addView(swap, lpMargins(dp(40), dp(40), dp(8), 0, 0, 0));
        col.addView(colors);

        palette = new Palette(this);
        col.addView(palette, lpMargins(-1, dp(84), 0, dp(8), 0, 0));
        TextView palHint = Ui.label(this, "Mantén presionado un color para editarlo · fila de abajo: Mis colores", 10, 0xFF888888);
        col.addView(palHint);
        col.addView(buildBucketBox());
        brushBox = modeBox("Modo del pincel", Brush.BRUSH_MODES, true);
        col.addView(brushBox);
        eraseBox = modeBox("Modo del borrador", Brush.ERASE_MODES, false);
        col.addView(eraseBox);

        widthLabel = Ui.label(this, "", 12, 0xFF555555);
        widthLabel.setPadding(0, dp(8), 0, 0);
        col.addView(widthLabel);
        widthBar = new SeekBar(this);
        widthBar.setMax(39);
        widthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (!user) return;
                st.width = v + 1;
                widthLabel.setText("Grosor: " + (v + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { stateChanged("w"); }
        });
        col.addView(widthBar);

        LinearLayout edit = new LinearLayout(this);
        edit.setPadding(0, dp(8), 0, 0);
        addBtn(edit, "↶", "Deshacer", () -> request(op("undo")));
        addBtn(edit, "↷", "Rehacer", () -> request(op("redo")));
        addBtn(edit, "✕", "Borrar lo seleccionado", () -> cmd("delSel"));
        addBtn(edit, "▲", "Traer al frente", () -> cmd("front"));
        col.addView(edit);
        LinearLayout edit2 = new LinearLayout(this);
        edit2.setPadding(0, dp(4), 0, 0);
        addBtn(edit2, "▼", "Enviar al fondo", () -> cmd("back"));
        addBtn(edit2, "⤢", "Ajustar la mesa a la pantalla", () -> cmd("fit"));
        addBtn(edit2, "☰", "Menú", this::menu);
        col.addView(edit2);

        // ---- símbolo gráfico
        TextView symT = Ui.label(this, "Símbolo gráfico", 12, 0xFF555555);
        symT.setPadding(0, dp(12), 0, dp(4));
        col.addView(symT);
        toSymBtn = Ui.sym(this, "◈  Convertir en símbolo", "Convierte lo seleccionado en un símbolo gráfico", () -> cmd("toSym"));
        toSymBtn.setTextSize(13);
        col.addView(toSymBtn, new LinearLayout.LayoutParams(-1, dp(40)));
        symBox = new LinearLayout(this);
        symBox.setOrientation(LinearLayout.VERTICAL);
        symName = Ui.label(this, "", 13, 0xFF5E35B1);
        symName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        symName.setPadding(0, dp(6), 0, dp(4));
        symBox.addView(symName);
        LinearLayout modes = new LinearLayout(this);
        loopBtns = new TextView[]{
                modeBtn(modes, "Repetir", "La animación del símbolo se repite en ciclo", 0),
                modeBtn(modes, "Una vez", "Se reproduce una vez y queda en el último fotograma", 1),
                modeBtn(modes, "1 foto", "Muestra un solo fotograma", 2)};
        symBox.addView(modes);
        TextView editSym = Ui.sym(this, "✎  Editar el símbolo (o toca dos veces)", "Animar por dentro el símbolo elegido", () -> cmd("editSym"));
        editSym.setTextSize(12);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, dp(36));
        elp.topMargin = dp(4);
        symBox.addView(editSym, elp);
        TextView breakSym = Ui.sym(this, "⤫  Separar (deja de ser símbolo)", "Vuelve a ser dibujo normal, como Modificar › Separar", () -> cmd("breakSym"));
        breakSym.setTextSize(12);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(36));
        blp.topMargin = dp(4);
        symBox.addView(breakSym, blp);
        col.addView(symBox);

        buildFxBox(col);

        // ---- interpolación del clave actual
        TextView secT = Ui.label(this, "Interpolación", 12, 0xFF555555);
        secT.setPadding(0, dp(12), 0, dp(4));
        col.addView(secT);
        tweenBtn = Ui.sym(this, "", "Crear o quitar la interpolación de movimiento", this::toggleTween);
        tweenBtn.setTextSize(13);
        col.addView(tweenBtn, new LinearLayout.LayoutParams(-1, dp(40)));
        TextView easeT = Ui.label(this, "Aceleración (arrastra los puntos naranjos)", 11, 0xFF777777);
        easeT.setPadding(0, dp(8), 0, dp(2));
        col.addView(easeT);
        curve = new CurveView(this);
        col.addView(curve, new LinearLayout.LayoutParams(-1, dp(130)));
        LinearLayout pre = new LinearLayout(this);
        pre.setPadding(0, dp(4), 0, 0);
        preset(pre, "Lineal", "Velocidad pareja", MDoc.LINEAR);
        preset(pre, "Entrada", "Parte lento y acelera", new float[]{0.42f, 0, 1, 1});
        preset(pre, "Salida", "Parte rápido y frena", new float[]{0, 0, 0.58f, 1});
        preset(pre, "Suave", "Acelera y frena", new float[]{0.42f, 0, 0.58f, 1});
        col.addView(pre);
        LinearLayout spinRow = new LinearLayout(this);
        spinRow.setGravity(Gravity.CENTER_VERTICAL);
        spinRow.setPadding(0, dp(8), 0, 0);
        spinRow.addView(Ui.label(this, "Giros", 12, 0xFF555555), new LinearLayout.LayoutParams(0, -2, 1));
        addBtn(spinRow, "↺", "Un giro más hacia la izquierda", () -> spin(-1));
        spinLabel = Ui.label(this, "0", 13, Ui.INK);
        spinLabel.setGravity(Gravity.CENTER);
        spinRow.addView(spinLabel, new LinearLayout.LayoutParams(dp(40), -2));
        addBtn(spinRow, "↻", "Un giro más hacia la derecha", () -> spin(1));
        col.addView(spinRow);
        alphaLabel = Ui.label(this, "", 12, 0xFF555555);
        alphaLabel.setPadding(0, dp(8), 0, 0);
        col.addView(alphaLabel);
        alphaBar = new SeekBar(this);
        alphaBar.setMax(100);
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { if (user) alphaLabel.setText("Alfa: " + v + " %"); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                request(op("xf", "l", st.layer, "f", st.frame, "a", s.getProgress() / 100.0));
            }
        });
        col.addView(alphaBar);

        return sv;
    }

    // ================================================================ efectos: resplandor exterior y sombra
    /** Efectos de lo elegido ({glow:{c,s,i}, shadow:{c,s,d,a}}), o null si no hay nada elegido. */
    private JSONObject selFx;
    private LinearLayout fxBox;
    private TextView fxHint, glowBtn, shadBtn, glowSw, shadSw, glowSL, glowIL, shadSL, shadDL, shadAL;

    private void buildFxBox(LinearLayout col) {
        TextView t = Ui.label(this, "Efectos", 12, 0xFF555555);
        t.setPadding(0, dp(12), 0, dp(4));
        col.addView(t);
        fxHint = Ui.label(this, "Elige algo con la flecha para ponerle resplandor o sombra", 11, 0xFF777777);
        col.addView(fxHint);
        fxBox = new LinearLayout(this);
        fxBox.setOrientation(LinearLayout.VERTICAL);
        // resplandor exterior
        LinearLayout g = new LinearLayout(this);
        g.setGravity(Gravity.CENTER_VERTICAL);
        glowBtn = Ui.sym(this, "", "Resplandor exterior (como en Photoshop): un brillo difuso alrededor", () -> toggleFx(true));
        glowBtn.setTextSize(12);
        g.addView(glowBtn, new LinearLayout.LayoutParams(0, dp(36), 1));
        glowSw = swatch(() -> fxColor(true));
        g.addView(glowSw, lpMargins(dp(36), dp(36), dp(6), 0, 0, 0));
        fxBox.addView(g);
        glowSL = fxRow(fxBox, "Más chico", "Más grande", () -> fxStep("glow", "s", -2), () -> fxStep("glow", "s", 2));
        glowIL = fxRow(fxBox, "Más suave", "Más fuerte", () -> fxStep("glow", "i", -0.25), () -> fxStep("glow", "i", 0.25));
        // sombra paralela
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, dp(8), 0, 0);
        shadBtn = Ui.sym(this, "", "Sombra paralela (como en Photoshop): una sombra corrida y difusa", () -> toggleFx(false));
        shadBtn.setTextSize(12);
        h.addView(shadBtn, new LinearLayout.LayoutParams(0, dp(36), 1));
        shadSw = swatch(() -> fxColor(false));
        h.addView(shadSw, lpMargins(dp(36), dp(36), dp(6), 0, 0, 0));
        fxBox.addView(h);
        shadSL = fxRow(fxBox, "Más nítida", "Más difusa", () -> fxStep("shadow", "s", -2), () -> fxStep("shadow", "s", 2));
        shadDL = fxRow(fxBox, "Más cerca", "Más lejos", () -> fxStep("shadow", "d", -2), () -> fxStep("shadow", "d", 2));
        shadAL = fxRow(fxBox, "Girar la luz a la izquierda", "Girar la luz a la derecha",
                () -> fxStep("shadow", "a", -15), () -> fxStep("shadow", "a", 15));
        TextView tip = Ui.label(this, "El color también lleva la opacidad. Para un brillo parejo en un dibujo de varias "
                + "partes, conviértelo en símbolo y ponle el efecto al símbolo. Se interpolan.", 11, 0xFF777777);
        tip.setPadding(0, dp(4), 0, 0);
        fxBox.addView(tip);
        col.addView(fxBox);
    }

    private TextView swatch(Runnable r) {
        TextView v = new TextView(this);
        v.setOnClickListener(x -> r.run());
        return v;
    }

    private TextView fxRow(LinearLayout box, String minus, String plus, Runnable dec, Runnable inc) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, 0);
        TextView l = Ui.label(this, "", 12, 0xFF555555);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        addBtn(r, "−", minus, dec);
        addBtn(r, "+", plus, inc);
        box.addView(r);
        return l;
    }

    private JSONObject fxPart(boolean glow) {
        if (selFx == null) selFx = new JSONObject();
        JSONObject p = selFx.optJSONObject(glow ? "glow" : "shadow");
        if (p == null) { p = new JSONObject(); try { selFx.put(glow ? "glow" : "shadow", p); } catch (JSONException ignored) { } }
        return p;
    }

    /** Prender o apagar: al prender usa el último color (resplandor amarillo claro y sombra negra al 75 %, como Photoshop). */
    private void toggleFx(boolean glow) {
        if (selN == 0) { Ui.toast(this, "Elige algo con la flecha"); return; }
        JSONObject p = fxPart(glow);
        try {
            if (p.optInt("c") != 0) p.put("c", 0);
            else p.put("c", prefs.getInt(glow ? "fxGlowC" : "fxShadC", glow ? 0xBFFFFF8C : 0xBF000000));
        } catch (JSONException ignored) { }
        applyFx();
    }

    private void fxColor(final boolean glow) {
        if (selN == 0) { Ui.toast(this, "Elige algo con la flecha"); return; }
        int cur = fxPart(glow).optInt("c");
        if (cur == 0) cur = prefs.getInt(glow ? "fxGlowC" : "fxShadC", glow ? 0xBFFFFF8C : 0xBF000000);
        openMixer(cur, glow ? "Color del resplandor" : "Color de la sombra", c -> {
            if ((c >>> 24) == 0) c |= 0xBF000000;
            prefs.edit().putInt(glow ? "fxGlowC" : "fxShadC", c).apply();
            try { fxPart(glow).put("c", c); } catch (JSONException ignored) { }
            applyFx();
        }, null);
    }

    private void fxStep(String part, String key, double d) {
        if (selN == 0) { Ui.toast(this, "Elige algo con la flecha"); return; }
        JSONObject p = fxPart("glow".equals(part));
        double def = "glow".equals(part) ? ("s".equals(key) ? 10 : 1) : ("s".equals(key) ? 6 : "d".equals(key) ? 8 : 120);
        try { p.put(key, p.optDouble(key, def) + d); } catch (JSONException ignored) { }
        applyFx();
    }

    private void applyFx() {
        JSONObject fx;
        try { fx = new JSONObject(selFx.toString()); } catch (Exception e) { return; }
        if (stage != null) stage.setFx(fx);
        else if (!connected) { Ui.toast(this, "Sin conexión con el extendido"); return; }
        else {
            try {
                JSONObject m = new JSONObject();
                m.put("t", "moCmd"); m.put("c", "fx"); m.put("fx", fx);
                sendRaw(m);
            } catch (JSONException ignored) { }
        }
        refreshFx();
    }

    private void refreshFx() {
        if (fxBox == null) return;
        boolean on = selN > 0;
        fxBox.setVisibility(on ? View.VISIBLE : View.GONE);
        fxHint.setVisibility(on ? View.GONE : View.VISIBLE);
        if (!on) return;
        JSONObject g = fxPart(true), h = fxPart(false);
        int gc = g.optInt("c"), hc = h.optInt("c");
        glowBtn.setText(gc != 0 ? "☀  Resplandor exterior: sí" : "☀  Resplandor exterior: no");
        glowBtn.setTextColor(gc != 0 ? Ui.ORANGE : Ui.INK);
        shadBtn.setText(hc != 0 ? "▰  Sombra paralela: sí" : "▰  Sombra paralela: no");
        shadBtn.setTextColor(hc != 0 ? Ui.ORANGE : Ui.INK);
        paintSwatch(glowSw, gc != 0 ? gc : prefs.getInt("fxGlowC", 0xBFFFFF8C));
        paintSwatch(shadSw, hc != 0 ? hc : prefs.getInt("fxShadC", 0xBF000000));
        glowSL.setText("Tamaño " + Math.round(g.optDouble("s", 10)));
        glowIL.setText("Intensidad " + Math.round(g.optDouble("i", 1) * 100) + " %");
        shadSL.setText("Desenfoque " + Math.round(h.optDouble("s", 6)));
        shadDL.setText("Distancia " + Math.round(h.optDouble("d", 8)));
        shadAL.setText("Ángulo de la luz " + Math.round(h.optDouble("a", 120)) + "°");
        float a = (gc != 0 ? 1f : 0.45f);
        glowSL.setAlpha(a); glowIL.setAlpha(a);
        float b = (hc != 0 ? 1f : 0.45f);
        shadSL.setAlpha(b); shadDL.setAlpha(b); shadAL.setAlpha(b);
    }

    private void paintSwatch(TextView v, int c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(c);
        d.setCornerRadius(dp(8));
        d.setStroke(dp(1), 0x55000000);
        v.setBackground(d);
    }

    private TextView toSymBtn, symName;
    private TextView[] loopBtns;
    private LinearLayout symBox;

    private TextView modeBtn(LinearLayout row, String name, String hint, final int mode) {
        TextView t = Ui.sym(this, name, hint, () -> cmd("loop" + mode));
        t.setTextSize(11);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1);
        lp.setMargins(dp(1), 0, dp(1), 0);
        row.addView(t, lp);
        return t;
    }

    private void preset(LinearLayout row, String name, String hint, final float[] c) {
        TextView t = Ui.sym(this, name, hint, () -> setEase(c));
        t.setTextSize(11);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1);
        lp.setMargins(dp(1), 0, dp(1), 0);
        row.addView(t, lp);
    }

    /** Clave vigente en el fotograma actual de la capa actual (null si la capa ya terminó). */
    private java.util.Map.Entry<Integer, MDoc.Key> curKeyEntry() {
        MDoc.Layer L = lay(st.layer);
        if (L == null || st.frame >= L.len) return null;
        return L.keys.floorEntry(st.frame);
    }

    private void toggleTween() {
        java.util.Map.Entry<Integer, MDoc.Key> e = curKeyEntry();
        if (e == null) { Ui.toast(this, "Elige un fotograma con dibujo"); return; }
        if (!e.getValue().tween && e.getValue().shapes.isEmpty()) { Ui.toast(this, "Este fotograma clave está vacío"); return; }
        if (e.getValue().tween) request(op("tween", "l", st.layer, "f", st.frame, "on", false));
        else createTween(st.layer, st.frame);
    }

    /**
     * Interpolación de movimiento como en Flash 8: va desde el clave del tramo hasta el SIGUIENTE fotograma clave,
     * que es la referencia (lo que se anima hacia él). Si no hay clave siguiente, se crea uno al final del tramo
     * (o un segundo después) y el cabezal se lleva ahí para mover o transformar el objeto.
     */
    private void createTween(int layer, int frame) {
        MDoc.Layer L = lay(layer);
        java.util.Map.Entry<Integer, MDoc.Key> e = L == null || frame >= L.len ? null : L.keys.floorEntry(frame);
        if (e == null) { Ui.toast(this, "Elige un fotograma con dibujo"); return; }
        Integer nk = L.keys.higherKey(e.getKey());
        int end;
        boolean made = nk == null;
        if (!made) end = nk;
        else {
            end = L.len - 1;
            if (end <= e.getKey()) end = e.getKey() + Math.max(1, doc.fps) - 1;
        }
        request(op("tween", "l", layer, "f", frame, "on", true));
        select(layer, end);
        Ui.toast(this, (made ? "Se creó el fotograma clave final en el " : "La interpolación llega al fotograma clave ")
                + (end + 1) + ": mueve o transforma ahí el objeto");
    }

    private void setEase(float[] c) {
        java.util.Map.Entry<Integer, MDoc.Key> e = curKeyEntry();
        if (e == null || !e.getValue().tween) { Ui.toast(this, "Primero crea la interpolación de movimiento"); return; }
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            for (float v : c) a.put((double) v);
            request(op("ease", "l", st.layer, "f", st.frame, "c", a));
        } catch (Exception ignored) { }
    }

    private void spin(int d) {
        java.util.Map.Entry<Integer, MDoc.Key> e = curKeyEntry();
        if (e == null || !e.getValue().tween) { Ui.toast(this, "Primero crea la interpolación de movimiento"); return; }
        request(op("spin", "l", st.layer, "f", st.frame, "n", e.getValue().spin + d));
    }

    // ================================================================ vista previa a pantalla completa (principal)
    private void buildPreview() {
        previewLayer = new FrameLayout(this);
        previewLayer.setBackgroundColor(0xFF000000);
        previewLayer.setVisibility(View.GONE);
        previewView = new View(this) {
            final Paint stageP = new Paint();
            final RectF box = new RectF();
            @Override protected void onDraw(Canvas c) {
                stageP.setColor(0xFFFFFFFF);
                box.set(0, 0, getWidth(), getHeight());
                Render.fitted(c, doc, shownFrame(), box, stageP);
            }
        };
        previewView.setOnClickListener(v ->
                previewBar.setVisibility(previewBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        previewLayer.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        Ui.panel(bar, Ui.glass(this));
        addBtn(bar, "✕", "Cerrar la vista previa", this::closePreview);
        previewPlay = addBtn(bar, "❚❚", "Reproducir / pausar", () -> {
            if (playing) pausePreview(); else resumePreview();
        });
        previewLoop = addBtn(bar, "⟲", "Repetir", () -> { loop = !loop; refreshPreviewBar(); });
        previewInfo = Ui.label(this, "", 13, Ui.INK);
        previewInfo.setPadding(dp(10), 0, dp(6), 0);
        bar.addView(previewInfo);
        previewBar = bar;
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, dp(52), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = dp(14);
        previewLayer.addView(bar, blp);
        root.addView(previewLayer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void openPreview() {
        previewLayer.setVisibility(View.VISIBLE);
        previewBar.setVisibility(View.VISIBLE);
        int at = st.inSymbol() ? st.pf : st.frame;
        int from = at >= doc.length() - 1 ? 0 : at;
        startPlay(from);
        sendPlay(true, from);
        refreshPreviewBar();
    }

    private void pausePreview() {
        stopPlay(true);
        sendPlay(false, st.frame);
        stateChanged(null);
        refreshPreviewBar();
    }

    private void resumePreview() {
        int at = st.inSymbol() ? st.pf : st.frame;
        int from = at >= doc.length() - 1 ? 0 : at;
        startPlay(from);
        sendPlay(true, from);
        refreshPreviewBar();
    }

    private void closePreview() {
        if (playing) pausePreview();
        previewLayer.setVisibility(View.GONE);
        hideBars();
        refreshAll();
    }

    private void refreshPreviewBar() {
        if (previewPlay == null) return;
        previewPlay.setText(playing ? "❚❚" : "▶");
        previewLoop.setTextColor(loop ? Ui.GREEN : 0xFF9E9E9E);
        int f = shownFrame();
        previewInfo.setText(String.format(Locale.US, "%d / %d  ·  %d fps", f + 1, doc.length(), doc.fps));
    }

    // ================================================================ reproducción
    private boolean playing, loop = true;
    private int pf0, pFrame;
    private long pt0, lastTick;

    private final Sounds.Player audio = new Sounds.Player();
    private final Sounds.Scrub scrubber = new Sounds.Scrub();

    /** Al mover la aguja suena ese fotograma (lo que dura uno, entre 60 y 150 ms). */
    private void scrub(int f) {
        if (isEdit() || st.inSymbol() || !doc.hasAudio() || !prefs.getBoolean("scrub", true)) return;
        int dur = Math.max(60, Math.min(150, 1000 / Math.max(1, doc.fps)));
        scrubber.play(Sounds.plan(this, doc, false), (long) f * 1000 / doc.fps, dur);
    }

    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override public void doFrame(long now) {
            if (!playing) return;
            int len = doc.length();
            long el = Math.max(0, now - pt0);
            int f = pf0 + (int) (el * doc.fps / 1_000_000_000L);
            long ams = audio.positionMs();
            if (ams >= 0) { // con sonido, la imagen sigue a lo que ya sonó
                int af = (int) (ams * doc.fps / 1000);
                f = loop ? af : Math.max(af, pf0);
            }
            if (f >= len) {
                if (loop) f %= len;
                else { pFrame = len - 1; stopPlay(true); if (isView()) { sendPlay(false, st.frame); stateChanged(null); } refreshAll(); refreshPreviewBar(); return; }
            }
            if (f != pFrame) { pFrame = f; onPlayFrame(); }
            if (isView() && System.currentTimeMillis() - lastTick > 1000) {
                lastTick = System.currentTimeMillis();
                send("moTick", "f", pFrame);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void startPlay(int from) {
        stopPlay(false);
        playing = true;
        pf0 = pFrame = Math.max(0, Math.min(from, doc.length() - 1));
        pt0 = System.nanoTime();
        lastTick = System.currentTimeMillis();
        if (stage != null) stage.toolChanged();
        // el sonido suena en el principal (o en el modo solo), no en la mesa
        if (!isEdit() && doc.hasAudio()) {
            Sounds.Plan plan = Sounds.plan(this, doc, false);
            audio.start(plan, (long) pf0 * 1000 / doc.fps, loop ? doc.durationMs() : 0);
        }
        Choreographer.getInstance().postFrameCallback(tick);
        onPlayFrame();
    }

    /** Detiene; keep = el cabezal queda donde se detuvo. */
    private void stopPlay(boolean keep) {
        if (!playing) return;
        playing = false;
        audio.stop();
        Choreographer.getInstance().removeFrameCallback(tick);
        if (keep) { if (st.inSymbol()) st.pf = pFrame; else st.frame = pFrame; }
        refreshAll();
    }

    private void onPlayFrame() {
        if (previewView != null && previewLayer.getVisibility() == View.VISIBLE) { previewView.invalidate(); refreshPreviewBar(); }
        if (tl != null) { tl.ensureVisible(pFrame); tl.invalidate(); }
        if (mini != null) mini.invalidate();
        if (stage != null) stage.invalidate();
        if (player != null) player.invalidate();
        updateInfo();
    }

    private void sendPlay(boolean on, int f) {
        JSONObject m = new JSONObject();
        try { m.put("t", "moPlay"); m.put("on", on); m.put("f", f); } catch (JSONException ignored) { }
        sendRaw(m);
    }

    // ================================================================ estado y órdenes
    private JSONObject op(String name) {
        JSONObject o = new JSONObject();
        try { o.put("op", name); } catch (JSONException ignored) { }
        return o;
    }

    private JSONObject op(String name, Object... kv) {
        JSONObject o = op(name);
        try { for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); } catch (JSONException ignored) { }
        return o;
    }

    /** Pide una orden: en solo se aplica aquí; en el principal se le pide al extendido (dueño del documento). */
    private static final java.util.Set<String> DOC_OPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "undo", "redo", "new", "load", "fps", "sndAdd", "symName"));

    private void request(JSONObject o) {
        if (st.inSymbol() && !o.has("sym") && !DOC_OPS.contains(o.optString("op")))
            try { o.put("sym", st.sym); } catch (JSONException ignored) { }
        if (isView()) {
            if (!connected) { Ui.toast(this, "Sin conexión con el extendido"); return; }
            JSONObject m = new JSONObject();
            try { m.put("t", "moReq"); m.put("op", o); } catch (JSONException ignored) { }
            sendRaw(m);
        } else {
            if (stage != null) stage.clearSel();
            submit(o);
        }
    }

    /** StageView y modo solo: aplica la orden y (en el extendido) la reenvía al principal, en orden. */
    @Override public void submit(JSONObject o) {
        if (!doc.apply(o)) return;
        if (isEdit()) {
            try {
                JSONObject m = new JSONObject(o.toString());
                m.put("t", "mo");
                sendRaw(m);
            } catch (JSONException ignored) { }
        }
        journal(o);
        docChanged();
    }

    private void docChanged() {
        markFileDirty();
        if (!doc.sounds.isEmpty()) { Sounds.need(this, doc); Sounds.warm(this, doc); }
        if (!doc.images.isEmpty()) Images.need(this, doc);
        clampState();
        refreshAll();
    }

    private void clampState() {
        if (st.inSymbol() && doc.symbol(st.sym) == null) { st.sym = ""; st.layer = st.pl; st.frame = st.pf; } // el símbolo ya no existe
        st.layer = Math.max(0, Math.min(st.layer, T().size() - 1));
        st.frame = Math.max(0, st.frame);
    }

    /** El principal (o el modo solo) cambió herramienta, color, capa, fotograma…; ap = qué aplicar a la selección. */
    private void stateChanged(String ap) {
        refreshAll();
        if (isSolo()) {
            if (ap != null && stage != null) stage.styleSelection(ap);
            return;
        }
        if (isView()) {
            JSONObject m = st.toJson();
            try { m.put("t", "moState"); if (ap != null) m.put("ap", ap); } catch (JSONException ignored) { }
            sendRaw(m);
        }
    }

    private void setTool(int t) {
        st.tool = t;
        if (stage != null) stage.toolChanged();
        stateChanged(null);
    }

    private void frameOp(String name, boolean blank) {
        JSONObject o = op(name, "l", st.layer, "f", st.frame);
        if (blank) try { o.put("blank", 1); } catch (JSONException ignored) { }
        request(o);
    }

    private void setFps(int v) { request(op("fps", "v", Math.max(1, Math.min(60, v)))); }

    /** Acciones sobre la selección, que vive en la mesa (extendido). */
    private void cmd(String c) {
        if (stage != null) {
            switch (c) {
                case "delSel": stage.deleteSelection(); break;
                case "front": stage.orderSelection(true); break;
                case "back": stage.orderSelection(false); break;
                case "fit": stage.fit(); break;
                case "toSym": stage.convertToSymbol(); break;
                case "editSym": stage.editSelectedSymbol(); break;
                case "loop0": stage.setInstLoop(0, -1); break;
                case "loop1": stage.setInstLoop(1, -1); break;
                case "loop2": stage.setInstLoop(2, -1); break;
                case "breakSym": stage.breakApart(); break;
            }
            if (!"playHere".equals(c)) return;
        }
        if ("playHere".equals(c)) { if (!isEdit()) playHere(); return; }
        if (!connected) { Ui.toast(this, "Sin conexión con el extendido"); return; }
        send("moCmd", "c", c);
    }

    private void setColor(boolean fill, int color) {
        if (fill) st.fill = color; else st.stroke = color;
        refreshProps();
        stateChanged(fill ? "f" : "s");
    }

    // ================================================================ red
    private void sendRaw(JSONObject m) { if (!isSolo()) LinkService.send(m); }

    private void send(String t, Object... kv) {
        JSONObject m = new JSONObject();
        try {
            m.put("t", t);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) { }
        sendRaw(m);
    }

    @Override public void onState(boolean on, String info) {
        connected = on;
        if (isEdit()) {
            if (on) {
                JSONObject m = new JSONObject();
                try { m.put("t", "moFull"); m.put("doc", doc.toJson()); } catch (JSONException ignored) { }
                sendRaw(m);
                send("moVis", "on", visible);
                Sounds.need(this, doc);
            }
        } else if (isView()) {
            if (!on) stageShown = false;
            if (on) { stateChanged(null); if (visible) askStage(); }
        }
        refreshAll();
    }

    @Override public void onMessage(JSONObject m) {
        switch (m.optString("t")) {
            // ---- llegan al principal
            case "mo":
                if (doc.apply(m)) docChanged();
                break;
            case "moFull": {
                JSONObject d = m.optJSONObject("doc");
                if (d != null) doc.load(d);
                clampState();
                stateChanged(null);
                Sounds.need(this, doc);
                Sounds.warm(this, doc);
                Images.need(this, doc);
                break;
            }
            case "moVis":
                stageShown = m.optBoolean("on");
                refreshProps();
                break;
            case "moMsg":
                Ui.toast(this, m.optString("msg"));
                break;
            case "moHide": // el principal se fue: la mesa se esconde (vuelve lo que había antes)
                if (isEdit() && visible) moveTaskToBack(true);
                break;
            case "moPick":
                st.stroke = m.optInt("sc", st.stroke);
                st.fill = m.optInt("fc", st.fill);
                st.width = (float) m.optDouble("sw", st.width);
                refreshProps();
                break;
            case "moSel":
                selN = m.optInt("n");
                selSym = m.optString("sym", "");
                if (selSym.isEmpty()) selSym = null;
                selLoop = m.optInt("lp");
                selFx = m.optJSONObject("fx");
                if (selFx != null && selFx.length() == 0) selFx = null;
                refreshProps();
                break;
            case "moEnter":
                if (isView()) enterSymbol(m.optInt("l"), m.optInt("f"), m.optInt("i"));
                break;
            case "moExit":
                if (isView()) exitSymbol();
                break;
            case "moQuit":
                quit(false);
                break;
            // ---- llegan al extendido
            case "moReq": {
                JSONObject o = m.optJSONObject("op");
                if (o != null && stage != null) { stage.clearSel(); submit(o); }
                break;
            }
            case "moState": {
                int oldTool = st.tool;
                String oldSym = st.sym;
                st.from(m);
                if (stage != null) {
                    if (!oldSym.equals(st.sym)) { stage.clearSel(); stage.toolChanged(); }
                    if (oldTool != st.tool) stage.toolChanged();
                    String ap = m.optString("ap", "");
                    if (!ap.isEmpty()) stage.styleSelection(ap);
                }
                refreshAll();
                break;
            }
            case "moPlay":
                if (m.optBoolean("on")) startPlay(m.optInt("f"));
                else { stopPlay(false); st.frame = m.optInt("f", st.frame); refreshAll(); }
                break;
            case "moTick":
                if (playing) {
                    int f = m.optInt("f"), len = doc.length();
                    int diff = ((f - pFrame) % len + len) % len;
                    if (diff > len / 2) diff -= len;
                    if (Math.abs(diff) > 1) { pf0 = f; pt0 = System.nanoTime(); pFrame = f; onPlayFrame(); }
                }
                break;
            case "moCmd":
                if ("fx".equals(m.optString("c"))) { if (stage != null) stage.setFx(m.optJSONObject("fx")); break; }
                cmd(m.optString("c"));
                break;
            case "moClip": // se copiaron fotogramas en el otro teléfono
                clip = m.optJSONObject("c");
                refreshProps();
                break;
        }
    }

    // ================================================================ archivos .dmo
    SharedPreferences prefs() { return prefs; }

    /** Carga un documento abierto (se puede deshacer). En el principal se le pide al extendido. */
    void loadDoc(JSONObject d) {
        if (isView()) { // primero viajan los sonidos que trae el archivo, después la orden
            org.json.JSONArray ss = d.optJSONArray("sounds");
            for (int i = 0; ss != null && i < ss.length(); i++) {
                JSONObject so = ss.optJSONObject(i);
                if (so != null) Sounds.sendFile(this, so.optString("id"), so.optString("ext"));
            }
            org.json.JSONArray is = d.optJSONArray("images");
            for (int i = 0; is != null && i < is.length(); i++) {
                JSONObject io = is.optJSONObject(i);
                if (io != null) Images.sendFile(this, io.optString("id"), io.optString("ext"));
            }
        }
        request(op("load", "doc", d));
    }

    // ================================================================ sonido
    @Override public void addSound() {
        if (isView() && !connected) { Ui.toast(this, "Sin conexión con el extendido"); return; }
        if (st.inSymbol()) { Ui.toast(this, "El sonido va en la escena: sal del símbolo primero"); return; }
        Files.pickAudio(this);
    }

    /** Llegó un archivo de sonido elegido: se importa y se pone en el cabezal (en una capa de sonido). */
    void importSound(Uri u) {
        Ui.toast(this, "Cargando el sonido… (uno de varios minutos puede tardar un rato)");
        Sounds.importUri(this, u, (snd, err) -> {
            if (snd == null) { Ui.toast(this, "No se pudo cargar el sonido: " + err); return; }
            if (isView()) Sounds.sendFile(this, snd.id, snd.ext); // el archivo viaja antes que las órdenes
            request(op("sndAdd", "id", snd.id, "name", snd.name, "ext", snd.ext, "dur", snd.dur));
            if (st.frame == 0) { placeSound(snd, 0); return; }
            String[] items = {"En el cabezal (fotograma " + (st.frame + 1) + ")", "Al comienzo (fotograma 1) · música de fondo"};
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("¿Dónde empieza \"" + snd.name + "\"?")
                    .setItems(items, (d, w) -> placeSound(snd, w == 0 ? st.frame : 0))
                    .setOnCancelListener(d -> placeSound(snd, st.frame))
                    .show();
        });
    }

    /**
     * Pone el sonido desde el fotograma f. Nunca encima de otro: si la capa de sonido elegida tiene ese tramo libre
     * va ahí; si no, en una capa de sonido nueva (con el nombre del sonido) arriba de la actual, que queda elegida.
     */
    private void placeSound(MDoc.Sound snd, int f) {
        if (st.inSymbol()) { Ui.toast(this, "El sonido va en la escena: sal del símbolo y agrégalo de nuevo"); return; }
        MDoc.Clip c = new MDoc.Clip();
        c.s = snd.id;
        c.at = (int) ((long) f * 1000 / doc.fps);
        c.in = 0;
        c.len = Math.max(1, snd.dur);
        int l = Math.max(0, Math.min(st.layer, doc.layers.size() - 1));
        MDoc.Layer L = lay(l);
        boolean free = L != null && L.audio;
        if (free) for (MDoc.Clip k : L.clips) if (c.at < k.end() && k.at < c.end()) { free = false; break; }
        if (!free) {
            request(op("layerAdd", "at", l, "audio", 1));
            request(op("layer", "l", l, "name", snd.name));
        }
        try { request(op("clipAdd", "l", l, "c", c.toJson())); } catch (JSONException ignored) { }
        st.layer = l;
        st.frame = f;
        if (tl != null) { tl.clearClipSel(); tl.ensureVisible(f); }
        stateChanged(null);
        int anim = doc.durationMs();
        if (c.end() > anim + 500)
            Ui.toast(this, String.format(Locale.US, "Sonido agregado: %s. Dura %.1f s y la animación termina en %.1f s: "
                    + "alarga los fotogramas (F5) para oírlo completo", snd.name, c.end() / 1000f, anim / 1000f));
        else Ui.toast(this, "Sonido agregado: " + snd.name);
    }

    // ================================================================ imagen (siempre se elige en el principal)
    private void addImage() {
        if (isEdit()) { Ui.toast(this, "La imagen se importa desde el principal (☰ › Importar imagen)"); return; }
        if (isView() && !connected) { Ui.toast(this, "Sin conexión con el extendido"); return; }
        MDoc.Layer L = lay(st.layer);
        if (L == null || L.audio) { Ui.toast(this, "Elige una capa de dibujo"); return; }
        if (L.lock || !L.vis) { Ui.toast(this, "La capa está bloqueada u oculta"); return; }
        Files.pickImage(this);
    }

    /** Llegó la imagen elegida: va al extendido y queda centrada en la mesa, en la capa y el fotograma actuales. */
    void importImage(Uri u) {
        Ui.toast(this, "Cargando la imagen…");
        Images.importUri(this, u, (im, err) -> {
            if (im == null) { Ui.toast(this, "No se pudo cargar la imagen: " + err); return; }
            if (isView()) Images.sendFile(this, im.id, im.ext); // el archivo viaja antes que las órdenes
            request(op("imgAdd", "id", im.id, "name", im.name, "ext", im.ext, "w", im.w, "h", im.h));
            // tamaño: el de la imagen, achicado para caber en el 80 % del escenario
            float k = Math.min(1f, Math.min(doc.w * 0.8f / im.w, doc.h * 0.8f / im.h));
            float w = im.w * k, h = im.h * k;
            float cx = doc.w / 2f, cy = doc.h / 2f;
            if (st.inSymbol()) { cx = 0; cy = 0; } // dentro de un símbolo: en su punto de registro
            MDoc.Layer L = lay(st.layer);
            MDoc.Pose p = L == null ? null : MDoc.pose(L, st.frame);
            if (p != null && !st.inSymbol()) { cx -= p.tx; cy -= p.ty; }
            MDoc.Shape s = new MDoc.Shape();
            s.img = im.id;
            s.n = new float[]{cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, cx - w / 2, cy - h / 2};
            s.stroke = 0;
            s.width = 0;
            try { request(op("add", "l", st.layer, "f", st.frame, "s", s.toJson())); } catch (JSONException ignored) { }
            Ui.toast(this, "Imagen agregada: muévela con Selección o Transformación libre");
        });
    }

    @Override public void clipChange(int layer, int index, MDoc.Clip c) {
        try { request(op("clipSet", "l", layer, "i", index, "c", c.toJson())); } catch (JSONException ignored) { }
    }

    /** Cortar en el cabezal: el trozo seleccionado o el que está bajo el cabezal en la capa de sonido actual. */
    private void cutClip() {
        int t = (int) ((long) st.frame * 1000 / doc.fps);
        int l = tl != null && tl.selClipL >= 0 ? tl.selClipL : st.layer;
        MDoc.Layer L = lay(l);
        if (L == null || !L.audio) { Ui.toast(this, "Elige una capa de sonido (o toca un trozo de sonido)"); return; }
        int i = tl != null && tl.selClipL == l ? tl.selClipI : -1;
        if (i < 0 || i >= L.clips.size() || t <= L.clips.get(i).at || t >= L.clips.get(i).end()) {
            i = -1;
            for (int k = L.clips.size() - 1; k >= 0; k--)
                if (t > L.clips.get(k).at && t < L.clips.get(k).end()) { i = k; break; }
        }
        if (i < 0) { Ui.toast(this, "Pon el cabezal sobre un sonido para cortarlo"); return; }
        request(op("clipSplit", "l", l, "i", i, "t", t));
    }

    @Override public void clipMenu(final int layer, final int index) {
        MDoc.Layer L = lay(layer);
        if (L == null || index >= L.clips.size()) return;
        final MDoc.Clip c = L.clips.get(index);
        MDoc.Sound snd = doc.sound(c.s);
        String[] items = {"Cortar en el cabezal", "Llevar al cabezal", "Volumen…", "Duplicar", "Borrar este sonido"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(snd != null ? "♪ " + snd.name : "Sonido")
                .setItems(items, (d, w) -> {
                    int t = (int) ((long) st.frame * 1000 / doc.fps);
                    switch (w) {
                        case 0:
                            if (t > c.at && t < c.end()) request(op("clipSplit", "l", layer, "i", index, "t", t));
                            else Ui.toast(this, "El cabezal no está sobre este sonido");
                            break;
                        case 1: { MDoc.Clip k = c.copy(); k.at = t; clipChange(layer, index, k); break; }
                        case 2: volumeDialog(layer, index, c); break;
                        case 3: {
                            MDoc.Clip k = c.copy();
                            k.at = c.end();
                            try { request(op("clipAdd", "l", layer, "c", k.toJson())); } catch (JSONException ignored) { }
                            break;
                        }
                        case 4:
                            request(op("clipDel", "l", layer, "i", index));
                            if (tl != null) tl.clearClipSel();
                            break;
                    }
                }).show();
    }

    private void volumeDialog(final int layer, final int index, final MDoc.Clip c) {
        final SeekBar sb = new SeekBar(this);
        sb.setMax(200);
        sb.setProgress(Math.round(c.vol * 100));
        final TextView lab = Ui.label(this, Math.round(c.vol * 100) + " %", 15, Ui.INK);
        lab.setGravity(Gravity.CENTER);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) { lab.setText(v + " %"); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(lab);
        box.addView(sb);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Volumen").setView(box)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Listo", (d, w) -> {
                    MDoc.Clip k = c.copy();
                    k.vol = sb.getProgress() / 100f;
                    clipChange(layer, index, k);
                }).show();
    }

    // ================================================================ exportar video
    private void exportVideo() {
        if (android.os.Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            keepStage = true;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9);
            return;
        }
        String base = prefs.getString("docName", "Animación");
        if (base.endsWith(".dmo")) base = base.substring(0, base.length() - 4);
        final String name = base + " " + new java.text.SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(new java.util.Date());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        final TextView t = Ui.label(this, "Preparando…", 14, Ui.INK);
        box.addView(t);
        final android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        box.addView(pb);
        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Exportando video").setView(box).setCancelable(false)
                .setNegativeButton("Cancelar", (d, w) -> Export.cancel = true).show();
        Export.start(this, doc, name, new Export.Listener() {
            @Override public void progress(int done, int total) {
                ui.post(() -> { pb.setProgress(done * 1000 / Math.max(1, total)); t.setText("Fotograma " + done + " de " + total); });
            }
            @Override public void finished(Uri uri, String error) {
                ui.post(() -> {
                    try { dlg.dismiss(); } catch (Exception ignored) { }
                    if (uri == null) { Ui.toast(MotionActivity.this, "No se pudo exportar: " + error); return; }
                    exported(uri, name);
                });
            }
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 9 && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) exportVideo();
    }

    private void exported(final Uri uri, String name) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Video listo")
                .setMessage(name + ".mp4\nQuedó en Películas › DUAL Motion.")
                .setNegativeButton("Cerrar", null)
                .setNeutralButton("Compartir", (d, w) -> {
                    try {
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("video/mp4")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartir video"));
                    } catch (Exception e) { Ui.toast(this, "No se pudo compartir"); }
                })
                .setPositiveButton("Ver", (d, w) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
                    catch (Exception e) { Ui.toast(this, "No hay app para ver el video"); }
                }).show();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Files.onResult(this, req, res, data);
        hideBars();
        refreshProps();
    }

    // ================================================================ StageView.Host
    @Override public MDoc doc() { return doc; }
    @Override public State st() { return st; }
    @Override public int shownFrame() { return playing ? pFrame : st.frame; }
    @Override public boolean playing() { return playing; }

    @Override public void picked(int stroke, int fill, float width) {
        st.stroke = stroke; st.fill = fill; st.width = width;
        if (isEdit()) send("moPick", "sc", stroke, "fc", fill, "sw", (double) width);
        Ui.toast(this, "Colores tomados");
        refreshProps();
    }

    @Override public void selChanged(int n, MDoc.Shape inst, MDoc.Shape first) {
        selN = n;
        MDoc.Symbol Y = inst == null ? null : doc.symbol(inst.sym);
        selSym = Y == null ? null : Y.name;
        selLoop = inst == null ? 0 : inst.loop;
        selFx = first == null ? null : first.fxJson();
        if (isEdit()) send("moSel", "n", n, "sym", selSym == null ? "" : selSym, "lp", selLoop,
                "fx", selFx == null ? new JSONObject() : selFx);
        else refreshProps();
    }

    // ================================================================ editar un símbolo en el lugar
    @Override public void enterSymbol(int layer, int frame, int index) {
        if (isEdit()) { send("moEnter", "l", layer, "f", frame, "i", index); return; } // lo decide el principal
        MDoc.Layer PL = doc.layer(layer);
        MDoc.Pose pp = PL == null ? null : MDoc.pose(PL, frame);
        if (pp == null || index < 0 || index >= pp.key.shapes.size() || !pp.key.shapes.get(index).isInst()) return;
        MDoc.Shape inst = pp.key.shapes.get(index);
        MDoc.Symbol Y = doc.symbol(inst.sym);
        if (Y == null) return;
        st.pl = layer; st.pf = frame; st.pi = index;
        st.sym = Y.id;
        st.layer = 0;
        // se empieza en el fotograma del símbolo que se estaba viendo
        st.frame = MDoc.symFrame(inst, frame - pp.keyFrame, MDoc.length(Y.layers));
        if (tl != null) { tl.clearClipSel(); tl.ensureVisible(st.frame); }
        if (stage != null) { stage.clearSel(); stage.toolChanged(); }
        Ui.toast(this, "Editando " + Y.name + ": dibuja sus fotogramas. Toca dos veces fuera o ↩ para volver");
        stateChanged(null);
    }

    @Override public void exitSymbol() {
        if (isEdit()) { send("moExit"); return; }
        if (!st.inSymbol()) return;
        st.sym = "";
        st.layer = st.pl;
        st.frame = st.pf;
        if (tl != null) { tl.clearClipSel(); tl.ensureVisible(st.frame); }
        if (stage != null) { stage.clearSel(); stage.toolChanged(); }
        stateChanged(null);
    }

    @Override public String status() {
        MDoc.Layer L = lay(st.layer);
        String s = (L == null ? "—" : L.name) + "  ·  Fotograma " + (shownFrame() + 1);
        if (L != null && L.lock) s += "  ·  🔒";
        if (isEdit()) s += connected ? "  ·  ● Principal" : "  ·  ○ Sin principal";
        return s;
    }

    // ================================================================ TimelineView.Host
    @Override public void select(int layer, int frame) {
        if (playing) {
            if (previewOpen() || isEdit()) return;
            stopHere(); // tocar la línea de tiempo mientras se prueba: se detiene (como en Flash)
        }
        st.layer = Math.max(0, Math.min(layer, T().size() - 1));
        int old = st.frame;
        st.frame = Math.max(0, frame);
        if (st.frame != old) scrub(st.frame);
        if (tl != null) tl.ensureVisible(st.frame);
        stateChanged(null);
    }

    @Override public void toggle(int layer, String which) {
        MDoc.Layer L = lay(layer);
        if (L == null) return;
        boolean v = "vis".equals(which) ? !L.vis : "lock".equals(which) ? !L.lock : !L.out;
        request(op("layer", "l", layer, which, v));
    }

    @Override public void moveKey(int layer, int from, int to) {
        request(op("moveKey", "l", layer, "f", from, "to", to));
        select(layer, to);
    }

    @Override public void addLayer() { request(op("layerAdd", "at", st.layer)); }

    @Override public void delLayer() {
        if (T().size() <= 1) { Ui.toast(this, "Debe quedar al menos una capa"); return; }
        request(op("layerDel", "l", st.layer));
    }

    @Override public void frameMenu(final int layer, final int frame) {
        MDoc.Layer FL = lay(layer);
        java.util.Map.Entry<Integer, MDoc.Key> ke = FL == null || frame >= FL.len ? null : FL.keys.floorEntry(frame);
        final boolean tw = ke != null && ke.getValue().tween;
        String[] items = {"Insertar fotograma clave", "Insertar fotograma clave vacío", "Insertar fotograma",
                "Quitar fotograma", "Quitar fotograma clave",
                tw ? "Quitar interpolación de movimiento" : "Crear interpolación de movimiento",
                "Copiar fotograma", "Copiar varios fotogramas desde aquí…",
                clip == null ? "Pegar fotogramas (no hay nada copiado)" : "Pegar " + clipName()};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Fotograma " + (frame + 1))
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: request(op("key", "l", layer, "f", frame)); break;
                        case 1: request(op("key", "l", layer, "f", frame, "blank", 1)); break;
                        case 2: request(op("frame", "l", layer, "f", frame)); break;
                        case 3: request(op("delFrame", "l", layer, "f", frame)); break;
                        case 4: request(op("clearKey", "l", layer, "f", frame)); break;
                        case 5:
                            if (tw) request(op("tween", "l", layer, "f", frame, "on", false));
                            else createTween(layer, frame);
                            break;
                        case 6: copyFrames(layer, frame, 1); break;
                        case 7: copyManyDialog(layer, frame); break;
                        case 8: pasteFrames(layer, frame); break;
                    }
                }).show();
    }

    // ================================================================ copiar y pegar fotogramas (como en Flash)
    /** Portapapeles de fotogramas: {n, keys:[{o, k}]}; vale entre capas y dentro de símbolos. */
    private JSONObject clip;

    private String clipName() {
        int n = clip == null ? 0 : clip.optInt("n", 1);
        return n == 1 ? "1 fotograma" : n + " fotogramas";
    }

    private void copyFrames(int layer, int frame, int n) {
        JSONObject c = MDoc.copyFrames(lay(layer), frame, n);
        if (c == null) { Ui.toast(this, "Ahí no hay fotogramas para copiar"); return; }
        clip = c;
        if (!isSolo()) send("moClip", "c", c); // el mismo portapapeles en los dos teléfonos
        Ui.toast(this, "Copiado: " + clipName() + ". Elige dónde y toca Pegar");
        refreshProps();
    }

    private void copyManyDialog(final int layer, final int frame) {
        MDoc.Layer L = lay(layer);
        if (L == null || L.audio || frame >= L.len) { Ui.toast(this, "Ahí no hay fotogramas para copiar"); return; }
        final int max = L.len - frame;
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(max));
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Copiar fotogramas")
                .setMessage("¿Cuántos fotogramas desde el " + (frame + 1) + "? (hasta " + max + ", el final de la capa)")
                .setView(e)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Copiar", (d, w) -> {
                    int n;
                    try { n = Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { n = max; }
                    copyFrames(layer, frame, Math.max(1, Math.min(max, n)));
                }).show();
    }

    private void pasteFrames(int layer, int frame) {
        if (clip == null) { Ui.toast(this, "Primero copia fotogramas"); return; }
        MDoc.Layer L = lay(layer);
        if (L == null || L.audio) { Ui.toast(this, "Elige una capa de dibujo"); return; }
        if (L.lock) { Ui.toast(this, "La capa está bloqueada"); return; }
        try {
            JSONObject o = op("pasteFrames", "l", layer, "f", frame, "n", clip.optInt("n", 1));
            o.put("keys", new org.json.JSONArray(clip.getJSONArray("keys").toString()));
            request(o);
            Ui.toast(this, "Pegado: " + clipName() + " desde el fotograma " + (frame + 1));
        } catch (JSONException ignored) { }
    }

    @Override public void layerMenu(final int layer) {
        final MDoc.Layer L = lay(layer);
        if (L == null) return;
        select(layer, st.frame);
        String[] items = L.audio
                ? new String[]{"Renombrar", "Eliminar capa", "Insertar capa encima", "Subir", "Bajar",
                        L.vis ? "Silenciar (ocultar)" : "Volver a oír (mostrar)", L.lock ? "Desbloquear" : "Bloquear",
                        "Volumen de la capa… (" + Math.round(L.vol * 100) + " %)"}
                : new String[]{"Renombrar", "Eliminar capa", "Insertar capa encima", "Subir", "Bajar",
                        L.vis ? "Ocultar" : "Mostrar", L.lock ? "Desbloquear" : "Bloquear"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle((L.audio ? "♪ " : "") + L.name)
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: rename(layer); break;
                        case 1:
                            if (T().size() <= 1) { Ui.toast(this, "Debe quedar al menos una capa"); break; }
                            request(op("layerDel", "l", layer));
                            Ui.toast(this, "Capa eliminada (↶ para deshacer)");
                            break;
                        case 2: request(op("layerAdd", "at", layer)); break;
                        case 3: if (layer > 0) { request(op("layerMove", "l", layer, "to", layer - 1)); select(layer - 1, st.frame); } break;
                        case 4: if (layer < T().size() - 1) { request(op("layerMove", "l", layer, "to", layer + 1)); select(layer + 1, st.frame); } break;
                        case 5: toggle(layer, "vis"); break;
                        case 6: toggle(layer, "lock"); break;
                        case 7: layerVolumeDialog(layer); break;
                    }
                }).show();
    }

    private void layerVolumeDialog(final int layer) {
        final MDoc.Layer L = lay(layer);
        if (L == null || !L.audio) return;
        final SeekBar sb = new SeekBar(this);
        sb.setMax(200);
        sb.setProgress(Math.round(L.vol * 100));
        final TextView lab = Ui.label(this, Math.round(L.vol * 100) + " %", 15, Ui.INK);
        lab.setGravity(Gravity.CENTER);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) { lab.setText(v + " %"); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(lab);
        box.addView(sb);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Volumen de la capa \"" + L.name + "\"").setView(box)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Listo", (d, w) -> request(op("layer", "l", layer, "vol", sb.getProgress() / 100.0)))
                .show();
    }

    // ================================================================ panel de sonido (propiedades)
    private LinearLayout soundBox;
    private TextView sndLayerL, sndClipL;
    private SeekBar sndLayerBar, sndClipBar;
    private LinearLayout sndClipRow;

    /** Volumen de la capa de sonido elegida y del trozo elegido (0 a 200 %), a la vista en propiedades. */
    private View buildSoundBox() {
        soundBox = new LinearLayout(this);
        soundBox.setOrientation(LinearLayout.VERTICAL);
        soundBox.setPadding(0, dp(6), 0, dp(4));
        TextView t = Ui.label(this, "♪ Nivel de audio", 13, 0xFF1565C0);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        soundBox.addView(t);
        sndLayerL = Ui.label(this, "", 12, 0xFF555555);
        sndLayerL.setPadding(0, dp(6), 0, 0);
        soundBox.addView(sndLayerL);
        sndLayerBar = volBar(sndLayerL, "Capa", true);
        soundBox.addView(sndLayerBar);
        sndClipRow = new LinearLayout(this);
        sndClipRow.setOrientation(LinearLayout.VERTICAL);
        sndClipL = Ui.label(this, "", 12, 0xFF555555);
        sndClipL.setPadding(0, dp(6), 0, 0);
        sndClipRow.addView(sndClipL);
        sndClipBar = volBar(sndClipL, "Trozo elegido", false);
        sndClipRow.addView(sndClipBar);
        soundBox.addView(sndClipRow);
        TextView hint = Ui.label(this, "Toca un trozo de sonido para su volumen · 100 % = original", 11, 0xFF777777);
        hint.setPadding(0, dp(4), 0, 0);
        soundBox.addView(hint);
        return soundBox;
    }

    private SeekBar volBar(final TextView label, final String what, final boolean layerVol) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(200);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) { if (u) label.setText(what + ": " + v + " %"); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                double v = s.getProgress() / 100.0;
                if (layerVol) request(op("layer", "l", st.layer, "vol", v));
                else if (tl != null && tl.selClipL >= 0) {
                    MDoc.Layer L = lay(tl.selClipL);
                    if (L == null || tl.selClipI >= L.clips.size()) return;
                    MDoc.Clip k = L.clips.get(tl.selClipI).copy();
                    k.vol = (float) v;
                    clipChange(tl.selClipL, tl.selClipI, k);
                }
            }
        });
        return sb;
    }

    private void refreshSoundBox() {
        if (soundBox == null) return;
        MDoc.Layer L = lay(st.layer);
        boolean on = L != null && L.audio;
        soundBox.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        int lv = Math.round(L.vol * 100);
        sndLayerL.setText("Capa \"" + L.name + "\": " + lv + " %");
        if (!sndLayerBar.isPressed()) sndLayerBar.setProgress(lv);
        MDoc.Layer CL = tl != null && tl.selClipL >= 0 ? lay(tl.selClipL) : null;
        boolean clip = CL != null && CL.audio && tl.selClipI < CL.clips.size();
        sndClipRow.setVisibility(clip ? View.VISIBLE : View.GONE);
        if (clip) {
            int cv = Math.round(CL.clips.get(tl.selClipI).vol * 100);
            sndClipL.setText("Trozo elegido: " + cv + " %");
            if (!sndClipBar.isPressed()) sndClipBar.setProgress(cv);
        }
    }

    @Override public void clipSelected() { refreshSoundBox(); }

    private void rename(final int layer) {
        MDoc.Layer L = lay(layer);
        if (L == null) return;
        final EditText e = new EditText(this);
        e.setText(L.name);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Nombre de la capa")
                .setView(e)
                .setPositiveButton("Listo", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) request(op("layer", "l", layer, "name", n));
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ================================================================ menú
    @Override public void menu() {
        String[] items = {"Guardar", "Guardar como…", "Abrir…", "Nuevo documento", "Conexión",
                "Cambiar el papel de este teléfono", "Buscar actualización", "Permisos para la mesa",
                "Exportar video (MP4)", "Agregar sonido…", "Cerrar DUAL Motion", "Acerca de DUAL Motion",
                "Importar imagen…", "Traer la mesa al frente",
                "Sonido al mover la aguja: " + (prefs.getBoolean("scrub", true) ? "sí ✓" : "no"),
                "Guardar solo en el archivo .dmo: " + (autoFile() ? "sí ✓" : "no")};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: Files.save(this, false); break;
                        case 1: Files.save(this, true); break;
                        case 2: Files.open(this); break;
                        case 3:
                            new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                                    .setMessage("¿Empezar un documento nuevo? (Se puede deshacer.)")
                                    .setPositiveButton("Nuevo", (a, b) -> {
                                        prefs.edit().remove("docUri").remove("docName").apply();
                                        request(op("new"));
                                    })
                                    .setNegativeButton("Cancelar", null).show();
                            break;
                        case 4: connectionDialog(); break;
                        case 5:
                            prefs.edit().remove("role").apply();
                            LinkService.detach(this);
                            LinkService.stop(this);
                            recreate();
                            break;
                        case 6: Updater.check(this); break;
                        case 7: permsDialog(); break;
                        case 8: exportVideo(); break;
                        case 9: addSound(); break;
                        case 10: askQuit(); break;
                        case 11: about(); break;
                        case 12: addImage(); break;
                        case 13: if (isView()) bringStage(); else Ui.toast(this, "Se usa desde el principal"); break;
                        case 14: {
                            boolean on = !prefs.getBoolean("scrub", true);
                            prefs.edit().putBoolean("scrub", on).apply();
                            Ui.toast(this, on ? "Al mover la aguja vas a oír el sonido" : "La aguja se mueve en silencio");
                            break;
                        }
                        case 15: {
                            boolean on = !autoFile();
                            prefs.edit().putBoolean("autoFile", on).apply();
                            if (on && prefs.getString("docUri", null) == null)
                                Ui.toast(this, "Guárdalo una vez (☰ › Guardar como…) y desde ahí se guarda solo");
                            else Ui.toast(this, on ? "Cada cambio se guarda solo en el archivo" : "El archivo se guarda solo cuando tú lo pides");
                            if (on) markFileDirty();
                            break;
                        }
                    }
                }).show();
    }

    // ================================================================ cerrar
    private void askQuit() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Cerrar DUAL Motion")
                .setMessage(isSolo() ? "Tu trabajo queda guardado." : "Se cierra en los dos teléfonos. Tu trabajo queda guardado en el extendido.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Cerrar", (d, w) -> quit(true))
                .show();
    }

    /** Guarda, avisa al otro teléfono (tell) y cierra la pantalla y la conexión. */
    void quit(boolean tell) {
        stopPlay(false);
        if (role != null && !isView()) snapshot(true);
        if (role != null) saveFileNow(true);
        if (tell && !isSolo()) send("moQuit");
        LinkService.detach(this);
        if (!isSolo()) ui.postDelayed(() -> LinkService.stop(getApplicationContext()), tell ? 600 : 0);
        finishAndRemoveTask();
    }

    private void about() {
        String v = "?";
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("DUAL Motion " + v)
                .setMessage("Animación en dos pantallas.\nArriba: herramientas y línea de tiempo.\n"
                        + "Abajo: la mesa de trabajo.\n\nPapel de este teléfono: " + roleName())
                .setPositiveButton("Listo", null).show();
    }

    private String roleName() { return isView() ? "Principal" : isEdit() ? "Extendido" : "Un solo teléfono"; }

    private void connectionDialog() {
        if (isSolo()) { Ui.toast(this, "Modo un solo teléfono: no usa conexión"); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        String txt = "Este teléfono: " + roleName() + "\nIP en el WiFi: " + Net.localIp() + "\nEstado: "
                + (connected ? "conectado con " + LinkService.peer() : "sin conexión");
        box.addView(Ui.label(this, txt, 15, Ui.INK));
        final EditText ip = new EditText(this);
        if (isView()) {
            TextView h = Ui.label(this, "\nSi no se encuentran solos, escribe la IP del extendido:", 13, 0xFF555555);
            box.addView(h);
            ip.setHint("192.168.0.10");
            ip.setInputType(InputType.TYPE_CLASS_PHONE);
            ip.setText(prefs.getString("ip", ""));
            box.addView(ip);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Conexión").setView(box).setNegativeButton("Cerrar", null);
        if (isView()) b.setPositiveButton("Conectar", (d, w) -> {
            String s = ip.getText().toString().trim();
            prefs.edit().putString("ip", s).apply();
            if (!s.isEmpty()) LinkService.connectTo(s);
        });
        b.show();
    }

    private void pickColor(final boolean fill) {
        openMixer(fill ? st.fill : st.stroke, fill ? "Color del relleno" : "Color del trazo", c -> setColor(fill, c),
                () -> setColor(fill, 0));
    }

    /**
     * Mezclador de colores (como en Flash): cuadro y barras de tono y transparencia, código hexadecimal, muestras
     * clásicas y "guardar en mis colores". onNone != null agrega el botón "Sin color".
     */
    private void openMixer(int start, String title, final java.util.function.IntConsumer onOk, final Runnable onNone) {
        final ColorMixer mx = new ColorMixer(this, start);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        box.addView(mx, new LinearLayout.LayoutParams(dp(330), dp(150)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        row.addView(Ui.label(this, "#", 16, Ui.INK));
        final EditText hex = new EditText(this);
        hex.setSingleLine(true);
        hex.setTextSize(15);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setText(hexOf(mx.color()));
        row.addView(hex, new LinearLayout.LayoutParams(dp(110), -2));
        final boolean[] typing = {false};
        mx.onChange = c -> { if (!typing[0]) hex.setText(hexOf(c)); };
        hex.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                Integer c = parseHex(s.toString());
                if (c == null || !hex.hasFocus()) return;
                typing[0] = true;
                mx.set(c);
                typing[0] = false;
            }
        });
        TextView save = Ui.sym(this, "＋ Mis colores", "Guardar este color en Mis colores", () -> {
            saveMyColor(mx.color());
            Ui.toast(this, "Guardado en Mis colores");
        });
        save.setTextSize(12);
        row.addView(save, lpMargins(0, dp(36), dp(8), 0, 0, 0));
        ((LinearLayout.LayoutParams) save.getLayoutParams()).weight = 1;
        box.addView(row);
        final AlertDialog[] dlg = new AlertDialog[1];
        TextView classic = Ui.sym(this, "Muestras clásicas…", "Los 216 colores de siempre", () -> {
            ColorGrid g = new ColorGrid(this, c -> { mx.set(c); hex.setText(hexOf(c)); });
            FrameLayout gb = new FrameLayout(this);
            gb.setPadding(dp(12), dp(8), dp(12), 0);
            gb.addView(g, new FrameLayout.LayoutParams(dp(18 * 18), dp(18 * 13)));
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("Muestras").setView(gb).setPositiveButton("Listo", null).show();
        });
        classic.setTextSize(12);
        box.addView(classic, lpMargins(-1, dp(36), 0, dp(6), 0, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(title).setView(scrollable(box))
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Listo", (d, w) -> onOk.accept(mx.color()));
        if (onNone != null) b.setNeutralButton("Sin color", (d, w) -> onNone.run());
        dlg[0] = b.show();
    }

    /** Con el teléfono acostado hay poca altura: el contenido del diálogo se puede deslizar. */
    private View scrollable(View v) {
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        return sv;
    }

    private static String hexOf(int c) {
        return (c >>> 24) == 0xFF ? String.format(Locale.US, "%06X", c & 0xFFFFFF) : String.format(Locale.US, "%08X", c);
    }

    /** "F57C1F" o "80F57C1F" (con transparencia); null si no es un color. */
    private static Integer parseHex(String s) {
        s = s.trim().replace("#", "");
        if (!s.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) return null;
        long v = Long.parseLong(s, 16);
        return s.length() == 6 ? (int) (0xFF000000L | v) : (int) v;
    }

    // ---- paleta del panel: 14 colores editables y una fila de "Mis colores" (se guarda en el teléfono)
    static final int EMPTY = 1; // casilla vacía de Mis colores
    static final int[] DEFAULT_COLS = {0, 0xFF000000, 0xFF666666, 0xFFFFFFFF, 0xFFE53935, 0xFFF57C1F, 0xFFFDD835,
            0xFF2FA866, 0xFF00897B, 0xFF2F7BE0, 0xFF3949AB, 0xFF8E44AD, 0xFFEC407A, 0xFF795548,
            EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY};

    private int[] paletteCols() {
        int[] c = DEFAULT_COLS.clone();
        String s = prefs.getString("palette", "");
        if (!s.isEmpty()) {
            String[] p = s.split(",");
            for (int i = 0; i < p.length && i < c.length; i++) try { c[i] = (int) Long.parseLong(p[i]); } catch (Exception ignored) { }
        }
        return c;
    }

    private void savePalette(int[] c) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < c.length; i++) { if (i > 0) b.append(','); b.append(c[i]); }
        prefs.edit().putString("palette", b.toString()).apply();
        if (palette != null) palette.invalidate();
    }

    /** Agrega a Mis colores (la primera casilla vacía; si no hay, se corre la fila y se pierde la más antigua). */
    private void saveMyColor(int color) {
        int[] c = paletteCols();
        int at = -1;
        for (int i = 14; i < c.length; i++) if (c[i] == EMPTY) { at = i; break; }
        if (at < 0) { System.arraycopy(c, 15, c, 14, c.length - 15); at = c.length - 1; }
        c[at] = color;
        savePalette(c);
    }

    private Palette palette;

    // ================================================================ refrescar la interfaz
    private void refreshAll() {
        if (player != null) player.invalidate();
        if (stage != null) stage.invalidate();
        if (tl != null) tl.invalidate();
        if (mini != null) mini.invalidate();
        refreshProps();
        updateInfo();
    }

    private void refreshProps() {
        refreshKeyButtons();
        refreshSoundBox();
        refreshFx();
        if (brushBox != null) {
            brushBox.setVisibility(st.tool == State.BRUSH ? View.VISIBLE : View.GONE);
            eraseBox.setVisibility(st.tool == State.ERASER ? View.VISIBLE : View.GONE);
            for (int i = 0; i < brushBtns.length; i++) brushBtns[i].setTextColor(i == st.brushMode ? Ui.ORANGE : Ui.INK);
            for (int i = 0; i < eraseBtns.length; i++) eraseBtns[i].setTextColor(i == st.eraseMode ? Ui.ORANGE : Ui.INK);
        }
        if (bucketBox != null) {
            bucketBox.setVisibility(st.tool == State.BUCKET ? View.VISIBLE : View.GONE);
            tolL.setText("Tolerancia " + st.tol);
            gapL.setText("Huecos " + st.gap + " px");
            allL.setText(st.fillAll ? "Mira: todas las capas" : "Mira: solo esta capa");
        }
        refreshDocLabel();
        if (symBox != null) {
            symBox.setVisibility(selSym != null ? View.VISIBLE : View.GONE);
            toSymBtn.setVisibility(selSym == null ? View.VISIBLE : View.GONE);
            if (selSym != null) {
                symName.setText("◈ " + selSym);
                for (int i = 0; i < loopBtns.length; i++) loopBtns[i].setTextColor(i == selLoop ? 0xFF5E35B1 : 0xFF9E9E9E);
            }
        }
        if (tweenBtn != null) {
            java.util.Map.Entry<Integer, MDoc.Key> e = curKeyEntry();
            MDoc.Layer L = lay(st.layer);
            boolean tw = e != null && e.getValue().tween;
            boolean ok = tw && L != null && MDoc.tweenOk(L, e.getKey());
            tweenBtn.setText(e == null ? "—" : tw ? (ok ? "↝ Movimiento (toca para quitar)" : "↝ Falta el clave final")
                    : "Crear interpolación de movimiento");
            tweenBtn.setTextColor(tw ? 0xFF5E35B1 : Ui.INK);
            curve.set(e == null ? MDoc.LINEAR : e.getValue().ease, tw);
            spinLabel.setText(e == null ? "0" : String.valueOf(e.getValue().spin));
            MDoc.Pose ps = L == null ? null : MDoc.pose(L, st.frame);
            int a = ps == null ? 100 : Math.round(ps.alpha * 100);
            alphaBar.setProgress(a);
            alphaLabel.setText("Alfa: " + a + " %");
        }
        if (toolBtns != null) for (int i = 0; i < toolBtns.length; i++) toolBtns[i].setOn(i == st.tool);
        if (strokeSw != null) {
            strokeSw.set(st.stroke, !targetFill);
            fillSw.set(st.fill, targetFill);
            widthBar.setProgress(Math.round(st.width) - 1);
            widthLabel.setText("Grosor: " + Math.round(st.width));
            if (fpsLabel != null) fpsLabel.setText(doc.fps + " fps");
            selLabel.setText(selN == 0 ? State.NAMES[st.tool] : State.NAMES[st.tool] + "  ·  " + selN
                    + (selN == 1 ? " forma seleccionada" : " formas seleccionadas"));
        }
        if (onionBtn != null) onionBtn.setTextColor(st.onion ? Ui.ORANGE : Ui.INK);
        if (connLabel != null && isView())
            connLabel.setText(!connected ? "○ Buscando el extendido…  (toca para ver)"
                    : stageShown ? "● Conectado · mesa a la vista" : "● Conectado · la mesa está oculta (toca para mostrarla)");
    }

    private void updateInfo() {
        if (infoLabel == null) return;
        int f = shownFrame();
        infoLabel.setText(String.format(Locale.US, "%d  ·  %d fps  ·  %.1f s", f + 1, doc.fps, f / (float) doc.fps)
                .replace('.', ','));
    }

    // ================================================================ guardar
    /*
     * Cada cambio queda grabado sin frenar el dibujo:
     *  - Diario (en el teléfono dueño del documento: extendido o modo solo): cada orden aplicada se agrega como una
     *    línea a diario.jsonl desde un hilo aparte (una orden es chica: escribirla no se nota).
     *  - Foto completa (actual.json): solo de vez en cuando (cada 400 órdenes, al deshacer, al abrir o al irse a
     *    segundo plano) y cuando no se está dibujando (2,5 s quieto); al tomarla, el diario se vacía.
     *  - Al abrir: foto + se repiten las órdenes del diario → queda como estaba aunque la app se haya caído.
     *  - En el principal (o modo solo), si el documento ya tiene archivo .dmo, se guarda solo en él unos segundos
     *    después del último cambio y al salir de la app (el archivo se escribe en otro hilo).
     */
    private final java.util.concurrent.ExecutorService disk = java.util.concurrent.Executors.newSingleThreadExecutor();
    private java.io.Writer journalW; // solo se usa en el hilo de disco
    private int journalWGen = -1;     // (ídem)
    /** Número de la foto actual: su diario es diario-N.jsonl. Así, si la app cae entre la foto y el borrado del
     *  diario anterior, al abrir no se repiten órdenes que ya estaban en la foto. */
    private int jgen;
    private int journalN;
    private long lastOpAt;

    private File autosaveFile() { return new File(getFilesDir(), "actual.json"); }

    private File journalFile(int g) { return new File(getFilesDir(), "diario-" + g + ".jsonl"); }

    /** Graba una orden recién aplicada. */
    private void journal(JSONObject o) {
        if (isView()) return;
        String op = o.optString("op");
        lastOpAt = android.os.SystemClock.uptimeMillis();
        if ("load".equals(op) || "new".equals(op)) { journalN++; ui.removeCallbacks(snapR); ui.postDelayed(snapR, 300); return; }
        final String line = o.toString();
        final int g = jgen;
        journalN++;
        disk.execute(() -> {
            try {
                if (journalW == null || journalWGen != g) {
                    if (journalW != null) try { journalW.close(); } catch (Exception ignored) { }
                    journalW = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                            new FileOutputStream(journalFile(g), true), StandardCharsets.UTF_8));
                    journalWGen = g;
                }
                journalW.write(line);
                journalW.write('\n');
                journalW.flush();
            } catch (Exception ignored) { }
        });
        if (journalN >= 400 || "undo".equals(op) || "redo".equals(op)) { ui.removeCallbacks(snapR); ui.postDelayed(snapR, 2500); }
    }

    /** Foto completa cuando no se está dibujando (si se sigue dibujando, espera; con muchísimas órdenes, igual). */
    private final Runnable snapR = new Runnable() {
        @Override public void run() {
            if (journalN == 0) return;
            long quiet = android.os.SystemClock.uptimeMillis() - lastOpAt;
            if (quiet < 2000 && journalN < 3000) { ui.postDelayed(this, 1500); return; }
            snapshot(false);
        }
    };

    private void snapshot(boolean wait) {
        ui.removeCallbacks(snapR);
        if (isView() || journalN == 0) return;
        final int ng = ++jgen;
        String js;
        try { JSONObject d = doc.toJson(); d.put("_jgen", ng); js = d.toString(); } catch (JSONException e) { return; }
        final String json = js;
        journalN = 0;
        java.util.concurrent.Future<?> f = disk.submit(() -> {
            writeAutosave(json);
            if (journalW != null && journalWGen < ng) {
                try { journalW.close(); } catch (Exception ignored) { }
                journalW = null; journalWGen = -1;
            }
            for (int g : journalGens()) if (g < ng) { //noinspection ResultOfMethodCallIgnored
                journalFile(g).delete(); }
        });
        if (wait) try { f.get(3, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) { }
    }

    private void writeAutosave(String json) {
        try {
            File tmp = new File(getFilesDir(), "actual.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(autosaveFile());
        } catch (Exception ignored) { }
    }

    /** Números de los diarios que hay en la carpeta, de menor a mayor. */
    private java.util.List<Integer> journalGens() {
        java.util.ArrayList<Integer> l = new java.util.ArrayList<>();
        String[] names = getFilesDir().list();
        if (names != null) for (String nm : names) {
            if (!nm.startsWith("diario-") || !nm.endsWith(".jsonl")) continue;
            try { l.add(Integer.parseInt(nm.substring(7, nm.length() - 6))); } catch (NumberFormatException ignored) { }
        }
        java.util.Collections.sort(l);
        return l;
    }

    private void loadAutosave() {
        File f = autosaveFile();
        int g0 = 0;
        if (f.exists()) try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            JSONObject d = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
            g0 = d.optInt("_jgen", 0);
            doc.load(d);
        } catch (Exception ignored) { }
        jgen = g0;
        // lo que se hizo después de la última foto (también si la app se cayó), en orden
        int replayed = 0;
        for (int g : journalGens()) {
            if (g < g0) { //noinspection ResultOfMethodCallIgnored
                journalFile(g).delete(); continue; } // ya estaba en la foto
            jgen = Math.max(jgen, g);
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new FileInputStream(journalFile(g)), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try { if (doc.apply(new JSONObject(line))) replayed++; } catch (Exception ignored) { } // línea a medias
                }
            } catch (Exception ignored) { }
        }
        //noinspection ResultOfMethodCallIgnored
        new File(getFilesDir(), "diario.jsonl").delete();
        if (replayed > 0) { journalN = replayed; snapshot(false); }
    }

    // ---- guardado automático en el archivo .dmo (principal o modo solo)
    private boolean fileDirty;
    private long fileSeq;

    private boolean autoFile() { return prefs.getBoolean("autoFile", true); }

    private void markFileDirty() {
        if (isEdit()) return;
        fileDirty = true;
        fileSeq++;
        ui.removeCallbacks(fileR);
        if (autoFile() && prefs.getString("docUri", null) != null) ui.postDelayed(fileR, 6000);
    }

    private final Runnable fileR = () -> saveFileNow(false);

    private boolean fileSaving;

    private void saveFileNow(boolean leaving) {
        ui.removeCallbacks(fileR);
        if (!fileDirty || !autoFile() || prefs.getString("docUri", null) == null) return;
        if (!leaving && (playing || fileSaving)) { ui.postDelayed(fileR, 3000); return; }
        final long seq = fileSeq;
        fileSaving = true;
        Files.autoWrite(this, ok -> {
            fileSaving = false;
            if (ok && seq == fileSeq) { fileDirty = false; refreshDocLabel(); }
            else if (!ok) ui.postDelayed(fileR, 30000); // quizás el archivo se movió: se reintenta más tarde
        });
    }

    /** Lo llama Files al guardar a mano. */
    void savedToFile() { fileDirty = false; ui.removeCallbacks(fileR); refreshDocLabel(); }

    private void refreshDocLabel() {
        if (docLabel == null) return;
        MDoc.Symbol Y = st.inSymbol() ? doc.symbol(st.sym) : null;
        String file = prefs.getString("docName", null);
        String mark = file == null ? "" : fileDirty ? "  •" : "  ✓";
        docLabel.setText(Y != null ? "🎬 " + doc.scene + "  ›  ◈ " + Y.name + "   ↩"
                : "🎬 " + doc.scene + "  ·  " + (file == null ? "Sin guardar" : file) + mark);
    }

    private void saveState() {
        prefs.edit().putInt("tool", st.tool).putInt("sc", st.stroke).putInt("fc", st.fill)
                .putFloat("sw", st.width).putBoolean("onion", st.onion).apply();
    }

    private void restoreState() {
        st.tool = prefs.getInt("tool", st.tool);
        st.stroke = prefs.getInt("sc", st.stroke);
        st.fill = prefs.getInt("fc", st.fill);
        st.width = prefs.getFloat("sw", st.width);
        st.onion = prefs.getBoolean("onion", false);
        st.tol = prefs.getInt("fillTol", 40);
        st.gap = prefs.getInt("fillGap", 2);
        st.fillAll = prefs.getBoolean("fillAll", false);
        st.brushMode = prefs.getInt("brushMode", 0);
        st.eraseMode = prefs.getInt("eraseMode", 0);
    }

    // ================================================================ piezas pequeñas
    /** Muestra de color de trazo o relleno; con borde naranja la que está activa. */
    final class Swatch extends View {
        final int iconTool;
        int color;
        boolean target;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        Swatch(Context c, int iconTool) { super(c); this.iconTool = iconTool; }

        void set(int c, boolean t) { color = c; target = t; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float pad = dp(3);
            r.set(pad, pad, getWidth() - pad, getHeight() - pad);
            drawColorCell(c, r, color, p, dp(8));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(target ? dp(3) : dp(1));
            p.setColor(target ? Ui.ORANGE : 0xFF9E9E9E);
            c.drawRoundRect(r, dp(8), dp(8), p);
            // ícono pequeño en la esquina
            float s = dp(16);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xEEFFFFFF);
            c.drawCircle(getWidth() - s / 2 - dp(2), getHeight() - s / 2 - dp(2), s / 2 + dp(1), p);
            c.save();
            c.translate(getWidth() - s - dp(2), getHeight() - s - dp(2));
            c.scale(s / 24f, s / 24f);
            Ui.icon(c, iconTool, Ui.INK, p);
            c.restore();
        }
    }

    static void drawColorCell(Canvas c, RectF r, int color, Paint p, float radius) {
        p.setStyle(Paint.Style.FILL);
        if (color == 0) {
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, radius, radius, p);
            p.setColor(Ui.RED);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2, r.width() / 14));
            c.drawLine(r.left + r.width() * 0.2f, r.bottom - r.height() * 0.2f,
                    r.right - r.width() * 0.2f, r.top + r.height() * 0.2f, p);
        } else {
            p.setColor(color);
            c.drawRoundRect(r, radius, radius, p);
        }
    }

    /** Colores rápidos: se aplican al trazo o al relleno, según cuál esté activo. */
    /** Colores rápidos: tocar = usar; mantener = editar (o quitar de Mis colores); casilla "+" = color nuevo. */
    final class Palette extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        int downI = -1;
        boolean longDone;
        final Runnable longPress = () -> { longDone = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); cellMenu(downI); };

        Palette(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            int[] cols = paletteCols();
            float cw = getWidth() / 7f, ch = getHeight() / 3f;
            for (int i = 0; i < cols.length; i++) {
                r.set((i % 7) * cw + dp(2), (i / 7) * ch + dp(2), (i % 7 + 1) * cw - dp(2), (i / 7 + 1) * ch - dp(2));
                if (cols[i] == EMPTY) { // casilla libre de Mis colores
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(1));
                    p.setColor(0x55000000);
                    p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(3), dp(3)}, 0));
                    c.drawRoundRect(r, dp(5), dp(5), p);
                    p.setPathEffect(null);
                    p.setStyle(Paint.Style.FILL);
                    p.setTextSize(dp(14));
                    p.setColor(0x88000000);
                    c.drawText("+", r.centerX() - p.measureText("+") / 2, r.centerY() + dp(5), p);
                    continue;
                }
                drawColorCell(c, r, cols[i], p, dp(5));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1));
                p.setColor(0x33000000);
                c.drawRoundRect(r, dp(5), dp(5), p);
            }
            p.setStyle(Paint.Style.FILL);
        }

        private int cellAt(float x, float y) {
            int i = (int) (y / (getHeight() / 3f)) * 7 + (int) (x / (getWidth() / 7f));
            return i >= 0 && i < DEFAULT_COLS.length ? i : -1;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downI = cellAt(e.getX(), e.getY());
                    longDone = false;
                    postDelayed(longPress, 450);
                    return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    if (!longDone && downI >= 0 && downI == cellAt(e.getX(), e.getY())) {
                        int c = paletteCols()[downI];
                        if (c == EMPTY) editCell(downI); else setColor(targetFill, c);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    return true;
            }
            return true;
        }

        private void cellMenu(final int i) {
            if (i < 0) return;
            final int[] cols = paletteCols();
            if (cols[i] == EMPTY) { editCell(i); return; }
            final boolean mine = i >= 14;
            new AlertDialog.Builder(MotionActivity.this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setItems(new String[]{"Editar este color", mine ? "Quitar de Mis colores" : "Volver al color original"}, (d, w) -> {
                        if (w == 0) editCell(i);
                        else { cols[i] = DEFAULT_COLS[i]; savePalette(cols); }
                    }).show();
        }

        /** Abre el mezclador para esta casilla; al aceptar se guarda ahí y se usa. */
        private void editCell(final int i) {
            int[] cols = paletteCols();
            int start = cols[i] == EMPTY ? (targetFill ? st.fill : st.stroke) : cols[i];
            openMixer(start, i >= 14 ? "Mis colores" : "Editar color", c -> {
                int[] cc = paletteCols();
                cc[i] = c;
                savePalette(cc);
                setColor(targetFill, c);
            }, null);
        }
    }

    /** Editor de la curva de aceleración: Bézier de (0,0) a (1,1) con dos puntos de control. */
    final class CurveView extends View {
        final float[] c = MDoc.LINEAR.clone();
        boolean on;
        int drag = -1;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path path = new android.graphics.Path();

        CurveView(Context ctx) { super(ctx); }

        void set(float[] e, boolean enabled) {
            if (drag >= 0) return;
            System.arraycopy(e, 0, c, 0, 4);
            on = enabled;
            invalidate();
        }

        private float pad() { return dp(12); }
        private float X(float v) { return pad() + v * (getWidth() - 2 * pad()); }
        private float Y(float v) { return getHeight() - pad() - (v + 0.25f) / 1.5f * (getHeight() - 2 * pad()); }
        private float vx(float x) { return (x - pad()) / (getWidth() - 2 * pad()); }
        private float vy(float y) { return (getHeight() - pad() - y) / (getHeight() - 2 * pad()) * 1.5f - 0.25f; }

        private float bz(float p1, float p2, float u) { float v = 1 - u; return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u; }

        @Override protected void onDraw(Canvas cv) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFF4F4F4);
            cv.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), dp(8), dp(8), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(0xFFD0D0D0);
            cv.drawRect(X(0), Y(1), X(1), Y(0), p);
            path.reset();
            for (int i = 0; i <= 48; i++) {
                float u = i / 48f, x = X(bz(c[0], c[2], u)), y = Y(bz(c[1], c[3], u));
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            int ink = on ? 0xFF5E35B1 : 0xFFBDBDBD;
            p.setColor(ink);
            p.setStrokeWidth(dp(2.5f));
            cv.drawPath(path, p);
            p.setStrokeWidth(dp(1.2f));
            p.setColor(on ? 0xFF9E9E9E : 0xFFDDDDDD);
            cv.drawLine(X(0), Y(0), X(c[0]), Y(c[1]), p);
            cv.drawLine(X(1), Y(1), X(c[2]), Y(c[3]), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? Ui.ORANGE : 0xFFDDDDDD);
            cv.drawCircle(X(c[0]), Y(c[1]), dp(7), p);
            cv.drawCircle(X(c[2]), Y(c[3]), dp(7), p);
            if (!on) {
                p.setColor(0xFF9E9E9E);
                p.setTextSize(dp(11));
                String t = "Sin interpolación";
                cv.drawText(t, getWidth() / 2f - p.measureText(t) / 2, getHeight() / 2f, p);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!on) return false;
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float d0 = (float) Math.hypot(x - X(c[0]), y - Y(c[1])), d1 = (float) Math.hypot(x - X(c[2]), y - Y(c[3]));
                    drag = Math.min(d0, d1) < dp(30) ? (d0 <= d1 ? 0 : 1) : -1;
                    if (drag >= 0) getParent().requestDisallowInterceptTouchEvent(true);
                    return drag >= 0;
                }
                case MotionEvent.ACTION_MOVE:
                    if (drag < 0) return false;
                    c[drag * 2] = Math.max(0, Math.min(1, vx(x)));
                    c[drag * 2 + 1] = Math.max(-0.25f, Math.min(1.25f, vy(y)));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (drag >= 0) { drag = -1; setEase(c.clone()); }
                    return true;
            }
            return true;
        }
    }

    /** Cuadrícula de 216 colores (como el panel de muestras clásico) más una fila de grises. */
    static final class ColorGrid extends View {
        interface Pick { void pick(int color); }
        final Pick pick;
        final Paint p = new Paint();

        ColorGrid(Context c, Pick pick) { super(c); this.pick = pick; }

        static int colorAt(int col, int row) {
            if (row == 12) { int v = Math.round(col * 255f / 17f); return 0xFF000000 | (v << 16) | (v << 8) | v; }
            int r = (row / 6) * 3 + col / 6, g = row % 6, b = col % 6;
            return 0xFF000000 | (r * 51 << 16) | (g * 51 << 8) | (b * 51);
        }

        @Override protected void onDraw(Canvas c) {
            float cw = getWidth() / 18f, ch = getHeight() / 13f;
            for (int row = 0; row < 13; row++)
                for (int col = 0; col < 18; col++) {
                    p.setColor(colorAt(col, row));
                    c.drawRect(col * cw, row * ch + (row == 12 ? ch * 0.15f : 0), (col + 1) * cw - 1, (row + 1) * ch - 1, p);
                }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                int col = (int) (e.getX() / (getWidth() / 18f)), row = (int) (e.getY() / (getHeight() / 13f));
                if (col >= 0 && col < 18 && row >= 0 && row < 13) pick.pick(colorAt(col, row));
            }
            return true;
        }
    }
}
