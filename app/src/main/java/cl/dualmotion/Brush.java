package cl.dualmotion;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;

import java.util.ArrayList;

/**
 * Pincel y borrador como en Flash:
 *  - El pincel pinta RELLENO (una forma de relleno sin línea, que el cubo puede recolorear y el borrador cortar),
 *    con los modos Normal, Pintar rellenos (queda bajo las líneas), Pintar detrás y Pintar dentro.
 *  - El borrador borra por donde pasa, como un pincel: recorta rellenos y corta líneas. Modos: Normal, Solo
 *    rellenos, Solo líneas (el grifo, que borra la figura entera, lo maneja la mesa).
 * Los rellenos se calculan en una imagen (trazo → máscara) y vuelven a vector con los contornos de Fill.
 */
final class Brush {
    private Brush() { }

    static final int NORMAL = 0, FILLS = 1, BEHIND = 2, INSIDE = 3;          // modos del pincel
    static final String[] BRUSH_MODES = {"Normal", "Pintar rellenos", "Pintar detrás", "Pintar dentro"};
    static final int E_NORMAL = 0, E_FILLS = 1, E_LINES = 2, E_FAUCET = 3;    // modos del borrador
    static final String[] ERASE_MODES = {"Normal", "Solo rellenos", "Solo líneas", "Grifo (figura entera)"};

    private static final int MAX = 1600;

