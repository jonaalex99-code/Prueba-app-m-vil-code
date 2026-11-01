package com.jonaalex.controluniversal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Panel táctil con dos modos:
 * <ul>
 *   <li><b>Mouse</b> (Bluetooth): mover el dedo mueve el cursor con aceleración, tocar = clic,
 *   dos dedos = clic derecho / desplazar, mantener y arrastrar = arrastrar, borde derecho = rueda.</li>
 *   <li><b>Flechas</b> (infrarrojo): deslizar envía flechas, tocar = OK, mantener = Atrás.</li>
 * </ul>
 */
public class TouchpadView extends View {

    /** Modo flechas (infrarrojo). */
    public interface Listener {
        void onArrow(String key);

        void onTap();

        void onLongPress();
    }

    /** Modo mouse (Bluetooth). */
    public interface PointerListener {
        void onMove(float dx, float dy);

        void onWheel(int ticks);

        void onClick(int button);

        void onButton(int mask, boolean down);
    }

    private static final long LONG_PRESS_MS = 450;
    private static final long TAP_MS = 220;
    private static final int TRAIL = 14;

    private Listener listener;
    private PointerListener pointer;
    private boolean pointerMode;
    private float sensitivity = 1.0f;

    private final float density;
    private final float stepPx, wheelStepPx, stripW;
    private final int touchSlop;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private StaticLayout hintLayout;
    private String hint = "";

    // Estado del gesto
    private boolean touching, moved, longFired, dragging, inStrip, twoFinger, twoMoved;
    private float lastX, lastY, downX, downY, accX, accY, wheelAcc, fingerX, fingerY;
    private float twoLastY;
    private long downTime, lastMoveTime;
    private final float[] trailX = new float[TRAIL], trailY = new float[TRAIL];
    private final long[] trailT = new long[TRAIL];
    private int trailHead, trailCount;

