package com.jonaalex.controluniversal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Cruceta circular que se adapta al espacio disponible. Mantener presionado repite. */
public class DpadView extends View {

    public interface Listener {
        void onKey(String key);
    }

    private static final String[] KEYS = {"right", "down", "left", "up"};
    private Listener listener;
    private final float density;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint center = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevron = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path path = new Path();

    private String pressed;
    private long pressedAt, releasedAt;
    private String lastPressed;

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (pressed != null && !"ok".equals(pressed)) {
                fire(pressed, false);
                postDelayed(this, 140);
            }
        }
    };

    public DpadView(Context c) {
        this(c, null);
    }

    public DpadView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        ring.setColor(getResources().getColor(R.color.surface));
        center.setColor(getResources().getColor(R.color.surface_hi));
        glow.setColor(0x405B8CFF);
        chevron.setColor(0xFFF2F2F5);
        chevron.setStyle(Paint.Style.STROKE);
        chevron.setStrokeWidth(3.5f * density);
        chevron.setStrokeCap(Paint.Cap.ROUND);
        chevron.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(0xFFF2F2F5);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        setContentDescription("Cruceta: arriba, abajo, izquierda, derecha y OK");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private float radius() {
        return Math.min(getWidth(), getHeight()) / 2f - 4 * density;
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius(), ir = r * 0.38f;
        long now = SystemClock.uptimeMillis();
        c.drawCircle(cx, cy, r, ring);

        String hl = pressed != null ? pressed : (now - releasedAt < 220 ? lastPressed : null);
        float hlAlpha = pressed != null ? 1f : 1f - (now - releasedAt) / 220f;
        if (hl != null && !"ok".equals(hl)) {
            int idx = indexOf(hl);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            glow.setAlpha((int) (90 * hlAlpha));
            c.drawArc(oval, idx * 90 - 45, 90, true, glow);
        }

        float s = r * 0.11f, d = r * 0.70f;
        path.reset();
        path.moveTo(cx - s, cy - d + s / 2); path.lineTo(cx, cy - d - s / 2); path.lineTo(cx + s, cy - d + s / 2);
        path.moveTo(cx - s, cy + d - s / 2); path.lineTo(cx, cy + d + s / 2); path.lineTo(cx + s, cy + d - s / 2);
        path.moveTo(cx - d + s / 2, cy - s); path.lineTo(cx - d - s / 2, cy); path.lineTo(cx - d + s / 2, cy + s);
        path.moveTo(cx + d - s / 2, cy - s); path.lineTo(cx + d + s / 2, cy); path.lineTo(cx + d - s / 2, cy + s);
        c.drawPath(path, chevron);

        boolean okDown = "ok".equals(pressed);
        float okR = ir * (okDown ? 0.92f : 1f);
        c.drawCircle(cx, cy, okR, center);
        if ("ok".equals(hl)) {
            glow.setAlpha((int) (110 * hlAlpha));
            c.drawCircle(cx, cy, okR, glow);
        }
        text.setTextSize(ir * 0.42f);
        c.drawText("OK", cx, cy + ir * 0.15f, text);

        if (pressed == null && now - releasedAt < 220) postInvalidateOnAnimation();
    }

    private static int indexOf(String k) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(k)) return i;
        return 0;
    }

    private String hit(float x, float y) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float dx = x - cx, dy = y - cy, dist = (float) Math.hypot(dx, dy), r = radius();
        if (dist > r * 1.05f) return null;
        if (dist < r * 0.38f) return "ok";
        double ang = Math.toDegrees(Math.atan2(dy, dx));
        if (ang < 0) ang += 360;
        return KEYS[(int) (((ang + 45) % 360) / 90)];
    }

    private void fire(String k, boolean first) {
        performHapticFeedback(first ? HapticFeedbackConstants.VIRTUAL_KEY : HapticFeedbackConstants.CLOCK_TICK);
        if (listener != null) listener.onKey(k);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                String k = hit(e.getX(), e.getY());
                if (k == null) return false;
                pressed = lastPressed = k;
                pressedAt = SystemClock.uptimeMillis();
                fire(k, true);
                postDelayed(repeat, 420);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                // fall through
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(repeat);
                pressed = null;
                releasedAt = SystemClock.uptimeMillis();
                invalidate();
                return true;
        }
        return true;
    }
}