    /** Imagen de trabajo: zona (coordenadas de la capa) y escala. */
    private static final class Grid {
        final RectF area;
        final float sc;
        final int W, H;
        Grid(RectF area, float want) {
            this.area = area;
            float s = Math.min(want, MAX / Math.max(1, Math.max(area.width(), area.height())));
            sc = Math.max(0.05f, s);
            W = Math.max(4, (int) Math.ceil(area.width() * sc));
            H = Math.max(4, (int) Math.ceil(area.height() * sc));
        }
        Canvas canvas(Bitmap bm) {
            Canvas c = new Canvas(bm);
            c.scale(sc, sc);
            c.translate(-area.left, -area.top);
            return c;
        }
        boolean[] read(Bitmap bm) {
            byte[] a = new byte[W * H];
            bm.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(a));
            bm.recycle();
            boolean[] m = new boolean[W * H];
            for (int i = 0; i < a.length; i++) m[i] = (a[i] & 0xFF) > 127;
            return m;
        }
    }

    /** Camino suave por los puntos del dedo. */
    static Path strokePath(ArrayList<float[]> pts) {
        MDoc.Shape s = new MDoc.Shape();
        s.n = Render.smooth(pts, 0.6f);
        if (pts.size() == 1) { float[] p = pts.get(0); s.n = new float[]{p[0], p[1], p[0], p[1], p[0], p[1]}; }
        return Render.path(s);
    }

    private static boolean[] strokeMask(Grid g, Path path, float width) {
        Bitmap bm = Bitmap.createBitmap(g.W, g.H, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(width);
        p.setColor(0xFF000000);
        g.canvas(bm).drawPath(path, p);
        return g.read(bm);
    }

    private static boolean[] fillMask(Grid g, MDoc.Shape s) {
        Bitmap bm = Bitmap.createBitmap(g.W, g.H, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        g.canvas(bm).drawPath(Render.path(s), p);
        return g.read(bm);
    }

    /** Máscara → forma de relleno vectorial (null si quedó vacía). */
    static MDoc.Shape vectorize(boolean[] m, Grid g, int color) {
        int[] px = new int[m.length];
        int n = 0;
        for (int i = 0; i < m.length; i++) if (m[i]) { px[i] = 2; n++; }
        if (n == 0) return null;
        ArrayList<float[]> loops = Fill.trace(px, g.W, g.H);
        ArrayList<Float> nodes = new ArrayList<>();
        ArrayList<Integer> starts = new ArrayList<>();
        for (float[] loop : loops) {
            float[] simp = Fill.simplify(loop, 0.5f);
            if (simp.length < 6) continue;
            if (!nodes.isEmpty()) starts.add(nodes.size() / 6);
            for (int i = 0; i < simp.length; i += 2) {
                float x = g.area.left + simp[i] / g.sc, y = g.area.top + simp[i + 1] / g.sc;
                for (int j = 0; j < 3; j++) { nodes.add(x); nodes.add(y); }
            }
        }
        if (nodes.size() < 18) return null;
        MDoc.Shape sh = new MDoc.Shape();
        sh.n = new float[nodes.size()];
        for (int i = 0; i < sh.n.length; i++) sh.n[i] = nodes.get(i);
        if (!starts.isEmpty()) { sh.sub = new int[starts.size()]; for (int i = 0; i < sh.sub.length; i++) sh.sub[i] = starts.get(i); }
        sh.closed = true;
        sh.fill = color;
        sh.stroke = 0;
        sh.width = 0;
        return sh;
    }

    // ---------------------------------------------------------------- pincel
    static final class Paint1 {
        MDoc.Shape shape;
        int at;
        String error;
    }

    /**
     * Pinta con el pincel: el trazo (puntos del dedo, ancho) pasa a ser una forma de RELLENO de color "color".
     * pxPerUnit = píxeles de pantalla por unidad del documento (para la resolución).
     */
    static Paint1 paint(MDoc.Key k, ArrayList<float[]> pts, float width, int color, int mode, float pxPerUnit) {
        Paint1 r = new Paint1();
        Path path = strokePath(pts);
        RectF area = new RectF();
        path.computeBounds(area, true);
        area.inset(-width / 2 - 3, -width / 2 - 3);
        Grid g = new Grid(area, Math.max(1, pxPerUnit * 1.5f));
        boolean[] m = strokeMask(g, path, width);
        int inside = -1;
        if (mode == INSIDE) { // solo dentro del relleno donde empezó el trazo
            float[] p0 = pts.get(0);
            for (int i = k.shapes.size() - 1; i >= 0; i--) {
                MDoc.Shape s = k.shapes.get(i);
                if (!s.isInst() && s.closed && s.fill != 0 && Render.hit(s, p0[0], p0[1], 0, Render.HIT_INSIDE)) { inside = i; break; }
            }
            if (inside < 0) { r.error = "Pintar dentro: empieza el trazo dentro de un relleno"; return r; }
            boolean[] f = fillMask(g, k.shapes.get(inside));
            for (int i = 0; i < m.length; i++) m[i] = m[i] && f[i];
        }
        r.shape = vectorize(m, g, color);
        if (r.shape == null) { r.error = mode == INSIDE ? "El trazo quedó fuera del relleno" : null; return r; }
        switch (mode) {
            case BEHIND: r.at = 0; break;
            case INSIDE: r.at = inside + 1; break;
            case FILLS: { // bajo las líneas que toca: va justo antes de la primera figura con línea que se cruza
                r.at = k.shapes.size();
                for (int i = 0; i < k.shapes.size(); i++) {
                    MDoc.Shape s = k.shapes.get(i);
                    if (!s.isInst() && s.stroke != 0 && s.width > 0 && RectF.intersects(Render.bounds(s), area)) { r.at = i; break; }
                }
                break;
            }
            default: r.at = k.shapes.size();
        }
        return r;
    }

    // ---------------------------------------------------------------- borrador
    /** Qué queda de cada figura tocada: índice → formas que la reemplazan (vacío = se borra entera). */
    static final class Cut {
        final ArrayList<Integer> index = new ArrayList<>();
        final ArrayList<ArrayList<MDoc.Shape>> parts = new ArrayList<>();
    }

    static Cut erase(MDoc.Key k, ArrayList<float[]> pts, float width, int mode, float pxPerUnit) {
        Cut cut = new Cut();
        Path path = strokePath(pts);
        RectF eb = new RectF();
        path.computeBounds(eb, true);
        float rad = width / 2;
        eb.inset(-rad - 1, -rad - 1);
        for (int i = 0; i < k.shapes.size(); i++) {
            MDoc.Shape s = k.shapes.get(i);
            if (s.isInst() || s.count() == 0) continue;
            RectF sb = Render.bounds(s);
            if (!RectF.intersects(sb, eb)) continue;
            ArrayList<MDoc.Shape> out = new ArrayList<>();
            boolean changed = false;
            boolean hasFill = s.closed && s.fill != 0;
            boolean hasLine = s.stroke != 0 && s.width > 0;
            // relleno: se le resta el trazo del borrador
            if (hasFill) {
                if (mode == E_LINES) out.add(fillOnly(s));
                else {
                    RectF area = new RectF(sb);
                    area.inset(-2, -2);
                    Grid g = new Grid(area, Math.max(1, pxPerUnit * 1.5f));
                    boolean[] f = fillMask(g, s), e = strokeMask(g, path, width);
                    boolean hit = false;
                    for (int j = 0; j < f.length; j++) if (f[j] && e[j]) { hit = true; f[j] = false; }
                    if (hit) {
                        changed = true;
                        MDoc.Shape rest = vectorize(f, g, s.fill);
                        if (rest != null) out.add(rest);
                    } else out.add(fillOnly(s));
                }
            }
            // línea: se corta donde pasa el borrador
            if (hasLine) {
                if (mode == E_FILLS) out.add(lineOnly(s));
                else {
                    ArrayList<ArrayList<float[]>> runs = new ArrayList<>();
                    boolean hit = cutLine(s, pts, rad + s.width / 2, runs);
                    if (hit) {
                        changed = true;
                        for (ArrayList<float[]> run : runs) {
                            if (run.size() < 2) continue;
                            MDoc.Shape piece = lineOnly(s);
                            piece.n = Render.smooth(run, 0.3f);
                            piece.closed = false;
                            piece.sub = null;
                            piece.dirty();
                            out.add(piece);
                        }
                    } else out.add(lineOnly(s));
                }
            }
            if (changed) { cut.index.add(i); cut.parts.add(out); }
        }
        return cut;
    }

    private static MDoc.Shape fillOnly(MDoc.Shape s) {
        MDoc.Shape f = s.copy();
        f.stroke = 0;
        f.width = 0;
        f.brush = false;
        f.dirty();
        return f;
    }

    private static MDoc.Shape lineOnly(MDoc.Shape s) {
        MDoc.Shape l = s.copy();
        l.fill = 0;
        l.dirty();
        return l;
    }

    /**
     * Recorre la línea de la figura (cada contorno) en pasos cortos y la corta donde queda a menos de "reach" del
     * camino del borrador. Devuelve si cortó algo; los trozos que quedan van en runs.
     */
    private static boolean cutLine(MDoc.Shape s, ArrayList<float[]> eraser, float reach, ArrayList<ArrayList<float[]>> runs) {
        PathMeasure pm = new PathMeasure(Render.path(s), false);
        boolean hit = false;
        float[] pos = new float[2];
        float step = Math.max(0.5f, reach / 4);
        do {
            float len = pm.getLength();
            if (len <= 0) continue;
            ArrayList<float[]> run = new ArrayList<>();
            ArrayList<float[]> first = null;
            boolean closed = pm.isClosed();
            boolean cutHere = false;
            int steps = Math.max(1, (int) Math.ceil(len / step));
            for (int k = 0; k <= steps; k++) {   // incluye justo el final de la línea
                pm.getPosTan(len * k / steps, pos, null);
                boolean gone = near(eraser, pos[0], pos[1], reach);
                if (gone) {
                    hit = true;
                    cutHere = true;
                    if (run.size() >= 2) { if (first == null && closed) first = run; else runs.add(run); }
                    else if (first == null && closed) first = new ArrayList<>();
                    run = new ArrayList<>();
                } else run.add(new float[]{pos[0], pos[1]});
            }
            if (closed && cutHere && first != null) { run.addAll(first); first = null; } // el contorno cerrado se une por la costura
            if (first != null && first.size() >= 2) runs.add(first);
            if (run.size() >= 2) runs.add(run);
            if (!cutHere && run.size() >= 2) { /* contorno sin tocar: queda entero */ }
        } while (pm.nextContour());
        return hit;
    }

    private static boolean near(ArrayList<float[]> poly, float x, float y, float r) {
        float r2 = r * r;
        if (poly.size() == 1) { float[] a = poly.get(0); return (a[0] - x) * (a[0] - x) + (a[1] - y) * (a[1] - y) <= r2; }
        for (int i = 1; i < poly.size(); i++) {
            float[] a = poly.get(i - 1), b = poly.get(i);
            float dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
            float t = l2 < 1e-6f ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / l2));
            float px = a[0] + t * dx - x, py = a[1] + t * dy - y;
            if (px * px + py * py <= r2) return true;
        }
        return false;
    }
}
