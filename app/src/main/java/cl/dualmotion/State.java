package cl.dualmotion;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Estado de trabajo que decide el principal (herramienta, colores, capa y fotograma actuales, papel cebolla).
 * Viaja al extendido como {t:"moState", …}.
 */
public final class State {
    public static final int SEL = 0, SUB = 1, FREE = 2, PEN = 3, LINE = 4, RECT = 5, OVAL = 6, PENCIL = 7,
            BRUSH = 8, BUCKET = 9, INK = 10, DROPPER = 11, ERASER = 12, HAND = 13;
    public static final String[] NAMES = {"Selección", "Subselección", "Transformación libre", "Pluma", "Línea",
            "Rectángulo", "Óvalo", "Lápiz", "Pincel", "Cubo de pintura", "Bote de tinta", "Cuentagotas", "Borrador",
            "Mano"};
    public static final int TOOLS = NAMES.length;

    public int tool = PENCIL;
    public int stroke = 0xFF000000, fill = 0xFFF57C1F;
    public float width = 3;
    public int layer, frame;
    public boolean onion;
    /**
     * Edición de un símbolo en el lugar: sym = id del símbolo ("" = la escena). layer y frame son entonces los
     * del símbolo; pl, pf, pi dicen dónde está la instancia por la que se entró (capa, fotograma y forma en la
     * escena), para dibujarla en su lugar y volver.
     */
    public String sym = "";
    public int pl, pf, pi;

    public boolean inSymbol() { return sym != null && !sym.isEmpty(); }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("tool", tool); o.put("sc", stroke); o.put("fc", fill); o.put("sw", (double) width);
            o.put("l", layer); o.put("f", frame); o.put("on", onion);
            o.put("sym", sym); o.put("pl", pl); o.put("pf", pf); o.put("pi", pi);
        } catch (JSONException ignored) { }
        return o;
    }

    public void from(JSONObject o) {
        tool = o.optInt("tool", tool);
        stroke = o.optInt("sc", stroke);
        fill = o.optInt("fc", fill);
        width = (float) o.optDouble("sw", width);
        layer = o.optInt("l", layer);
        frame = o.optInt("f", frame);
        onion = o.optBoolean("on", onion);
        sym = o.optString("sym", "");
        pl = o.optInt("pl"); pf = o.optInt("pf"); pi = o.optInt("pi");
    }
}
