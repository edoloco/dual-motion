package cl.dualmotion;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;

/**
 * Cubo de pintura como en Flash: rellena el área encerrada por los trazos alrededor del punto tocado, aunque la
 * figura se haya hecho a mano (lápiz, pincel) o con varias líneas. Los trazos se pasan a una imagen, se inunda
 * desde el toque y el área encontrada se convierte en una forma de relleno (vectorial, con huecos si los hay).
 * "gap" cierra huecos pequeños entre trazos (como "cerrar huecos" de Flash).
 */
final class Fill {
    private Fill() { }

    static final class Result {
        /** Forma nueva de relleno (o null). */
        MDoc.Shape shape;
        /** Índice donde insertarla dentro del clave. */
        int at;
        /** Índice de una forma cerrada a la que basta cambiarle el relleno (o -1). */
        int recolor = -1;
        String error;
    }

    private static final int MAX = 900; // lado máximo de la imagen de trabajo (px)

    /**
     * Busca qué rellenar en el clave k tocando (x, y) en sus coordenadas. gapDoc = tamaño de hueco a cerrar,
     * en unidades del documento; inside = forma cerrada de más arriba que contiene el punto (o -1).
     */
    static Result find(MDoc.Key k, float x, float y, float gapDoc, int inside, int color) {
        Result r = new Result();
        // zona de trabajo: lo que ocupan las formas, con un margen
        RectF area = new RectF();
        boolean any = false;
        for (MDoc.Shape sh : k.shapes) {
            if (!barrier(sh)) continue;
            RectF b = Render.bounds(sh);
            if (!any) { area.set(b); any = true; } else area.union(b);
        }
        if (!any || !area.contains(x, y)) return leak(r, inside);
        float pad = Math.max(4, gapDoc * 2);
        area.inset(-pad, -pad);
        float sc = Math.min(4f, MAX / Math.max(area.width(), area.height()));
        int W = Math.max(8, (int) Math.ceil(area.width() * sc)), H = Math.max(8, (int) Math.ceil(area.height() * sc));
        float gapPx = gapDoc * sc;

        // 1) trazos como barreras (más gruesos según el hueco a cerrar)
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(bm);
        c.scale(sc, sc);
        c.translate(-area.left, -area.top);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xFF000000);
        Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillP.setColor(0xFF000000);
        for (MDoc.Shape sh : k.shapes) {
            if (!barrier(sh)) continue;
            if (sh.isInst()) { Render.shape(c, sh, 0xFF000000, 0, 1); continue; }
            stroke.setStrokeWidth(Math.max(sh.width, 1.2f / sc) + 2 * gapDoc);
            c.drawPath(Render.path(sh), stroke);
        }
        int[] px = new int[W * H];
        byte[] a = new byte[W * H];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(a);
        bm.copyPixelsToBuffer(buf);
        bm.recycle();

        // 2) inundar desde el toque (0 = libre, 1 = barrera, 2 = área)
        int sx = (int) ((x - area.left) * sc), sy = (int) ((y - area.top) * sc);
        if (sx < 0 || sy < 0 || sx >= W || sy >= H) return leak(r, inside);
        for (int i = 0; i < a.length; i++) px[i] = (a[i] & 0xFF) > 40 ? 1 : 0;
        if (px[sy * W + sx] != 0) {
            // tocó justo sobre un trazo: se busca un punto libre muy cerca
            int best = -1;
            for (int d = 1; d <= 6 && best < 0; d++)
                for (int dy = -d; dy <= d && best < 0; dy++)
                    for (int dx = -d; dx <= d; dx++) {
                        int qx = sx + dx, qy = sy + dy;
                        if (qx >= 0 && qy >= 0 && qx < W && qy < H && px[qy * W + qx] == 0) { best = qy * W + qx; break; }
                    }
            if (best < 0) { r.error = "Toca dentro del área, no sobre la línea"; return r; }
            sx = best % W; sy = best / W;
        }
        int count = flood(px, W, H, sx, sy);
        if (count < 0) return leak(r, inside);

        // 3) ¿es justo el interior de la forma cerrada tocada? entonces solo se le cambia el relleno
        if (inside >= 0) {
            MDoc.Shape S = k.shapes.get(inside);
            int interior = interiorPixels(S, area, sc, W, H);
            if (interior > 0 && count >= interior * 0.75f) { r.recolor = inside; return r; }
        }

        // 4) el área se agranda un poco para quedar bajo los trazos (el relleno va detrás de ellos)
        int grow = Math.max(1, Math.round(gapPx) + 1);
        if (inside >= 0) grow = 1;
        dilate(px, W, H, grow);

        // 5) contornos del área → forma vectorial
        ArrayList<float[]> loops = trace(px, W, H);
        if (loops.isEmpty()) return leak(r, inside);
        ArrayList<Float> nodes = new ArrayList<>();
        ArrayList<Integer> starts = new ArrayList<>();
        for (float[] loop : loops) {
            float[] simp = simplify(loop, 0.8f);
            if (simp.length < 6) continue;
            if (!nodes.isEmpty()) starts.add(nodes.size() / 6);
            for (int i = 0; i < simp.length; i += 2) {
                float dx = area.left + simp[i] / sc, dy = area.top + simp[i + 1] / sc;
                for (int j = 0; j < 3; j++) { nodes.add(dx); nodes.add(dy); }
            }
        }
        if (nodes.size() < 18) return leak(r, inside);
        MDoc.Shape sh = new MDoc.Shape();
        sh.n = new float[nodes.size()];
        for (int i = 0; i < sh.n.length; i++) sh.n[i] = nodes.get(i);
        if (!starts.isEmpty()) { sh.sub = new int[starts.size()]; for (int i = 0; i < sh.sub.length; i++) sh.sub[i] = starts.get(i); }
        sh.closed = true;
        sh.fill = color;
        sh.stroke = 0;
        sh.width = 0;
        r.shape = sh;
        // detrás de todo, o justo encima de la forma cerrada que lo contiene (para no quedar tapado por su relleno)
        r.at = inside >= 0 ? inside + 1 : 0;
        return r;
    }

    private static Result leak(Result r, int inside) {
        if (inside >= 0) r.recolor = inside;
        else r.error = "Esa área no está cerrada. Junta las líneas, o elige \"cerrar huecos\" más grandes (mantén presionado el cubo)";
        return r;
    }

    /** Lo que encierra áreas: todo menos los rellenos sueltos (formas sin trazo, como los que deja el cubo). */
    private static boolean barrier(MDoc.Shape sh) {
        if (sh.isInst()) return true;
        if (sh.count() == 0) return false;
        return sh.stroke != 0 && sh.width > 0;
    }

    private static int interiorPixels(MDoc.Shape S, RectF area, float sc, int W, int H) {
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
        for (byte b : a) if ((b & 0xFF) > 127) n++;
        return n;
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
