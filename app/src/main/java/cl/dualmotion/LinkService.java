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
    private void showStage() {
        if (MotionActivity.visible) return;
        Intent i = new Intent(this, MotionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startOrNotify(this, i, 21, "DUAL Motion", "Toca para mostrar la mesa de trabajo");
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
    static void startOrNotify(Context c, Intent i, int code, String title, String text) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
