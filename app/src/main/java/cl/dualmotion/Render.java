package cl.dualmotion;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;

import java.util.ArrayList;
import java.util.HashSet;

/** Dibuja formas y fotogramas; también prueba toques sobre formas y suaviza trazos a mano alzada. */
public final class Render {
    private Render() { }

    /** Pinceles por hilo: la pantalla y la exportación a video dibujan al mismo tiempo. */
    private static final class Tools {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Matrix m = new Matrix();
        /** Documento que se está dibujando (para encontrar los símbolos), cuánto lleva el clave actual y
         *  la profundidad de símbolos dentro de símbolos. */
        MDoc doc;
        int elapsed, depth;
        /** Instancia que no se dibuja (la que se está editando en el lugar). */
        MDoc.Shape skip;
        Tools() {
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }
    }
    private static final ThreadLocal<Tools> TOOLS = new ThreadLocal<Tools>() {
        @Override protected Tools initialValue() { return new Tools(); }
    };
    /** Documento con que se dibuja en este hilo (los símbolos se buscan ahí). */
    public static void use(MDoc d) { TOOLS.get().doc = d; }

    /** Cuántos fotogramas lleva el clave actual (para tocar o medir instancias fuera del dibujo). */
    public static void at(int elapsed) { TOOLS.get().elapsed = elapsed; }

    public static void skip(MDoc.Shape s) { TOOLS.get().skip = s; }

    public static Path path(MDoc.Shape s) {
        if (s.path != null) return s.path;
        Path p = new Path();
        int c = s.count();
        if (s.sub == null) contour(p, s.n, 0, c, s.closed);
        else { // relleno con huecos: varios contornos, se rellena par-impar
            p.setFillType(Path.FillType.EVEN_ODD);
            int a = 0;
            for (int i = 0; i <= s.sub.length; i++) {
                int b = i < s.sub.length ? s.sub[i] : c;
                if (b > a) contour(p, s.n, a, b, s.closed);
                a = b;
            }
        }
        s.path = p;
        return p;
    }

    /** Un contorno con los nodos [from, to). */
    private static void contour(Path p, float[] n, int from, int to, boolean closed) {
        int c = to - from;
        if (c <= 0) return;
        int o = from * 6;
        p.moveTo(n[o], n[o + 1]);
        if (c == 1) p.lineTo(n[o] + 0.01f, n[o + 1]);
        for (int i = from + 1; i < to; i++) {
            int a = (i - 1) * 6, b = i * 6;
            p.cubicTo(n[a + 4], n[a + 5], n[b + 2], n[b + 3], n[b], n[b + 1]);
        }
        if (closed && c > 2) {
            int a = (to - 1) * 6;
            p.cubicTo(n[a + 4], n[a + 5], n[o + 2], n[o + 3], n[o], n[o + 1]);
            p.close();
        }
    }

    public static RectF bounds(MDoc.Shape s) {
        if (s.isInst()) { RectF r = new RectF(); instBounds(s, TOOLS.get().elapsed, r); return r; }
        if (s.bounds != null) return s.bounds;
        RectF r = new RectF();
        path(s).computeBounds(r, true);
        float e = s.stroke != 0 ? s.width / 2 : 0;
        r.inset(-e, -e);
        s.bounds = r;
        return r;
    }

    /**
     * Dibuja una forma. tint != 0: todo de ese color (papel cebolla); outline != 0: solo contorno fino
     * (modo contorno de la capa).
     */
    public static void shape(Canvas c, MDoc.Shape s, int tint, int outline, float hair) {
        if (s.isInst()) { instance(c, s, tint, outline, hair); return; }
        Path p = path(s);
        Tools t = TOOLS.get();
        Paint fillP = t.fill, strokeP = t.stroke;
        if (outline != 0) {
            strokeP.setColor(outline);
            strokeP.setStrokeWidth(hair);
            c.drawPath(p, strokeP);
            return;
        }
        if (s.fill != 0 && s.closed) {
            fillP.setColor(tint != 0 ? withAlpha(tint, Math.min(0x55, tint >>> 24)) : s.fill);
            c.drawPath(p, fillP);
        }
        if (s.stroke != 0 && s.width > 0) {
            strokeP.setColor(tint != 0 ? tint : s.stroke);
            strokeP.setStrokeWidth(s.width);
            c.drawPath(p, strokeP);
        }
    }

