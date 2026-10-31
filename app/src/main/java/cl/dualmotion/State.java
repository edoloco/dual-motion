package cl.dualmotion;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Estado de trabajo que decide el principal (herramienta, colores, capa y fotograma actuales, papel cebolla).
 * Viaja al extendido como {t:"moState", …}.
 */
public final class State {
    public static final int SEL = 0, SUB = 1, PEN = 2, LINE = 3, RECT = 4, OVAL = 5, PENCIL = 6, BRUSH = 7,
            BUCKET = 8, INK = 9, DROPPER = 10, ERASER = 11, HAND = 12;
    public static final String[] NAMES = {"Selección", "Subselección", "Pluma", "Línea", "Rectángulo", "Óvalo",
            "Lápiz", "Pincel", "Cubo de pintura", "Bote de tinta", "Cuentagotas", "Borrador", "Mano"};
    public static final int TOOLS = NAMES.length;

    public int tool = PENCIL;
    public int stroke = 0xFF000000, fill = 0xFFF57C1F;
    public float width = 3;
    public int layer, frame;
    public boolean onion;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("tool", tool); o.put("sc", stroke); o.put("fc", fill); o.put("sw", (double) width);
            o.put("l", layer); o.put("f", frame); o.put("on", onion);
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
    }
}
