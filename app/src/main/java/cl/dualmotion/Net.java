package cl.dualmotion;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Conexión entre los dos teléfonos por el WiFi local (TCP, una línea JSON por mensaje).
 * El extendido (dueño del documento) espera conexiones y se anuncia por difusión UDP cada 2 s;
 * el principal escucha el anuncio y se conecta. También se puede escribir la IP a mano.
 * Todo lo que se envía sale por UNA sola fila, en orden.
 * Puertos propios (47830/47831) para no chocar con ChronOS (47820/47821).
 */
public final class Net {
    public interface Listener {
        void onMessage(JSONObject m);
        void onState(boolean connected, String info);
    }

    public static final int PORT = 47830, BEACON = 47831;
    private static final String HELLO = "DUALMOTION";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService out = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private final WifiManager.MulticastLock lock;
    private final Context ctx;

    private volatile boolean running, host, connecting;
    private volatile Socket sock;
    private volatile BufferedWriter writer;
    private volatile ServerSocket server;
    private volatile DatagramSocket beaconIn;
    private volatile String manualIp;
    private volatile long lastRead;

    public Net(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        lock = wm != null ? wm.createMulticastLock("dualmotion") : null;
        if (lock != null) lock.setReferenceCounted(false);
    }

    public boolean connected() { return sock != null; }

    public String peer() {
        Socket s = sock;
        return s == null ? null : s.getInetAddress().getHostAddress();
    }

    // ---------------------------------------------------------------- arrancar / detener
    public void startHost() {
        stop();
        running = true; host = true;
        if (lock != null) lock.acquire();
        state(false, "Esperando al principal…");
        new Thread(this::acceptLoop, "dm-accept").start();
        new Thread(this::beaconLoop, "dm-beacon").start();
        main.postDelayed(beat, 3000);
    }

    public void startClient(String savedIp) {
        stop();
        running = true; host = false;
        manualIp = savedIp;
        if (lock != null) lock.acquire();
        state(false, "Buscando el extendido…");
        new Thread(this::listenBeacon, "dm-listen").start();
        new Thread(this::retryLoop, "dm-retry").start();
        main.postDelayed(beat, 3000);
    }

    /** Conectarse a una IP escrita a mano. */
    public void connectTo(String ip) {
        manualIp = ip;
        if (!host && sock == null) new Thread(() -> connect(ip), "dm-connect").start();
    }

    public void stop() {
        running = false;
        main.removeCallbacks(beat);
        closeSock();
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        try { if (beaconIn != null) beaconIn.close(); } catch (Exception ignored) { }
        server = null; beaconIn = null;
        if (lock != null) try { lock.release(); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- enviar
    public void send(JSONObject m) {
        final String line = m.toString();
        out.execute(() -> {
            BufferedWriter w = writer;
            if (w == null) return;
            try {
                w.write(line);
                w.write('\n');
                w.flush();
            } catch (Exception e) {
                closeSock();
            }
        });
    }

    /** Latido: mantiene viva la conexión y detecta cuando se corta. */
    private final Runnable beat = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (sock != null) {
                if (System.currentTimeMillis() - lastRead > 12000) closeSock();
                else {
                    JSONObject m = new JSONObject();
                    try { m.put("t", "hb"); } catch (Exception ignored) { }
                    send(m);
                }
            }
            main.postDelayed(this, 3000);
        }
    };

    // ---------------------------------------------------------------- extendido: espera y se anuncia
    private void acceptLoop() {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT));
            server = ss;
            while (running) {
                Socket s = ss.accept();
                closeSock(); // una conexión nueva reemplaza a la anterior
                attach(s);
            }
        } catch (Exception e) {
            if (running) main.postDelayed(() -> { if (running && host) new Thread(this::acceptLoop).start(); }, 2000);
        }
    }

    private void beaconLoop() {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            byte[] b = HELLO.getBytes(StandardCharsets.UTF_8);
            while (running) {
                if (sock == null) {
                    for (InetAddress a : broadcasts()) {
                        try { ds.send(new DatagramPacket(b, b.length, a, BEACON)); } catch (Exception ignored) { }
                    }
                }
                Thread.sleep(2000);
            }
        } catch (Exception ignored) { }
    }

    private static ArrayList<InetAddress> broadcasts() {
        ArrayList<InetAddress> to = new ArrayList<>();
        try {
            to.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses())
                    if (ia.getBroadcast() != null) to.add(ia.getBroadcast());
            }
        } catch (Exception ignored) { }
        return to;
    }

    // ---------------------------------------------------------------- principal: busca y se conecta
    private void listenBeacon() {
        try {
            DatagramSocket ds = new DatagramSocket(null);
            ds.setReuseAddress(true);
            ds.setBroadcast(true);
            ds.bind(new InetSocketAddress(BEACON));
            beaconIn = ds;
            byte[] buf = new byte[64];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ds.receive(p);
                String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (HELLO.equals(s) && sock == null && !connecting) connect(p.getAddress().getHostAddress());
            }
        } catch (Exception ignored) { }
    }

    /** Si el anuncio no llega (algunas redes lo bloquean), prueba con la última IP conocida. */
    private void retryLoop() {
        while (running) {
            String ip = manualIp;
            if (sock == null && !connecting && ip != null && !ip.isEmpty()) connect(ip);
            try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
        }
    }

    private synchronized void connect(String ip) {
        if (sock != null || connecting || !running) return;
        connecting = true;
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ip, PORT), 3000);
            manualIp = ip;
            attach(s);
        } catch (Exception ignored) {
        } finally {
            connecting = false;
        }
    }

    // ---------------------------------------------------------------- conexión activa
    private void attach(Socket s) throws Exception {
        s.setTcpNoDelay(true);
        s.setSoTimeout(15000);
        writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        sock = s;
        lastRead = System.currentTimeMillis();
        final String ip = s.getInetAddress().getHostAddress();
        state(true, ip);
        new Thread(() -> readLoop(s), "dm-read").start();
    }

    private void readLoop(Socket s) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lastRead = System.currentTimeMillis();
                final JSONObject m;
                try { m = new JSONObject(line); } catch (Exception e) { continue; }
                if ("hb".equals(m.optString("t"))) continue;
                main.post(() -> listener.onMessage(m));
            }
        } catch (Exception ignored) { }
        if (sock == s) closeSock();
    }

    private void closeSock() {
        Socket s = sock;
        sock = null;
        writer = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) { }
            if (running) state(false, host ? "Esperando al principal…" : "Buscando el extendido…");
        }
    }

    private void state(boolean on, String info) { main.post(() -> listener.onState(on, info)); }

    /** IP de este teléfono en el WiFi (para escribirla en el otro). */
    public static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return "?";
    }
}
