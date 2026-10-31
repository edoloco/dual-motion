package cl.dualmotion;

import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Documento de animación: capas × fotogramas clave con formas vectoriales (nodos Bézier).
 * Todo cambio es una orden JSON {op:…}; los dos teléfonos aplican las mismas órdenes en el mismo orden,
 * así que también tienen la misma pila de deshacer.
 * Capa 0 = la de más arriba (como en la lista de capas).
 */
public final class MDoc {

    // ---------------------------------------------------------------- forma vectorial
    public static final class Shape {
        /** Por nodo: x, y, manilla de entrada (ix, iy), manilla de salida (ox, oy). */
        public float[] n = new float[0];
        public boolean closed, brush;
        /** Colores ARGB; 0 = sin contorno / sin relleno. */
        public int stroke = 0xFF000000, fill;
        public float width = 2;
        transient Path path;
        transient RectF bounds;

        public int count() { return n.length / 6; }
        public void dirty() { path = null; bounds = null; }

        public Shape copy() {
            Shape s = new Shape();
            s.n = n.clone(); s.closed = closed; s.brush = brush;
            s.stroke = stroke; s.fill = fill; s.width = width;
            return s;
        }

        public void translate(float dx, float dy) {
            for (int i = 0; i < n.length; i += 2) { n[i] += dx; n[i + 1] += dy; }
            dirty();
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            JSONArray a = new JSONArray();
            for (float v : n) a.put(Math.round(v * 100) / 100.0);
            o.put("n", a);
            if (closed) o.put("c", 1);
            if (brush) o.put("b", 1);
            if (stroke != 0) o.put("s", stroke);
            if (fill != 0) o.put("f", fill);
            o.put("w", Math.round(width * 100) / 100.0);
            return o;
        }

        public static Shape from(JSONObject o) {
            Shape s = new Shape();
            JSONArray a = o.optJSONArray("n");
            int len = a == null ? 0 : a.length() / 6 * 6;
            s.n = new float[len];
            for (int i = 0; i < len; i++) s.n[i] = (float) a.optDouble(i, 0);
            s.closed = o.optInt("c") == 1;
            s.brush = o.optInt("b") == 1;
            s.stroke = o.optInt("s", 0);
            s.fill = o.optInt("f", 0);
            s.width = (float) o.optDouble("w", 2);
            return s;
        }
    }

    // ---------------------------------------------------------------- fotograma clave y capa
    public static final class Key {
        public final ArrayList<Shape> shapes = new ArrayList<>();
        Key copy() { Key k = new Key(); for (Shape s : shapes) k.shapes.add(s.copy()); return k; }
    }