    public static void key(Canvas c, MDoc.Key k, int tint, int outline, float hair) {
        if (k == null) return;
        for (MDoc.Shape s : k.shapes) shape(c, s, tint, outline, hair);
    }

    /** Matriz de la pose: escala y giro alrededor de (px, py), luego traslado. */
    public static Matrix matrix(MDoc.Pose p, Matrix out) {
        if (out == null) out = new Matrix();
        out.reset();
        out.postTranslate(-p.px, -p.py);
        out.postScale(p.sx, p.sy);
        out.postRotate(p.rot);
        out.postTranslate(p.px + p.tx, p.py + p.ty);
        return out;
    }


    /** Dibuja la capa en el fotograma f con su transformación y alfa (interpolados si corresponde). */
    public static void layer(Canvas c, MDoc.Layer L, int f, int tint, int outline, float hair) {
        MDoc.Pose p = MDoc.pose(L, f);
        if (p != null) pose(c, p, tint, outline, hair);
    }

    public static void pose(Canvas c, MDoc.Pose p, int tint, int outline, float hair) {
        if (p.key.shapes.isEmpty() || p.alpha <= 0) return;
        int save = p.alpha < 1 && outline == 0 ? c.saveLayerAlpha(null, Math.round(p.alpha * 255)) : c.save();
        c.concat(matrix(p, TOOLS.get().m));
        float sc = Math.max(0.01f, (Math.abs(p.sx) + Math.abs(p.sy)) / 2);
        Tools t = TOOLS.get();
        int was = t.elapsed;
        t.elapsed = p.frame - p.keyFrame;
        key(c, p.key, tint, outline, hair / sc);
        t.elapsed = was;
        c.restoreToCount(save);
    }

    // ---------------------------------------------------------------- símbolos gráficos
    /** Dibuja una instancia: la línea de tiempo del símbolo, en el fotograma que le toca (en ciclo). */
    private static void instance(Canvas c, MDoc.Shape s, int tint, int outline, float hair) {
        Tools t = TOOLS.get();
        if (s == t.skip) return;
        MDoc.Symbol Y = t.doc == null ? null : t.doc.symbol(s.sym);
        if (Y == null || t.depth > 8 || s.n.length < 2) return;
        int sf = MDoc.symFrame(s, t.elapsed, MDoc.length(Y.layers));
        int was = t.elapsed;
        c.save();
        c.translate(s.n[0], s.n[1]);
        t.depth++;
        timeline(c, Y.layers, sf, true, outline != 0, tint, hair);
        t.depth--;
        c.restore();
        t.elapsed = was;
    }

    /** Dibuja una línea de tiempo (escena o símbolo) en el fotograma f, de la capa de abajo hacia arriba. */
    public static void timeline(Canvas c, java.util.List<MDoc.Layer> T, int f, boolean respectVis, boolean outlines,
                                int tint, float hair) {
        for (int i = T.size() - 1; i >= 0; i--) {
            MDoc.Layer L = T.get(i);
            if (L.audio || (respectVis && !L.vis)) continue;
            layer(c, L, f, tint, outlines && L.out ? L.color : 0, hair);
        }
    }

    /** Rectángulo que ocupa una instancia (en las coordenadas del clave donde está). */
    public static void instBounds(MDoc.Shape s, int elapsed, RectF out) {
        Tools t = TOOLS.get();
        out.setEmpty();
        MDoc.Symbol Y = t.doc == null ? null : t.doc.symbol(s.sym);
        if (Y == null || t.depth > 8 || s.n.length < 2) { out.set(s.n[0] - 10, s.n[1] - 10, s.n[0] + 10, s.n[1] + 10); return; }
        t.depth++;
        contentBounds(Y.layers, MDoc.symFrame(s, elapsed, MDoc.length(Y.layers)), out);
        t.depth--;
        if (out.isEmpty()) out.set(-10, -10, 10, 10);
        out.offset(s.n[0], s.n[1]);
    }