    // Animaciones
    private String flashKey;
    private long flashUntil;
    private float rippleX, rippleY;
    private long rippleStart;
    private boolean rippleRight;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!touching || moved || twoFinger || inStrip) return;
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (pointerMode) {
                dragging = true;
                if (pointer != null) pointer.onButton(1, true);
                ripple(fingerX, fingerY, false);
            } else {
                if (listener != null) listener.onLongPress();
                flash("back");
            }
        }
    };

    public TouchpadView(Context c) {
        this(c, null);
    }

    public TouchpadView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        stepPx = 46 * density;
        wheelStepPx = 18 * density;
        stripW = 44 * density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        bgPaint.setColor(getResources().getColor(R.color.pad));
        dotPaint.setColor(0x1FFFFFFF);
        fgPaint.setColor(0xCCFFFFFF);
        fgPaint.setStyle(Paint.Style.STROKE);
        fgPaint.setStrokeWidth(3.5f * density);
        fgPaint.setStrokeCap(Paint.Cap.ROUND);
        fgPaint.setStrokeJoin(Paint.Join.ROUND);
        stripPaint.setColor(0x14FFFFFF);
        hintPaint.setColor(getResources().getColor(R.color.muted));
        hintPaint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        setHint(c.getString(R.string.pad_hint));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setPointerListener(PointerListener l) {
        pointer = l;
    }

    public void setPointerMode(boolean on) {
        if (pointerMode == on) return;
        pointerMode = on;
        invalidate();
    }

    public void setSensitivity(float s) {
        sensitivity = s;
    }

    public void setHint(String h) {
        hint = h;
        buildHint(getWidth());
        invalidate();
    }

    private void buildHint(int w) {
        int width = Math.max(1, w - (int) (64 * density));
        hintLayout = new StaticLayout(hint, hintPaint, width, Layout.Alignment.ALIGN_CENTER, 1.25f, 0, false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        buildHint(w);
    }

    // ------------------------------------------------------------------ dibujo

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float r = 28 * density;
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, bgPaint);

        float gap = 22 * density, dr = 1.3f * density;
        for (float y = gap; y < h - gap / 2; y += gap)
            for (float x = gap; x < w - gap / 2; x += gap) canvas.drawCircle(x, y, dr, dotPaint);

        long now = SystemClock.uptimeMillis();
        boolean animating = false;

        if (pointerMode) {
            // Franja de desplazamiento (rueda) en el borde derecho
            rect.set(w - stripW, 12 * density, w - 8 * density, h - 12 * density);
            stripPaint.setAlpha(inStrip && touching ? 60 : 22);
            canvas.drawRoundRect(rect, stripW / 2, stripW / 2, stripPaint);
            float cx = w - stripW / 2 - 4 * density, s = 7 * density;
            fgPaint.setAlpha(110);
            path.reset();
            path.moveTo(cx - s, 34 * density);
            path.lineTo(cx, 34 * density - s);
            path.lineTo(cx + s, 34 * density);
            path.moveTo(cx - s, h - 34 * density);
            path.lineTo(cx, h - 34 * density + s);
            path.lineTo(cx + s, h - 34 * density);
            canvas.drawPath(path, fgPaint);
        }

        if (!touching && hintLayout != null) {
            canvas.save();
            canvas.translate(32 * density - (pointerMode ? stripW / 2 : 0), (h - hintLayout.getHeight()) / 2f);
            hintLayout.draw(canvas);
            canvas.restore();
        }

        // Estela del dedo
        for (int i = 0; i < trailCount; i++) {
            int idx = (trailHead - 1 - i + TRAIL) % TRAIL;
            long age = now - trailT[idx];
            if (age > 220) continue;
            float a = 1f - age / 220f;
            glowPaint.setShader(null);
            glowPaint.setColor(0x5B8CFF | ((int) (a * 120) << 24));
            canvas.drawCircle(trailX[idx], trailY[idx], (10 - i * 0.5f) * density * a + 2 * density, glowPaint);
            animating = true;
        }

        if (touching) {
            float gr = 72 * density;
            int core = dragging ? 0x88FFC15E : 0x665B8CFF;
            glowPaint.setShader(new RadialGradient(fingerX, fingerY, gr, core, core & 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawCircle(fingerX, fingerY, gr, glowPaint);
            glowPaint.setShader(null);
            glowPaint.setColor(0xEEFFFFFF);
            canvas.drawCircle(fingerX, fingerY, 7 * density, glowPaint);
        }

        // Onda del clic
        long ra = now - rippleStart;
        if (ra < 380) {
            float p = ra / 380f;
            fgPaint.setAlpha((int) (200 * (1 - p)));
            fgPaint.setColor(rippleRight ? 0xFFFFC15E : 0xFFFFFFFF);
            fgPaint.setAlpha((int) (200 * (1 - p)));
            canvas.drawCircle(rippleX, rippleY, (16 + 46 * p) * density, fgPaint);
            fgPaint.setColor(0xCCFFFFFF);
            animating = true;
        }

        if (flashKey != null && now < flashUntil) {
            drawFlash(canvas, flashKey, (flashUntil - now) / 260f);
            animating = true;
        }
        if (animating) postInvalidateOnAnimation();
    }

    private void drawFlash(Canvas c, String key, float alpha) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, s = 18 * density;
        fgPaint.setAlpha((int) (210 * Math.max(0, Math.min(1, alpha))));
        path.reset();
        switch (key) {
            case "up":
                cy = 36 * density;
                path.moveTo(cx - s, cy + s / 2);
                path.lineTo(cx, cy - s / 2);
                path.lineTo(cx + s, cy + s / 2);
                break;
            case "down":
                cy = getHeight() - 36 * density;
                path.moveTo(cx - s, cy - s / 2);
                path.lineTo(cx, cy + s / 2);
                path.lineTo(cx + s, cy - s / 2);
                break;
            case "left":
                cx = 36 * density;
                path.moveTo(cx + s / 2, cy - s);
                path.lineTo(cx - s / 2, cy);
                path.lineTo(cx + s / 2, cy + s);
                break;
            case "right":
                cx = getWidth() - 36 * density;
                path.moveTo(cx - s / 2, cy - s);
                path.lineTo(cx + s / 2, cy);
                path.lineTo(cx - s / 2, cy + s);
                break;
            case "back":
                path.moveTo(cx + s, cy);
                path.lineTo(cx - s, cy);
                path.moveTo(cx - s / 3, cy - s * 0.7f);
                path.lineTo(cx - s, cy);
                path.lineTo(cx - s / 3, cy + s * 0.7f);
                break;
        }
        c.drawPath(path, fgPaint);
    }

    private void flash(String key) {
        flashKey = key;
        flashUntil = SystemClock.uptimeMillis() + 260;
        invalidate();
    }

    private void ripple(float x, float y, boolean right) {
        rippleX = x;
        rippleY = y;
        rippleRight = right;
        rippleStart = SystemClock.uptimeMillis();
        invalidate();
    }

    private void addTrail(float x, float y) {
        trailX[trailHead] = x;
        trailY[trailHead] = y;
        trailT[trailHead] = SystemClock.uptimeMillis();
        trailHead = (trailHead + 1) % TRAIL;
        trailCount = Math.min(TRAIL, trailCount + 1);
    }

    // ------------------------------------------------------------------ gestos

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                moved = longFired = dragging = twoFinger = twoMoved = false;
                inStrip = pointerMode && x > getWidth() - stripW;
                downX = lastX = fingerX = x;
                downY = lastY = fingerY = y;
                accX = accY = wheelAcc = 0;
                smoothV = 0;
                downTime = lastMoveTime = e.getEventTime();
                trailCount = 0;
                postDelayed(longPress, LONG_PRESS_MS);
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerMode && e.getPointerCount() == 2 && !dragging) {
                    twoFinger = true;
                    twoLastY = (e.getY(0) + e.getY(1)) / 2f;
                    removeCallbacks(longPress);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (twoFinger && e.getPointerCount() >= 2) {
                    float my = (e.getY(0) + e.getY(1)) / 2f;
                    wheelAcc += my - twoLastY;
                    twoLastY = my;
                    emitWheel();
                    fingerX = x;
                    fingerY = y;
                    invalidate();
                    return true;
                }
                if (!moved && Math.hypot(x - downX, y - downY) > touchSlop) {
                    moved = true;
                    removeCallbacks(longPress);
                }
                if (!moved && !dragging) return true;
                if (pointerMode) {
                    if (inStrip) {
                        wheelAcc += y - lastY;
                        emitWheel();
                    } else {
                        // Usa las muestras intermedias para un movimiento más suave.
                        int n = e.getHistorySize();
                        for (int i = 0; i < n; i++) {
                            pointerStep(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i));
                        }
                        pointerStep(x, y, e.getEventTime());
                    }
                } else if (!longFired) {
                    arrowStep(x - lastX, y - lastY);
                }
                lastX = x;
                lastY = y;
                fingerX = x;
                fingerY = y;
                addTrail(x, y);
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                long dur = e.getEventTime() - downTime;
                if (twoFinger) {
                    if (Math.abs(wheelAcc) < wheelStepPx && dur < 350 && pointer != null) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        pointer.onClick(2);
                        ripple(x, y, true);
                    }
                } else if (dragging) {
                    if (pointer != null) pointer.onButton(1, false);
                } else if (!moved && !longFired && !inStrip) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    performClick();
                    if (pointerMode) {
                        if (pointer != null) pointer.onClick(1);
                        ripple(x, y, false);
                    } else if (dur < LONG_PRESS_MS) {
                        if (listener != null) listener.onTap();
                        ripple(x, y, false);
                    }
                }
                touching = dragging = twoFinger = false;
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (dragging && pointer != null) pointer.onButton(1, false);
                touching = dragging = twoFinger = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private float smoothV;

    /**
     * Movimiento con aceleración tipo touchpad de laptop: lento = preciso,
     * rápido = cruza la pantalla de la TV con un solo deslizamiento.
     */
    private void pointerStep(float x, float y, long t) {
        float dx = x - lastX, dy = y - lastY;
        lastX = x;
        lastY = y;
        long dt = Math.max(1, t - lastMoveTime);
        lastMoveTime = t;
        float dxDp = dx / density, dyDp = dy / density;
        float v = (float) Math.hypot(dxDp, dyDp) / dt;      // dp por ms
        smoothV = smoothV * 0.5f + v * 0.5f;                 // suaviza saltos
        float accel = 1f + Math.min(5f, Math.max(0f, smoothV - 0.08f) * 2.2f);
        float gain = 2.6f * sensitivity * accel;             // "cuentas" HID por dp
        if (pointer != null) pointer.onMove(dxDp * gain, dyDp * gain);
    }

    private void emitWheel() {
        int ticks = (int) (wheelAcc / wheelStepPx);
        if (ticks != 0) {
            wheelAcc -= ticks * wheelStepPx;
            if (pointer != null) pointer.onWheel(ticks);
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void arrowStep(float dx, float dy) {
        accX += dx;
        accY += dy;
        while (Math.abs(accX) >= stepPx || Math.abs(accY) >= stepPx) {
            String k;
            if (Math.abs(accX) >= Math.abs(accY)) {
                k = accX > 0 ? "right" : "left";
                accX -= Math.signum(accX) * stepPx;
                accY *= 0.5f;
            } else {
                k = accY > 0 ? "down" : "up";
                accY -= Math.signum(accY) * stepPx;
                accX *= 0.5f;
            }
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (listener != null) listener.onArrow(k);
            flash(k);
        }
    }
}
