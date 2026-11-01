package com.jonaalex.controluniversal;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * Joystick para mover el cursor: mientras el dedo inclina la palanca, el cursor avanza
 * de forma continua (más lejos del centro = más rápido). Tocar sin mover = clic.
 * La franja de la derecha desplaza (rueda del mouse).
 */
public class JoystickView extends View {

    public interface Listener {
        void onMove(float dx, float dy);

        void onWheel(int ticks);

        void onClick();
    }

    private static final long TICK_MS = 12;
    private static final float DEADZONE = 0.07f;

    private Listener listener;
    private final float density;
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float speed = 1f;           // multiplicador (calibración)
    private float knobX, knobY;         // desplazamiento de la palanca (px, relativo al centro)
    private boolean holding, moved, inStrip;
    private float stripLastY, wheelAcc;
    private long downTime, lastTick;
    private int pointerId = -1;
    private ValueAnimator spring;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!holding) return;
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(50, now - lastTick) / 1000f;
            lastTick = now;
            float r = maxR();
            float nx = knobX / r, ny = knobY / r;
            float mag = (float) Math.hypot(nx, ny);
            if (mag > DEADZONE && listener != null) {
                // Curva suave: cerca del centro es preciso, al borde es muy rápido.
                float t = (mag - DEADZONE) / (1 - DEADZONE);
                float v = 2200f * speed / 3f * (0.08f + 0.92f * t * t); // cuentas por segundo
                listener.onMove(nx / mag * v * dt, ny / mag * v * dt);
            }
            postDelayed(this, TICK_MS);
        }
    };

    public JoystickView(Context c) {
        this(c, null);
    }

    public JoystickView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        base.setColor(getResources().getColor(R.color.pad));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * density);
        ring.setColor(0x335B8CFF);
        knob.setColor(0xFF2A2A30);
        strip.setColor(0x14FFFFFF);
        text.setColor(getResources().getColor(R.color.muted));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
        setContentDescription("Joystick del mouse");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setSpeed(float s) {
        speed = s;
    }

    private float stripW() {
        return 48 * density;
    }

    private float cx() {
        return (getWidth() - stripW()) / 2f;
    }

    private float cy() {
        return getHeight() / 2f;
    }

    private float maxR() {
        return Math.min(getWidth() - stripW(), getHeight()) / 2f - 46 * density;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = 28 * density;
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, r, r, base);

        // Franja de desplazamiento
        rect.set(w - stripW() + 4 * density, 12 * density, w - 8 * density, h - 12 * density);
        strip.setAlpha(inStrip && holding ? 60 : 24);
        c.drawRoundRect(rect, 20 * density, 20 * density, strip);
        c.save();
        c.rotate(-90, w - stripW() / 2f, h / 2f);
        c.drawText("desplazar", w - stripW() / 2f, h / 2f + 5 * density, text);
        c.restore();

        float cx = cx(), cy = cy(), mr = maxR();
        c.drawCircle(cx, cy, mr + 30 * density, ring);
        c.drawCircle(cx, cy, mr * 0.5f, ring);

        float kx = cx + knobX, ky = cy + knobY, kr = 40 * density;
        float mag = (float) Math.min(1, Math.hypot(knobX, knobY) / mr);
        int alpha = (int) (0x50 + 0x70 * mag);
        glow.setShader(new RadialGradient(kx, ky, kr * 2.2f, (alpha << 24) | 0x5B8CFF, 0x005B8CFF, Shader.TileMode.CLAMP));
        c.drawCircle(kx, ky, kr * 2.2f, glow);
        knob.setColor(holding ? 0xFF3A3A44 : 0xFF2A2A30);
        c.drawCircle(kx, ky, kr, knob);
        glow.setShader(null);
        glow.setColor(0xEEFFFFFF);
        c.drawCircle(kx, ky, 6 * density, glow);

        if (!holding && knobX == 0 && knobY == 0) {
            c.drawText("Inclina para mover · toca = clic", cx, h - 22 * density, text);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int idx = e.getActionIndex();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                if (spring != null) spring.cancel();
                pointerId = e.getPointerId(0);
                holding = true;
                moved = false;
                downTime = SystemClock.uptimeMillis();
                inStrip = e.getX() > getWidth() - stripW();
                if (inStrip) {
                    stripLastY = e.getY();
                    wheelAcc = 0;
                } else {
                    setKnob(e.getX() - cx(), e.getY() - cy());
                    lastTick = SystemClock.uptimeMillis();
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    post(tick);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int pi = e.findPointerIndex(pointerId);
                if (pi < 0) return true;
                float x = e.getX(pi), y = e.getY(pi);
                if (inStrip) {
                    wheelAcc += y - stripLastY;
                    stripLastY = y;
                    int ticks = (int) (wheelAcc / (18 * density));
                    if (ticks != 0) {
                        wheelAcc -= ticks * 18 * density;
                        if (listener != null) listener.onWheel(ticks);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                } else {
                    setKnob(x - cx(), y - cy());
                    if (Math.hypot(knobX, knobY) > 14 * density) moved = true;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(tick);
                boolean wasStrip = inStrip;
                holding = false;
                inStrip = false;
                if (!wasStrip && e.getActionMasked() == MotionEvent.ACTION_UP && !moved
                        && SystemClock.uptimeMillis() - downTime < 300 && listener != null) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    performClick();
                    listener.onClick();
                }
                springBack();
                return true;
            }
        }
        return true;
    }

    private void setKnob(float x, float y) {
        float mr = maxR(), d = (float) Math.hypot(x, y);
        if (d > mr) {
            x = x / d * mr;
            y = y / d * mr;
        }
        knobX = x;
        knobY = y;
    }

    /** La palanca vuelve al centro con un rebote. */
    private void springBack() {
        final float sx = knobX, sy = knobY;
        spring = ValueAnimator.ofFloat(1f, 0f);
        spring.setDuration(260);
        spring.setInterpolator(new OvershootInterpolator(2.5f));
        spring.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            knobX = sx * f;
            knobY = sy * f;
            invalidate();
        });
        spring.start();
    }
}