    public static final class Layer {
        public String name = "Capa";
        public boolean vis = true, lock, out;
        public int color = 0xFF2FA866;
        /** Cantidad de fotogramas de la capa (el último clave se extiende hasta aquí). */
        public int len = 1;
        public final TreeMap<Integer, Key> keys = new TreeMap<>();

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name); o.put("vis", vis); o.put("lock", lock); o.put("out", out);
            o.put("color", color); o.put("len", len);
            JSONArray ks = new JSONArray();
            for (Map.Entry<Integer, Key> e : keys.entrySet()) {
                JSONObject k = new JSONObject();
                k.put("f", e.getKey());
                JSONArray ss = new JSONArray();
                for (Shape s : e.getValue().shapes) ss.put(s.toJson());
                k.put("s", ss);
                ks.put(k);
            }
            o.put("keys", ks);
            return o;
        }

        static Layer from(JSONObject o) {
            Layer L = new Layer();
            L.name = o.optString("name", "Capa");
            L.vis = o.optBoolean("vis", true); L.lock = o.optBoolean("lock"); L.out = o.optBoolean("out");
            L.color = o.optInt("color", 0xFF2FA866);
            L.len = Math.max(1, o.optInt("len", 1));
            JSONArray ks = o.optJSONArray("keys");
            if (ks != null) for (int i = 0; i < ks.length(); i++) {
                JSONObject k = ks.optJSONObject(i);
                if (k == null) continue;
                Key key = new Key();
                JSONArray ss = k.optJSONArray("s");
                if (ss != null) for (int j = 0; j < ss.length(); j++) {
                    JSONObject so = ss.optJSONObject(j);
                    if (so != null) key.shapes.add(Shape.from(so));
                }
                int f = Math.max(0, k.optInt("f"));
                L.keys.put(f, key);
                if (f >= L.len) L.len = f + 1;
            }
            if (!L.keys.containsKey(0)) L.keys.put(0, new Key());
            return L;
        }
    }

    // ---------------------------------------------------------------- documento
    public static final int[] LAYER_COLORS = {0xFF2FA866, 0xFFF57C1F, 0xFF2F7BE0, 0xFFC2185B, 0xFF8E44AD,
            0xFF00897B, 0xFFE0B000, 0xFF6D4C41};

    public int w = 1280, h = 720, fps = 24;
    public String scene = "Escena 1";
    /** Contador para nombrar capas nuevas (parte del documento, para que los dos teléfonos coincidan). */
    public int seq = 1;
    public final ArrayList<Layer> layers = new ArrayList<>();

    public MDoc() { reset(); }

    void reset() {
        w = 1280; h = 720; fps = 24; scene = "Escena 1"; seq = 1;
        layers.clear();
        layers.add(newLayer());
        undo.clear(); redo.clear();
    }

    private Layer newLayer() {
        Layer L = new Layer();
        L.name = "Capa " + seq;
        L.color = LAYER_COLORS[(seq - 1) % LAYER_COLORS.length];
        seq++;
        L.keys.put(0, new Key());
        return L;
    }

    /** Largo de la animación = la capa más larga. */
    public int length() {
        int n = 1;
        for (Layer L : layers) n = Math.max(n, L.len);
        return n;
    }

    /** Clave vigente en el fotograma f (null si la capa ya terminó). */
    public static Key keyAt(Layer L, int f) {
        if (f < 0 || f >= L.len) return null;
        Map.Entry<Integer, Key> e = L.keys.floorEntry(f);
        return e == null ? null : e.getValue();
    }

    /** Número del fotograma clave vigente en f (-1 si no hay). */
    public static int keyIndex(Layer L, int f) {
        if (f < 0 || f >= L.len) return -1;
        Integer k = L.keys.floorKey(f);
        return k == null ? -1 : k;
    }

    public Layer layer(int l) { return l >= 0 && l < layers.size() ? layers.get(l) : null; }

    // ---------------------------------------------------------------- JSON
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("v", 1); o.put("w", w); o.put("h", h); o.put("fps", fps);
            o.put("scene", scene); o.put("seq", seq);
            JSONArray ls = new JSONArray();
            for (Layer L : layers) ls.put(L.toJson());
            o.put("layers", ls);
        } catch (JSONException ignored) { }
        return o;
    }

    /** Carga un documento completo (borra la historia de deshacer). */
    public void load(JSONObject o) {
        loadInto(o);
        undo.clear(); redo.clear();
    }

    private void loadInto(JSONObject o) {
        w = Math.max(16, o.optInt("w", 1280));
        h = Math.max(16, o.optInt("h", 720));
        fps = clamp(o.optInt("fps", 24), 1, 60);
        scene = o.optString("scene", "Escena 1");
        seq = Math.max(1, o.optInt("seq", 1));
        layers.clear();
        JSONArray ls = o.optJSONArray("layers");
        if (ls != null) for (int i = 0; i < ls.length(); i++) {
            JSONObject lo = ls.optJSONObject(i);
            if (lo != null) layers.add(Layer.from(lo));
        }
        if (layers.isEmpty()) layers.add(newLayer());
    }

    // ---------------------------------------------------------------- deshacer
    /** Cada paso guarda lo que había antes: una capa (l, json) o el documento entero (l = -1). */
    private static final class Step { int l; String json; Step(int l, String j) { this.l = l; json = j; } }
    private final ArrayList<Step> undo = new ArrayList<>(), redo = new ArrayList<>();
    private static final int MAX_UNDO = 60;

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    private Step snap(int l) {
        try {
            return l < 0 ? new Step(-1, toJson().toString()) : new Step(l, layers.get(l).toJson().toString());
        } catch (JSONException e) { return new Step(-1, toJson().toString()); }
    }

    private void push(int l) {
        undo.add(snap(l));
        if (undo.size() > MAX_UNDO) undo.remove(0);
        redo.clear();
    }

    private void restore(Step s) {
        try {
            JSONObject o = new JSONObject(s.json);
            if (s.l < 0) loadInto(o);
            else if (s.l < layers.size()) layers.set(s.l, Layer.from(o));
        } catch (JSONException ignored) { }
    }

    private boolean undoRedo(ArrayList<Step> from, ArrayList<Step> to) {
        if (from.isEmpty()) return false;
        Step s = from.remove(from.size() - 1);
        to.add(snap(s.l));
        restore(s);
        return true;
    }

    // ---------------------------------------------------------------- órdenes
    /** Aplica una orden. Devuelve true si cambió algo. */
    public boolean apply(JSONObject m) {
        try { return applyOp(m); } catch (Exception e) { return false; }
    }

    private boolean applyOp(JSONObject m) throws JSONException {
        String op = m.optString("op");
        switch (op) {
            case "undo": return undoRedo(undo, redo);
            case "redo": return undoRedo(redo, undo);
            case "new": push(-1); reset(); return true;
            case "load": push(-1); loadInto(m.getJSONObject("doc")); return true;
            case "fps": {
                int v = clamp(m.optInt("v", fps), 1, 60);
                if (v == fps) return false;
                push(-1); fps = v; return true;
            }
            case "layerAdd": {
                push(-1);
                layers.add(clamp(m.optInt("at", 0), 0, layers.size()), newLayer());
                return true;
            }
            case "layerDel": {
                int l = m.optInt("l", -1);
                if (layers.size() <= 1 || l < 0 || l >= layers.size()) return false;
                push(-1); layers.remove(l); return true;
            }
            case "layerMove": {
                int l = m.optInt("l", -1), to = m.optInt("to", -1);
                if (l < 0 || l >= layers.size() || to < 0 || to >= layers.size() || l == to) return false;
                push(-1); layers.add(to, layers.remove(l)); return true;
            }
        }

        int l = m.optInt("l", -1);
        if (l < 0 || l >= layers.size()) return false;
        Layer L = layers.get(l);
        int f = Math.max(0, m.optInt("f", 0));

        switch (op) {
            case "layer": {
                push(l);
                if (m.has("name")) L.name = m.optString("name", L.name);
                if (m.has("vis")) L.vis = m.optBoolean("vis");
                if (m.has("lock")) L.lock = m.optBoolean("lock");
                if (m.has("out")) L.out = m.optBoolean("out");
                return true;
            }
            case "key": { // F6 (copia la clave anterior) o F7 (blank = clave vacía)
                if (L.keys.containsKey(f)) return false;
                boolean blank = m.optInt("blank") == 1;
                push(l);
                Map.Entry<Integer, Key> prev = L.keys.floorEntry(f);
                L.keys.put(f, blank || prev == null ? new Key() : prev.getValue().copy());
                if (f >= L.len) L.len = f + 1;
                return true;
            }
            case "frame": { // F5: inserta un fotograma (lo que sigue se corre a la derecha)
                push(l);
                if (f >= L.len) L.len = f + 1;
                else { shift(L, f + 1, 1); L.len++; }
                return true;
            }
            case "delFrame": { // Mayús+F5
                if (f >= L.len) return false;
                push(l);
                if (L.len == 1) { L.keys.clear(); L.keys.put(0, new Key()); return true; }
                Key k = L.keys.remove(f);
                // si se borra un clave que sigue en el fotograma siguiente, su contenido pasa a ese fotograma
                if (k != null && f + 1 < L.len && !L.keys.containsKey(f + 1)) L.keys.put(f + 1, k);
                shift(L, f + 1, -1);
                L.len--;
                if (!L.keys.containsKey(0)) L.keys.put(0, new Key());
                return true;
            }
            case "clearKey": { // Mayús+F6: el fotograma pasa a extender el clave anterior
                if (f == 0 || !L.keys.containsKey(f)) return false;
                push(l); L.keys.remove(f); return true;
            }
            case "add": {
                Shape s = Shape.from(m.getJSONObject("s"));
                if (s.count() == 0) return false;
                push(l);
                editKey(L, f).shapes.add(s);
                return true;
            }
            case "set": {
                Key k = keyAt(L, f);
                JSONArray is = m.optJSONArray("is"), ss = m.optJSONArray("ss");
                if (k == null || is == null || ss == null) return false;
                push(l);
                for (int i = 0; i < is.length() && i < ss.length(); i++) {
                    int idx = is.optInt(i, -1);
                    JSONObject so = ss.optJSONObject(i);
                    if (idx >= 0 && idx < k.shapes.size() && so != null) k.shapes.set(idx, Shape.from(so));
                }
                return true;
            }
            case "del": {
                Key k = keyAt(L, f);
                JSONArray is = m.optJSONArray("is");
                if (k == null || is == null || is.length() == 0) return false;
                ArrayList<Integer> list = indices(is, k.shapes.size());
                if (list.isEmpty()) return false;
                push(l);
                Collections.sort(list, Collections.reverseOrder());
                for (int idx : list) k.shapes.remove(idx);
                return true;
            }
            case "order": { // al frente / atrás
                Key k = keyAt(L, f);
                JSONArray is = m.optJSONArray("is");
                if (k == null || is == null) return false;
                ArrayList<Integer> list = indices(is, k.shapes.size());
                if (list.isEmpty()) return false;
                push(l);
                Collections.sort(list);
                ArrayList<Shape> moved = new ArrayList<>();
                for (int i = list.size() - 1; i >= 0; i--) moved.add(0, k.shapes.remove((int) list.get(i)));
                if ("back".equals(m.optString("to"))) k.shapes.addAll(0, moved);
                else k.shapes.addAll(moved);
                return true;
            }
        }
        return false;
    }

    private static ArrayList<Integer> indices(JSONArray is, int size) {
        ArrayList<Integer> list = new ArrayList<>();
        for (int i = 0; i < is.length(); i++) {
            int idx = is.optInt(i, -1);
            if (idx >= 0 && idx < size && !list.contains(idx)) list.add(idx);
        }
        return list;
    }

    /** Clave donde se dibuja en f: si la capa ya terminó, crea un clave vacío ahí (como en Flash). */
    private static Key editKey(Layer L, int f) {
        if (f >= L.len) {
            Key k = new Key();
            L.keys.put(f, k);
            L.len = f + 1;
            return k;
        }
        return L.keys.floorEntry(f).getValue();
    }

    /** Corre en d los claves desde el fotograma "from". */
    private static void shift(Layer L, int from, int d) {
        TreeMap<Integer, Key> tail = new TreeMap<>(L.keys.tailMap(from, true));
        for (Integer k : tail.keySet()) L.keys.remove(k);
        for (Map.Entry<Integer, Key> e : tail.entrySet()) {
            int nk = e.getKey() + d;
            if (nk >= 0) L.keys.put(nk, e.getValue());
        }
    }

    static int clamp(int v, int a, int b) { return Math.max(a, Math.min(b, v)); }
}
