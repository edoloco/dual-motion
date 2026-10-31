package cl.dualmotion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Línea de tiempo: nombres de capas a la izquierda (ojo, candado, contorno) y la grilla de fotogramas.
 * Punto negro = fotograma clave; círculo vacío = clave vacío; gris = fotogramas con contenido;
 * rectángulo pequeño = fin del tramo. El cabezal rojo marca el fotograma actual.
 */
public final class TimelineView extends View {

    public interface Host {
        MDoc doc();
        State st();
        int shownFrame();
        void select(int layer, int frame);
        void toggle(int layer, String which);
        void addLayer();
        void delLayer();
        void frameMenu(int layer, int frame);
        void rename(int layer);
    }

    private final Host host;
    private final float dp;
    private final int headerH, rowH, cellW, nameW, iconW;
    private float scrollX, scrollY;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int slop;

    public TimelineView(Context c, Host h) {
        super(c);
        host = h;
        dp = c.getResources().getDisplayMetrics().density;
        headerH = Math.round(26 * dp);
        rowH = Math.round(32 * dp);
        cellW = Math.round(15 * dp);
        nameW = Math.round(176 * dp);
        iconW = Math.round(22 * dp);
        tp.setTextSize(13 * dp);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    // ---------------------------------------------------------------- geometría
    private int gridW() { return Math.max(1, getWidth() - nameW); }
    private int frameAt(float x) { return (int) Math.floor((x - nameW + scrollX) / cellW); }
    private int rowAt(float y) { return (int) Math.floor((y - headerH + scrollY) / rowH); }
    private float cellX(int f) { return nameW + f * cellW - scrollX; }

    private void clampScroll() {
        MDoc d = host.doc();
        int frames = Math.max(d.length(), host.st().frame + 1) + 60;
        scrollX = Math.max(0, Math.min(scrollX, frames * cellW - gridW()));
        scrollY = Math.max(0, Math.min(scrollY, d.layers.size() * rowH - (getHeight() - headerH)));
    }

    /** Desplaza la grilla para que se vea el fotograma f. */
    public void ensureVisible(int f) {
        float x0 = f * cellW, x1 = x0 + cellW;
        if (x0 < scrollX) scrollX = Math.max(0, x0 - cellW * 2);
        else if (x1 > scrollX + gridW()) scrollX = x1 - gridW() + cellW * 2;
        clampScroll();
        invalidate();
    }

    // ---------------------------------------------------------------- dibujo
    @Override protected void onDraw(Canvas c) {
        MDoc d = host.doc();
        State st = host.st();
        int cur = host.shownFrame();
        clampScroll();
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFFFFFFFF);

        int f0 = Math.max(0, (int) (scrollX / cellW)), f1 = f0 + gridW() / cellW + 2;

        // ---- filas
        c.save();
        c.clipRect(0, headerH, w, h);
        for (int l = 0; l < d.layers.size(); l++) {
            float y = headerH + l * rowH - scrollY;
            if (y > h || y + rowH < headerH) continue;
            MDoc.Layer L = d.layers.get(l);
            drawFrames(c, L, l, y, f0, f1, st, cur);
            drawName(c, L, l == st.layer, y);
            p.setColor(0xFFDDDDDD);
            p.setStrokeWidth(dp);
            c.drawLine(0, y + rowH, w, y + rowH, p);
        }
        c.restore();

        // ---- regla de fotogramas y cabezal
        drawRuler(c, f0, f1, cur, w);
        p.setColor(0xFFBDBDBD);
        p.setStrokeWidth(dp);
        c.drawLine(nameW, 0, nameW, h, p);
        float px = cellX(cur) + cellW / 2f;
        if (px >= nameW) {
            p.setColor(Ui.RED);
            p.setStrokeWidth(1.5f * dp);
            c.drawLine(px, headerH, px, h, p);
        }
    }

