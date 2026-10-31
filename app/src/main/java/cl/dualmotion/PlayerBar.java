package cl.dualmotion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Barra de reproducción, al estilo del control de video de ChronOS: fondo oscuro, botones redondos,
 * el de reproducir grande y rojo, y una barra de avance roja que mueve el cabezal (con marcas en los claves
 * de la capa actual). A la derecha: fotograma, velocidad (toca para cambiarla), repetir y papel cebolla.
 */
public final class PlayerBar extends View {

    public interface Host {
        MDoc doc();
        State st();
        int shownFrame();
        boolean playing();
        boolean loop();
        void select(int layer, int frame);
        void play();
        void toggleLoop();
        void toggleOnion();
        void fpsMenu();
    }

    static final int RED = 0xFFFF0033, CARD = 0xFF2A2A2A, BG = 0xFF141414;
    private static final int B_FIRST = 0, B_PREV = 1, B_PLAY = 2, B_NEXT = 3, B_LAST = 4, B_FPS = 5, B_LOOP = 6, B_ONION = 7;

    private final Host host;
    private final float dp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] btn = new RectF[8];
    private final RectF track = new RectF(), r = new RectF();
    private int pressed = -1;
    private boolean seeking;

    public PlayerBar(Context c, Host h) {
        super(c);
        host = h;
        dp = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i < btn.length; i++) btn[i] = new RectF();
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setElevation(6 * dp);
    }

    private static String time(int frame, int fps) {
        double s = frame / (double) Math.max(1, fps);
        int m = (int) (s / 60);
        return String.format(Locale.US, "%d:%04.1f", m, s - m * 60).replace('.', ',');
    }

    // ---------------------------------------------------------------- medidas
    private void layout(int w, int h) {
        float cy = h / 2f, x = 10 * dp;
        float sm = 15 * dp, big = 20 * dp, gap = 5 * dp;
        float[] rad = {sm, sm, big, sm, sm};
        for (int i = 0; i <= B_LAST; i++) {
            btn[i].set(x, cy - rad[i], x + rad[i] * 2, cy + rad[i]);
            x += rad[i] * 2 + gap;
        }
        // derecha: papel cebolla, repetir, velocidad
        float xr = w - 10 * dp;
        btn[B_ONION].set(xr - 2 * sm, cy - sm, xr, cy + sm);
        xr -= 2 * sm + gap;
        btn[B_LOOP].set(xr - 2 * sm, cy - sm, xr, cy + sm);
        xr -= 2 * sm + gap;
        tp.setTextSize(12 * dp);
        float fw = tp.measureText("60 fps") + 18 * dp;
        btn[B_FPS].set(xr - fw, cy - 13 * dp, xr, cy + 13 * dp);
        xr -= fw + 12 * dp;
        // barra de avance a todo lo ancho que queda; el tiempo y el fotograma van en letra chica encima
        float t0 = x + 8 * dp, t1 = Math.max(t0 + 40 * dp, xr);
        track.set(t0, cy + 6 * dp, t1, cy + 10 * dp);
    }

    // ---------------------------------------------------------------- dibujo
    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        layout(w, h);
        MDoc d = host.doc();
        int f = host.shownFrame(), len = d.length();
        r.set(0, 0, w, h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(BG);
        c.drawRoundRect(r, h / 2f, h / 2f, p);

        for (int i = 0; i <= B_LAST; i++) {
            RectF b = btn[i];
            p.setColor(i == B_PLAY ? (pressed == i ? 0xFFCC0029 : RED) : (pressed == i ? 0xFF3D3D3D : CARD));
            c.drawCircle(b.centerX(), b.centerY(), b.width() / 2, p);
            icon(c, i, b);
        }

        // tiempo y fotograma
        tp.setTextSize(11 * dp);
        tp.setColor(0xFFDDDDDD);
        float above = track.top - 7 * dp;
        String tm = time(f, d.fps) + "  /  " + time(len, d.fps);
        c.drawText(tm, track.left, above, tp);
        String fr = "Fotograma " + (f + 1) + " de " + len;
        c.drawText(fr, track.right - tp.measureText(fr), above, tp);
        tp.setTextSize(12 * dp);
        float ty = h / 2f + tp.getTextSize() * 0.36f;

        // barra de avance
        float cy = track.centerY();
        p.setColor(0xFF4A4A4A);
        r.set(track.left, cy - 2 * dp, track.right, cy + 2 * dp);
        c.drawRoundRect(r, 2 * dp, 2 * dp, p);
        float pos = len <= 1 ? 0 : Math.min(1, f / (float) (len - 1));
        float px = track.left + pos * track.width();
        p.setColor(RED);
        r.set(track.left, cy - 2 * dp, px, cy + 2 * dp);
        c.drawRoundRect(r, 2 * dp, 2 * dp, p);
        // marcas de los fotogramas clave de la capa actual
        MDoc.Layer L = d.layer(host.st().layer);
        if (L != null && !L.audio && len > 1) {
            p.setColor(0xCCFFFFFF);
            for (int k : L.keys.keySet()) {
                if (k >= len) continue;
                float kx = track.left + k / (float) (len - 1) * track.width();
                c.drawCircle(kx, cy, 1.8f * dp, p);
            }
        }
        p.setColor(RED);
        c.drawCircle(px, cy, (seeking ? 9 : 7) * dp, p);

        // velocidad
        RectF b = btn[B_FPS];
        p.setColor(pressed == B_FPS ? 0xFF3D3D3D : CARD);
        c.drawRoundRect(b, b.height() / 2, b.height() / 2, p);
        tp.setColor(0xFFFFFFFF);
        String fs = d.fps + " fps";
        c.drawText(fs, b.centerX() - tp.measureText(fs) / 2, ty, tp);

        // repetir y papel cebolla (encendidos en rojo)
        boolean lp = host.loop(), on = host.st().onion;
        b = btn[B_LOOP];
        p.setColor(pressed == B_LOOP ? 0xFF3D3D3D : CARD);
        c.drawCircle(b.centerX(), b.centerY(), b.width() / 2, p);
        loopIcon(c, b, lp ? RED : 0xFF9E9E9E);
        b = btn[B_ONION];
        p.setColor(pressed == B_ONION ? 0xFF3D3D3D : CARD);
        c.drawCircle(b.centerX(), b.centerY(), b.width() / 2, p);
        onionIcon(c, b, on);
    }

    private void icon(Canvas c, int i, RectF b) {
        float cx = b.centerX(), cy = b.centerY(), s = (i == B_PLAY ? 8.5f : 5.5f) * dp;
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.FILL);
        switch (i) {
            case B_PLAY:
                if (host.playing()) {
                    c.drawRect(cx - s * 0.75f, cy - s, cx - s * 0.2f, cy + s, p);
                    c.drawRect(cx + s * 0.2f, cy - s, cx + s * 0.75f, cy + s, p);
                } else tri(c, cx + s * 0.15f, cy, s, true);
                break;
            case B_PREV: tri(c, cx - s * 0.2f, cy, s, false); c.drawRect(cx + s * 0.55f, cy - s, cx + s * 0.85f, cy + s, p); break;
            case B_NEXT: tri(c, cx + s * 0.2f, cy, s, true); c.drawRect(cx - s * 0.85f, cy - s, cx - s * 0.55f, cy + s, p); break;
            case B_FIRST: c.drawRect(cx - s * 1.05f, cy - s, cx - s * 0.7f, cy + s, p); tri(c, cx - s * 0.05f, cy, s * 0.8f, false); tri(c, cx + s * 0.9f, cy, s * 0.8f, false); break;
            case B_LAST: c.drawRect(cx + s * 0.7f, cy - s, cx + s * 1.05f, cy + s, p); tri(c, cx + s * 0.05f, cy, s * 0.8f, true); tri(c, cx - s * 0.9f, cy, s * 0.8f, true); break;
        }
    }

    private void tri(Canvas c, float cx, float cy, float s, boolean right) {
        Path t = new Path();
        if (right) { t.moveTo(cx - s * 0.6f, cy - s); t.lineTo(cx + s * 0.9f, cy); t.lineTo(cx - s * 0.6f, cy + s); }
        else { t.moveTo(cx + s * 0.6f, cy - s); t.lineTo(cx - s * 0.9f, cy); t.lineTo(cx + s * 0.6f, cy + s); }
        t.close();
        c.drawPath(t, p);
    }

    private void loopIcon(Canvas c, RectF b, int color) {
        float cx = b.centerX(), cy = b.centerY(), rr = 6.5f * dp;
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * dp);
        p.setStrokeCap(Paint.Cap.ROUND);
        r.set(cx - rr, cy - rr, cx + rr, cy + rr);
        c.drawArc(r, 300, 290, false, p);
        p.setStyle(Paint.Style.FILL);
        Path a = new Path(); // punta de flecha al final del arco
        float ax = cx + rr * (float) Math.cos(Math.toRadians(300)), ay = cy + rr * (float) Math.sin(Math.toRadians(300));
        a.moveTo(ax + 3.5f * dp, ay - 0.5f * dp);
        a.lineTo(ax - 2 * dp, ay - 3.5f * dp);
        a.lineTo(ax - 1 * dp, ay + 3 * dp);
        a.close();
        c.drawPath(a, p);
    }

    private void onionIcon(Canvas c, RectF b, boolean on) {
        float cx = b.centerX(), cy = b.centerY(), rr = 5.5f * dp;
        p.setStyle(Paint.Style.FILL);
        p.setColor(on ? 0x99E04848 : 0x55FFFFFF);
        c.drawCircle(cx - 3.5f * dp, cy, rr, p);
        p.setColor(on ? 0x9930A060 : 0x55FFFFFF);
        c.drawCircle(cx + 3.5f * dp, cy, rr, p);
        p.setColor(on ? 0xFFFFFFFF : 0xFF9E9E9E);
        c.drawCircle(cx, cy, rr * 0.75f, p);
    }

    // ---------------------------------------------------------------- toque
    private int hit(float x, float y) {
        float t = 6 * dp;
        for (int i = 0; i < btn.length; i++) {
            RectF b = btn[i];
            if (x >= b.left - t && x <= b.right + t && y >= b.top - t * 2 && y <= b.bottom + t * 2) return i;
        }
        return -1;
    }

    private void seekTo(float x) {
        int len = host.doc().length();
        float u = Math.max(0, Math.min(1, (x - track.left) / Math.max(1, track.width())));
        int f = Math.round(u * (len - 1));
        if (f != host.st().frame) host.select(host.st().layer, f);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (x >= track.left - 10 * dp && x <= track.right + 10 * dp && hit(x, y) < 0 && !host.playing()) {
                    seeking = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    seekTo(x);
                } else pressed = hit(x, y);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (seeking) seekTo(x);
                return true;
            case MotionEvent.ACTION_UP: {
                int b = pressed;
                pressed = -1;
                boolean was = seeking;
                seeking = false;
                invalidate();
                if (was || b < 0 || hit(x, y) != b) return true;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                State st = host.st();
                int len = host.doc().length();
                switch (b) {
                    case B_FIRST: host.select(st.layer, 0); break;
                    case B_PREV: host.select(st.layer, Math.max(0, st.frame - 1)); break;
                    case B_PLAY: host.play(); break;
                    case B_NEXT: host.select(st.layer, st.frame + 1); break;
                    case B_LAST: host.select(st.layer, len - 1); break;
                    case B_FPS: host.fpsMenu(); break;
                    case B_LOOP: host.toggleLoop(); break;
                    case B_ONION: host.toggleOnion(); break;
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                seeking = false;
                invalidate();
                return true;
        }
        return true;
    }
}
