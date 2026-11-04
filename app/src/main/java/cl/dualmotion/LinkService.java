package cl.dualmotion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Servicio en primer plano que mantiene la conexión entre los teléfonos aunque la pantalla de DUAL Motion
 * esté en segundo plano. Así el principal puede pedirle al extendido que muestre u oculte la mesa (moShow /
 * moHide), y los dos pueden actualizarse juntos (moUpdate). Los demás mensajes van a la pantalla abierta.
 */
public final class LinkService extends Service implements Net.Listener {

    static final String CHANNEL = "conexion", CH_ALERT = "avisos";
    private static LinkService inst;
    /** La pantalla abierta (MotionActivity) recibe aquí los mensajes. */
    private static Net.Listener client;
    private static boolean connected;
    private static String info = "";

    private Net net;
    private String role;

    static void start(Context c, String role) {
        Intent i = new Intent(c, LinkService.class).putExtra("role", role);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, LinkService.class));
    }

    static void attach(Net.Listener l) {
        client = l;
        if (l != null) l.onState(connected, info);
    }

    static void detach(Net.Listener l) { if (client == l) client = null; }

    static void send(JSONObject m) {
        LinkService s = inst;
        if (s != null && s.net != null) s.net.send(m);
    }

    static boolean isConnected() { return connected; }

    static String peer() { LinkService s = inst; return s != null && s.net != null ? s.net.peer() : null; }

    static void connectTo(String ip) { LinkService s = inst; if (s != null && s.net != null) s.net.connectTo(ip); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        inst = this;
        channels(this);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String r = i != null ? i.getStringExtra("role") : null;
        if (r == null) r = getSharedPreferences("dm", MODE_PRIVATE).getString("role", MotionActivity.EDIT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("DUAL Motion")
                .setContentText(MotionActivity.EDIT.equals(r) ? "Mesa de trabajo lista para el principal" : "Conectado con la mesa de trabajo")
                .setContentIntent(PendingIntent.getActivity(this, 1, new Intent(this, MotionActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, 0x10 /* FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE */);
            else startForeground(7, n);
        } catch (Exception e) {
            try { startForeground(7, n); } catch (Exception ignored) { }
        }
        if (net == null || !r.equals(role)) {
            if (net != null) net.stop();
            role = r;
            net = new Net(this, this);
            if (MotionActivity.EDIT.equals(r)) net.startHost();
            else net.startClient(getSharedPreferences("dm", MODE_PRIVATE).getString("ip", null));
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (net != null) net.stop();
        net = null;
        connected = false;
        if (inst == this) inst = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- mensajes
    @Override public void onState(boolean on, String inf) {
        connected = on;
        info = inf;
        Net.Listener l = client;
        if (l != null) l.onState(on, inf);
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "moShow": if (MotionActivity.EDIT.equals(role)) showStage(); return;
            case "moUpdate": Updater.fromRemote(this); return;
            case "moSnd": Sounds.onChunk(this, m); return;
            case "moQuit": if (client == null) { stopSelf(); return; } break; // sin pantalla abierta: solo se apaga
            case "moSndReq": Sounds.onRequest(this, m); return;
        }
        Net.Listener l = client;
        if (l != null) l.onMessage(m);
    }

    /** Extendido: trae la mesa de trabajo al frente (o deja un aviso si Android no lo permite). */
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Extendido: trae la mesa al frente. Se intenta siempre (en Xiaomi puede bloquearse en silencio aunque haya
     * permiso); si al rato no apareció, sale un aviso que se abre a pantalla completa (o se toca).
     */
    private void showStage() {
        if (MotionActivity.visible) return;
        final Intent i = new Intent(this, MotionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Exception ignored) { }
        main.postDelayed(() -> { if (!MotionActivity.visible) stageNotice(i); }, 1500);
    }

    private void stageNotice(Intent i) {
        channels(this);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        PendingIntent pi = PendingIntent.getActivity(this, 21, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("DUAL Motion")
                .setContentText("Toca para mostrar la mesa de trabajo")
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(21, n);
    }

    static void cancelStageNotice(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(21);
    }

    /** Al encender el teléfono: el extendido queda escuchando, para que la mesa aparezca al abrir el principal. */
    public static final class Boot extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String role = c.getSharedPreferences("dm", MODE_PRIVATE).getString("role", null);
            if (MotionActivity.EDIT.equals(role)) start(c, role);
        }
    }

    // ---------------------------------------------------------------- avisos
    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel a = new NotificationChannel(CHANNEL, "Conexión entre teléfonos", NotificationManager.IMPORTANCE_LOW);
        a.setShowBadge(false);
        nm.createNotificationChannel(a);
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Avisos de DUAL Motion", NotificationManager.IMPORTANCE_HIGH));
    }

    /**
     * Abre una pantalla desde segundo plano. Android solo lo permite si la app puede "mostrarse sobre otras apps"
     * o está a la vista; si no, deja un aviso para tocar.
     */
    private static android.view.View blipView;
    private static final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Android 15: "mostrar sobre otras apps" solo deja abrir una pantalla desde segundo plano si la app tiene una
     * ventana visible en ese momento. Se pone una de 1 px un par de segundos (no se nota).
     */
    private static void blip(Context c) {
        try {
            android.view.WindowManager wm = (android.view.WindowManager) c.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
            if (blipView == null) {
                blipView = new android.view.View(c.getApplicationContext());
                blipView.setBackgroundColor(0x01000000);
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(1, 1,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                wm.addView(blipView, lp);
            }
            ui.removeCallbacksAndMessages(wm);
            ui.postAtTime(() -> {
                try { if (blipView != null) wm.removeView(blipView); } catch (Exception ignored) { }
                blipView = null;
            }, wm, android.os.SystemClock.uptimeMillis() + 3000);
        } catch (Exception ignored) { blipView = null; }
    }

    static void startOrNotify(Context c, Intent i, int code, String title, String text) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!MotionActivity.visible && Settings.canDrawOverlays(c)) {
            ui.post(() -> {
                blip(c);
                ui.postDelayed(() -> { try { c.startActivity(i); } catch (Exception ignored) { } }, 150);
            });
            return;
        }
        if (MotionActivity.visible || Settings.canDrawOverlays(c)) {
            try { c.startActivity(i); return; } catch (Exception ignored) { }
        }
        channels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        nm.notify(code, n);
    }
}
