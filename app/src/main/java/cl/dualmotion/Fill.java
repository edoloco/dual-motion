package cl.dualmotion;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;

/**
 * Cubo de pintura: rellena la zona del color tocado (con tolerancia), cerrando huecos entre las líneas, como el
 * balde de DUAL-Draw; la zona se convierte en una forma de relleno vectorial (con huecos si los hay) que va detrás
 * de las líneas y reemplaza a los rellenos que cubre.
 */
final class Fill {
    private Fill() { }

    /** Opciones del cubo (como el balde de DUAL-Draw). */
    static final class Opts {
        int tol = 40;        // cuánto puede variar el color y seguir siendo "la misma zona" (0–120)
        float gapDoc;        // huecos a cerrar, en unidades del documento
        boolean all;         // mirar todas las capas (no solo esta)
        int color;
    }

    /** Dibuja lo que se ve de todas las capas, en las coordenadas de la capa actual (modo "todas las capas"). */
    interface Scene { void draw(Canvas c); }

    static final class Result {
        /** Forma nueva de relleno (o null). */
        MDoc.Shape shape;
        /** Índice donde insertarla dentro del clave (ya contando las que se quitan). */
        int at;
        /** Rellenos que la forma nueva reemplaza (se quitan). */
        ArrayList<Integer> remove = new ArrayList<>();
        /** Índice de una forma a la que basta cambiarle el color de relleno (o -1). */
        int recolor = -1;
        String error;
    }

    private static final int MAX = 1200; // lado máximo de la imagen de trabajo (px)

