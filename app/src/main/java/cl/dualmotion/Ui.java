package cl.dualmotion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/** Estética común: cristal blanco con verde, panel naranja, botones con un símbolo grande. */
public final class Ui {
    private Ui() { }

    public static final int ORANGE = 0xFFF57C1F, GREEN = 0xFF2FA866, WORK = 0xFF8C8C8C, INK = 0xFF263238,
            BLUE = 0xFF2F7BE0, RED = 0xFFE53935;

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** Panel de cristal blanco con borde verde. */
    public static GradientDrawable glass(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xEEFFFFFF);
        g.setCornerRadius(dp(c, 16));
        g.setStroke(dp(c, 1.5f), 0x992FA866);
        return g;
    }

    /** Panel naranja (lo principal: las herramientas). */
    public static GradientDrawable orange(Context c) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF5FF9440, 0xF5F06A10});
        g.setCornerRadius(dp(c, 16));
        g.setStroke(dp(c, 1.5f), 0x88FFFFFF);
        return g;
    }

    public static void panel(View v, GradientDrawable bg) {
        v.setBackground(bg);
        v.setElevation(dp(v.getContext(), 6));
        v.setClipToOutline(true);
    }

    /** Botón con un símbolo grande; el nombre aparece al mantenerlo presionado. */
    public static TextView sym(Context c, String symbol, final String name, final Runnable r) {
        final TextView t = new TextView(c);
        t.setText(symbol);
        t.setTextSize(20);
        t.setTextColor(INK);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x14000000);
        g.setCornerRadius(dp(c, 10));
        t.setBackground(g);
        t.setOnClickListener(v -> { if (r != null) r.run(); });
        t.setOnLongClickListener(v -> { toast(c, name); return true; });
        t.setContentDescription(name);
        return t;
    }

    public static TextView label(Context c, String s, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    public static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); }

    // ---------------------------------------------------------------- íconos de herramientas
    /** Botón de herramienta: ícono dibujado en código. */
    public static final class ToolButton extends View {
        final int tool;
        boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public ToolButton(Context c, int tool) {
            super(c);
            this.tool = tool;
            setContentDescription(State.NAMES[tool]);
            setOnLongClickListener(v -> { toast(c, State.NAMES[tool]); return true; });
        }

        public void setOn(boolean b) { if (on != b) { on = b; invalidate(); } }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float pad = dp(getContext(), 3);
            r.set(pad, pad, w - pad, h - pad);
            if (on) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xF2FFFFFF);
                c.drawRoundRect(r, dp(getContext(), 10), dp(getContext(), 10), p);
            }
            float s = Math.min(w, h) * 0.62f;
            c.save();
            c.translate(w / 2 - s / 2, h / 2 - s / 2);
            c.scale(s / 24f, s / 24f);
            icon(c, tool, on ? ORANGE : Color.WHITE, p);
            c.restore();
        }
    }

    /** Dibuja el ícono de la herramienta en una caja de 24 × 24. */
    public static void icon(Canvas c, int tool, int color, Paint p) {
        p.setColor(color);
        p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        Path a = new Path();
        switch (tool) {
            case State.SEL:
            case State.SUB:
                a.moveTo(6, 3); a.lineTo(6, 19); a.lineTo(10, 15); a.lineTo(13, 21); a.lineTo(15.5f, 20);
                a.lineTo(12.5f, 14); a.lineTo(18, 14); a.close();
                p.setStyle(tool == State.SEL ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
                c.drawPath(a, p);
                break;
            case State.FREE: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.4f);
                p.setPathEffect(new android.graphics.DashPathEffect(new float[]{2.2f, 1.8f}, 0));
                c.drawRect(5, 7, 17, 19, p);
                p.setPathEffect(null);
                p.setStyle(Paint.Style.FILL);
                for (float[] q : new float[][]{{5, 7}, {17, 7}, {5, 19}, {17, 19}, {11, 7}, {11, 19}, {5, 13}, {17, 13}})
                    c.drawRect(q[0] - 1.6f, q[1] - 1.6f, q[0] + 1.6f, q[1] + 1.6f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.6f);
                c.drawArc(new RectF(15, 1.5f, 23, 9.5f), 200, 160, false, p);
                break;
            }
            case State.PEN:
                a.moveTo(12, 2.5f); a.lineTo(18, 13); a.lineTo(14.5f, 20.5f); a.lineTo(9.5f, 20.5f); a.lineTo(6, 13); a.close();
                p.setStyle(Paint.Style.STROKE); c.drawPath(a, p);
                c.drawLine(12, 2.5f, 12, 11, p);
                p.setStyle(Paint.Style.FILL); c.drawCircle(12, 13, 1.8f, p);
                break;
            case State.LINE:
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f); c.drawLine(5, 19, 19, 5, p);
                break;
            case State.RECT:
                p.setStyle(Paint.Style.STROKE); c.drawRect(4.5f, 6.5f, 19.5f, 17.5f, p);
                break;
            case State.OVAL:
                p.setStyle(Paint.Style.STROKE); c.drawOval(new RectF(3.5f, 6, 20.5f, 18), p);
                break;
            case State.PENCIL:
                a.moveTo(5, 19); a.lineTo(6, 15); a.lineTo(16, 5); a.lineTo(19, 8); a.lineTo(9, 18); a.close();
                p.setStyle(Paint.Style.STROKE); c.drawPath(a, p);
                c.drawLine(14, 7, 17, 10, p);
                break;
            case State.BRUSH:
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.4f); c.drawLine(19.5f, 4.5f, 12, 12, p);
                p.setStyle(Paint.Style.FILL);
                a.moveTo(11.5f, 11); a.lineTo(13.5f, 13); a.cubicTo(12, 18, 8, 20, 4.5f, 20);
                a.cubicTo(6, 17, 7, 12.5f, 11.5f, 11); a.close();
                c.drawPath(a, p);
                break;
            case State.BUCKET:
                a.moveTo(4.5f, 11); a.lineTo(11.5f, 4); a.lineTo(18.5f, 11); a.lineTo(11.5f, 18); a.close();
                p.setStyle(Paint.Style.STROKE); c.drawPath(a, p);
                p.setStyle(Paint.Style.FILL);
                Path d = new Path(); d.moveTo(19.5f, 14); d.cubicTo(21.5f, 17, 21.5f, 19.5f, 19.5f, 19.5f);
                d.cubicTo(17.5f, 19.5f, 17.5f, 17, 19.5f, 14); d.close(); c.drawPath(d, p);
                break;
            case State.INK:
                p.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(7, 10, 17, 20.5f), 2, 2, p);
                c.drawRect(9.5f, 5.5f, 14.5f, 10, p);
                p.setStyle(Paint.Style.FILL); c.drawRect(7, 15, 17, 20.5f, p);
                break;
            case State.DROPPER:
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.6f); c.drawLine(5, 19, 14, 10, p);
                p.setStyle(Paint.Style.FILL); c.drawCircle(16.5f, 7.5f, 3.6f, p);
                break;
            case State.ERASER:
                a.moveTo(4.5f, 15); a.lineTo(12.5f, 7); a.lineTo(19.5f, 14); a.lineTo(11.5f, 22); a.close();
                p.setStyle(Paint.Style.STROKE); c.drawPath(a, p);
                Path half = new Path(); half.moveTo(4.5f, 15); half.lineTo(8.5f, 11); half.lineTo(15.5f, 18);
                half.lineTo(11.5f, 22); half.close();
                p.setStyle(Paint.Style.FILL); c.drawPath(half, p);
                break;
            case State.HAND:
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f);
                c.drawLine(8.5f, 12, 8.5f, 6, p); c.drawLine(11, 11, 11, 4, p);
                c.drawLine(13.5f, 11, 13.5f, 4.5f, p); c.drawLine(16, 12, 16, 6.5f, p);
                c.drawLine(7.5f, 15, 4.5f, 12, p);
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(7.2f, 11, 17.3f, 20.5f), 4, 4, p);
                break;
        }
    }

    // ---------------------------------------------------------------- botón cerrar (estilo Frutiger Aero)
    /** Cuadrado rojo brillante con una X blanca, dibujado (no depende de la letra del teléfono). */
    public static final class AeroClose extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private boolean down;

        public AeroClose(Context c, Runnable onTap) {
            super(c);
            setContentDescription("Cerrar DUAL Motion");
            setOnClickListener(v -> onTap.run());
            setOnLongClickListener(v -> { toast(c, "Cerrar DUAL Motion"); return true; });
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) { down = true; invalidate(); }
            else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) { down = false; invalidate(); }
            return super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float s = Math.min(getWidth(), getHeight()) - 4 * d;
            float x0 = (getWidth() - s) / 2f, y0 = (getHeight() - s) / 2f, rad = s * 0.24f;
            r.set(x0, y0, x0 + s, y0 + s);
            // sombra suave
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(0x33000000);
            c.drawRoundRect(r.left, r.top + 1.5f * d, r.right, r.bottom + 1.5f * d, rad, rad, p);
            // cuerpo: rojo brillante con degradado
            p.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.bottom,
                    down ? new int[]{0xFFE04848, 0xFFB00000, 0xFF8A0000} : new int[]{0xFFFF7A6E, 0xFFE31B12, 0xFFB80A05},
                    new float[]{0, 0.55f, 1}, android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, p);
            // brillo de vidrio en la mitad de arriba
            RectF g = new RectF(r.left + s * 0.08f, r.top + s * 0.05f, r.right - s * 0.08f, r.top + s * 0.5f);
            p.setShader(new android.graphics.LinearGradient(0, g.top, 0, g.bottom, 0xCCFFFFFF, 0x10FFFFFF,
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(g, rad * 0.8f, rad * 0.8f, p);
            p.setShader(null);
            // borde
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * d);
            p.setColor(0xFF7A0000);
            c.drawRoundRect(r, rad, rad, p);
            // X blanca con una sombrita
            float m = s * 0.3f;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(s * 0.11f);
            p.setColor(0x55600000);
            c.drawLine(r.left + m, r.top + m + d, r.right - m, r.bottom - m + d, p);
            c.drawLine(r.right - m, r.top + m + d, r.left + m, r.bottom - m + d, p);
            p.setColor(0xFFFFFFFF);
            c.drawLine(r.left + m, r.top + m, r.right - m, r.bottom - m, p);
            c.drawLine(r.right - m, r.top + m, r.left + m, r.bottom - m, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
