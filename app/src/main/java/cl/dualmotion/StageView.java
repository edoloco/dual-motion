package cl.dualmotion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * La mesa de trabajo (escenario): donde se dibuja, se selecciona y se mueve.
 * Va en el extendido (o abajo en modo solo). Un dedo usa la herramienta; dos dedos mueven y hacen zoom.
 */
public final class StageView extends View {

    public interface Host {
        MDoc doc();
        State st();
        /** Aplica la orden en el documento y la reenvía al otro teléfono. */
        void submit(JSONObject op);
        /** Cuentagotas: colores tomados de una forma. */
        void picked(int stroke, int fill, float width);
        /** Cambió la selección; inst = la instancia de símbolo si es lo único elegido. */
        void selChanged(int n, MDoc.Shape inst);
        /** Doble toque en una instancia: editar su símbolo en el lugar. */
        void enterSymbol(int layer, int frame, int index);
        void exitSymbol();
        int shownFrame();
        boolean playing();
        String status();
        void menu();
    }

    private final Host host;
    private final float dp;

    // vista: punto del documento → pantalla = p * s + t
    private float s = 1, tx, ty;
    private boolean fitted;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stageP = new Paint();
    private final Paint shadowP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);

    public StageView(Context c, Host h) {
        super(c);
        host = h;
        dp = c.getResources().getDisplayMetrics().density;
        stageP.setColor(0xFFFFFFFF);
        shadowP.setColor(0x22000000);
        textP.setTextSize(13 * dp);
        textP.setColor(Ui.INK);
        textP.setFakeBoldText(true);
    }

    // ---------------------------------------------------------------- vista
    public void fit() {
        MDoc d = host.doc();
        if (getWidth() == 0) return;
        float m = 24 * dp;
        s = Math.min((getWidth() - 2 * m) / d.w, (getHeight() - 2 * m - 30 * dp) / d.h);
        tx = (getWidth() - d.w * s) / 2;
        ty = (getHeight() - d.h * s) / 2 + 10 * dp;
        fitted = true;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (!fitted || ow == 0 || (w > h) != (ow > oh)) fit(); // también al girar el teléfono
    }

    private float dx(float x) { return (x - tx) / s; }
    private float dy(float y) { return (y - ty) / s; }

    // ---------------------------------------------------------------- selección
    private final ArrayList<Integer> sel = new ArrayList<>();
    private int selL = -1, selK = -1;

    public void clearSel() {
        if (!sel.isEmpty()) { sel.clear(); notifySel(); }
        subIdx = -1; subCopy = null;
        invalidate();
    }

    /** Capas de la línea de tiempo en que se trabaja (la escena o el símbolo que se edita). */
    private List<MDoc.Layer> timeline() { return host.doc().timeline(host.st().sym); }

    private MDoc.Layer curLayer() {
        List<MDoc.Layer> T = timeline();
        int l = host.st().layer;
        return l >= 0 && l < T.size() ? T.get(l) : null;
    }

    private void notifySel() {
        MDoc.Key k = curKey();
        MDoc.Shape inst = null;
        if (sel.size() == 1 && k != null && sel.get(0) < k.shapes.size() && k.shapes.get(sel.get(0)).isInst())
            inst = k.shapes.get(sel.get(0));
        host.selChanged(sel.size(), inst);
    }

    /** Lo que se ve de la capa actual (en un intermedio de interpolación, lo interpolado). */
    private MDoc.Key curKey() {
        MDoc.Layer L = curLayer();
        return L == null ? null : MDoc.shown(L, host.st().frame);
    }

    /** La selección vale solo para el clave donde se hizo. */
    private void validateSel() {
        MDoc.Layer L = curLayer();
        int k = L == null ? -1 : MDoc.keyIndex(L, host.st().frame);
        if (host.st().layer != selL || k != selK) { sel.clear(); selL = host.st().layer; selK = k; notifySel(); }
        MDoc.Key key = curKey();
        int n = key == null ? 0 : key.shapes.size();
        for (int i = sel.size() - 1; i >= 0; i--) if (sel.get(i) >= n) sel.remove(i);
    }

    public int selCount() { return sel.size(); }

    private void setSel(ArrayList<Integer> list) {
        sel.clear();
        sel.addAll(list);
        selL = host.st().layer;
        MDoc.Layer L = curLayer();
        selK = L == null ? -1 : MDoc.keyIndex(L, host.st().frame);
        notifySel();
    }

    // ---------------------------------------------------------------- órdenes
    private JSONObject op(String name) {
        JSONObject o = new JSONObject();
        try {
            o.put("op", name); o.put("l", host.st().layer); o.put("f", host.st().frame);
            if (host.st().inSymbol()) o.put("sym", host.st().sym);
        } catch (JSONException ignored) { }
        return o;
    }

    private void submitSet(ArrayList<Integer> idx, ArrayList<MDoc.Shape> shapes) {
        if (idx.isEmpty()) return;
        JSONObject o = op("set");
        try {
            JSONArray is = new JSONArray(), ss = new JSONArray();
            for (int i = 0; i < idx.size(); i++) { is.put(idx.get(i)); ss.put(shapes.get(i).toJson()); }
            o.put("is", is); o.put("ss", ss);
        } catch (JSONException ignored) { }
        host.submit(o);
    }

    private void submitAdd(MDoc.Shape sh) {
        JSONObject o = op("add");
        try { o.put("s", sh.toJson()); } catch (JSONException ignored) { }
        host.submit(o);
        // la forma nueva queda en el clave de este fotograma: la selección se recalcula
        validateSel();
    }

    /** Cambia colores/grosor de lo seleccionado (cuando el principal los cambia). which: "s", "f" o "w". */
    public void styleSelection(String which) {
        validateSel();
        MDoc.Key k = curKey();
        if (k == null || sel.isEmpty()) return;
        State st = host.st();
        ArrayList<Integer> idx = new ArrayList<>();
        ArrayList<MDoc.Shape> ss = new ArrayList<>();
        for (int i : sel) {
            MDoc.Shape sh = k.shapes.get(i).copy();
            if (sh.isObj()) continue; // los colores de un símbolo se cambian dentro de él; una imagen no tiene
            if ("s".equals(which)) { if (sh.brush) sh.stroke = st.fill != 0 ? st.fill : st.stroke; else sh.stroke = st.stroke; }
            else if ("f".equals(which)) { if (sh.brush) sh.stroke = st.fill != 0 ? st.fill : sh.stroke; else sh.fill = st.fill; }
            else if ("w".equals(which)) { sh.width = sh.brush ? st.width * 3 + 2 : st.width; }
            idx.add(i); ss.add(sh);
        }
        submitSet(idx, ss);
    }

    public void deleteSelection() {
        validateSel();
        if (sel.isEmpty()) { Ui.toast(getContext(), "No hay nada seleccionado"); return; }
        JSONObject o = op("del");
        try { o.put("is", new JSONArray(sel)); } catch (JSONException ignored) { }
        sel.clear();
        host.submit(o);
        notifySel();
    }

    public void orderSelection(boolean front) {
        validateSel();
        if (sel.isEmpty()) { Ui.toast(getContext(), "No hay nada seleccionado"); return; }
        JSONObject o = op("order");
        try { o.put("is", new JSONArray(sel)); o.put("to", front ? "front" : "back"); } catch (JSONException ignored) { }
        sel.clear();
        host.submit(o);
        notifySel();
    }

    /** Convierte lo seleccionado en un símbolo gráfico (queda una instancia en su lugar). */
    public void convertToSymbol() {
        validateSel();
        if (sel.isEmpty()) { Ui.toast(getContext(), "Selecciona primero lo que será el símbolo (herramienta Selección)"); return; }
        if (!editable()) return;
        JSONObject o = op("toSym");
        try {
            o.put("is", new JSONArray(sel));
            o.put("id", "y" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36));
        } catch (JSONException ignored) { }
        sel.clear();
        host.submit(o);
        notifySel();
        Ui.toast(getContext(), "Símbolo gráfico creado: toca dos veces para animarlo por dentro");
    }

    /** Cómo se repite el símbolo elegido: 0 repetir, 1 una vez, 2 un solo fotograma. */
    public void setInstLoop(int mode, int first) {
        validateSel();
        MDoc.Key k = curKey();
        if (k == null) return;
        ArrayList<Integer> idx = new ArrayList<>();
        ArrayList<MDoc.Shape> ss = new ArrayList<>();
        for (int i : sel) {
            MDoc.Shape sh = k.shapes.get(i);
            if (!sh.isInst()) continue;
            sh = sh.copy();
            if (mode >= 0) sh.loop = mode;
            if (first >= 0) sh.first = first;
            idx.add(i); ss.add(sh);
        }
        if (idx.isEmpty()) { Ui.toast(getContext(), "Selecciona un símbolo"); return; }
        submitSet(idx, ss);
        notifySel();
    }

    /** Separar (como Modificar › Separar de Flash): el símbolo elegido vuelve a ser dibujo normal. */
    public void breakApart() {
        validateSel();
        MDoc.Key k = curKey();
        ArrayList<Integer> is = new ArrayList<>();
        if (k != null) for (int i : sel) if (k.shapes.get(i).isInst()) is.add(i);
        if (is.isEmpty()) { Ui.toast(getContext(), "Selecciona un símbolo"); return; }
        if (!editable()) return;
        JSONObject o = op("breakApart");
        try { o.put("is", new JSONArray(is)); } catch (JSONException ignored) { }
        sel.clear();
        host.submit(o);
        notifySel();
        Ui.toast(getContext(), "Separado: ahora es dibujo normal (↶ para deshacer)");
    }

    /** Entra a editar el símbolo seleccionado (como el doble toque). */
    public void editSelectedSymbol() {
        validateSel();
        MDoc.Key k = curKey();
        if (k != null && sel.size() == 1 && k.shapes.get(sel.get(0)).isInst()) enter(sel.get(0));
        else Ui.toast(getContext(), "Selecciona un símbolo");
    }

    private void enter(int i) {
        if (host.st().inSymbol()) { Ui.toast(getContext(), "Primero vuelve a la escena (↩)"); return; }
        MDoc.Layer L = curLayer();
        int kf = L == null ? -1 : MDoc.keyIndex(L, host.st().frame);
        sel.clear();
        notifySel();
        host.enterSymbol(host.st().layer, host.st().frame, i);
        if (kf < 0) invalidate();
    }

    /** Al cambiar de herramienta: termina el trazado de la pluma. */
    public void toolChanged() {
        if (pen != null) finishPen(false);
        subIdx = -1; subCopy = null;
        invalidate();
    }

    // ---------------------------------------------------------------- dibujo
    private final RectF tmp = new RectF();
    private final RectF chipMenu = new RectF(), chipFit = new RectF(), chipDone = new RectF(), chipCancel = new RectF(),
            chipBack = new RectF();

    @Override protected void onDraw(Canvas c) {
        MDoc d = host.doc();
        State st = host.st();
        int f = host.shownFrame();
        boolean playing = host.playing();
        if (!playing) validateSel();
        c.drawColor(Ui.WORK);

        c.save();
        c.translate(tx, ty);
        c.scale(s, s);
        float hair = 1f / s;
        // sombra suave: tres rectángulos transparentes superpuestos
        for (int k = 1; k <= 3; k++)
            c.drawRect(k * 2 * hair, k * 3 * hair, d.w + k * 2 * hair, d.h + k * 3 * hair, shadowP);
        c.drawRect(0, 0, d.w, d.h, stageP);
        Render.use(d);
        boolean inSym = st.inSymbol() && !playing;
        List<MDoc.Layer> T = inSym ? timeline() : d.layers;
        if (inSym) {
            // editar en el lugar: la escena atenuada detrás y el símbolo encima, donde está su instancia
            c.saveLayerAlpha(null, 0x55);
            Render.skip(editInst());
            Render.frame(c, d, st.pf, true, false, hair);
            Render.skip(null);
            c.restore();
            c.save();
            c.concat(base(mtx2));
        }
        if (st.onion && !playing) Render.onion(c, T, f, 2, hair);

        for (int i = T.size() - 1; i >= 0; i--) {
            MDoc.Layer L = T.get(i);
            if (!L.vis || L.audio) continue;
            MDoc.Pose ps = i == st.layer && !playing ? curPose() : MDoc.pose(L, f);
            if (ps == null) continue;
            int outline = L.out ? L.color : 0;
            if (i != st.layer || playing) { Render.pose(c, ps, 0, outline, 1.5f * hair); continue; }
            MDoc.Key k = ps.key;
            int save = ps.alpha < 1 && outline == 0 ? c.saveLayerAlpha(null, Math.round(ps.alpha * 255)) : c.save();
            c.concat(Render.matrix(ps, mtx));
            Render.at(ps.frame - ps.keyFrame);
            float lh = 1.5f * hair / poseScale(ps);
            for (int j = 0; j < k.shapes.size(); j++) {
                MDoc.Shape sh = k.shapes.get(j);
                if (j == subIdx && subCopy != null) sh = subCopy;
                boolean mv = moving && sel.contains(j);
                if (erasing.contains(j)) { c.saveLayerAlpha(null, 0x40); Render.shape(c, sh, 0, outline, lh); c.restore(); continue; }
                if (mv) { c.save(); c.translate(mdx, mdy); }
                Render.shape(c, sh, 0, outline, lh);
                if (mv) c.restore();
            }
            c.restoreToCount(save);
        }

        if (!playing) {
            // lo que se dibuja sobre la capa actual va en sus coordenadas (con su transformación)
            MDoc.Pose cp = curPose();
            c.save();
            if (cp != null) { c.concat(Render.matrix(cp, mtx)); Render.at(cp.frame - cp.keyFrame); }
            drawOverlay(c, d, st, hair / poseScale(cp));
            c.restore();
            if (st.tool == State.FREE) drawFreeBox(c, hair);
        }
        if (inSym) {
            // punto de registro del símbolo (su centro)
            p(Paint.Style.STROKE, Ui.INK, 1.5f * hair);
            c.drawLine(-8 * dp * hair, 0, 8 * dp * hair, 0, p);
            c.drawLine(0, -8 * dp * hair, 0, 8 * dp * hair, p);
            c.restore();
        }
        c.restore();

        drawChips(c, playing);
    }

    private final Matrix mtx = new Matrix(), mtx2 = new Matrix(), inv = new Matrix(), invBase = new Matrix();

    /** La instancia por la que se entró a editar el símbolo (null si se está en la escena o ya no existe). */
    private MDoc.Shape editInst() {
        State st = host.st();
        if (!st.inSymbol()) return null;
        MDoc.Layer PL = host.doc().layer(st.pl);
        MDoc.Pose pp = PL == null ? null : MDoc.pose(PL, st.pf);
        if (pp == null || st.pi < 0 || st.pi >= pp.key.shapes.size()) return null;
        MDoc.Shape sh = pp.key.shapes.get(st.pi);
        return sh.isInst() && st.sym.equals(sh.sym) ? sh : null;
    }

    /** Matriz del símbolo que se edita en el lugar → escena (identidad si se está en la escena). */
    private Matrix base(Matrix out) {
        out.reset();
        State st = host.st();
        MDoc.Shape inst = editInst();
        if (inst == null) return out;
        MDoc.Pose pp = MDoc.pose(host.doc().layer(st.pl), st.pf);
        Render.matrix(pp, out);
        out.preTranslate(inst.n[0], inst.n[1]);
        return out;
    }

    /** Punto de la escena → coordenadas del símbolo que se edita (o la escena). */
    private float[] toBase(float x, float y) {
        pt[0] = x; pt[1] = y;
        invBase.mapPoints(pt);
        return pt;
    }
    private final float[] pt = new float[2];

    /** Pose de la capa actual en el fotograma que se ve (o la que se está transformando). */
    private MDoc.Pose curPose() {
        if (xfPose != null) return xfPose;
        MDoc.Layer L = curLayer();
        return L == null ? null : MDoc.pose(L, host.shownFrame());
    }

    private static float poseScale(MDoc.Pose p) {
        return p == null ? 1 : Math.max(0.01f, (Math.abs(p.sx) + Math.abs(p.sy)) / 2);
    }

    /** Punto del documento → coordenadas propias de la capa actual (deshace su transformación). */
    private void prepareLocal() {
        Matrix b = base(mtx2);
        b.invert(invBase);
        MDoc.Pose p = curPose();
        Matrix full = new Matrix();
        if (p != null) Render.matrix(p, full);
        full.postConcat(b);
        full.invert(inv);
        Render.use(host.doc());
        if (p != null) Render.at(p.frame - p.keyFrame);
    }

    private float[] local(float x, float y) {
        pt[0] = x; pt[1] = y;
        inv.mapPoints(pt);
        return pt;
    }

    private void drawOverlay(Canvas c, MDoc d, State st, float hair) {
        MDoc.Key k = curKey();
        // selección: rectángulo punteado azul alrededor de cada forma
        if (k != null && (st.tool == State.SEL || st.tool == State.SUB)) {
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Ui.BLUE);
            p.setStrokeWidth(1.5f * hair);
            p.setPathEffect(new DashPathEffect(new float[]{5 * hair, 4 * hair}, 0));
            for (int i : sel) {
                if (i >= k.shapes.size()) continue;
                tmp.set(Render.bounds(i == subIdx && subCopy != null ? subCopy : k.shapes.get(i)));
                if (moving) tmp.offset(mdx, mdy);
                tmp.inset(-3 * hair, -3 * hair);
                c.drawRect(tmp, p);
            }
            p.setPathEffect(null);
            if (st.tool == State.SUB) for (int i : sel) {
                if (i >= k.shapes.size() || k.shapes.get(i).isObj()) continue;
                drawNodes(c, i == subIdx && subCopy != null ? subCopy : k.shapes.get(i), hair, -1);
            }
        }
        if (marquee) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x222F7BE0);
            tmp.set(Math.min(sx, cx), Math.min(sy, cy), Math.max(sx, cx), Math.max(sy, cy));
            c.drawRect(tmp, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Ui.BLUE);
            p.setStrokeWidth(hair);
            c.drawRect(tmp, p);
        }
        if (temp != null) Render.shape(c, temp, 0, 0, hair);
        if (pen != null) {
            MDoc.Shape ps = penShape(false);
            Render.shape(c, ps, 0, 0, hair);
            drawNodes(c, ps, hair, 0);
        }
    }

    private void drawNodes(Canvas c, MDoc.Shape sh, float hair, int highlight) {
        float r = 4.5f * dp * hair, hr = 3.5f * dp * hair;
        p.setStrokeWidth(hair * 1.2f);
        for (int i = 0; i < sh.count(); i++) {
            float x = sh.n[i * 6], y = sh.n[i * 6 + 1];
            float ix = sh.n[i * 6 + 2], iy = sh.n[i * 6 + 3], ox = sh.n[i * 6 + 4], oy = sh.n[i * 6 + 5];
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Ui.BLUE);
            if (ix != x || iy != y) { c.drawLine(x, y, ix, iy, p); c.drawCircle(ix, iy, hr, p); }
            if (ox != x || oy != y) { c.drawLine(x, y, ox, oy, p); c.drawCircle(ox, oy, hr, p); }
            float rr = i == highlight ? r * 1.6f : r;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            c.drawRect(x - rr, y - rr, x + rr, y + rr, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(i == highlight ? Ui.ORANGE : Ui.BLUE);
            c.drawRect(x - rr, y - rr, x + rr, y + rr, p);
        }
    }

    private void chip(Canvas c, RectF r, String text, boolean dark) {
        chipP.setStyle(Paint.Style.FILL);
        chipP.setColor(dark ? 0xEE2FA866 : 0xEEFFFFFF);
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, chipP);
        chipP.setStyle(Paint.Style.STROKE);
        chipP.setStrokeWidth(dp);
        chipP.setColor(0x992FA866);
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, chipP);
        textP.setColor(dark ? 0xFFFFFFFF : Ui.INK);
        float tw = textP.measureText(text);
        c.drawText(text, r.centerX() - tw / 2, r.centerY() + textP.getTextSize() * 0.35f, textP);
    }

    private void drawChips(Canvas c, boolean playing) {
        float h = 30 * dp, m = 10 * dp;
        String st = host.status();
        float w = textP.measureText(st) + 28 * dp;
        tmp.set(m, m, m + w, m + h);
        chip(c, tmp, st, false);
        if (host.st().inSymbol() && !playing) {
            String b = "↩ " + host.doc().scene;
            float bw = textP.measureText(b) + 28 * dp;
            chipBack.set(m + w + 8 * dp, m, m + w + 8 * dp + bw, m + h);
            chip(c, chipBack, b, true);
        } else chipBack.setEmpty();
        chipMenu.set(getWidth() - m - h * 1.4f, m, getWidth() - m, m + h);
        chip(c, chipMenu, "☰", false);
        String z = Math.round(s * 100) + " %";
        float zw = textP.measureText(z) + 28 * dp;
        chipFit.set(getWidth() - m - zw, getHeight() - m - h, getWidth() - m, getHeight() - m);
        chip(c, chipFit, z, false);
        if (pen != null && !playing) {
            float bw = 120 * dp;
            chipDone.set(getWidth() / 2f - bw - 6 * dp, getHeight() - m - h, getWidth() / 2f - 6 * dp, getHeight() - m);
            chipCancel.set(getWidth() / 2f + 6 * dp, getHeight() - m - h, getWidth() / 2f + 6 * dp + bw, getHeight() - m);
            chip(c, chipDone, "✓ Terminar", true);
            chip(c, chipCancel, "✕ Cancelar", false);
        } else { chipDone.setEmpty(); chipCancel.setEmpty(); }
    }

    // ---------------------------------------------------------------- toque
    private boolean pinching, gesture, blocked;
    private float pd0, ps0, pmx, pmy, pdocX, pdocY;
    private float sx, sy, cx, cy;          // inicio y punto actual (documento)
    private float lsx, lsy;                // último punto en pantalla (mano)
    private boolean moving, marquee, dragged;
    private float mdx, mdy;
    private MDoc.Shape temp;
    private ArrayList<float[]> pts;
    private final HashSet<Integer> erasing = new HashSet<>();
    private ArrayList<float[]> pen;        // nodos de la pluma en construcción
    private boolean penClosing, penFinishing, penNew;
    private int subIdx = -1, subNode = -1, subPart;
    private long lastTap;
    private boolean ghost;
    private int lastTapI = -2, lastHit = -1;
    private MDoc.Shape subCopy;

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (e.getPointerCount() >= 2) {
            if (!pinching) { cancelGesture(); pinchStart(e); }
            else pinchMove(e);
            return true;
        }
        if (pinching) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) pinching = false;
            return true;
        }
        float x = e.getX(), y = e.getY();
        // toques que no son del usuario dibujando (como en DUAL-Draw): la palma o el canto de la mano (toque muy
        // ancho) y los que empiezan pegados al borde (la unión entre los teléfonos, la mano que los sostiene)
        if (a == MotionEvent.ACTION_DOWN) {
            float mm = getResources().getDisplayMetrics().xdpi / 25.4f;
            boolean finger = e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
            boolean edge = x < 4 * dp || y < 4 * dp || x > getWidth() - 4 * dp || y > getHeight() - 4 * dp;
            ghost = finger && (e.getTouchMajor() > 20 * mm || edge);
        }
        if (ghost) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) ghost = false;
            return true;
        }
        if (a == MotionEvent.ACTION_DOWN) {
            if (chipMenu.contains(x, y)) { host.menu(); return true; }
            if (chipFit.contains(x, y)) { fit(); return true; }
            if (chipDone.contains(x, y)) { finishPen(false); return true; }
            if (chipCancel.contains(x, y)) { pen = null; invalidate(); return true; }
            if (host.playing()) return true;
            gesture = true;
            blocked = false;
            lsx = x; lsy = y;
            if (chipBack.contains(x, y)) { host.exitSymbol(); return true; }
            prepareLocal();
            if (host.st().tool == State.FREE) { float[] b = toBase(dx(x), dy(y)); freeDown(b[0], b[1]); invalidate(); return true; }
            float[] q = local(dx(x), dy(y));
            down(q[0], q[1]);
        } else if (!gesture) {
            return true;
        } else if (a == MotionEvent.ACTION_MOVE) {
            if (host.st().tool == State.FREE) { float[] b = toBase(dx(x), dy(y)); freeMove(b[0], b[1]); }
            else { float[] q = local(dx(x), dy(y)); move(q[0], q[1], x, y); }
            lsx = x; lsy = y;
        } else if (a == MotionEvent.ACTION_UP) {
            gesture = false;
            int tool = host.st().tool;
            // un toque cortísimo sin mover el dedo no deja un punto con el lápiz o el pincel (suele ser un roce);
            // para hacer un punto a propósito basta mantener el dedo un instante
            if ((tool == State.PENCIL || tool == State.BRUSH) && !dragged && e.getEventTime() - e.getDownTime() < 120) {
                cancelGesture();
                return true;
            }
            if (tool == State.FREE) freeUp();
            else { float[] q = local(dx(x), dy(y)); up(q[0], q[1]); }
        } else if (a == MotionEvent.ACTION_CANCEL) {
            cancelGesture();
        }
        invalidate();
        return true;
    }

    private void pinchStart(MotionEvent e) {
        pinching = true;
        pd0 = Math.max(1, dist(e));
        ps0 = s;
        pmx = (e.getX(0) + e.getX(1)) / 2;
        pmy = (e.getY(0) + e.getY(1)) / 2;
        pdocX = dx(pmx); pdocY = dy(pmy);
    }

    private void pinchMove(MotionEvent e) {
        float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
        s = Math.max(0.05f, Math.min(30f, ps0 * dist(e) / pd0));
        tx = mx - pdocX * s;
        ty = my - pdocY * s;
        invalidate();
    }

    private static float dist(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private void cancelGesture() {
        gesture = false;
        moving = marquee = false;
        temp = null; pts = null;
        erasing.clear();
        subCopy = null; subNode = -1;
        xfPose = null; xfMode = 0;
        if (pen != null && penNew && !pen.isEmpty()) pen.remove(pen.size() - 1);
        penNew = penClosing = penFinishing = false;
        invalidate();
    }

    /** ¿Se puede editar la capa actual? Si no, avisa una vez por gesto. */
    private boolean editable() {
        MDoc.Layer L = curLayer();
        if (L != null && L.vis && !L.lock && !L.audio) return true;
        if (!blocked) Ui.toast(getContext(), L == null ? "No hay capa" : L.audio ? "Es una capa de sonido: elige una capa de dibujo"
                : !L.vis ? "La capa está oculta" : "La capa está bloqueada");
        blocked = true;
        return false;
    }

    private float tol() { return 12 * dp / s; }

    private void down(float x, float y) {
        State st = host.st();
        sx = cx = x; sy = cy = y;
        dragged = false;
        switch (st.tool) {
            case State.HAND: return;
            case State.SEL: {
                if (curLayer() == null) return;
                MDoc.Key k = curKey();
                int i = Render.hitKey(k, x, y, tol(), Render.HIT_FILL | Render.HIT_STROKE);
                lastHit = i;
                if (i >= 0) {
                    if (!sel.contains(i)) { ArrayList<Integer> l = new ArrayList<>(); l.add(i); setSel(l); }
                    moving = true; mdx = mdy = 0;
                } else marquee = true;
                return;
            }
            case State.SUB: subDown(x, y); return;
            case State.PEN: penDown(x, y); return;
            case State.LINE: case State.RECT: case State.OVAL:
                if (!editable()) return;
                temp = new MDoc.Shape();
                return;
            case State.PENCIL: case State.BRUSH:
                if (!editable()) return;
                pts = new ArrayList<>();
                pts.add(new float[]{x, y});
                temp = new MDoc.Shape();
                freehandShape();
                return;
            case State.ERASER:
                if (!editable()) return;
                if (st.eraseMode == Brush.E_FAUCET) { eraseAt(x, y); return; }
                pts = new ArrayList<>();
                pts.add(new float[]{x, y});
                temp = eraserPreview();
                return;
            case State.BUCKET: case State.INK: case State.DROPPER:
                return; // actúan al soltar
        }
    }

    private void move(float x, float y, float sxp, float syp) {
        State st = host.st();
        if (Math.hypot((x - sx) * s, (y - sy) * s) > 6 * dp) dragged = true;
        cx = x; cy = y;
        switch (st.tool) {
            case State.HAND:
                tx += sxp - lsx; ty += syp - lsy;
                return;
            case State.SEL:
                if (moving) { mdx = x - sx; mdy = y - sy; }
                return;
            case State.SUB: subMove(x, y); return;
            case State.PEN: penMove(x, y); return;
            case State.LINE: case State.RECT: case State.OVAL:
                if (temp != null) buildPrimitive(st.tool);
                return;
            case State.PENCIL: case State.BRUSH:
                if (pts == null) return;
                float[] last = pts.get(pts.size() - 1);
                if (Math.hypot(x - last[0], y - last[1]) * s >= 2 * dp) { pts.add(new float[]{x, y}); freehandShape(); }
                return;
            case State.ERASER:
                if (blocked) return;
                if (st.eraseMode == Brush.E_FAUCET) { eraseAt(x, y); return; }
                if (pts == null) return;
                float[] le = pts.get(pts.size() - 1);
                if (Math.hypot(x - le[0], y - le[1]) * s >= 2 * dp) { pts.add(new float[]{x, y}); temp = eraserPreview(); }
        }
    }

    /** Ancho del pincel y del borrador (en unidades del documento). */
    private float brushWidth() { return host.st().width * 3 + 2; }

    /** Muestra por dónde pasa el borrador (franja translúcida). */
    private MDoc.Shape eraserPreview() {
        MDoc.Shape e = new MDoc.Shape();
        e.n = Render.smooth(pts, 0.6f);
        if (pts.size() == 1) { float[] q = pts.get(0); e.n = new float[]{q[0], q[1], q[0], q[1], q[0], q[1]}; }
        e.stroke = 0x66E53935;
        e.width = brushWidth() + 2;
        e.brush = true;
        e.dirty();
        return e;
    }

    /** Píxeles de pantalla por unidad de la capa (para la resolución del pincel y el borrador). */
    private float pxPerUnit() { return s * poseScale(curPose()); }

    private void up(float x, float y) {
        State st = host.st();
        cx = x; cy = y;
        switch (st.tool) {
            case State.SEL: {
                long now = System.currentTimeMillis();
                if (!dragged) {
                    int hitI = moving && sel.size() >= 1 ? lastHit : -1;
                    boolean dbl = now - lastTap < 450 && hitI == lastTapI;
                    lastTap = now; lastTapI = hitI;
                    if (dbl) {
                        MDoc.Key k = curKey();
                        if (hitI >= 0 && k != null && hitI < k.shapes.size() && k.shapes.get(hitI).isInst()) {
                            moving = false; enter(hitI); lastTap = 0; return;
                        }
                        if (hitI < 0 && host.st().inSymbol()) { marquee = false; lastTap = 0; host.exitSymbol(); return; }
                    }
                }
                if (moving) {
                    moving = false;
                    if (dragged && editable()) {
                        MDoc.Key k = curKey();
                        ArrayList<MDoc.Shape> ss = new ArrayList<>();
                        ArrayList<Integer> idx = new ArrayList<>();
                        if (k != null) for (int i : sel) {
                            MDoc.Shape sh = k.shapes.get(i).copy();
                            sh.translate(mdx, mdy);
                            idx.add(i); ss.add(sh);
                        }
                        submitSet(idx, ss);
                    }
                    mdx = mdy = 0;
                } else if (marquee) {
                    marquee = false;
                    ArrayList<Integer> l = new ArrayList<>();
                    MDoc.Key k = curKey();
                    if (dragged && k != null) {
                        RectF r = new RectF(Math.min(sx, x), Math.min(sy, y), Math.max(sx, x), Math.max(sy, y));
                        for (int i = 0; i < k.shapes.size(); i++)
                            if (RectF.intersects(r, Render.bounds(k.shapes.get(i)))) l.add(i);
                    }
                    setSel(l);
                }
                return;
            }
            case State.SUB: subUp(); return;
            case State.PEN: penUp(); return;
            case State.LINE: case State.RECT: case State.OVAL:
                if (temp != null && dragged) { buildPrimitive(st.tool); submitAdd(temp); }
                temp = null;
                return;
            case State.BRUSH:
                if (pts != null) {
                    pts.add(new float[]{x, y});
                    MDoc.Key bk = curKey();
                    int col = st.fill != 0 ? st.fill : (st.stroke != 0 ? st.stroke : 0xFF000000);
                    Brush.Paint1 r = bk == null ? null : Brush.paint(bk, pts, brushWidth(), col, st.brushMode, pxPerUnit());
                    if (r != null && r.shape != null) {
                        JSONObject o = op("fillArea");
                        try { o.put("s", r.shape.toJson()); o.put("at", r.at); o.put("rm", new JSONArray()); } catch (JSONException ignored) { }
                        host.submit(o);
                        validateSel();
                    } else if (r != null && r.error != null) Ui.toast(getContext(), r.error);
                }
                pts = null; temp = null;
                return;
            case State.PENCIL:
                if (pts != null) {
                    pts.add(new float[]{x, y});
                    freehandShape();
                    submitAdd(temp);
                }
                pts = null; temp = null;
                return;
            case State.ERASER:
                if (st.eraseMode != Brush.E_FAUCET) {
                    if (pts != null && !blocked) {
                        pts.add(new float[]{x, y});
                        MDoc.Key ek = curKey();
                        Brush.Cut cut = ek == null ? null : Brush.erase(ek, pts, brushWidth(), st.eraseMode, pxPerUnit());
                        if (cut != null && !cut.index.isEmpty()) {
                            JSONObject o = op("replace");
                            try {
                                JSONArray rep = new JSONArray();
                                for (int j = 0; j < cut.index.size(); j++) {
                                    JSONObject e = new JSONObject();
                                    e.put("i", cut.index.get(j));
                                    JSONArray ss = new JSONArray();
                                    for (MDoc.Shape part : cut.parts.get(j)) ss.put(part.toJson());
                                    e.put("ss", ss);
                                    rep.put(e);
                                }
                                o.put("rep", rep);
                            } catch (JSONException ignored) { }
                            host.submit(o);
                            validateSel();
                        }
                    }
                    pts = null; temp = null;
                    return;
                }
                if (!erasing.isEmpty()) {
                    JSONObject o = op("del");
                    try { o.put("is", new JSONArray(new ArrayList<>(erasing))); } catch (JSONException ignored) { }
                    erasing.clear();
                    host.submit(o);
                }
                return;
            case State.BUCKET: {
                if (dragged || !editable()) return;
                MDoc.Key k = curKey();
                if (k == null) return;
                if (st.fill == 0) { Ui.toast(getContext(), "Elige un color de relleno"); return; }
                // un símbolo no se pinta desde afuera: se edita por dentro (como en Flash)
                int top = Render.hitKey(k, x, y, 0, Render.HIT_FILL | Render.HIT_STROKE | Render.HIT_INSIDE);
                if (top >= 0 && k.shapes.get(top).isImg()) { Ui.toast(getContext(), "Es una imagen: el cubo no la pinta"); return; }
                if (top >= 0 && k.shapes.get(top).isInst()) {
                    Ui.toast(getContext(), "Es un símbolo: tócalo dos veces con Selección para pintarlo por dentro");
                    return;
                }
                // como el balde de DUAL-Draw: la zona del color tocado (con tolerancia), cerrando huecos
                Fill.Opts fo = new Fill.Opts();
                fo.tol = st.tol;
                fo.gapDoc = st.gap * dp / (s * poseScale(curPose()));
                fo.all = st.fillAll;
                fo.color = st.fill;
                final MDoc.Pose cp = curPose();
                RectF stageR = new RectF(0, 0, host.doc().w, host.doc().h);
                Matrix toLocal = new Matrix();
                {   // escena → coordenadas de esta capa (deshace la pose de la capa y, dentro de un símbolo, su instancia)
                    Matrix full = new Matrix();
                    if (cp != null) Render.matrix(cp, full);
                    full.postConcat(base(new Matrix()));
                    full.invert(toLocal);
                    toLocal.mapRect(stageR);
                }
                final Matrix tl = toLocal;
                Fill.Result r = Fill.find(k, x, y, fo, stageR, c -> {
                    c.save();
                    c.concat(tl);
                    Render.frame(c, host.doc(), host.st().inSymbol() ? host.st().pf : host.st().frame, true, false, 1);
                    c.restore();
                });
                if (r.recolor >= 0) {
                    MDoc.Shape sh = k.shapes.get(r.recolor).copy();
                    sh.fill = st.fill;
                    one(r.recolor, sh);
                } else if (r.shape != null) {
                    JSONObject o = op("fillArea");
                    try { o.put("s", r.shape.toJson()); o.put("at", r.at); o.put("rm", new JSONArray(r.remove)); } catch (JSONException ignored) { }
                    host.submit(o);
                    validateSel();
                } else if (r.error != null) Ui.toast(getContext(), r.error);
                return;
            }
            case State.INK: {
                if (dragged || !editable()) return;
                MDoc.Key k = curKey();
                int i = Render.hitKey(k, x, y, tol(), Render.HIT_STROKE | Render.HIT_FILL);
                if (i < 0) return;
                MDoc.Shape sh = k.shapes.get(i).copy();
                if (sh.isObj()) return;
                if (st.stroke == 0 && !sh.closed) { Ui.toast(getContext(), "Una línea abierta necesita color de trazo"); return; }
                sh.stroke = st.stroke;
                if (!sh.brush) sh.width = st.width;
                one(i, sh);
                return;
            }
            case State.DROPPER: {
                if (dragged) return;
                MDoc.Key k = curKey();
                int i = Render.hitKey(k, x, y, tol(), Render.HIT_STROKE | Render.HIT_FILL);
                if (i < 0) return;
                MDoc.Shape sh = k.shapes.get(i);
                if (sh.isObj()) return;
                if (sh.brush) host.picked(st.stroke, sh.stroke, Math.max(1, (sh.width - 2) / 3));
                else host.picked(sh.stroke, sh.closed ? sh.fill : st.fill, sh.width);
            }
        }
    }

    private void one(int i, MDoc.Shape sh) {
        ArrayList<Integer> idx = new ArrayList<>(); idx.add(i);
        ArrayList<MDoc.Shape> ss = new ArrayList<>(); ss.add(sh);
        submitSet(idx, ss);
    }

    private boolean instWarned;

    private void eraseAt(float x, float y) {
        MDoc.Key k = curKey();
        if (k == null) return;
        for (int i = 0; i < k.shapes.size(); i++) {
            MDoc.Shape sh = k.shapes.get(i);
            if (erasing.contains(i) || !Render.hit(sh, x, y, 8 * dp / s, Render.HIT_FILL | Render.HIT_STROKE)) continue;
            if (sh.isObj()) { // el borrador no borra símbolos ni imágenes (como en Flash)
                if (!instWarned) Ui.toast(getContext(), "El borrador no borra símbolos ni imágenes: elígelos con Selección y toca ✕");
                instWarned = true;
                continue;
            }
            erasing.add(i);
        }
    }

    // ---------------------------------------------------------------- formas básicas y mano alzada
    private void style(MDoc.Shape sh, boolean closed) {
        State st = host.st();
        sh.closed = closed;
        sh.width = st.width;
        sh.stroke = st.stroke;
        sh.fill = closed ? st.fill : 0;
        if (sh.stroke == 0 && sh.fill == 0) sh.stroke = 0xFF000000;
        sh.dirty();
    }

    private void buildPrimitive(int tool) {
        float x0 = Math.min(sx, cx), y0 = Math.min(sy, cy), x1 = Math.max(sx, cx), y1 = Math.max(sy, cy);
        if (tool == State.LINE) {
            temp.n = concat(Render.corner(sx, sy), Render.corner(cx, cy));
            style(temp, false);
            if (temp.stroke == 0) temp.stroke = 0xFF000000;
        } else if (tool == State.RECT) {
            temp.n = concat(concat(Render.corner(x0, y0), Render.corner(x1, y0)),
                    concat(Render.corner(x1, y1), Render.corner(x0, y1)));
            style(temp, true);
        } else {
            float rx = (x1 - x0) / 2, ry = (y1 - y0) / 2, ox = x0 + rx, oy = y0 + ry, k = 0.5523f;
            temp.n = new float[]{
                    ox, oy - ry, ox - k * rx, oy - ry, ox + k * rx, oy - ry,
                    ox + rx, oy, ox + rx, oy - k * ry, ox + rx, oy + k * ry,
                    ox, oy + ry, ox + k * rx, oy + ry, ox - k * rx, oy + ry,
                    ox - rx, oy, ox - rx, oy + k * ry, ox - rx, oy - k * ry};
            style(temp, true);
        }
        temp.dirty();
    }

    private void freehandShape() {
        State st = host.st();
        temp.n = Render.smooth(pts, 1.2f * dp / s);
        temp.closed = false;
        temp.fill = 0;
        if (st.tool == State.BRUSH) {
            temp.brush = true;
            temp.stroke = st.fill != 0 ? st.fill : (st.stroke != 0 ? st.stroke : 0xFF000000);
            temp.width = st.width * 3 + 2;
        } else {
            temp.stroke = st.stroke != 0 ? st.stroke : 0xFF000000;
            temp.width = st.width;
        }
        temp.dirty();
    }

    private static float[] concat(float[] a, float[] b) {
        float[] r = new float[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // ---------------------------------------------------------------- pluma (como FreeHand)
    // tocar = punto recto, arrastrar = curva, tocar el primer punto = cerrar, tocar el último = terminar.
    private void penDown(float x, float y) {
        if (!editable()) return;
        if (pen == null) pen = new ArrayList<>();
        penClosing = penFinishing = penNew = false;
        float t = tol();
        if (pen.size() >= 2) {
            float[] f0 = pen.get(0), fl = pen.get(pen.size() - 1);
            if (Math.hypot(x - f0[0], y - f0[1]) < t) { penClosing = true; return; }
            if (Math.hypot(x - fl[0], y - fl[1]) < t) { penFinishing = true; return; }
        }
        pen.add(Render.corner(x, y));
        penNew = true;
    }

    private void penMove(float x, float y) {
        if (pen == null || pen.isEmpty()) return;
        float[] nd = penClosing ? pen.get(0) : penNew ? pen.get(pen.size() - 1) : null;
        if (nd == null || !dragged) return;
        // arrastrar: manilla de salida hacia el dedo, la de entrada en espejo
        nd[4] = x; nd[5] = y;
        nd[2] = 2 * nd[0] - x; nd[3] = 2 * nd[1] - y;
        if (penClosing) { nd[2] = x; nd[3] = y; nd[4] = 2 * nd[0] - x; nd[5] = 2 * nd[1] - y; }
    }

    private void penUp() {
        if (pen == null) return;
        if (penClosing) finishPen(true);
        else if (penFinishing) finishPen(false);
        penNew = penClosing = penFinishing = false;
    }

    private MDoc.Shape penShape(boolean closed) {
        MDoc.Shape sh = new MDoc.Shape();
        float[] n = new float[pen.size() * 6];
        for (int i = 0; i < pen.size(); i++) System.arraycopy(pen.get(i), 0, n, i * 6, 6);
        sh.n = n;
        style(sh, closed);
        if (!closed && sh.stroke == 0) sh.stroke = 0xFF000000;
        return sh;
    }

    private void finishPen(boolean close) {
        if (pen != null && pen.size() >= 2) {
            MDoc.Shape sh = penShape(close && pen.size() >= 3);
            pen = null;
            submitAdd(sh);
        } else pen = null;
        invalidate();
    }

    // ---------------------------------------------------------------- subselección: mover nodos y manillas
    private void subDown(float x, float y) {
        MDoc.Key k = curKey();
        if (k == null) return;
        float t = tol();
        subIdx = -1; subNode = -1;
        for (int si : sel) {
            if (si >= k.shapes.size()) continue;
            MDoc.Shape sh = k.shapes.get(si);
            if (sh.isObj()) continue;
            for (int i = 0; i < sh.count(); i++) {
                float nx = sh.n[i * 6], ny = sh.n[i * 6 + 1];
                for (int part = 1; part <= 2; part++) { // primero las manillas (si están separadas del nodo)
                    float hx = sh.n[i * 6 + part * 2], hy = sh.n[i * 6 + part * 2 + 1];
                    if ((hx != nx || hy != ny) && Math.hypot(x - hx, y - hy) < t) { subStart(si, i, part, sh); return; }
                }
                if (Math.hypot(x - nx, y - ny) < t) { subStart(si, i, 0, sh); return; }
            }
        }
        int i = Render.hitKey(k, x, y, tol(), Render.HIT_FILL | Render.HIT_STROKE);
        ArrayList<Integer> l = new ArrayList<>();
        if (i >= 0) l.add(i);
        setSel(l);
    }

    private void subStart(int si, int node, int part, MDoc.Shape sh) {
        if (!editable()) return;
        subIdx = si; subNode = node; subPart = part;
        subCopy = sh.copy();
    }

    private void subMove(float x, float y) {
        if (subCopy == null || subNode < 0) return;
        float[] n = subCopy.n;
        int b = subNode * 6;
        if (subPart == 0) {
            float ddx = x - n[b], ddy = y - n[b + 1];
            for (int j = 0; j < 6; j += 2) { n[b + j] += ddx; n[b + j + 1] += ddy; }
        } else {
            n[b + subPart * 2] = x; n[b + subPart * 2 + 1] = y;
        }
        subCopy.dirty();
    }

    private void subUp() {
        if (subCopy != null && subNode >= 0 && dragged) one(subIdx, subCopy);
        subCopy = null; subNode = -1;
    }

    // ---------------------------------------------------------------- transformación libre (todo el clave)
    // Dentro del cuadro = mover; manillas de las esquinas = escalar; de los lados = escalar en un sentido;
    // un poco por fuera de las esquinas = girar. Escala y giro son alrededor del centro.
    private MDoc.Pose xfPose, xf0;
    private int xfMode; // 1 mover, 2 girar, 3 escalar
    private int xfHx, xfHy;
    private float xfSx, xfSy, xfA0, xfVx, xfVy;
    private final RectF lb = new RectF();

    /** Rectángulo (en coordenadas propias) que ocupan las formas del clave. */
    private boolean localBounds(MDoc.Key k, RectF out) {
        out.setEmpty();
        boolean any = false;
        for (MDoc.Shape sh : k.shapes) {
            if (!any) { out.set(Render.bounds(sh)); any = true; } else out.union(Render.bounds(sh));
        }
        return any;
    }

    private float[] corners(MDoc.Pose p, RectF b) {
        float[] q = {b.left, b.top, b.centerX(), b.top, b.right, b.top, b.right, b.centerY(),
                b.right, b.bottom, b.centerX(), b.bottom, b.left, b.bottom, b.left, b.centerY()};
        Render.matrix(p, mtx).mapPoints(q);
        return q;
    }

    private static final int[][] HANDLE = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};

    private static MDoc.Pose copyPose(MDoc.Pose a) {
        MDoc.Pose p = new MDoc.Pose();
        p.key = a.key; p.keyFrame = a.keyFrame; p.tweening = a.tweening;
        p.tx = a.tx; p.ty = a.ty; p.sx = a.sx; p.sy = a.sy; p.rot = a.rot; p.alpha = a.alpha; p.px = a.px; p.py = a.py;
        return p;
    }

    private void freeDown(float x, float y) {
        sx = x; sy = y; dragged = false; xfMode = 0;
        MDoc.Layer L = curLayer();
        MDoc.Pose p = L == null ? null : MDoc.pose(L, host.st().frame);
        if (p == null || !localBounds(p.key, lb)) { Ui.toast(getContext(), "No hay nada que transformar en este fotograma"); return; }
        if (!editable()) return;
        xf0 = copyPose(p);
        // sin giro ni escala, el centro se puede cambiar sin mover nada: se usa el centro del dibujo
        if (xf0.sx == 1 && xf0.sy == 1 && xf0.rot == 0) { xf0.px = lb.centerX(); xf0.py = lb.centerY(); }
        float[] q = corners(xf0, lb);
        float t = tol();
        for (int i = 0; i < 8; i++) {
            if (Math.hypot(x - q[i * 2], y - q[i * 2 + 1]) < t) { xfMode = 3; xfHx = HANDLE[i][0]; xfHy = HANDLE[i][1]; break; }
        }
        Render.matrix(xf0, mtx).invert(inv);
        float[] lp = local(x, y);
        boolean inside = lb.contains(lp[0], lp[1]);
        if (xfMode == 0 && !inside) {
            for (int i = 0; i < 8; i += 2)
                if (Math.hypot(x - q[i * 2], y - q[i * 2 + 1]) < t * 3.5f) { xfMode = 2; break; }
        }
        if (xfMode == 0 && inside) xfMode = 1;
        if (xfMode == 0) return;
        float cx0 = xf0.px + xf0.tx, cy0 = xf0.py + xf0.ty;
        xfA0 = (float) Math.atan2(y - cy0, x - cx0);
        float[] v = unrot(x - cx0, y - cy0, xf0.rot);
        xfVx = v[0]; xfVy = v[1]; xfSx = xf0.sx; xfSy = xf0.sy;
        xfPose = copyPose(xf0);
    }

    private static float[] unrot(float vx, float vy, float deg) {
        double a = Math.toRadians(-deg);
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[]{vx * c - vy * s, vx * s + vy * c};
    }

    private void freeMove(float x, float y) {
        if (xfPose == null) return;
        if (Math.hypot((x - sx) * s, (y - sy) * s) > 4 * dp) dragged = true;
        float cx0 = xf0.px + xf0.tx, cy0 = xf0.py + xf0.ty;
        if (xfMode == 1) { xfPose.tx = xf0.tx + x - sx; xfPose.ty = xf0.ty + y - sy; }
        else if (xfMode == 2) {
            float a = (float) Math.atan2(y - cy0, x - cx0);
            xfPose.rot = xf0.rot + (float) Math.toDegrees(a - xfA0);
        } else if (xfMode == 3) {
            float[] v = unrot(x - cx0, y - cy0, xf0.rot);
            if (xfHx != 0 && Math.abs(xfVx) > 1e-3f) xfPose.sx = xfSx * v[0] / xfVx;
            if (xfHy != 0 && Math.abs(xfVy) > 1e-3f) xfPose.sy = xfSy * v[1] / xfVy;
            if (Math.abs(xfPose.sx) < 0.02f) xfPose.sx = Math.copySign(0.02f, xfPose.sx);
            if (Math.abs(xfPose.sy) < 0.02f) xfPose.sy = Math.copySign(0.02f, xfPose.sy);
        }
    }

    private void freeUp() {
        MDoc.Pose p = xfPose;
        xfPose = null;
        int mode = xfMode;
        xfMode = 0;
        if (p == null || mode == 0 || !dragged) { invalidate(); return; }
        JSONObject o = op("xf");
        try {
            o.put("x", (double) p.tx); o.put("y", (double) p.ty); o.put("sx", (double) p.sx); o.put("sy", (double) p.sy);
            o.put("r", (double) p.rot); o.put("px", (double) p.px); o.put("py", (double) p.py);
        } catch (JSONException ignored) { }
        host.submit(o);
    }

    private void drawFreeBox(Canvas c, float hair) {
        MDoc.Pose p = curPose();
        if (p == null || !localBounds(p.key, lb)) return;
        float[] q = corners(p, lb);
        p(Paint.Style.STROKE, Ui.BLUE, 1.5f * hair);
        android.graphics.Path box = new android.graphics.Path();
        box.moveTo(q[0], q[1]); box.lineTo(q[4], q[5]); box.lineTo(q[8], q[9]); box.lineTo(q[12], q[13]); box.close();
        c.drawPath(box, this.p);
        float r = 4.5f * dp * hair;
        for (int i = 0; i < 8; i++) {
            p(Paint.Style.FILL, 0xFFFFFFFF, hair);
            c.drawRect(q[i * 2] - r, q[i * 2 + 1] - r, q[i * 2] + r, q[i * 2 + 1] + r, this.p);
            p(Paint.Style.STROKE, Ui.BLUE, 1.2f * hair);
            c.drawRect(q[i * 2] - r, q[i * 2 + 1] - r, q[i * 2] + r, q[i * 2 + 1] + r, this.p);
        }
        // centro de giro
        float cx0 = p.px + p.tx, cy0 = p.py + p.ty;
        if (p.sx == 1 && p.sy == 1 && p.rot == 0) { cx0 = lb.centerX() + p.tx; cy0 = lb.centerY() + p.ty; }
        p(Paint.Style.FILL, 0xFFFFFFFF, hair);
        c.drawCircle(cx0, cy0, r, this.p);
        p(Paint.Style.STROKE, Ui.INK, 1.2f * hair);
        c.drawCircle(cx0, cy0, r, this.p);
    }

    private void p(Paint.Style st, int color, float w) { p.setStyle(st); p.setColor(color); p.setStrokeWidth(w); }
}
