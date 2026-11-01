package com.jonaalex.controluniversal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Panel táctil tipo "mouse": deslizar el dedo envía flechas (una por cada tramo
 * recorrido, como mover un cursor), tocar envía OK y mantener presionado envía Atrás.
 */
public class TouchpadView extends View {

    public interface Listener {
        void onArrow(String key); // "up", "down", "left", "right"

        void onTap();

        void onLongPress();
    }

    private static final long LONG_PRESS_MS = 550;

    private Listener listener;
    private final float density;
    private final float stepPx;
    private final int touchSlop;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path arrow = new Path();
    private String hint = "";
    private StaticLayout hintLayout;

    private boolean touching;
    private float lastX, lastY, downX, downY, accX, accY, fingerX, fingerY;
    private long downTime;
    private boolean moved, longFired;
    private String flashKey;
    private long flashUntil;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (touching && !moved) {
                longFired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
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
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        bgPaint.setColor(getResources().getColor(R.color.pad));
        dotPaint.setColor(0x22FFFFFF);
        arrowPaint.setColor(0xCCFFFFFF);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeWidth(4 * density);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
        hintPaint.setColor(getResources().getColor(R.color.muted));
        hintPaint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        hint = c.getString(R.string.pad_hint);
        setContentDescription(c.getString(R.string.tab_pad));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int width = Math.max(1, w - (int) (48 * density));
        hintLayout = new StaticLayout(hint, hintPaint, width, Layout.Alignment.ALIGN_CENTER, 1.2f, 0, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = 28 * density;
        rect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, r, r, bgPaint);

        // Retícula de puntos
        float gap = 22 * density;
        float dr = 1.4f * density;
        for (float y = gap; y < getHeight() - gap / 2; y += gap) {
            for (float x = gap; x < getWidth() - gap / 2; x += gap) {
                canvas.drawCircle(x, y, dr, dotPaint);
            }
        }

        if (!touching && hintLayout != null) {
            canvas.save();
            canvas.translate(24 * density, (getHeight() - hintLayout.getHeight()) / 2f);
            hintLayout.draw(canvas);
            canvas.restore();
        }

        if (touching) {
            float gr = 70 * density;
            glowPaint.setShader(new RadialGradient(fingerX, fingerY, gr, 0x665B8CFF, 0x005B8CFF, Shader.TileMode.CLAMP));
            canvas.drawCircle(fingerX, fingerY, gr, glowPaint);
            glowPaint.setShader(null);
            glowPaint.setColor(0xEEFFFFFF);
            canvas.drawCircle(fingerX, fingerY, 7 * density, glowPaint);
        }

        long now = System.currentTimeMillis();
        if (flashKey != null && now < flashUntil) {
            drawFlash(canvas, flashKey, (flashUntil - now) / 260f);
            postInvalidateOnAnimation();
        }
    }

    private void drawFlash(Canvas c, String key, float alpha) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, s = 18 * density;
        arrowPaint.setAlpha((int) (200 * Math.max(0, Math.min(1, alpha))));
        arrow.reset();
        switch (key) {
            case "up":
                cy = 36 * density;
                arrow.moveTo(cx - s, cy + s / 2);
                arrow.lineTo(cx, cy - s / 2);
                arrow.lineTo(cx + s, cy + s / 2);
                break;
            case "down":
                cy = getHeight() - 36 * density;
                arrow.moveTo(cx - s, cy - s / 2);
                arrow.lineTo(cx, cy + s / 2);
                arrow.lineTo(cx + s, cy - s / 2);
                break;
            case "left":
                cx = 36 * density;
                arrow.moveTo(cx + s / 2, cy - s);
                arrow.lineTo(cx - s / 2, cy);
                arrow.lineTo(cx + s / 2, cy + s);
                break;
            case "right":
                cx = getWidth() - 36 * density;
                arrow.moveTo(cx - s / 2, cy - s);
                arrow.lineTo(cx + s / 2, cy);
                arrow.lineTo(cx - s / 2, cy + s);
                break;
            case "ok":
                c.drawCircle(cx, cy, s * 1.6f, arrowPaint);
                return;
            case "back":
                arrow.moveTo(cx + s, cy);
                arrow.lineTo(cx - s, cy);
                arrow.moveTo(cx - s / 3, cy - s * 0.7f);
                arrow.lineTo(cx - s, cy);
                arrow.lineTo(cx - s / 3, cy + s * 0.7f);
                break;
        }
        c.drawPath(arrow, arrowPaint);
    }

    private void flash(String key) {
        flashKey = key;
        flashUntil = System.currentTimeMillis() + 260;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                moved = false;
                longFired = false;
                downX = lastX = fingerX = x;
                downY = lastY = fingerY = y;
                accX = accY = 0;
                downTime = System.currentTimeMillis();
                postDelayed(longPress, LONG_PRESS_MS);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                fingerX = x;
                fingerY = y;
                if (!moved && Math.hypot(x - downX, y - downY) > touchSlop) {
                    moved = true;
                    removeCallbacks(longPress);
                }
                if (moved && !longFired) {
                    accX += x - lastX;
                    accY += y - lastY;
                    // Una flecha por cada tramo recorrido en el eje dominante.
                    while (Math.abs(accX) >= stepPx || Math.abs(accY) >= stepPx) {
                        if (Math.abs(accX) >= Math.abs(accY)) {
                            emit(accX > 0 ? "right" : "left");
                            accX -= Math.signum(accX) * stepPx;
                            accY *= 0.5f;
                        } else {
                            emit(accY > 0 ? "down" : "up");
                            accY -= Math.signum(accY) * stepPx;
                            accX *= 0.5f;
                        }
                    }
                }
                lastX = x;
                lastY = y;
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (!moved && !longFired && System.currentTimeMillis() - downTime < LONG_PRESS_MS) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (listener != null) listener.onTap();
                    flash("ok");
                }
                touching = false;
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                touching = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void emit(String key) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (listener != null) listener.onArrow(key);
        flash(key);
    }
}