    /**
     * Como el balde de DUAL-Draw, pero el resultado es vectorial: se mira el COLOR de lo que se ve alrededor del
     * toque (con tolerancia), se cierran los huecos, y la zona encontrada pasa a ser una forma de relleno que
     * REEMPLAZA a los rellenos que cubre (así, volver a pintar una zona la cambia entera, sin dejar borde).
     */
    static Result find(MDoc.Key k, float x, float y, Opts o, RectF stage, Scene scene) {
        Result r = new Result();
        RectF area = new RectF();
        boolean any = false;
        for (MDoc.Shape sh : k.shapes) {
            if (sh.isObj() || sh.count() == 0) continue;
            RectF b = Render.bounds(sh);
            if (!any) { area.set(b); any = true; } else area.union(b);
        }
        if (o.all && stage != null) { if (!any) area.set(stage); else area.union(stage); any = true; }
        if (!any || !area.contains(x, y)) return leak(r);
        // tocar sobre una línea no la rellena (como en Flash)
        for (int i = k.shapes.size() - 1; i >= 0; i--) {
            MDoc.Shape sh = k.shapes.get(i);
            if (sh.isObj() || sh.count() == 0) continue;
            if (sh.stroke != 0 && sh.width > 0 && Render.hit(sh, x, y, 0, Render.HIT_STROKE)) {
                r.error = "Toca dentro del área, no sobre la línea";
                return r;
            }
            if (sh.closed && sh.fill != 0 && Render.hit(sh, x, y, 0, Render.HIT_INSIDE)) break; // lo de abajo queda tapado
        }
        float pad = Math.max(4, o.gapDoc * 2 + 2);
        area.inset(-pad, -pad);
        float sc = Math.min(4f, MAX / Math.max(area.width(), area.height()));
        int W = Math.max(8, (int) Math.ceil(area.width() * sc)), H = Math.max(8, (int) Math.ceil(area.height() * sc));
        int N = W * H;

        // 1) lo que se ve, en colores (sobre blanco)
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(0xFFFFFFFF);
        c.scale(sc, sc);
        c.translate(-area.left, -area.top);
        if (o.all && scene != null) scene.draw(c);
        else for (MDoc.Shape sh : k.shapes) if (!sh.isObj()) Render.shape(c, sh, 0, 0, 1);
        int[] px = new int[N];
        bm.getPixels(px, 0, W, 0, 0, W, H);
        bm.recycle();

        int sx = (int) ((x - area.left) * sc), sy = (int) ((y - area.top) * sc);
        if (sx < 0 || sy < 0 || sx >= W || sy >= H) return leak(r);
        int seed = sy * W + sx, target = px[seed];
        boolean[] ok = new boolean[N];
        for (int i = 0; i < N; i++) ok[i] = near(px[i], target, o.tol);

        // 2) huecos: las líneas se engrosan para taparlos; después el relleno vuelve a llegar hasta la línea
        int gap = Math.round(o.gapDoc * sc);
        boolean[] allowed = ok;
        if (gap > 0) {
            boolean[] wall = new boolean[N];
            for (int i = 0; i < N; i++) wall[i] = !ok[i];
            wall = grow(wall, W, H, gap);
            boolean[] a2 = new boolean[N];
            for (int i = 0; i < N; i++) a2[i] = ok[i] && !wall[i];
            if (a2[seed]) allowed = a2; // si se tocó justo junto a la línea, sin cerrar huecos
        }
        boolean[] mark = new boolean[N];
        if (!floodMark(allowed, mark, W, H, seed)) return leak(r);
        if (allowed != ok) {
            boolean[] g = grow(mark, W, H, gap + 1);
            for (int i = 0; i < N; i++) mark[i] = mark[i] || (g[i] && ok[i]);
        }
        int count = 0;
        for (boolean b : mark) if (b) count++;

        // 3) rellenos que esta zona cubre: se reemplazan (o, si es justo uno, solo se le cambia el color)
        int recolorCand = -1, candArea = 0, covered = 0;
        boolean[] sm = new boolean[N];
        for (int i = 0; i < k.shapes.size(); i++) {
            MDoc.Shape sh = k.shapes.get(i);
            if (sh.isObj() || !sh.closed || sh.fill == 0 || !near(sh.fill | 0xFF000000, target | 0xFF000000, o.tol)) continue;
            if (!RectF.intersects(Render.bounds(sh), area)) continue;
            int vis = 0, in = 0, tot = shapeMask(sh, area, sc, W, H, sm);
            for (int j = 0; j < N; j++) if (sm[j]) { if (ok[j]) { vis++; if (mark[j]) in++; } }
            if (vis == 0 || in < vis * 0.9f) continue;
            covered++;
            if (sh.stroke == 0) r.remove.add(i);
            if (recolorCand < 0 || i > recolorCand) { recolorCand = i; candArea = tot; }
        }
        if (covered == 1 && count <= candArea * 1.12f) { r.recolor = recolorCand; r.remove.clear(); return r; }

        // 4) el relleno pasa 1 px bajo la línea, para que no quede una rendija blanca
        boolean[] g1 = grow(mark, W, H, 1);
        int[] m = new int[N];
        for (int i = 0; i < N; i++) m[i] = g1[i] ? 2 : 0;

        // 5) contornos → forma vectorial
        ArrayList<float[]> loops = trace(m, W, H);
        ArrayList<Float> nodes = new ArrayList<>();
        ArrayList<Integer> starts = new ArrayList<>();
        for (float[] loop : loops) {
            float[] simp = simplify(loop, 0.6f);
            if (simp.length < 6) continue;
            if (!nodes.isEmpty()) starts.add(nodes.size() / 6);
            for (int i = 0; i < simp.length; i += 2) {
                float dx = area.left + simp[i] / sc, dy = area.top + simp[i + 1] / sc;
                for (int j = 0; j < 3; j++) { nodes.add(dx); nodes.add(dy); }
            }
        }
        if (nodes.size() < 18) return leak(r);
        MDoc.Shape sh = new MDoc.Shape();
        sh.n = new float[nodes.size()];
        for (int i = 0; i < sh.n.length; i++) sh.n[i] = nodes.get(i);
        if (!starts.isEmpty()) { sh.sub = new int[starts.size()]; for (int i = 0; i < sh.sub.length; i++) sh.sub[i] = starts.get(i); }
        sh.closed = true;
        sh.fill = o.color;
        sh.stroke = 0;
        sh.width = 0;
        r.shape = sh;
        // va donde estaba el relleno de más abajo que reemplaza; si no, encima del relleno que lo contiene, o al fondo
        int at = 0;
        if (!r.remove.isEmpty()) {
            at = Integer.MAX_VALUE;
            for (int i : r.remove) at = Math.min(at, i);
        } else for (int i = 0; i < k.shapes.size(); i++) {
            MDoc.Shape t = k.shapes.get(i);
            if (!t.isObj() && t.closed && t.fill != 0 && Render.hit(t, x, y, 0, Render.HIT_INSIDE)) at = i + 1;
        }
        int shift = 0;
        for (int i : r.remove) if (i < at) shift++;
        r.at = at - shift;
        return r;
    }