    /** Rectángulo que ocupa todo lo visible de una línea de tiempo en el fotograma f. */
    public static void contentBounds(java.util.List<MDoc.Layer> T, int f, RectF out) {
        Tools t = TOOLS.get();
        int was = t.elapsed;
        RectF r = new RectF();
        Matrix m = new Matrix();
        boolean any = false;
        for (MDoc.Layer L : T) {
            if (L.audio || !L.vis) continue;
            MDoc.Pose p = MDoc.pose(L, f);
            if (p == null) continue;
            t.elapsed = p.frame - p.keyFrame;
            for (MDoc.Shape sh : p.key.shapes) {
                r.set(bounds(sh));
                matrix(p, m).mapRect(r);
                if (!any) { out.set(r); any = true; } else out.union(r);
            }
        }
        t.elapsed = was;
        if (!any) out.setEmpty();
    }

    /**
     * Dibuja el fotograma f completo (de la capa de abajo hacia arriba).
     * respectVis: no dibuja capas ocultas. hair = grosor de las líneas de contorno (en unidades del documento).
     */
    public static void frame(Canvas c, MDoc d, int f, boolean respectVis, boolean outlines, float hair) {
        use(d);
        timeline(c, d.layers, f, respectVis, outlines, 0, hair);
    }

    public static final int ONION_PREV = 0x99E04848, ONION_NEXT = 0x9930A060;

