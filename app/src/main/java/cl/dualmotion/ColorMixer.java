package cl.dualmotion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * Mezclador de colores (como el de Flash): cuadro de saturación/brillo, barra de tono, barra de transparencia
 * (alfa) y, a la derecha, el color anterior arriba y el nuevo abajo.
 */
final class ColorMixer extends View {

    interface Change { void changed(int argb); }

    private final float[] hsv = new float[3];
    private int alpha = 255, old;
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF sq = new RectF(), hue = new RectF(), alp = new RectF(), prev = new RectF();
    private int touching; // 1 cuadro, 2 tono, 3 alfa
    Change onChange;

    ColorMixer(Context c, int color) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        old = color;
        set(color == 0 ? 0xFFFFFFFF : color);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * d);
    }

    void set(int c) {
        Color.colorToHSV(c | 0xFF000000, hsv);
        alpha = c == 0 ? 255 : (c >>> 24);
        invalidate();
    }

    int color() { return (Color.HSVToColor(hsv) & 0x00FFFFFF) | (alpha << 24); }

    private void layout() {
        float w = getWidth(), h = getHeight(), g = 10 * d, bar = 26 * d;
        float side = Math.min(h, w - 2 * bar - 3 * g - 60 * d);
        sq.set(0, 0, side, side);
        hue.set(sq.right + g, 0, sq.right + g + bar, side);
        alp.set(hue.right + g, 0, hue.right + g + bar, side);
        prev.set(alp.right + g, 0, w, side);
    }

    @Override protected void onDraw(Canvas c) {
        layout();
        float r = 6 * d;
        // cuadro: de blanco al tono (horizontal) y a negro (vertical)
        int pure = Color.HSVToColor(new float[]{hsv[0], 1, 1});
        p.setShader(new LinearGradient(sq.left, 0, sq.right, 0, 0xFFFFFFFF, pure, Shader.TileMode.CLAMP));
        c.drawRoundRect(sq, r, r, p);
        p.setShader(new LinearGradient(0, sq.top, 0, sq.bottom, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(sq, r, r, p);
        // barra de tono
        int[] hs = new int[7];
        for (int i = 0; i < 7; i++) hs[i] = Color.HSVToColor(new float[]{i * 60f % 360, 1, 1});
        p.setShader(new LinearGradient(0, hue.top, 0, hue.bottom, hs, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(hue, r, r, p);
        // barra de transparencia, sobre cuadritos
        checker(c, alp);
        int solid = Color.HSVToColor(hsv);
        p.setShader(new LinearGradient(0, alp.top, 0, alp.bottom, solid, solid & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(alp, r, r, p);
        p.setShader(null);
        // marcas
        float mx = sq.left + hsv[1] * sq.width(), my = sq.top + (1 - hsv[2]) * sq.height();
        ring.setColor(hsv[2] > 0.5f ? 0xFF000000 : 0xFFFFFFFF);
        c.drawCircle(mx, my, 8 * d, ring);
        ring.setColor(0xFFFFFFFF);
        float hy = hue.top + hsv[0] / 360f * hue.height();
        c.drawRect(hue.left - 2 * d, hy - 3 * d, hue.right + 2 * d, hy + 3 * d, ring);
        float ay = alp.top + (1 - alpha / 255f) * alp.height();
        c.drawRect(alp.left - 2 * d, ay - 3 * d, alp.right + 2 * d, ay + 3 * d, ring);
        // anterior (arriba) y nuevo (abajo)
        RectF top = new RectF(prev.left, prev.top, prev.right, prev.centerY()), bot = new RectF(prev.left, prev.centerY(), prev.right, prev.bottom);
        checker(c, prev);
        p.setColor(old == 0 ? 0xFFFFFFFF : old);
        c.drawRect(top, p);
        if (old == 0) { p.setColor(Ui.RED); p.setStrokeWidth(2 * d); c.drawLine(top.left, top.bottom, top.right, top.top, p); }
        p.setColor(color());
        c.drawRect(bot, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d);
        p.setColor(0x55000000);
        c.drawRect(prev, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void checker(Canvas c, RectF r) {
        float s = 6 * d;
        c.save();
        c.clipRect(r);
        for (float y = r.top; y < r.bottom; y += s)
            for (float x = r.left; x < r.right; x += s) {
                boolean dark = (((int) ((x - r.left) / s) + (int) ((y - r.top) / s)) & 1) == 1;
                p.setColor(dark ? 0xFFCCCCCC : 0xFFFFFFFF);
                c.drawRect(x, y, x + s, y + s, p);
            }
        c.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            layout();
            float t = 12 * d;
            touching = sq.contains(x, y) ? 1 : (x >= hue.left - t && x <= hue.right + t / 2) ? 2
                    : (x >= alp.left - t / 2 && x <= alp.right + t) ? 3 : 0;
            if (touching != 0) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (touching == 0) return true;
        float u = Math.max(0, Math.min(1, (x - sq.left) / sq.width())), v = Math.max(0, Math.min(1, (y - sq.top) / sq.height()));
        if (touching == 1) { hsv[1] = u; hsv[2] = 1 - v; }
        else if (touching == 2) hsv[0] = Math.min(359.9f, v * 360f);
        else alpha = Math.round((1 - v) * 255);
        invalidate();
        if (onChange != null) onChange.changed(color());
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) touching = 0;
        return true;
    }
}
