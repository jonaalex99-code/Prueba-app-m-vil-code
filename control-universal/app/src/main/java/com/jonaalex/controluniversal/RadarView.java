package com.jonaalex.controluniversal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Radar animado: barrido giratorio, ondas y un punto por cada TV encontrada. */
public class RadarView extends View {
    private final float density;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final List<float[]> blips = new ArrayList<>(); // {ángulo, radio relativo, tipo, nacimiento}
    private boolean running = true;
    private SweepGradient gradient;

    public RadarView(Context c) {
        this(c, null);
    }

    public RadarView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.2f * density);
        ring.setColor(0x335B8CFF);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeWidth(2f * density);
    }

    public void setRunning(boolean r) {
        running = r;
        invalidate();
    }

    /** Coloca un punto por TV (posición estable según su dirección). */
    public void setDevices(List<TvScanner.Found> tvs) {
        List<float[]> old = new ArrayList<>(blips);
        blips.clear();
        for (TvScanner.Found f : tvs) {
            int h = f.address != null ? f.address.hashCode() : f.name.hashCode();
            float ang = (h & 0xFFFF) / 65535f * 360f;
            float rad = 0.45f + ((h >>> 16) & 0xFF) / 255f * 0.45f;
            float born = SystemClock.uptimeMillis();
            for (float[] o : old) if (o[0] == ang && o[1] == rad) born = o[3];
            blips.add(new float[]{ang, rad, f.kind, born});
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        gradient = new SweepGradient(w / 2f, h / 2f, new int[]{0x005B8CFF, 0x005B8CFF, 0x885B8CFF}, new float[]{0f, 0.75f, 1f});
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - 4 * density;
        long now = SystemClock.uptimeMillis();
        for (int i = 1; i <= 3; i++) c.drawCircle(cx, cy, r * i / 3f, ring);

        if (running) {
            float angle = (now % 2400) / 2400f * 360f;
            matrix.setRotate(angle, cx, cy);
            gradient.setLocalMatrix(matrix);
            sweep.setShader(gradient);
            c.drawCircle(cx, cy, r, sweep);
            for (int i = 0; i < 2; i++) {
                float p = ((now + i * 1200) % 2400) / 2400f;
                wave.setColor(0x5B8CFF | ((int) ((1 - p) * 120) << 24));
                c.drawCircle(cx, cy, r * p, wave);
            }
        }

        dot.setColor(0xFFF2F2F5);
        c.drawCircle(cx, cy, 6 * density, dot);

        for (float[] b : blips) {
            double a = Math.toRadians(b[0]);
            float x = cx + (float) Math.cos(a) * r * b[1], y = cy + (float) Math.sin(a) * r * b[1];
            int color = b[2] == TvScanner.KIND_SINK ? 0xFF3FBF7F : b[2] <= TvScanner.KIND_NAME ? 0xFF5B8CFF : 0xFF9A9AA3;
            float age = Math.min(1f, (now - b[3]) / 400f);
            float pulse = 1f + 0.25f * (float) Math.sin(now / 180.0 + b[0]);
            dot.setColor(color);
            dot.setAlpha((int) (255 * age));
            c.drawCircle(x, y, 7 * density * pulse * (0.4f + 0.6f * age), dot);
            dot.setAlpha(255);
        }
        if (running || !blips.isEmpty()) postInvalidateOnAnimation();
    }
}