    /**
     * Papel cebolla: fotogramas anteriores y siguientes que se vean distintos del actual, teñidos y
     * transparentes (rojo antes, verde después). Sin interpolación, un tramo se dibuja una sola vez.
     */
    public static void onion(Canvas c, java.util.List<MDoc.Layer> T, int f, int range, float hair) {
        for (int i = T.size() - 1; i >= 0; i--) {
            MDoc.Layer L = T.get(i);
            if (L.audio || !L.vis) continue;
            MDoc.Pose cur = MDoc.pose(L, f);
            HashSet<Integer> done = new HashSet<>();
            if (cur != null && !cur.tweening) done.add(cur.keyFrame);
            for (int k = 1; k <= range; k++) {
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    MDoc.Pose p = MDoc.pose(L, f + sgn * k);
                    if (p == null) continue;
                    if (!p.tweening && !hasInst(p.key) && !done.add(p.keyFrame)) continue;
                    if (p.tweening && cur != null && cur.tweening && cur.keyFrame == p.keyFrame && same(cur, p) && p.key == cur.key) continue;
                    int a = k == 1 ? 0x99 : 0x55;
                    pose(c, p, withAlpha(sgn < 0 ? ONION_PREV : ONION_NEXT, a), 0, hair);
                }
            }
        }
    }

    private static boolean hasInst(MDoc.Key k) {
        for (MDoc.Shape s : k.shapes) if (s.isInst()) return true;
        return false;
    }

    private static boolean same(MDoc.Pose a, MDoc.Pose b) {
        return a.tx == b.tx && a.ty == b.ty && a.sx == b.sx && a.sy == b.sy && a.rot == b.rot;
    }

    static int withAlpha(int c, int a) { return (c & 0x00FFFFFF) | (a << 24); }

    /** Dibuja el documento ajustado al rectángulo (con fondo blanco del escenario). */
    public static void fitted(Canvas c, MDoc d, int f, RectF box, Paint stageP) {
        float s = Math.min(box.width() / d.w, box.height() / d.h);
        float x = box.centerX() - d.w * s / 2, y = box.centerY() - d.h * s / 2;
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        c.drawRect(0, 0, d.w, d.h, stageP);
        c.clipRect(0, 0, d.w, d.h);
        frame(c, d, f, true, false, 1);
        c.restore();
    }

    // ---------------------------------------------------------------- toques sobre formas
    public static final int HIT_FILL = 1, HIT_STROKE = 2, HIT_INSIDE = 4;
    private static final Paint hitP = new Paint();
    static { hitP.setStyle(Paint.Style.STROKE); hitP.setStrokeCap(Paint.Cap.ROUND); hitP.setStrokeJoin(Paint.Join.ROUND); }

    /** ¿El punto (x, y) toca la forma? tol en unidades del documento. */
    public static boolean hit(MDoc.Shape s, float x, float y, float tol, int mode) {
        RectF b = new RectF(bounds(s));
        b.inset(-tol, -tol);
        if (!b.contains(x, y)) return false;
        if (s.isInst()) return (mode & (HIT_FILL | HIT_STROKE)) != 0; // una instancia se toma por su rectángulo
        Path p = path(s);
        Region clip = new Region((int) Math.floor(b.left) - 2, (int) Math.floor(b.top) - 2,
                (int) Math.ceil(b.right) + 2, (int) Math.ceil(b.bottom) + 2);
        if (s.closed && ((mode & HIT_INSIDE) != 0 || ((mode & HIT_FILL) != 0 && s.fill != 0))) {
            Region r = new Region();
            r.setPath(p, clip);
            if (r.contains(Math.round(x), Math.round(y))) return true;
        }
        if ((mode & HIT_STROKE) != 0) {
            hitP.setStrokeWidth(Math.max(0.5f, Math.max(s.stroke != 0 ? s.width : 0, 1) + tol * 2));
            Path out = new Path();
            hitP.getFillPath(p, out);
            Region r = new Region();
            r.setPath(out, clip);
            if (r.contains(Math.round(x), Math.round(y))) return true;
        }
        return false;
    }

    /** Índice de la forma de más arriba bajo el punto, o -1. */
    public static int hitKey(MDoc.Key k, float x, float y, float tol, int mode) {
        if (k == null) return -1;
        for (int i = k.shapes.size() - 1; i >= 0; i--)
            if (hit(k.shapes.get(i), x, y, tol, mode)) return i;
        return -1;
    }

    // ---------------------------------------------------------------- trazo a mano alzada → curvas
    /** Simplifica los puntos (Douglas-Peucker) y los convierte en nodos Bézier suaves. */
    public static float[] smooth(ArrayList<float[]> pts, float eps) {
        int n = pts.size();
        if (n == 0) return new float[0];
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        rdp(pts, 0, n - 1, eps, keep);
        ArrayList<float[]> q = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) q.add(pts.get(i));
        int m = q.size();
        float[] out = new float[m * 6];
        for (int i = 0; i < m; i++) {
            float[] p = q.get(i);
            float[] a = q.get(Math.max(0, i - 1)), b = q.get(Math.min(m - 1, i + 1));
            // tangente tipo Catmull-Rom, limitada para no pasarse en tramos cortos
            float tx = (b[0] - a[0]) / 6f, ty = (b[1] - a[1]) / 6f;
            if (i == 0 || i == m - 1) { tx = 0; ty = 0; }
            out[i * 6] = p[0]; out[i * 6 + 1] = p[1];
            out[i * 6 + 2] = p[0] - tx; out[i * 6 + 3] = p[1] - ty;
            out[i * 6 + 4] = p[0] + tx; out[i * 6 + 5] = p[1] + ty;
        }
        return out;
    }

    private static void rdp(ArrayList<float[]> p, int a, int b, float eps, boolean[] keep) {
        if (b <= a + 1) return;
        float[] A = p.get(a), B = p.get(b);
        float dx = B[0] - A[0], dy = B[1] - A[1];
        float len = (float) Math.hypot(dx, dy);
        float best = -1; int bi = -1;
        for (int i = a + 1; i < b; i++) {
            float[] P = p.get(i);
            float d = len < 1e-3f ? (float) Math.hypot(P[0] - A[0], P[1] - A[1])
                    : Math.abs(dy * P[0] - dx * P[1] + B[0] * A[1] - B[1] * A[0]) / len;
            if (d > best) { best = d; bi = i; }
        }
        if (best > eps) {
            keep[bi] = true;
            rdp(p, a, bi, eps, keep);
            rdp(p, bi, b, eps, keep);
        }
    }

    /** Nodo recto (manillas sobre el punto). */
    public static float[] corner(float x, float y) { return new float[]{x, y, x, y, x, y}; }
}
