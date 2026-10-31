package cl.dualmotion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;

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
        void selChanged(int n);
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
        if (!fitted || ow == 0) fit();
    }

    private float dx(float x) { return (x - tx) / s; }
    private float dy(float y) { return (y - ty) / s; }

    // ---------------------------------------------------------------- selección
    private final ArrayList<Integer> sel = new ArrayList<>();
    private int selL = -1, selK = -1;

    public void clearSel() {
        if (!sel.isEmpty()) { sel.clear(); host.selChanged(0); }
        subIdx = -1; subCopy = null;
        invalidate();
    }

    private MDoc.Layer curLayer() { return host.doc().layer(host.st().layer); }

    private MDoc.Key curKey() {
        MDoc.Layer L = curLayer();
        return L == null ? null : MDoc.keyAt(L, host.st().frame);
    }

    /** La selección vale solo para el clave donde se hizo. */
    private void validateSel() {
        MDoc.Layer L = curLayer();
        int k = L == null ? -1 : MDoc.keyIndex(L, host.st().frame);
        if (host.st().layer != selL || k != selK) { sel.clear(); selL = host.st().layer; selK = k; host.selChanged(0); }
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
        host.selChanged(sel.size());
    }

    // ---------------------------------------------------------------- órdenes
    private JSONObject op(String name) {
        JSONObject o = new JSONObject();
        try { o.put("op", name); o.put("l", host.st().layer); o.put("f", host.st().frame); } catch (JSONException ignored) { }
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
        host.selChanged(0);
    }

    public void orderSelection(boolean front) {
        validateSel();
        if (sel.isEmpty()) { Ui.toast(getContext(), "No hay nada seleccionado"); return; }
        JSONObject o = op("order");
        try { o.put("is", new JSONArray(sel)); o.put("to", front ? "front" : "back"); } catch (JSONException ignored) { }
        sel.clear();
        host.submit(o);
        host.selChanged(0);
    }

    /** Al cambiar de herramienta: termina el trazado de la pluma. */
    public void toolChanged() {
        if (pen != null) finishPen(false);
        subIdx = -1; subCopy = null;
        invalidate();
    }

    // ---------------------------------------------------------------- dibujo
    private final RectF tmp = new RectF();
    private final RectF chipMenu = new RectF(), chipFit = new RectF(), chipDone = new RectF(), chipCancel = new RectF();

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
        if (st.onion && !playing) Render.onion(c, d, f, 2, hair);

        for (int i = d.layers.size() - 1; i >= 0; i--) {
            MDoc.Layer L = d.layers.get(i);
            if (!L.vis) continue;
            MDoc.Key k = MDoc.keyAt(L, f);
            if (k == null) continue;
            int outline = L.out ? L.color : 0;
            if (i != st.layer || playing) { Render.key(c, k, 0, outline, 1.5f * hair); continue; }
            for (int j = 0; j < k.shapes.size(); j++) {
                MDoc.Shape sh = k.shapes.get(j);
                if (j == subIdx && subCopy != null) sh = subCopy;
                boolean mv = moving && sel.contains(j);
                if (erasing.contains(j)) { c.saveLayerAlpha(null, 0x40); Render.shape(c, sh, 0, outline, 1.5f * hair); c.restore(); continue; }
                if (mv) { c.save(); c.translate(mdx, mdy); }
                Render.shape(c, sh, 0, outline, 1.5f * hair);
                if (mv) c.restore();
            }
        }

        if (!playing) drawOverlay(c, d, st, hair);
        c.restore();

        drawChips(c, playing);
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
                if (i >= k.shapes.size()) continue;
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
        if (a == MotionEvent.ACTION_DOWN) {
            if (chipMenu.contains(x, y)) { host.menu(); return true; }
            if (chipFit.contains(x, y)) { fit(); return true; }
            if (chipDone.contains(x, y)) { finishPen(false); return true; }
            if (chipCancel.contains(x, y)) { pen = null; invalidate(); return true; }
            if (host.playing()) return true;
            gesture = true;
            blocked = false;
            lsx = x; lsy = y;
            down(dx(x), dy(y));
        } else if (!gesture) {
            return true;
        } else if (a == MotionEvent.ACTION_MOVE) {
            move(dx(x), dy(y), x, y);
            lsx = x; lsy = y;
        } else if (a == MotionEvent.ACTION_UP) {
            gesture = false;
            up(dx(x), dy(y));
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
        if (pen != null && penNew && !pen.isEmpty()) pen.remove(pen.size() - 1);
        penNew = penClosing = penFinishing = false;
        invalidate();
    }

    /** ¿Se puede editar la capa actual? Si no, avisa una vez por gesto. */
    private boolean editable() {
        MDoc.Layer L = curLayer();
        if (L != null && L.vis && !L.lock) return true;
        if (!blocked) Ui.toast(getContext(), L == null ? "No hay capa" : !L.vis ? "La capa está oculta" : "La capa está bloqueada");
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
                eraseAt(x, y);
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
                if (!blocked) eraseAt(x, y);
        }
    }

    private void up(float x, float y) {
        State st = host.st();
        cx = x; cy = y;
        switch (st.tool) {
            case State.SEL: {
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
            case State.PENCIL: case State.BRUSH:
                if (pts != null) {
                    pts.add(new float[]{x, y});
                    freehandShape();
                    submitAdd(temp);
                }
                pts = null; temp = null;
                return;
            case State.ERASER:
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
                int i = Render.hitKey(k, x, y, tol() / 2, Render.HIT_INSIDE);
                if (i < 0) { Ui.toast(getContext(), "Toca dentro de una forma cerrada"); return; }
                MDoc.Shape sh = k.shapes.get(i).copy();
                sh.fill = st.fill;
                one(i, sh);
                return;
            }
            case State.INK: {
                if (dragged || !editable()) return;
                MDoc.Key k = curKey();
                int i = Render.hitKey(k, x, y, tol(), Render.HIT_STROKE | Render.HIT_FILL);
                if (i < 0) return;
                MDoc.Shape sh = k.shapes.get(i).copy();
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

    private void eraseAt(float x, float y) {
        MDoc.Key k = curKey();
        if (k == null) return;
        for (int i = 0; i < k.shapes.size(); i++)
            if (!erasing.contains(i) && Render.hit(k.shapes.get(i), x, y, 8 * dp / s, Render.HIT_FILL | Render.HIT_STROKE))
                erasing.add(i);
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
}