    private static Result leak(Result r) {
        r.error = "Esa área no está cerrada: junta las líneas o sube \"Huecos\" en las opciones del cubo";
        return r;
    }

    /** ¿Dos colores se parecen (cada canal, también la transparencia, dentro de la tolerancia)? */
    static boolean near(int a, int b, int tol) {
        return Math.abs((a >>> 24) - (b >>> 24)) <= tol && Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= tol
                && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= tol && Math.abs((a & 0xFF) - (b & 0xFF)) <= tol;
    }

    /** Píxeles que ocupa el relleno de una forma (en sm); devuelve cuántos. */
    private static int shapeMask(MDoc.Shape S, RectF area, float sc, int W, int H, boolean[] sm) {
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(bm);
        c.scale(sc, sc);
        c.translate(-area.left, -area.top);
        Paint p = new Paint();
        p.setColor(0xFF000000);
        c.drawPath(Render.path(S), p);
        byte[] a = new byte[W * H];
        bm.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(a));
        bm.recycle();
        int n = 0;
        for (int i = 0; i < a.length; i++) { sm[i] = (a[i] & 0xFF) > 127; if (sm[i]) n++; }
        return n;
    }

    /** Inunda desde seed por los permitidos (4 vecinos). false si la zona llega al borde (no está cerrada). */
    static boolean floodMark(boolean[] allowed, boolean[] mark, int W, int H, int seed) {
        int N = W * H;
        if (!allowed[seed]) return false;
        int[] stack = new int[Math.max(64, W * 4)];
        int sp = 0;
        stack[sp++] = seed;
        mark[seed] = true;
        while (sp > 0) {
            int i = stack[--sp], cx = i % W, cy = i / W;
            if (cx == 0 || cy == 0 || cx == W - 1 || cy == H - 1) return false;
            if (sp + 4 > stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
            if (!mark[i - 1] && allowed[i - 1]) { mark[i - 1] = true; stack[sp++] = i - 1; }
            if (!mark[i + 1] && allowed[i + 1]) { mark[i + 1] = true; stack[sp++] = i + 1; }
            if (!mark[i - W] && allowed[i - W]) { mark[i - W] = true; stack[sp++] = i - W; }
            if (!mark[i + W] && allowed[i + W]) { mark[i + W] = true; stack[sp++] = i + W; }
        }
        return true;
    }

    /** Engrosa la zona marcada r píxeles (cuadrado, en dos pasadas), como en DUAL-Draw. */
    static boolean[] grow(boolean[] in, int W, int H, int r) {
        int N = W * H;
        boolean[] h = new boolean[N], v = new boolean[N];
        for (int y = 0; y < H; y++) {
            int base = y * W, last = -100000;
            for (int x = 0; x < W; x++) { if (in[base + x]) last = x; if (x - last <= r) h[base + x] = true; }
            last = 100000;
            for (int x = W - 1; x >= 0; x--) { if (in[base + x]) last = x; if (last - x <= r) h[base + x] = true; }
        }
        for (int x = 0; x < W; x++) {
            int last = -100000;
            for (int y = 0; y < H; y++) { if (h[y * W + x]) last = y; if (y - last <= r) v[y * W + x] = true; }
            last = 100000;
            for (int y = H - 1; y >= 0; y--) { if (h[y * W + x]) last = y; if (last - y <= r) v[y * W + x] = true; }
        }
        return v;
    }

    /** Inunda (relleno por líneas). Devuelve los píxeles marcados, o -1 si el área se escapa por el borde. */
    static int flood(int[] px, int W, int H, int sx, int sy) {
        int[] stack = new int[Math.max(64, W * 2)];
        int sp = 0, count = 0;
        boolean edge = false;
        stack[sp++] = sy * W + sx;
        while (sp > 0) {
            int p = stack[--sp];
            int y = p / W, x = p % W;
            if (px[p] != 0) continue;
            int l = x, rr = x;
            while (l > 0 && px[y * W + l - 1] == 0) l--;
            while (rr < W - 1 && px[y * W + rr + 1] == 0) rr++;
            if (l == 0 || rr == W - 1 || y == 0 || y == H - 1) edge = true;
            for (int i = l; i <= rr; i++) { px[y * W + i] = 2; count++; }
            for (int ny = y - 1; ny <= y + 1; ny += 2) {
                if (ny < 0 || ny >= H) continue;
                boolean run = false;
                for (int i = l; i <= rr; i++) {
                    boolean free = px[ny * W + i] == 0;
                    if (free && !run) {
                        if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                        stack[sp++] = ny * W + i;
                    }
                    run = free;
                }
            }
            if (edge) return -1;
        }
        return count;
    }

    /** Agranda el área (valor 2) en d píxeles (cuadrado). */
    static void dilate(int[] px, int W, int H, int d) {
        boolean[] m = new boolean[W * H], t = new boolean[W * H];
        for (int i = 0; i < m.length; i++) m[i] = px[i] == 2;
        for (int y = 0; y < H; y++) { // horizontal
            int run = -1;
            for (int x = 0; x < W; x++) if (m[y * W + x]) run = x; else if (run >= 0 && x - run <= d) t[y * W + x] = true;
            run = -1;
            for (int x = W - 1; x >= 0; x--) { if (m[y * W + x]) { run = x; t[y * W + x] = true; } else if (run >= 0 && run - x <= d) t[y * W + x] = true; }
        }
        for (int x = 0; x < W; x++) { // vertical
            int run = -1;
            for (int y = 0; y < H; y++) if (t[y * W + x]) run = y; else if (run >= 0 && y - run <= d) m[y * W + x] = true;
            run = -1;
            for (int y = H - 1; y >= 0; y--) { if (t[y * W + x]) { run = y; m[y * W + x] = true; } else if (run >= 0 && run - y <= d) m[y * W + x] = true; }
        }
        for (int i = 0; i < m.length; i++) px[i] = m[i] ? 2 : 0;
    }

    /**
     * Contornos del área (valor 2): se recorren los bordes de los píxeles; cada contorno queda como lista de
     * esquinas (x, y) en píxeles. El de afuera y los huecos salen por separado.
     */
    static ArrayList<float[]> trace(int[] px, int W, int H) {
        int VW = W + 1;
        int[] out1 = new int[VW * (H + 1)], out2 = new int[VW * (H + 1)];
        java.util.Arrays.fill(out1, -1);
        java.util.Arrays.fill(out2, -1);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                if (px[y * W + x] != 2) continue;
                if (y == 0 || px[(y - 1) * W + x] != 2) link(out1, out2, y * VW + x, y * VW + x + 1);                 // arriba →
                if (x == W - 1 || px[y * W + x + 1] != 2) link(out1, out2, y * VW + x + 1, (y + 1) * VW + x + 1);   // derecha ↓
                if (y == H - 1 || px[(y + 1) * W + x] != 2) link(out1, out2, (y + 1) * VW + x + 1, (y + 1) * VW + x); // abajo ←
                if (x == 0 || px[y * W + x - 1] != 2) link(out1, out2, (y + 1) * VW + x, y * VW + x);               // izquierda ↑
            }
        ArrayList<float[]> loops = new ArrayList<>();
        for (int v = 0; v < out1.length; v++) {
            while (out1[v] >= 0 || out2[v] >= 0) {
                ArrayList<Integer> pts = new ArrayList<>();
                int cur = v, guard = 0;
                do {
                    int nx;
                    if (out1[cur] >= 0) { nx = out1[cur]; out1[cur] = -1; }
                    else if (out2[cur] >= 0) { nx = out2[cur]; out2[cur] = -1; }
                    else break;
                    pts.add(cur);
                    cur = nx;
                } while (cur != v && ++guard < out1.length);
                if (pts.size() < 4) continue;
                // solo las esquinas (donde cambia la dirección)
                ArrayList<Integer> corners = new ArrayList<>();
                int n = pts.size();
                for (int i = 0; i < n; i++) {
                    int pa = pts.get((i + n - 1) % n), pb = pts.get(i), pc = pts.get((i + 1) % n);
                    int d1x = pb % VW - pa % VW, d1y = pb / VW - pa / VW, d2x = pc % VW - pb % VW, d2y = pc / VW - pb / VW;
                    if (d1x != d2x || d1y != d2y) corners.add(pb);
                }
                float[] f = new float[corners.size() * 2];
                for (int i = 0; i < corners.size(); i++) { f[i * 2] = corners.get(i) % VW; f[i * 2 + 1] = corners.get(i) / VW; }
                if (f.length >= 6) loops.add(f);
            }
        }
        return loops;
    }

    private static void link(int[] o1, int[] o2, int from, int to) {
        if (o1[from] < 0) o1[from] = to; else o2[from] = to;
    }

    /** Simplifica un contorno cerrado (Douglas-Peucker) quitando los escalones de los píxeles. */
    static float[] simplify(float[] p, float eps) {
        int n = p.length / 2;
        if (n < 4) return p;
        // se parte en dos mitades desde el punto más lejano al primero
        int far = 0;
        float best = -1;
        for (int i = 1; i < n; i++) {
            float d = (p[i * 2] - p[0]) * (p[i * 2] - p[0]) + (p[i * 2 + 1] - p[1]) * (p[i * 2 + 1] - p[1]);
            if (d > best) { best = d; far = i; }
        }
        boolean[] keep = new boolean[n + 1];
        keep[0] = keep[far] = keep[n] = true;
        rdp(p, 0, far, eps, keep, n);
        rdp(p, far, n, eps, keep, n);
        int k = 0;
        for (int i = 0; i < n; i++) if (keep[i]) k++;
        float[] out = new float[k * 2];
        k = 0;
        for (int i = 0; i < n; i++) if (keep[i]) { out[k++] = p[i * 2]; out[k++] = p[i * 2 + 1]; }
        return out;
    }

    private static void rdp(float[] p, int a, int b, float eps, boolean[] keep, int n) {
        if (b <= a + 1) return;
        float ax = p[(a % n) * 2], ay = p[(a % n) * 2 + 1], bx = p[(b % n) * 2], by = p[(b % n) * 2 + 1];
        float dx = bx - ax, dy = by - ay, len = (float) Math.hypot(dx, dy);
        float best = -1;
        int bi = -1;
        for (int i = a + 1; i < b; i++) {
            float x = p[i * 2], y = p[i * 2 + 1];
            float d = len < 1e-3f ? (float) Math.hypot(x - ax, y - ay) : Math.abs(dy * x - dx * y + bx * ay - by * ax) / len;
            if (d > best) { best = d; bi = i; }
        }
        if (best > eps) {
            keep[bi] = true;
            rdp(p, a, bi, eps, keep, n);
            rdp(p, bi, b, eps, keep, n);
        }
    }
}