    private void drawFrames(Canvas c, MDoc.Layer L, int l, float y, int f0, int f1, State st, int cur) {
        c.save();
        c.clipRect(nameW, y, getWidth(), y + rowH);
        for (int f = f0; f <= f1; f++) {
            float x = cellX(f);
            int ki = MDoc.keyIndex(L, f);
            boolean inSpan = ki >= 0;
            MDoc.Key kk = inSpan ? L.keys.get(ki) : null;
            boolean content = kk != null && !kk.shapes.isEmpty();
            boolean tw = kk != null && kk.tween;
            int bg = inSpan ? (tw ? 0xFFCDC2F2 : content ? 0xFFD9D9D9 : 0xFFFFFFFF)
                    : ((f + 1) % 5 == 0 ? 0xFFEDEDED : 0xFFF8F8F8);
            p.setStyle(Paint.Style.FILL);
            p.setColor(bg);
            c.drawRect(x, y, x + cellW, y + rowH, p);
            boolean selected = l == st.layer && f == st.frame;
            if (selected) { p.setColor(0xFF303030); c.drawRect(x, y + dp, x + cellW, y + rowH - dp, p); }
            // líneas: en los tramos solo al empezar un clave
            p.setColor(inSpan ? 0xFF9E9E9E : 0xFFE3E3E3);
            p.setStrokeWidth(dp);
            if (!inSpan || L.keys.containsKey(f)) c.drawLine(x, y, x, y + rowH, p);
            float mx = x + cellW / 2f, my = y + rowH - 9 * dp;
            int ink = selected ? 0xFFFFFFFF : 0xFF000000;
            if (tw) { // interpolación de movimiento: flecha hasta el clave siguiente (punteada si falta)
                Integer nk = L.keys.higherKey(ki);
                boolean ok = nk != null;
                p.setColor(ink);
                p.setStrokeWidth(1.2f * dp);
                p.setStyle(Paint.Style.STROKE);
                float x0 = f == ki ? mx + 4 * dp : x, x1 = ok && f == nk - 1 ? x + cellW - 2 * dp : x + cellW;
                if (ok) c.drawLine(x0, my, x1, my, p);
                else for (float xx = x0; xx < x1; xx += 4 * dp) c.drawLine(xx, my, Math.min(xx + 2 * dp, x1), my, p);
                if (ok && f == nk - 1) {
                    c.drawLine(x1, my, x1 - 4 * dp, my - 3 * dp, p);
                    c.drawLine(x1, my, x1 - 4 * dp, my + 3 * dp, p);
                }
            }
            if (L.keys.containsKey(f)) {
                p.setColor(ink);
                p.setStyle(content ? Paint.Style.FILL : Paint.Style.STROKE);
                p.setStrokeWidth(1.3f * dp);
                c.drawCircle(mx, my, 3.6f * dp, p);
            } else if (inSpan && !tw && (f == L.len - 1 || L.keys.containsKey(f + 1))) {
                // fin del tramo
                p.setColor(ink);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.1f * dp);
                c.drawRect(mx - 3 * dp, my - 4.5f * dp, mx + 3 * dp, my + 3.5f * dp, p);
            }
            if (f == L.len - 1) { // fin de la capa
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF9E9E9E);
                c.drawRect(x + cellW - dp, y, x + cellW, y + rowH, p);
            }
        }
        c.restore();
        p.setStyle(Paint.Style.FILL);
    }

    private void drawName(Canvas c, MDoc.Layer L, boolean selected, float y) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(selected ? Ui.GREEN : 0xFFFFFFFF);
        c.drawRect(0, y, nameW, y + rowH, p);
        int ink = selected ? 0xFFFFFFFF : Ui.INK;
        tp.setColor(ink);
        tp.setFakeBoldText(selected);
        float textMax = nameW - 3 * iconW - 14 * dp;
        String name = L.name;
        while (name.length() > 1 && tp.measureText(name) > textMax) name = name.substring(0, name.length() - 1);
        if (!name.equals(L.name)) name = name + "…";
        c.drawText(name, 10 * dp, y + rowH / 2f + tp.getTextSize() * 0.35f, tp);
        float cy = y + rowH / 2f;
        float ex = nameW - 3 * iconW + iconW / 2f, lx = ex + iconW, ox = lx + iconW;
        // ojo: punto = visible, ✕ rojo = oculta
        if (L.vis) { p.setColor(ink); c.drawCircle(ex, cy, 2.4f * dp, p); }
        else {
            p.setColor(selected ? 0xFFFFFFFF : Ui.RED);
            p.setStrokeWidth(2 * dp);
            c.drawLine(ex - 4 * dp, cy - 4 * dp, ex + 4 * dp, cy + 4 * dp, p);
            c.drawLine(ex - 4 * dp, cy + 4 * dp, ex + 4 * dp, cy - 4 * dp, p);
        }
        // candado
        if (L.lock) lockIcon(c, lx, cy, ink);
        else { p.setColor(selected ? 0xAAFFFFFF : 0xFF9E9E9E); c.drawCircle(lx, cy, 2.4f * dp, p); }
        // contorno: cuadrado del color de la capa (hueco = modo contorno)
        p.setColor(L.color);
        p.setStyle(L.out ? Paint.Style.STROKE : Paint.Style.FILL);
        p.setStrokeWidth(1.6f * dp);
        r.set(ox - 5 * dp, cy - 5 * dp, ox + 5 * dp, cy + 5 * dp);
        c.drawRect(r, p);
        if (selected) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp); p.setColor(0xFFFFFFFF); c.drawRect(r, p); }
        p.setStyle(Paint.Style.FILL);
    }

    private void lockIcon(Canvas c, float x, float y, int color) {
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        c.drawRect(x - 4.5f * dp, y - 1 * dp, x + 4.5f * dp, y + 5 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f * dp);
        r.set(x - 3 * dp, y - 6 * dp, x + 3 * dp, y + 2 * dp);
        c.drawArc(r, 180, 180, false, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawRuler(Canvas c, int f0, int f1, int cur, int w) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFEFEFEF);
        c.drawRect(0, 0, w, headerH, p);
        // botones de capas y encabezados de columnas
        tp.setColor(Ui.INK);
        tp.setFakeBoldText(true);
        float ty = headerH / 2f + tp.getTextSize() * 0.38f;
        tp.setTextSize(17 * dp);
        c.drawText("＋", 8 * dp, ty + 2 * dp, tp);
        c.drawText("−", 38 * dp, ty + 2 * dp, tp);
        tp.setTextSize(13 * dp);
        float cy = headerH / 2f;
        float ex = nameW - 3 * iconW + iconW / 2f, lx = ex + iconW, ox = lx + iconW;
        Path eye = new Path();
        eye.moveTo(ex - 6 * dp, cy);
        eye.quadTo(ex, cy - 6 * dp, ex + 6 * dp, cy);
        eye.quadTo(ex, cy + 6 * dp, ex - 6 * dp, cy);
        p.setColor(Ui.INK);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.3f * dp);
        c.drawPath(eye, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(ex, cy, 2 * dp, p);
        lockIcon(c, lx, cy, Ui.INK);
        p.setStyle(Paint.Style.STROKE);
        c.drawRect(ox - 5 * dp, cy - 5 * dp, ox + 5 * dp, cy + 5 * dp, p);
        p.setStyle(Paint.Style.FILL);

        c.save();
        c.clipRect(nameW, 0, w, headerH);
        tp.setTextSize(11 * dp);
        tp.setFakeBoldText(false);
        for (int f = f0; f <= f1; f++) {
            float x = cellX(f);
            p.setColor(0xFFBDBDBD);
            p.setStrokeWidth(dp);
            float tick = (f + 1) % 5 == 0 ? 8 * dp : 4 * dp;
            c.drawLine(x, headerH - tick, x, headerH, p);
            if (f == 0 || (f + 1) % 5 == 0) {
                String s = String.valueOf(f + 1);
                tp.setColor(0xFF616161);
                c.drawText(s, x + cellW / 2f - tp.measureText(s) / 2, headerH - 10 * dp, tp);
            }
        }
        // cabezal
        float x = cellX(cur);
        String s = String.valueOf(cur + 1);
        float tw = Math.max(cellW, tp.measureText(s) + 6 * dp);
        r.set(x + cellW / 2f - tw / 2, 2 * dp, x + cellW / 2f + tw / 2, headerH - 2 * dp);
        p.setColor(Ui.RED);
        c.drawRoundRect(r, 3 * dp, 3 * dp, p);
        tp.setColor(0xFFFFFFFF);
        tp.setFakeBoldText(true);
        c.drawText(s, r.centerX() - tp.measureText(s) / 2, r.centerY() + tp.getTextSize() * 0.36f, tp);
        c.restore();
        tp.setTextSize(13 * dp);
        tp.setFakeBoldText(false);
        p.setColor(0xFFBDBDBD);
        p.setStrokeWidth(dp);
        c.drawLine(0, headerH, w, headerH, p);
    }

    // ---------------------------------------------------------------- toque
    private float downX, downY, lastX, lastY;
    private int zone; // 0 regla, 1 nombres, 2 grilla, 3 botones de capa
    private boolean moved, longDone;
    private final Runnable longPress = this::onLong;

    private void onLong() {
        if (moved) return;
        longDone = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        int l = rowAt(downY);
        if (l < 0 || l >= host.doc().layers.size()) return;
        if (zone == 2) { int f = frameAt(downX); if (f >= 0) { host.select(l, f); host.frameMenu(l, f); } }
        else if (zone == 1) host.rename(l);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = x; downY = lastY = y;
                moved = longDone = false;
                zone = y < headerH ? (x < nameW ? 3 : 0) : (x < nameW ? 1 : 2);
                if (zone == 0) scrub(x);
                if (zone == 1 || zone == 2) postDelayed(longPress, 500);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!moved && Math.hypot(x - downX, y - downY) > slop) { moved = true; removeCallbacks(longPress); }
                if (moved && !longDone) {
                    if (zone == 0) scrub(x);
                    else if (zone == 1 || zone == 2) {
                        if (zone == 2) scrollX -= x - lastX;
                        scrollY -= y - lastY;
                        clampScroll();
                        invalidate();
                    }
                }
                lastX = x; lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (!moved && !longDone) tap(x, y);
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                return true;
        }
        return true;
    }

    private void scrub(float x) {
        int f = Math.max(0, frameAt(Math.max(nameW, x)));
        if (f != host.st().frame) host.select(host.st().layer, f);
    }

    private void tap(float x, float y) {
        if (zone == 3) {
            if (x < 32 * dp) host.addLayer();
            else if (x < 62 * dp) host.delLayer();
            return;
        }
        if (zone == 0) return;
        int l = rowAt(y);
        if (l < 0 || l >= host.doc().layers.size()) return;
        if (zone == 1) {
            float ex0 = nameW - 3 * iconW;
            if (x >= ex0) {
                int col = (int) ((x - ex0) / iconW);
                host.toggle(l, col == 0 ? "vis" : col == 1 ? "lock" : "out");
            } else host.select(l, host.st().frame);
            return;
        }
        int f = frameAt(x);
        if (f >= 0) host.select(l, f);
    }
}
