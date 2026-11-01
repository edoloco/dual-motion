package cl.dualmotion;

import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        /**
         * Instancia de un símbolo gráfico (sym = id del símbolo): se dibuja la línea de tiempo del símbolo en
         * el punto n[0], n[1]. first = fotograma del símbolo con que empieza; loop: 0 repetir, 1 una vez,
         * 2 un solo fotograma.
         */
        public String sym;
        public int first, loop;
        /** Relleno con huecos: índices de nodo donde empieza cada contorno extra (null = un solo contorno). */
        public int[] sub;
        transient Path path;
        transient RectF bounds;

        public int count() { return n.length / 6; }
        public void dirty() { path = null; bounds = null; }
        public boolean isInst() { return sym != null; }

        public Shape copy() {
            Shape s = new Shape();
            s.n = n.clone(); s.closed = closed; s.brush = brush;
            s.stroke = stroke; s.fill = fill; s.width = width;
            s.sym = sym; s.first = first; s.loop = loop;
            s.sub = sub == null ? null : sub.clone();
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
            if (sym != null) { o.put("y", sym); if (first != 0) o.put("ff", first); if (loop != 0) o.put("lp", loop); }
            if (sub != null && sub.length > 0) { JSONArray b = new JSONArray(); for (int v : sub) b.put(v); o.put("sub", b); }
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
            if (o.has("y")) { s.sym = o.optString("y"); s.first = Math.max(0, o.optInt("ff")); s.loop = clamp(o.optInt("lp"), 0, 2); }
            JSONArray b = o.optJSONArray("sub");
            if (b != null && b.length() > 0) {
                int cnt = s.count(), k = 0;
                int[] v = new int[b.length()];
                for (int i = 0; i < b.length(); i++) { int x = b.optInt(i); if (x > 0 && x < cnt && (k == 0 || x > v[k - 1])) v[k++] = x; }
                if (k > 0) s.sub = java.util.Arrays.copyOf(v, k);
            }
            return s;
        }
    }

    // ---------------------------------------------------------------- símbolo gráfico
    /** Símbolo gráfico: un dibujo con su propia línea de tiempo, que se pone en la escena como instancias. */
    public static final class Symbol {
        public String id, name;
        public final ArrayList<Layer> layers = new ArrayList<>();

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name);
            JSONArray ls = new JSONArray();
            for (Layer L : layers) ls.put(L.toJson());
            o.put("layers", ls);
            return o;
        }

        static Symbol from(JSONObject o) {
            Symbol S = new Symbol();
            S.id = o.optString("id"); S.name = o.optString("name", "Símbolo");
            JSONArray ls = o.optJSONArray("layers");
            if (ls != null) for (int i = 0; i < ls.length(); i++) {
                JSONObject lo = ls.optJSONObject(i);
                if (lo != null) S.layers.add(Layer.from(lo));
            }
            if (S.layers.isEmpty()) { Layer L = new Layer(); L.name = "Capa 1"; L.keys.put(0, new Key()); S.layers.add(L); }
            return S;
        }
    }

    /** Fotograma del símbolo que muestra una instancia, "elapsed" fotogramas después de su clave. */
    public static int symFrame(Shape inst, int elapsed, int symLen) {
        int n = Math.max(1, symLen);
        if (inst.loop == 2) return Math.min(inst.first, n - 1);
        int f = inst.first + Math.max(0, elapsed);
        return inst.loop == 1 ? Math.min(f, n - 1) : f % n;
    }

    // ---------------------------------------------------------------- fotograma clave y capa
    /**
     * Fotograma clave: sus formas se mueven juntas como un grupo con una transformación propia
     * (posición, escala, giro y alfa, alrededor del punto px, py). Con tween = true se interpola hasta el
     * clave siguiente (interpolación de movimiento), con la curva de aceleración ease y "spin" giros extra.
     */
    public static final class Key {
        public final ArrayList<Shape> shapes = new ArrayList<>();
        public float tx, ty, sx = 1, sy = 1, rot, alpha = 1, px, py;
        public boolean tween;
        /** Curva de aceleración: puntos de control (x1, y1, x2, y2) de una Bézier de (0,0) a (1,1). */
        public float[] ease = LINEAR.clone();
        /** Giros completos extra durante la interpolación (+ horario, − antihorario). */
        public int spin;

        Key copy() {
            Key k = new Key();
            for (Shape s : shapes) k.shapes.add(s.copy());
            k.setPose(this);
            k.tween = tween; k.ease = ease.clone(); k.spin = spin;
            return k;
        }

        void setPose(Key o) {
            tx = o.tx; ty = o.ty; sx = o.sx; sy = o.sy; rot = o.rot; alpha = o.alpha; px = o.px; py = o.py;
        }

        public boolean identity() { return tx == 0 && ty == 0 && sx == 1 && sy == 1 && rot == 0; }

        void toJson(JSONObject k) throws JSONException {
            if (!identity() || alpha != 1) {
                JSONArray x = new JSONArray();
                for (float v : new float[]{tx, ty, sx, sy, rot, alpha, px, py}) x.put(Math.round(v * 10000) / 10000.0);
                k.put("x", x);
            }
            if (tween) {
                k.put("tw", 1);
                JSONArray e = new JSONArray();
                for (float v : ease) e.put(Math.round(v * 10000) / 10000.0);
                k.put("ease", e);
                if (spin != 0) k.put("spin", spin);
            }
        }

        void fromJson(JSONObject k) {
            JSONArray x = k.optJSONArray("x");
            if (x != null && x.length() >= 8) {
                tx = (float) x.optDouble(0); ty = (float) x.optDouble(1);
                sx = (float) x.optDouble(2, 1); sy = (float) x.optDouble(3, 1);
                rot = (float) x.optDouble(4); alpha = (float) x.optDouble(5, 1);
                px = (float) x.optDouble(6); py = (float) x.optDouble(7);
            }
            tween = k.optInt("tw") == 1;
            JSONArray e = k.optJSONArray("ease");
            if (e != null && e.length() == 4) for (int i = 0; i < 4; i++) ease[i] = (float) e.optDouble(i);
            spin = k.optInt("spin");
        }
    }

    public static final float[] LINEAR = {0.25f, 0.25f, 0.75f, 0.75f};

    // ---------------------------------------------------------------- pose: cómo se ve una capa en un fotograma
    /** Contenido y transformación de una capa en un fotograma (ya interpolada si hay interpolación). */
    public static final class Pose {
        public Key key;          // formas que se dibujan
        public int keyFrame;     // número del clave de donde vienen
        public int frame;        // fotograma pedido (frame - keyFrame = cuánto lleva el clave: anima los símbolos)
        public boolean tweening; // true si es un fotograma intermedio de una interpolación
        public float tx, ty, sx = 1, sy = 1, rot, alpha = 1, px, py;
    }

    /** Pose de la capa en f, o null si la capa no tiene nada ahí. */
    public static Pose pose(Layer L, int f) {
        if (f < 0 || f >= L.len) return null;
        Map.Entry<Integer, Key> a = L.keys.floorEntry(f);
        if (a == null) return null;
        Key A = a.getValue();
        Pose p = new Pose();
        p.key = A;
        p.keyFrame = a.getKey();
        p.frame = f;
        p.tx = A.tx; p.ty = A.ty; p.sx = A.sx; p.sy = A.sy; p.rot = A.rot; p.alpha = A.alpha; p.px = A.px; p.py = A.py;
        if (!A.tween || f == a.getKey()) return p;
        Map.Entry<Integer, Key> b = L.keys.higherEntry(f);
        if (b == null) return p; // interpolación rota: no hay clave final
        Key B = b.getValue();
        float t = (f - a.getKey()) / (float) (b.getKey() - a.getKey());
        float e = ease(A.ease, t);
        p.tweening = true;
        p.tx = lerp(A.tx, B.tx, e); p.ty = lerp(A.ty, B.ty, e);
        p.sx = lerp(A.sx, B.sx, e); p.sy = lerp(A.sy, B.sy, e);
        p.rot = lerp(A.rot, B.rot + 360f * A.spin, e);
        p.alpha = lerp(A.alpha, B.alpha, e);
        p.px = lerp(A.px, B.px, e); p.py = lerp(A.py, B.py, e);
        p.key = between(L, A, B, f, e);
        return p;
    }

    /** Cambia con cada orden aplicada: invalida los fotogramas intermedios ya calculados. */
    static volatile int version;

    /**
     * Contenido de un fotograma intermedio, como en Flash: lo que cambió entre el clave inicial y el final se
     * interpola (posición movida con Selección, nodos, grosor y colores), forma por forma, si las dos tienen la
     * misma cantidad de formas y cada par es "la misma" (mismo símbolo, o misma cantidad de nodos).
     * Se guarda en caché para que el mismo fotograma devuelva el mismo objeto (selección, edición en el lugar).
     */
    private static Key between(Layer L, Key A, Key B, int f, float e) {
        if (L.cacheVer != version) { L.cache.clear(); L.cacheVer = version; }
        Key K = L.cache.get(f);
        if (K != null) return K;
        K = A;
        if (A.shapes.size() == B.shapes.size() && !A.shapes.isEmpty()) {
            boolean diff = false;
            Key X = new Key();
            for (int i = 0; i < A.shapes.size(); i++) {
                Shape a = A.shapes.get(i), b = B.shapes.get(i);
                Shape m = lerpShape(a, b, e);
                if (m != a) diff = true;
                X.shapes.add(m);
            }
            if (diff) K = X;
        }
        if (L.cache.size() > 200) L.cache.clear();
        L.cache.put(f, K);
        return K;
    }

    private static Shape lerpShape(Shape a, Shape b, float e) {
        if (a.isInst() != b.isInst()) return a;
        if (a.isInst() && !a.sym.equals(b.sym)) return a;
        if (a.n.length != b.n.length || a.closed != b.closed || !java.util.Arrays.equals(a.sub, b.sub)) return a;
        boolean same = a.width == b.width && a.stroke == b.stroke && a.fill == b.fill && java.util.Arrays.equals(a.n, b.n);
        if (same) return a;
        Shape s = a.copy();
        if (a.isInst()) {
            float x = lerp(a.n[0], b.n[0], e), y = lerp(a.n[1], b.n[1], e);
            s.n = new float[]{x, y, x, y, x, y};
        } else for (int i = 0; i < s.n.length; i++) s.n[i] = lerp(a.n[i], b.n[i], e);
        s.width = lerp(a.width, b.width, e);
        s.stroke = lerpColor(a.stroke, b.stroke, e);
        s.fill = lerpColor(a.fill, b.fill, e);
        s.dirty();
        return s;
    }

    private static int lerpColor(int a, int b, float e) {
        if (a == b) return a;
        if (a == 0) a = b & 0x00FFFFFF; // sin color → aparece con transparencia
        if (b == 0) b = a & 0x00FFFFFF;
        int r = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int x = (a >>> sh) & 0xFF, y = (b >>> sh) & 0xFF;
            r |= (Math.round(x + (y - x) * e) & 0xFF) << sh;
        }
        return r;
    }

    /** Lo que se ve de la capa en f (en un intermedio, el contenido interpolado). */
    static Key shown(Layer L, int f) {
        Pose p = pose(L, f);
        return p == null ? null : p.key;
    }

    /** ¿La interpolación que empieza en este clave tiene clave final? */
    public static boolean tweenOk(Layer L, int keyFrame) {
        Key k = L.keys.get(keyFrame);
        return k != null && k.tween && L.keys.higherKey(keyFrame) != null;
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Curva de aceleración: Bézier cúbica de (0,0) a (1,1); devuelve y para x = t. */
    public static float ease(float[] c, float t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        float lo = 0, hi = 1, u = t;
        for (int i = 0; i < 30; i++) {
            u = (lo + hi) / 2;
            float x = bez(c[0], c[2], u);
            if (Math.abs(x - t) < 1e-5f) break;
            if (x < t) lo = u; else hi = u;
        }
        return bez(c[1], c[3], u);
    }

    private static float bez(float p1, float p2, float u) {
        float v = 1 - u;
        return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u;
    }

    // ---------------------------------------------------------------- sonido
    /** Sonido de la biblioteca: el archivo vive en cada teléfono (Sounds), el documento solo lo nombra. */
    public static final class Sound {
        public String id, name, ext;
        public int dur; // ms
        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name); o.put("ext", ext); o.put("dur", dur);
            return o;
        }
        static Sound from(JSONObject o) {
            Sound s = new Sound();
            s.id = o.optString("id"); s.name = o.optString("name", "Sonido"); s.ext = o.optString("ext", "m4a");
            s.dur = Math.max(0, o.optInt("dur"));
            return s;
        }
    }

    /** Trozo de un sonido puesto en una capa de audio: empieza en "at" (ms de la línea de tiempo), usa el
     *  sonido desde "in" (ms) durante "len" ms, con volumen "vol". */
    public static final class Clip {
        public String s;
        public int at, in, len;
        public float vol = 1;
        public Clip copy() { Clip c = new Clip(); c.s = s; c.at = at; c.in = in; c.len = len; c.vol = vol; return c; }
        public int end() { return at + len; }
        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("s", s); o.put("at", at); o.put("in", in); o.put("len", len);
            if (vol != 1) o.put("v", Math.round(vol * 100) / 100.0);
            return o;
        }
        public static Clip from(JSONObject o) {
            Clip c = new Clip();
            c.s = o.optString("s"); c.at = Math.max(0, o.optInt("at")); c.in = Math.max(0, o.optInt("in"));
            c.len = Math.max(1, o.optInt("len", 1)); c.vol = (float) Math.max(0, Math.min(2, o.optDouble("v", 1)));
            return c;
        }
    }

    public static final class Layer {
        public String name = "Capa";
        public boolean vis = true, lock, out;
        public int color = 0xFF2FA866;
        /** Cantidad de fotogramas de la capa (el último clave se extiende hasta aquí). */
        public int len = 1;
        public final TreeMap<Integer, Key> keys = new TreeMap<>();
        /** Capa de audio: no tiene dibujo, sino trozos de sonido (puede tener varios). */
        public boolean audio;
        public final ArrayList<Clip> clips = new ArrayList<>();
        /** Fotogramas intermedios de interpolación ya calculados (ver between). */
        transient final java.util.HashMap<Integer, Key> cache = new java.util.HashMap<>();
        transient int cacheVer = -1;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name); o.put("vis", vis); o.put("lock", lock); o.put("out", out);
            o.put("color", color); o.put("len", len);
            if (audio) {
                o.put("type", "audio");
                JSONArray cs = new JSONArray();
                for (Clip c : clips) cs.put(c.toJson());
                o.put("clips", cs);
            }
            JSONArray ks = new JSONArray();
            for (Map.Entry<Integer, Key> e : keys.entrySet()) {
                JSONObject k = new JSONObject();
                k.put("f", e.getKey());
                JSONArray ss = new JSONArray();
                for (Shape s : e.getValue().shapes) ss.put(s.toJson());
                k.put("s", ss);
                e.getValue().toJson(k);
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
            L.audio = "audio".equals(o.optString("type"));
            JSONArray cs = o.optJSONArray("clips");
            if (L.audio && cs != null) for (int i = 0; i < cs.length(); i++) {
                JSONObject co = cs.optJSONObject(i);
                if (co != null) L.clips.add(Clip.from(co));
            }
            JSONArray ks = o.optJSONArray("keys");
            if (ks != null) for (int i = 0; i < ks.length(); i++) {
                JSONObject k = ks.optJSONObject(i);
                if (k == null) continue;
                Key key = new Key();
                key.fromJson(k);
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
    /** Biblioteca de sonidos del documento. */
    public final ArrayList<Sound> sounds = new ArrayList<>();
    /** Biblioteca de símbolos gráficos. */
    public final ArrayList<Symbol> symbols = new ArrayList<>();
    public int symSeq = 1;

    public MDoc() { reset(); }

    void reset() {
        w = 1280; h = 720; fps = 24; scene = "Escena 1"; seq = 1;
        layers.clear();
        sounds.clear();
        symbols.clear();
        symSeq = 1;
        layers.add(newLayer());
        undo.clear(); redo.clear();
    }

    private Layer newLayer() { return newLayer(false); }

    private Layer newLayer(boolean audio) {
        Layer L = new Layer();
        L.audio = audio;
        L.name = (audio ? "Sonido " : "Capa ") + seq;
        L.color = LAYER_COLORS[(seq - 1) % LAYER_COLORS.length];
        seq++;
        L.keys.put(0, new Key());
        return L;
    }

    /** Largo de la animación = la capa de dibujo más larga (el sonido no alarga la animación). */
    public int length() { return length(layers); }

    /** Largo de una línea de tiempo (la escena o la de un símbolo). */
    public static int length(List<Layer> T) {
        int n = 1;
        for (Layer L : T) if (!L.audio) n = Math.max(n, L.len);
        return n;
    }

    public Symbol symbol(String id) {
        if (id == null) return null;
        for (Symbol S : symbols) if (S.id.equals(id)) return S;
        return null;
    }

    /** Capas de la escena (sym vacío) o de un símbolo. */
    public List<Layer> timeline(String sym) {
        if (sym == null || sym.isEmpty()) return layers;
        Symbol S = symbol(sym);
        return S == null ? layers : S.layers;
    }

    /** Duración de la animación en ms. */
    public int durationMs() { return (int) Math.round(length() * 1000.0 / fps); }

    public Sound sound(String id) {
        for (Sound s : sounds) if (s.id.equals(id)) return s;
        return null;
    }

    public boolean hasAudio() {
        for (Layer L : layers) if (L.audio && L.vis && !L.clips.isEmpty()) return true;
        return false;
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
            if (!sounds.isEmpty()) {
                JSONArray ss = new JSONArray();
                for (Sound s : sounds) ss.put(s.toJson());
                o.put("sounds", ss);
            }
            if (!symbols.isEmpty()) {
                JSONArray ys = new JSONArray();
                for (Symbol S : symbols) ys.put(S.toJson());
                o.put("symbols", ys);
                o.put("symSeq", symSeq);
            }
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
        sounds.clear();
        JSONArray ss = o.optJSONArray("sounds");
        if (ss != null) for (int i = 0; i < ss.length(); i++) {
            JSONObject so = ss.optJSONObject(i);
            if (so != null) sounds.add(Sound.from(so));
        }
        symbols.clear();
        symSeq = Math.max(1, o.optInt("symSeq", 1));
        JSONArray ys = o.optJSONArray("symbols");
        if (ys != null) for (int i = 0; i < ys.length(); i++) {
            JSONObject yo = ys.optJSONObject(i);
            if (yo != null) symbols.add(Symbol.from(yo));
        }
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
        version++;
        try { return applyOp(m); } catch (Exception e) { return false; } finally { version++; }
    }

    /**
     * Clave donde se edita lo que se ve en f. En medio de una interpolación (como en Flash) se crea ahí un
     * fotograma clave con lo interpolado, y la interpolación sigue a los dos lados.
     */
    private static Key editAt(Layer L, int f) {
        if (f >= L.len) return editKey(L, f);
        Key k = L.keys.get(f);
        if (k != null) return k;
        Map.Entry<Integer, Key> a = L.keys.floorEntry(f);
        Pose ps = pose(L, f);
        if (ps != null && ps.tweening) {
            Key A = a.getValue();
            k = ps.key.copy();
            poseInto(ps, k);
            advance(k, f - ps.keyFrame);
            k.tween = A.tween;
            k.ease = A.ease.clone();
            k.spin = 0;
            L.keys.put(f, k);
            return k;
        }
        return a.getValue();
    }

    private boolean applyOp(JSONObject m) throws JSONException {
        String op = m.optString("op");
        // las órdenes de capas y fotogramas actúan sobre la escena o, con "sym", sobre la línea de tiempo del símbolo
        String sy = m.optString("sym", "");
        Symbol S = null;
        if (!sy.isEmpty()) { S = symbol(sy); if (S == null) return false; }
        final List<Layer> T = S == null ? layers : S.layers;
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
                boolean audio = m.optInt("audio") == 1;
                if (audio && S != null) return false; // el sonido va en la escena
                push(-1);
                Layer nl = newLayer(audio);
                if (S != null) nl.name = "Capa " + (T.size() + 1);
                T.add(clamp(m.optInt("at", 0), 0, T.size()), nl);
                return true;
            }
            case "sndAdd": { // agrega un sonido a la biblioteca (el archivo ya llegó a los dos teléfonos)
                String id = m.optString("id");
                if (id.isEmpty() || sound(id) != null) return false;
                push(-1);
                Sound s = new Sound();
                s.id = id; s.name = m.optString("name", "Sonido"); s.ext = m.optString("ext", "m4a");
                s.dur = Math.max(0, m.optInt("dur"));
                sounds.add(s);
                return true;
            }
            case "layerDel": {
                int l = m.optInt("l", -1);
                if (T.size() <= 1 || l < 0 || l >= T.size()) return false;
                push(-1); T.remove(l); return true;
            }
            case "layerMove": {
                int l = m.optInt("l", -1), to = m.optInt("to", -1);
                if (l < 0 || l >= T.size() || to < 0 || to >= T.size() || l == to) return false;
                push(-1); T.add(to, T.remove(l)); return true;
            }
            case "symName": {
                Symbol Y = symbol(m.optString("id"));
                if (Y == null) return false;
                push(-1); Y.name = m.optString("name", Y.name); return true;
            }
        }

        int l = m.optInt("l", -1);
        if (l < 0 || l >= T.size()) return false;
        Layer L = T.get(l);
        // deshacer: en la escena basta guardar la capa; dentro de un símbolo se guarda todo
        final int pl = S == null ? l : -1;
        int f = Math.max(0, m.optInt("f", 0));
        boolean clipOp = op.startsWith("clip");
        if (L.audio != clipOp && !"layer".equals(op)) return false; // el audio no se dibuja; el dibujo no tiene clips

        switch (op) {
            case "clipAdd": {
                Clip c = Clip.from(m.getJSONObject("c"));
                if (sound(c.s) == null) return false;
                push(pl);
                L.clips.add(c);
                return true;
            }
            case "clipSet": {
                int i = m.optInt("i", -1);
                if (i < 0 || i >= L.clips.size()) return false;
                push(pl);
                L.clips.set(i, Clip.from(m.getJSONObject("c")));
                return true;
            }
            case "clipDel": {
                int i = m.optInt("i", -1);
                if (i < 0 || i >= L.clips.size()) return false;
                push(pl);
                L.clips.remove(i);
                return true;
            }
            case "clipSplit": { // cortar en t (ms): el trozo queda en dos
                int i = m.optInt("i", -1), t = m.optInt("t");
                if (i < 0 || i >= L.clips.size()) return false;
                Clip c = L.clips.get(i);
                if (t <= c.at || t >= c.end()) return false;
                push(pl);
                Clip b = c.copy();
                int cut = t - c.at;
                c.len = cut;
                b.at = t; b.in = c.in + cut; b.len -= cut;
                L.clips.add(i + 1, b);
                return true;
            }
            case "layer": {
                push(pl);
                if (m.has("name")) L.name = m.optString("name", L.name);
                if (m.has("vis")) L.vis = m.optBoolean("vis");
                if (m.has("lock")) L.lock = m.optBoolean("lock");
                if (m.has("out")) L.out = m.optBoolean("out");
                return true;
            }
            case "key": { // F6 (copia la clave anterior) o F7 (blank = clave vacía)
                if (L.keys.containsKey(f)) return false;
                boolean blank = m.optInt("blank") == 1;
                push(pl);
                Map.Entry<Integer, Key> prev = L.keys.floorEntry(f);
                Pose kps = blank || prev == null ? null : pose(L, f);
                if (kps != null && kps.tweening) { editAt(L, f); return true; } // dentro de una interpolación: toma lo interpolado
                Key nk;
                if (blank || prev == null) nk = new Key();
                else {
                    nk = prev.getValue().copy();
                    advance(nk, f - prev.getKey());
                }
                // si el clave nuevo queda al final y no hay otro después, no hay hacia dónde interpolar
                if (nk.tween && L.keys.higherKey(f) == null && f >= L.len - 1) nk.tween = false;
                L.keys.put(f, nk);
                if (f >= L.len) L.len = f + 1;
                return true;
            }
            case "frame": { // F5: inserta un fotograma (lo que sigue se corre a la derecha)
                push(pl);
                if (f >= L.len) L.len = f + 1;
                else { shift(L, f + 1, 1); L.len++; }
                return true;
            }
            case "delFrame": { // Mayús+F5
                if (f >= L.len) return false;
                push(pl);
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
                push(pl); L.keys.remove(f); return true;
            }
            case "xf": { // transformación del clave (posición, escala, giro, alfa, punto de giro)
                if (f >= L.len) return false;
                push(pl);
                Key k = editAt(L, f); // en medio de una interpolación: crea ahí un clave (como en Flash)
                if (m.has("x")) k.tx = (float) m.getDouble("x");
                if (m.has("y")) k.ty = (float) m.getDouble("y");
                if (m.has("sx")) k.sx = (float) m.getDouble("sx");
                if (m.has("sy")) k.sy = (float) m.getDouble("sy");
                if (m.has("r")) k.rot = (float) m.getDouble("r");
                if (m.has("a")) k.alpha = Math.max(0, Math.min(1, (float) m.getDouble("a")));
                if (m.has("px")) k.px = (float) m.getDouble("px");
                if (m.has("py")) k.py = (float) m.getDouble("py");
                return true;
            }
            case "tween": { // crear / quitar interpolación de movimiento en el clave vigente
                Map.Entry<Integer, Key> e = f < L.len ? L.keys.floorEntry(f) : null;
                if (e == null) return false;
                boolean on = m.optBoolean("on", true);
                if (e.getValue().tween == on) return false;
                push(pl);
                e.getValue().tween = on;
                // Flash: si no hay clave final, se crea uno al final del tramo
                if (on && L.keys.higherKey(e.getKey()) == null) {
                    int end = L.len - 1;
                    if (end <= e.getKey()) { end = e.getKey() + Math.max(1, fps) - 1; L.len = end + 1; }
                    Key b = e.getValue().copy();
                    b.tween = false;
                    advance(b, end - e.getKey()); // un símbolo en ciclo sigue su paso en el clave final
                    L.keys.put(end, b);
                }
                return true;
            }
            case "ease": {
                Map.Entry<Integer, Key> e = f < L.len ? L.keys.floorEntry(f) : null;
                JSONArray c = m.optJSONArray("c");
                if (e == null || c == null || c.length() != 4) return false;
                push(pl);
                for (int i = 0; i < 4; i++) {
                    float v = (float) c.optDouble(i);
                    e.getValue().ease[i] = i % 2 == 0 ? Math.max(0, Math.min(1, v)) : Math.max(-1, Math.min(2, v));
                }
                return true;
            }
            case "spin": {
                Map.Entry<Integer, Key> e = f < L.len ? L.keys.floorEntry(f) : null;
                if (e == null) return false;
                push(pl);
                e.getValue().spin = clamp(m.optInt("n"), -20, 20);
                return true;
            }
            case "add": {
                Shape s = Shape.from(m.getJSONObject("s"));
                if (s.count() == 0) return false;
                push(pl);
                Key ek = editAt(L, f);
                int at = m.optInt("at", -1);
                if (at >= 0 && at <= ek.shapes.size()) ek.shapes.add(at, s); else ek.shapes.add(s);
                return true;
            }
            case "set": {
                Key k = shown(L, f);
                JSONArray is = m.optJSONArray("is"), ss = m.optJSONArray("ss");
                if (k == null || is == null || ss == null) return false;
                push(pl);
                // si el clave se crea ahora (en medio de una interpolación), los símbolos siguen su ciclo donde iban
                int adv = L.keys.containsKey(f) || f >= L.len ? 0 : f - L.keys.floorKey(f);
                k = editAt(L, f);
                for (int i = 0; i < is.length() && i < ss.length(); i++) {
                    int idx = is.optInt(i, -1);
                    JSONObject so = ss.optJSONObject(i);
                    if (idx < 0 || idx >= k.shapes.size() || so == null) continue;
                    Shape ns = Shape.from(so);
                    if (adv > 0 && ns.isInst() && ns.loop != 2) ns.first += adv;
                    k.shapes.set(idx, ns);
                }
                return true;
            }
            case "del": {
                Key k = shown(L, f);
                JSONArray is = m.optJSONArray("is");
                if (k == null || is == null || is.length() == 0) return false;
                ArrayList<Integer> list = indices(is, k.shapes.size());
                if (list.isEmpty()) return false;
                push(pl);
                k = editAt(L, f);
                Collections.sort(list, Collections.reverseOrder());
                for (int idx : list) k.shapes.remove(idx);
                return true;
            }
            case "breakApart": { // separar: cada instancia elegida se cambia por el dibujo que muestra en ese fotograma
                Key k = shown(L, f);
                JSONArray is = m.optJSONArray("is");
                if (k == null || is == null) return false;
                ArrayList<Integer> list = indices(is, k.shapes.size());
                for (int i = list.size() - 1; i >= 0; i--) if (!k.shapes.get(list.get(i)).isInst()) list.remove(i);
                if (list.isEmpty()) return false;
                push(-1);
                k = editAt(L, f);
                int elapsed = f - L.keys.floorKey(f);
                Collections.sort(list, Collections.reverseOrder());
                for (int idx : list) {
                    Shape inst = k.shapes.remove(idx);
                    ArrayList<Shape> flat = new ArrayList<>();
                    flatten(inst, elapsed, flat, 0);
                    k.shapes.addAll(idx, flat);
                }
                return true;
            }
            case "toSym": { // convertir lo elegido en símbolo gráfico: queda una instancia en su lugar
                Key k = shown(L, f);
                JSONArray is = m.optJSONArray("is");
                String id = m.optString("id");
                if (k == null || is == null || id.isEmpty() || symbol(id) != null) return false;
                ArrayList<Integer> list = indices(is, k.shapes.size());
                if (list.isEmpty()) return false;
                push(-1);
                k = editAt(L, f);
                Collections.sort(list);
                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                for (int idx : list) {
                    Shape sh = k.shapes.get(idx);
                    int cnt = sh.isInst() ? 1 : sh.count() * 3;
                    for (int j = 0; j < cnt; j++) {
                        float x = sh.n[j * 2], y = sh.n[j * 2 + 1];
                        x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                    }
                }
                float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
                Symbol Y = new Symbol();
                Y.id = id;
                Y.name = m.optString("name", "");
                if (Y.name.isEmpty()) Y.name = "Símbolo " + symSeq;
                symSeq++;
                Layer SL = new Layer();
                SL.name = "Capa 1";
                Key k0 = new Key();
                for (int idx : list) { Shape sh = k.shapes.get(idx).copy(); sh.translate(-cx, -cy); k0.shapes.add(sh); }
                SL.keys.put(0, k0);
                Y.layers.add(SL);
                for (int i = list.size() - 1; i >= 0; i--) k.shapes.remove((int) list.get(i));
                Shape inst = new Shape();
                inst.sym = id;
                inst.n = new float[]{cx, cy, cx, cy, cx, cy};
                inst.stroke = 0; inst.width = 0;
                k.shapes.add(Math.min(list.get(0), k.shapes.size()), inst);
                symbols.add(Y);
                return true;
            }
            case "order": { // al frente / atrás
                Key k = shown(L, f);
                JSONArray is = m.optJSONArray("is");
                if (k == null || is == null) return false;
                ArrayList<Integer> list = indices(is, k.shapes.size());
                if (list.isEmpty()) return false;
                push(pl);
                k = editAt(L, f);
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

    /**
     * El dibujo que muestra una instancia (en el fotograma que le toca), pasado a formas sueltas en las coordenadas
     * del clave donde está: se aplica la pose de cada capa del símbolo y su alfa. Los símbolos dentro quedan como
     * instancias (solo cambia su posición).
     */
    private void flatten(Shape inst, int elapsed, List<Shape> out, int depth) {
        Symbol Y = symbol(inst.sym);
        if (Y == null || depth > 8) return;
        int sf = symFrame(inst, elapsed, length(Y.layers));
        for (int i = Y.layers.size() - 1; i >= 0; i--) {   // de la capa de abajo a la de arriba
            Layer SL = Y.layers.get(i);
            if (SL.audio || !SL.vis) continue;
            Pose p = pose(SL, sf);
            if (p == null) continue;
            double a = Math.toRadians(p.rot), cos = Math.cos(a), sin = Math.sin(a);
            float scale = (Math.abs(p.sx) + Math.abs(p.sy)) / 2;
            for (Shape s0 : p.key.shapes) {
                Shape s = s0.copy();
                if (s.isInst()) { // queda como símbolo, con su paso y su lugar
                    float[] q = xform(s.n[0], s.n[1], p, cos, sin);
                    s.n = new float[]{q[0], q[1], q[0], q[1], q[0], q[1]};
                    if (s.loop != 2) s.first += sf - p.keyFrame;
                } else {
                    for (int j = 0; j + 1 < s.n.length; j += 2) {
                        float[] q = xform(s.n[j], s.n[j + 1], p, cos, sin);
                        s.n[j] = q[0]; s.n[j + 1] = q[1];
                    }
                    s.width *= scale;
                    if (p.alpha < 1) { s.stroke = mulAlpha(s.stroke, p.alpha); s.fill = mulAlpha(s.fill, p.alpha); }
                }
                s.translate(inst.n[0], inst.n[1]);
                out.add(s);
            }
        }
    }

    /** Punto del símbolo → coordenadas con la pose (escala y giro alrededor de px, py; luego traslado). */
    private static float[] xform(float x, float y, Pose p, double cos, double sin) {
        double u = (x - p.px) * p.sx, v = (y - p.py) * p.sy;
        return new float[]{(float) (u * cos - v * sin + p.px + p.tx), (float) (u * sin + v * cos + p.py + p.ty)};
    }

    private static int mulAlpha(int c, float a) {
        if (c == 0) return 0;
        int al = Math.round((c >>> 24) * a);
        return (c & 0x00FFFFFF) | (al << 24);
    }

    /** Las instancias de un clave copiado "más adelante" siguen su animación donde iba (no vuelven a empezar). */
    private static void advance(Key k, int frames) {
        if (frames <= 0) return;
        for (Shape sh : k.shapes) if (sh.isInst() && sh.loop != 2) sh.first += frames;
    }

    private static void poseInto(Pose p, Key k) {
        k.tx = p.tx; k.ty = p.ty; k.sx = p.sx; k.sy = p.sy; k.rot = p.rot; k.alpha = p.alpha; k.px = p.px; k.py = p.py;
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
