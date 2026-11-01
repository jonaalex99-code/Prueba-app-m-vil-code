package com.jonaalex.controluniversal;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements IrEngine.SweepListener, BtHid.Listener {

    private static final int REQ_BT_PERMS = 10;
    private static final int REQ_BT_ENABLE = 11;

    private IrEngine ir;
    private BtHid bt;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView modeChip, bannerText, btText;
    private ImageView btIcon;
    private View banner, irDot, navPill;
    private ProgressBar bannerProgress;
    private Button bannerYes, bannerNo;
    private ImageButton powerBtn;
    private TouchpadView pad;
    private EditText textInput;
    private Switch liveSwitch;
    private View[] pages, navItems;
    private ImageView[] navIcons;
    private TextView[] navTexts;
    private int page = -1;
    private ValueAnimator powerPulse;

    // Detección de la TV por infrarrojo
    private enum Banner { NONE, SWEEP, SWEEP_DONE, REFINE_READY, REFINE_ASK, INFO }

    private Banner bannerState = Banner.NONE;
    private List<IrEngine.Family> candidates = new ArrayList<>();
    private List<IrEngine.Family> testGroup = new ArrayList<>();
    private int volumeUps;

    // Teclado en vivo
    private String lastText = "";
    private boolean suppressWatcher;
    private boolean pendingCastAfterSetup;
    private boolean pendingCastAfterWifi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("control", MODE_PRIVATE);
        ir = IrEngine.get(this);
        bt = BtHid.get(this);

        modeChip = findViewById(R.id.modeChip);
        irDot = findViewById(R.id.irDot);
        banner = findViewById(R.id.banner);
        bannerText = findViewById(R.id.bannerText);
        bannerProgress = findViewById(R.id.bannerProgress);
        bannerYes = findViewById(R.id.bannerYes);
        bannerNo = findViewById(R.id.bannerNo);
        btText = findViewById(R.id.btText);
        btIcon = findViewById(R.id.btIcon);
        powerBtn = findViewById(R.id.btnPower);

        ir.setTxListener(active -> irDot.animate().alpha(active ? 1f : 0.15f)
                .scaleX(active ? 1.4f : 1f).scaleY(active ? 1.4f : 1f).setDuration(active ? 40 : 250).start());

        setupControlPage();
        setupMousePage();
        setupKeyboardPage();
        setupNav();

        findViewById(R.id.btChip).setOnClickListener(v -> onBtAction());
        bannerYes.setOnClickListener(v -> onBannerYes());
        bannerNo.setOnClickListener(v -> onBannerNo());
        pressFx(findViewById(R.id.btChip));
        pressFx(bannerYes);
        pressFx(bannerNo);

        refreshMode();
        bt.addListener(this);
        // Reconecta solo a la TV Bluetooth guardada.
        if (bt.isSupported() && BtHid.hasPermission(this) && bt.adapterEnabled() && bt.savedAddress() != null) {
            bt.start();
        }
        if (!prefs.getBoolean("intro_shown", false) && ir.hasEmitter() && ir.isUniversal()) {
            prefs.edit().putBoolean("intro_shown", true).apply();
            handler.postDelayed(() -> showInfo("Consejo: toca el botón rojo apuntando a la TV y, cuando prenda, "
                    + "toca «¡Ya prendió!». Así el control reconoce tu TV y responde al instante."), 600);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCast();
        if (pendingCastAfterSetup && CastFlow.serviceEnabled(this)) {
            pendingCastAfterSetup = false;
            onCast();
        }
        onResumeCastIfReady();
    }

    @Override
    public void onBackPressed() {
        if (findViewById(R.id.scanOverlay).getVisibility() == View.VISIBLE) {
            hideScanner();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        bt.removeListener(this);
        ir.setTxListener(null);
        if (isFinishing()) bt.shutdown();
        super.onDestroy();
    }

    // ================================================================== secciones

    private void setupNav() {
        pages = new View[]{findViewById(R.id.pageControl), findViewById(R.id.pageMouse), findViewById(R.id.pageKeyboard)};
        navItems = new View[]{findViewById(R.id.navControl), findViewById(R.id.navMouse), findViewById(R.id.navKeyboard)};
        navIcons = new ImageView[]{findViewById(R.id.navControlIcon), findViewById(R.id.navMouseIcon), findViewById(R.id.navKeyboardIcon)};
        navTexts = new TextView[]{findViewById(R.id.navControlText), findViewById(R.id.navMouseText), findViewById(R.id.navKeyboardText)};
        navPill = findViewById(R.id.navPill);
        for (int i = 0; i < navItems.length; i++) {
            final int idx = i;
            navItems[i].setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                showPage(idx, true);
            });
        }
        final int saved = prefs.getInt("page", 0);
        navItems[0].post(() -> showPage(saved, false));
    }

    private void showPage(int idx, boolean animate) {
        if (idx == page) return;
        int old = page;
        page = idx;
        prefs.edit().putInt("page", idx).apply();
        float shift = dp(36) * (old < idx ? 1 : -1);
        for (int i = 0; i < pages.length; i++) {
            final View p = pages[i];
            if (i == idx) {
                p.setVisibility(View.VISIBLE);
                if (animate) {
                    p.setAlpha(0f);
                    p.setTranslationX(shift);
                    p.animate().alpha(1f).translationX(0).setDuration(240)
                            .setInterpolator(new DecelerateInterpolator(2f)).withEndAction(null).start();
                }
            } else if (i == old && animate) {
                p.animate().alpha(0f).translationX(-shift * 0.6f).setDuration(160)
                        .withEndAction(() -> {
                            p.setVisibility(View.GONE);
                            p.setAlpha(1f);
                            p.setTranslationX(0);
                        }).start();
            } else {
                p.setVisibility(View.GONE);
            }
        }
        // Indicador animado de la barra inferior
        View item = navItems[idx];
        float target = item.getLeft() + (item.getWidth() - navPill.getWidth()) / 2f;
        if (animate) {
            navPill.animate().translationX(target).setDuration(280).setInterpolator(new OvershootInterpolator(1.2f)).start();
        } else {
            navPill.setTranslationX(target);
        }
        int on = getResources().getColor(R.color.ink), off = getResources().getColor(R.color.muted);
        for (int i = 0; i < navIcons.length; i++) {
            boolean sel = i == idx;
            navIcons[i].setImageTintList(ColorStateList.valueOf(sel ? on : off));
            navTexts[i].setTextColor(sel ? on : off);
            if (sel && animate) {
                navIcons[i].setScaleX(0.7f);
                navIcons[i].setScaleY(0.7f);
                navIcons[i].animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(3f)).start();
            }
        }
        onStateChanged(bt.state(), bt.deviceName());
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (idx == 2) {
            textInput.requestFocus();
            if (bt.isConnected()) handler.postDelayed(() -> imm.showSoftInput(textInput, 0), 250);
        } else if (imm != null) {
            imm.hideSoftInputFromWindow(textInput.getWindowToken(), 0);
        }
    }

    /** Animación al presionar: el botón se encoge y rebota al soltar. */
    private void pressFx(final View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).setInterpolator(null).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(new OvershootInterpolator(4f)).start();
                    break;
            }
            return false;
        });
    }

    // ================================================================== CONTROL

    private void setupControlPage() {
        bindKey(R.id.btnInput, "source");
        bindKey(R.id.btnMute, "mute");
        bindKey(R.id.btnVolUp, "vol_up");
        bindKey(R.id.btnVolDn, "vol_dn");
        bindKey(R.id.btnChUp, "ch_up");
        bindKey(R.id.btnChDn, "ch_dn");
        bindKey(R.id.btnHome, "home");
        bindKey(R.id.btnMenu, "menu");
        bindKey(R.id.btnBack, "back");

        powerBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            onPower(false);
        });
        powerBtn.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            onPower(true);
            return true;
        });
        pressFx(powerBtn);

        // En pantallas bajas, los botones centrales se achican para que todo quepa sin scroll.
        final LinearLayout col = findViewById(R.id.centerCol);
        col.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (h == ob - ot && col.getTag() != null) return;
            col.setTag(h);
            int size = Math.max(dp(36), Math.min(dp(52), (h - dp(16)) / 3));
            for (int i = 0; i < col.getChildCount(); i++) {
                View c = col.getChildAt(i);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams();
                if (lp.height != size) {
                    lp.width = lp.height = size;
                    c.setLayoutParams(lp);
                }
            }
        });

        DpadView dpad = findViewById(R.id.dpad);
        dpad.setListener(this::send);

        View cast = findViewById(R.id.btnCast);
        cast.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            onCast();
        });
        cast.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showCastOptions();
            return true;
        });
        pressFx(cast);

        modeChip.setOnClickListener(v -> showModeDialog());
        pressFx(modeChip);

        final TextView segA = findViewById(R.id.segArrows), segN = findViewById(R.id.segNumbers);
        segA.setOnClickListener(v -> selectSeg(false));
        segN.setOnClickListener(v -> selectSeg(true));
        buildNumbers();
        selectSeg(prefs.getBoolean("seg_numbers", false));
    }

    private void selectSeg(boolean numbers) {
        TextView segA = findViewById(R.id.segArrows), segN = findViewById(R.id.segNumbers);
        segA.setBackgroundResource(numbers ? 0 : R.drawable.bg_seg_on);
        segN.setBackgroundResource(numbers ? R.drawable.bg_seg_on : 0);
        int on = getResources().getColor(R.color.ink), off = getResources().getColor(R.color.muted);
        segA.setTextColor(numbers ? off : on);
        segN.setTextColor(numbers ? on : off);
        View show = findViewById(numbers ? R.id.numbers : R.id.dpad);
        View hide = findViewById(numbers ? R.id.dpad : R.id.numbers);
        hide.setVisibility(View.GONE);
        if (show.getVisibility() != View.VISIBLE) {
            show.setVisibility(View.VISIBLE);
            show.setAlpha(0f);
            show.setScaleX(0.94f);
            show.setScaleY(0.94f);
            show.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
        }
        prefs.edit().putBoolean("seg_numbers", numbers).apply();
    }

    private void buildNumbers() {
        LinearLayout grid = findViewById(R.id.numbers);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"ok", "0", "back"}};
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            for (final String k : row) {
                View b;
                if (k.equals("back")) {
                    ImageButton ib = new ImageButton(this);
                    ib.setImageResource(R.drawable.ic_back);
                    ib.setColorFilter(getResources().getColor(R.color.ink));
                    ib.setContentDescription(getString(R.string.d_back));
                    b = ib;
                } else {
                    TextView tv = new TextView(this);
                    tv.setText(k.equals("ok") ? "OK" : k);
                    tv.setTextColor(getResources().getColor(R.color.ink));
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, k.equals("ok") ? 17 : 24);
                    tv.setGravity(Gravity.CENTER);
                    b = tv;
                }
                b.setBackgroundResource(R.drawable.bg_click);
                b.setClickable(true);
                b.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    send(k);
                });
                pressFx(b);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                bp.setMargins(dp(5), dp(4), dp(5), dp(4));
                line.addView(b, bp);
            }
        }
    }

    private void bindKey(int id, final String key) {
        View v = findViewById(id);
        v.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            send(key);
        });
        pressFx(v);
    }

    /**
     * Envía una tecla. Si hay una TV conectada por Bluetooth, la navegación y el
     * volumen van por Bluetooth (no hace falta apuntar); lo demás va por infrarrojo.
     */
    private void send(String key) {
        if (bt.isConnected() && sendBt(key)) return;
        if (!ir.hasEmitter()) {
            toast(bt.isSupported() ? "Sin infrarrojo: conecta la TV por Bluetooth" : "Este celular no tiene infrarrojo");
            return;
        }
        if (ir.isSweeping()) {
            toast("Buscando tu TV… toca «¡Ya prendió!» o «Detener» primero");
            return;
        }
        ir.sendKey(key);
    }

    private boolean sendBt(String key) {
        switch (key) {
            case "up": bt.key(BtHid.K_UP, 0); return true;
            case "down": bt.key(BtHid.K_DOWN, 0); return true;
            case "left": bt.key(BtHid.K_LEFT, 0); return true;
            case "right": bt.key(BtHid.K_RIGHT, 0); return true;
            case "ok": bt.key(BtHid.K_ENTER, 0); return true;
            case "back": bt.consumer(BtHid.CC_BACK); return true;
            case "home": bt.consumer(BtHid.CC_HOME); return true;
            case "vol_up": bt.consumer(BtHid.CC_VOL_UP); return true;
            case "vol_dn": bt.consumer(BtHid.CC_VOL_DN); return true;
            case "mute": bt.consumer(BtHid.CC_MUTE); return true;
            case "menu": bt.key(0x65, 0); return true;
            default: return false; // encendido, entrada, canales y números: infrarrojo
        }
    }

    // ================================================================== MOUSE

    private void setupMousePage() {
        pad = findViewById(R.id.touchpad);
        pad.setListener(new TouchpadView.Listener() {
            @Override
            public void onArrow(String key) {
                send(key);
            }

            @Override
            public void onTap() {
                send("ok");
            }

            @Override
            public void onLongPress() {
                send("back");
            }
        });
        pad.setPointerListener(new TouchpadView.PointerListener() {
            @Override
            public void onMove(float dx, float dy) {
                bt.move(dx, dy);
            }

            @Override
            public void onWheel(int ticks) {
                bt.wheel(ticks);
            }

            @Override
            public void onClick(int button) {
                bt.click(button);
            }

            @Override
            public void onButton(int mask, boolean down) {
                bt.button(mask, down);
            }
        });

        bindClickButton(findViewById(R.id.btnLeftClick), 1, "ok");
        bindClickButton(findViewById(R.id.btnRightClick), 2, "back");
        bindKey(R.id.mBack, "back");
        bindKey(R.id.mHome, "home");
        bindKey(R.id.mVolUp, "vol_up");
        bindKey(R.id.mVolDn, "vol_dn");

        setupCalibration();
        setupBtCard(findViewById(R.id.mouseBt));
    }

    // ------------------------------------------------------------------ calibración del mouse

    private static final float DEF_SPEED = 3.0f, DEF_ACCEL = 1.0f;
    private static final int DEF_INTERVAL = 10, DEF_MAX = 127;
    private float calSpeed, calAccel;
    private int calInterval, calMax;
    private SeekBar quickSpeed, calSpeedBar, calAccelBar, calIntervalBar, calMaxBar;
    private TextView calSpeedText, calAccelText, calIntervalText, calMaxText, calStats, calAssistText;
    private View calAssistRow;

    private static int speedToProgress(float sp) {
        return Math.round((float) (Math.log(sp / 0.5f) / Math.log(24) * 100));
    }

    private static float progressToSpeed(int p) {
        return (float) (0.5f * Math.pow(24, p / 100f)); // 0.5x – 12x (escala logarítmica)
    }

    private void setupCalibration() {
        calSpeed = prefs.getFloat("cal_speed", DEF_SPEED);
        calAccel = prefs.getFloat("cal_accel", DEF_ACCEL);
        calInterval = prefs.getInt("cal_interval", DEF_INTERVAL);
        calMax = prefs.getInt("cal_max", DEF_MAX);

        quickSpeed = findViewById(R.id.speed);
        quickSpeed.setProgress(speedToProgress(calSpeed));
        quickSpeed.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (user) setSpeed(progressToSpeed(p), sb);
            }
        });

        LinearLayout card = findViewById(R.id.calCard);
        TextView head = new TextView(this);
        head.setText("Calibrar mouse · los cambios se aplican al instante, prueba en el panel");
        head.setTextColor(getResources().getColor(R.color.ink));
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(head);

        calSpeedText = new TextView(this);
        calSpeedBar = addSlider(card, calSpeedText, 100, speedToProgress(calSpeed), (p, user) -> {
            if (user) setSpeed(progressToSpeed(p), calSpeedBar);
        });
        calAccelText = new TextView(this);
        calAccelBar = addSlider(card, calAccelText, 100, Math.round(calAccel / 3f * 100), (p, user) -> {
            calAccel = p / 100f * 3f;
            applyCalibration();
        });
        calIntervalText = new TextView(this);
        calIntervalBar = addSlider(card, calIntervalText, 46, calInterval - 4, (p, user) -> {
            calInterval = p + 4;
            applyCalibration();
        });
        calMaxText = new TextView(this);
        calMaxBar = addSlider(card, calMaxText, 117, calMax - 10, (p, user) -> {
            calMax = p + 10;
            applyCalibration();
        });

        calStats = new TextView(this);
        calStats.setTextColor(getResources().getColor(R.color.muted));
        calStats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        calStats.setPadding(0, dp(4), 0, dp(4));
        card.addView(calStats);

        // Asistente: deslizas y dices hasta dónde llegó el cursor
        calAssistText = new TextView(this);
        calAssistText.setTextColor(getResources().getColor(R.color.ink));
        calAssistText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        calAssistText.setVisibility(View.GONE);
        card.addView(calAssistText);
        LinearLayout assist = new LinearLayout(this);
        assist.setOrientation(LinearLayout.HORIZONTAL);
        assist.setVisibility(View.GONE);
        calAssistRow = assist;
        String[] opts = {"Casi nada", "La mitad", "Justo", "Se pasó"};
        final float[] mult = {3.5f, 1.9f, 1f, 0.6f};
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            assist.addView(chip(opts[i], v -> onAssistAnswer(mult[idx])), weighted());
        }
        card.addView(assist);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, 0);
        actions.addView(chip("Asistente", v -> startAssist()), weighted());
        actions.addView(chip("Restablecer", v -> resetCalibration()), weighted());
        TextView done = chip("Listo", v -> toggleCalibration(false));
        done.setBackgroundResource(R.drawable.bg_btn_accent);
        done.setTextColor(0xFFFFFFFF);
        actions.addView(done, weighted());
        card.addView(actions);

        View calBtn = findViewById(R.id.mCalibrate);
        calBtn.setOnClickListener(v -> toggleCalibration(true));
        pressFx(calBtn);
        applyCalibration();
    }

    private interface SeekChange {
        void onChange(int progress, boolean fromUser);
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }

    private SeekBar addSlider(LinearLayout parent, TextView label, int max, int progress, final SeekChange change) {
        label.setTextColor(getResources().getColor(R.color.muted));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setPadding(0, dp(6), 0, 0);
        parent.addView(label);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(Math.max(0, Math.min(max, progress)));
        sb.setProgressTintList(ColorStateList.valueOf(getResources().getColor(R.color.accent)));
        sb.setThumbTintList(ColorStateList.valueOf(getResources().getColor(R.color.accent)));
        sb.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                change.onChange(p, user);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                saveCalibration();
            }
        });
        parent.addView(sb, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30)));
        return sb;
    }

    private TextView chip(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(getResources().getColor(R.color.ink));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setBackgroundResource(R.drawable.bg_btn_flat);
        t.setClickable(true);
        t.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            l.onClick(v);
        });
        pressFx(t);
        return t;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private void setSpeed(float sp, SeekBar from) {
        calSpeed = Math.max(0.5f, Math.min(12f, sp));
        int p = speedToProgress(calSpeed);
        if (from != quickSpeed) quickSpeed.setProgress(p);
        if (from != calSpeedBar && calSpeedBar != null) calSpeedBar.setProgress(p);
        applyCalibration();
        if (from == quickSpeed) saveCalibration();
    }

    private void applyCalibration() {
        pad.setSensitivity(calSpeed);
        pad.setAcceleration(calAccel);
        bt.setMouseTuning(calInterval, calMax);
        java.util.Locale L = java.util.Locale.ROOT;
        ((TextView) findViewById(R.id.speedLabel)).setText(String.format(L, "Velocidad %.1fx", calSpeed));
        if (calSpeedText == null) return;
        calSpeedText.setText(String.format(L, "Velocidad (multiplicador): %.1fx", calSpeed));
        calAccelText.setText(calAccel < 0.05f ? "Aceleración: ninguna (lineal)"
                : String.format(L, "Aceleración al deslizar rápido: %.1fx", calAccel));
        calIntervalText.setText(String.format(L, "Intervalo entre paquetes: %d ms (≈ %d por segundo)",
                calInterval, 1000 / calInterval));
        calMaxText.setText(String.format(L, "Movimiento máximo por paquete: %d", calMax));
    }

    private void saveCalibration() {
        prefs.edit().putFloat("cal_speed", calSpeed).putFloat("cal_accel", calAccel)
                .putInt("cal_interval", calInterval).putInt("cal_max", calMax).apply();
    }

    private void resetCalibration() {
        calSpeed = DEF_SPEED;
        calAccel = DEF_ACCEL;
        calInterval = DEF_INTERVAL;
        calMax = DEF_MAX;
        quickSpeed.setProgress(speedToProgress(calSpeed));
        calSpeedBar.setProgress(speedToProgress(calSpeed));
        calAccelBar.setProgress(Math.round(calAccel / 3f * 100));
        calIntervalBar.setProgress(calInterval - 4);
        calMaxBar.setProgress(calMax - 10);
        applyCalibration();
        saveCalibration();
        toast("Calibración restablecida");
    }

    private void toggleCalibration(boolean show) {
        final View card = findViewById(R.id.calCard);
        View quick = findViewById(R.id.mouseQuick);
        View clicks = findViewById(R.id.clickRow);
        if (show) {
            card.setVisibility(View.VISIBLE);
            quick.setVisibility(View.GONE);
            clicks.setVisibility(View.GONE);
            card.setAlpha(0f);
            card.setTranslationY(dp(24));
            card.animate().alpha(1f).translationY(0).setDuration(220).start();
            handler.post(statsTick);
        } else {
            saveCalibration();
            handler.removeCallbacks(statsTick);
            calAssistText.setVisibility(View.GONE);
            calAssistRow.setVisibility(View.GONE);
            card.setVisibility(View.GONE);
            clicks.setVisibility(View.VISIBLE);
            quick.setVisibility(View.VISIBLE);
            quick.setAlpha(0f);
            quick.animate().alpha(1f).setDuration(200).start();
        }
    }

    /** Estadísticas en vivo del envío de paquetes. */
    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            View card = findViewById(R.id.calCard);
            if (card.getVisibility() != View.VISIBLE) return;
            int[] st = bt.takeStats(); // por 0.5 s
            String txt;
            if (!bt.isConnected()) {
                txt = "Conecta la TV por Bluetooth para ver las estadísticas";
            } else {
                int sent = st[0] * 2, failed = st[1] * 2;
                txt = "Paquetes: " + sent + "/s enviados · " + failed + "/s fallidos · pendiente " + st[2];
                if (failed > 0 && failed * 5 > sent + failed) {
                    txt += "\nMuchos fallos: sube el intervalo entre paquetes";
                } else if (st[2] > 400) {
                    txt += "\nEl movimiento se acumula: sube el máximo por paquete o baja el intervalo";
                }
            }
            calStats.setText(txt);
            handler.postDelayed(this, 500);
        }
    };

    private void startAssist() {
        calAssistText.setText("Desliza el dedo de lado a lado del panel, a velocidad normal. "
                + "¿Hasta dónde llegó el cursor en la TV?");
        calAssistText.setVisibility(View.VISIBLE);
        calAssistRow.setVisibility(View.VISIBLE);
        calAssistRow.setAlpha(0f);
        calAssistRow.animate().alpha(1f).setDuration(200).start();
    }

    private void onAssistAnswer(float mult) {
        if (mult == 1f) {
            calAssistText.setText("¡Perfecto! Calibración guardada.");
            calAssistRow.setVisibility(View.GONE);
            saveCalibration();
            handler.postDelayed(() -> calAssistText.setVisibility(View.GONE), 2500);
            return;
        }
        setSpeed(calSpeed * mult, null);
        saveCalibration();
        calAssistText.setText(String.format(java.util.Locale.ROOT,
                "Velocidad ajustada a %.1fx. Desliza otra vez de lado a lado: ¿hasta dónde llegó?", calSpeed));
    }

    /** Botón de clic: mantenerlo presionado permite arrastrar con el otro dedo. */
    private void bindClickButton(final View v, final int mask, final String irKey) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    view.animate().scaleX(0.95f).scaleY(0.92f).setDuration(70).start();
                    view.setPressed(true);
                    if (bt.isConnected()) bt.button(mask, true);
                    return true;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    view.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(new OvershootInterpolator(3f)).start();
                    if (bt.isConnected()) bt.button(mask, false);
                    else if (e.getActionMasked() == MotionEvent.ACTION_UP) send(irKey);
                    return true;
            }
            return false;
        });
    }

    // ================================================================== TECLADO

    private void setupKeyboardPage() {
        textInput = findViewById(R.id.textInput);
        liveSwitch = findViewById(R.id.liveSwitch);
        final View sendBtn = findViewById(R.id.btnSendText);
        liveSwitch.setChecked(prefs.getBoolean("live_typing", true));
        sendBtn.setVisibility(liveSwitch.isChecked() ? View.GONE : View.VISIBLE);
        liveSwitch.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("live_typing", on).apply();
            sendBtn.setVisibility(on ? View.GONE : View.VISIBLE);
            if (on) setTextSilently("");
        });
        sendBtn.setOnClickListener(v -> {
            if (!requireBt()) return;
            bt.type(textInput.getText().toString());
            setTextSilently("");
        });
        pressFx(sendBtn);

        textInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String now = s.toString();
                if (suppressWatcher || !liveSwitch.isChecked()) {
                    lastText = now;
                    return;
                }
                if (!bt.isConnected()) {
                    lastText = now;
                    toastOnce("Conecta la TV por Bluetooth para escribir en ella");
                    return;
                }
                // Envía solo la diferencia: lo borrado como Retroceso y lo nuevo como teclas.
                int p = 0, max = Math.min(lastText.length(), now.length());
                while (p < max && lastText.charAt(p) == now.charAt(p)) p++;
                bt.backspace(lastText.length() - p);
                bt.type(now.substring(p));
                lastText = now;
            }
        });

        bindTypingKey(R.id.kLeft, () -> bt.key(BtHid.K_LEFT, 0));
        bindTypingKey(R.id.kRight, () -> bt.key(BtHid.K_RIGHT, 0));
        bindTypingKey(R.id.kSpace, () -> {
            if (liveSwitch.isChecked()) textInput.getText().append(" ");
            else bt.key(BtHid.K_SPACE, 0);
        });
        bindTypingKey(R.id.kBackspace, () -> {
            bt.key(BtHid.K_BACKSPACE, 0);
            String t = textInput.getText().toString();
            if (!t.isEmpty()) setTextSilently(t.substring(0, t.length() - 1));
        });
        bindTypingKey(R.id.kEnter, () -> {
            if (!liveSwitch.isChecked() && textInput.length() > 0) bt.type(textInput.getText().toString());
            bt.key(BtHid.K_ENTER, 0);
            setTextSilently("");
        });

        setupBtCard(findViewById(R.id.keyboardBt));
    }

    private void bindTypingKey(int id, final Runnable action) {
        View v = findViewById(id);
        v.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (requireBt()) action.run();
        });
        pressFx(v);
    }

    private void setTextSilently(String t) {
        suppressWatcher = true;
        textInput.setText(t);
        textInput.setSelection(t.length());
        lastText = t;
        suppressWatcher = false;
    }

    private boolean requireBt() {
        if (bt.isConnected()) return true;
        toast("Primero conecta la TV por Bluetooth");
        onBtAction();
        return false;
    }

    // ================================================================== BLUETOOTH

    private void setupBtCard(View card) {
        View action = card.findViewById(R.id.btCardAction);
        action.setOnClickListener(v -> onBtAction());
        pressFx(action);
    }

    @Override
    public void onStateChanged(BtHid.State state, String name) {
        boolean connected = state == BtHid.State.CONNECTED;
        boolean busy = state == BtHid.State.CONNECTING || state == BtHid.State.REGISTERING;
        int accent = getResources().getColor(R.color.accent), muted = getResources().getColor(R.color.muted);

        btIcon.setImageTintList(ColorStateList.valueOf(connected ? accent : muted));
        btText.setText(connected ? (name != null ? name : "Conectado") : busy ? "Conectando…" : "Bluetooth");

        String title, sub, act;
        switch (state) {
            case UNSUPPORTED:
                title = "Bluetooth no disponible";
                sub = "Tu celular no permite funcionar como mouse (requiere Android 9+)";
                act = "Info";
                break;
            case OFF:
                title = "Bluetooth apagado";
                sub = "Actívalo para usar el mouse y el teclado";
                act = "Activar";
                break;
            case CONNECTED:
                title = "Conectado a " + (name != null ? name : "la TV");
                sub = page == 2 ? "Lo que escribas aparece en la TV" : "Mueve el dedo: el cursor se mueve en la TV";
                act = "Cambiar";
                break;
            case CONNECTING:
            case REGISTERING:
                title = "Conectando" + (name != null ? " con " + name : "") + "…";
                sub = "Acepta la conexión en la TV si lo pide";
                act = "TVs";
                break;
            default:
                title = "Sin conexión Bluetooth";
                sub = page == 2 ? "Conéctate para escribir en la TV" : "Por ahora el panel envía flechas por infrarrojo";
                act = "Conectar";
        }
        for (int id : new int[]{R.id.mouseBt, R.id.keyboardBt}) {
            View card = findViewById(id);
            ((TextView) card.findViewById(R.id.btCardTitle)).setText(title);
            ((TextView) card.findViewById(R.id.btCardSub)).setText(sub);
            ((TextView) card.findViewById(R.id.btCardAction)).setText(act);
            ((ImageView) card.findViewById(R.id.btCardIcon)).setImageTintList(ColorStateList.valueOf(connected ? accent : muted));
            View pulse = card.findViewById(R.id.btPulse);
            pulse.animate().cancel();
            if (busy) pulseLoop(pulse);
            else pulse.animate().alpha(connected ? 0.6f : 0f).scaleX(1f).scaleY(1f).setDuration(250).start();
        }

        pad.setPointerMode(connected);
        pad.setHint(connected
                ? "Mueve el dedo para mover el cursor\nToca = clic · Dos dedos = clic derecho o desplazar\nMantén presionado y arrastra = arrastrar"
                : "Sin Bluetooth: desliza = flechas · toca = OK\nmantén presionado = Atrás (infrarrojo)\n\nConecta el Bluetooth para tener cursor de mouse");
    }

    private void pulseLoop(final View v) {
        v.setAlpha(0.7f);
        v.setScaleX(0.6f);
        v.setScaleY(0.6f);
        v.animate().alpha(0f).scaleX(1.25f).scaleY(1.25f).setDuration(900).withEndAction(() -> {
            BtHid.State s = bt.state();
            if (s == BtHid.State.CONNECTING || s == BtHid.State.REGISTERING) pulseLoop(v);
        }).start();
    }

    @android.annotation.SuppressLint("MissingPermission") // el permiso se verifica antes con BtHid.hasPermission
    private void onBtAction() {
        if (!bt.isSupported()) {
            new AlertDialog.Builder(this)
                    .setTitle("Mouse Bluetooth no disponible")
                    .setMessage("Este celular no permite presentarse como mouse/teclado Bluetooth "
                            + "(se necesita Android 9 o superior y que el fabricante lo tenga activado). "
                            + "Puedes seguir usando el panel con flechas por infrarrojo.")
                    .setPositiveButton("Entendido", null)
                    .show();
            return;
        }
        if (!BtHid.hasPermission(this)) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT",
                        "android.permission.BLUETOOTH_ADVERTISE"}, REQ_BT_PERMS);
            }
            return;
        }
        if (!bt.adapterEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT_ENABLE);
            } catch (Exception e) {
                toast("Activa el Bluetooth en los ajustes");
            }
            return;
        }
        bt.start();
        showDeviceDialog();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_SCAN) {
            if (TvScanner.hasPermission(this)) onCast();
            else {
                toast("Sin el permiso de dispositivos cercanos se abre la lista del sistema");
                castViaSystemList();
            }
            return;
        }
        if (code != REQ_BT_PERMS) return;
        if (BtHid.hasPermission(this)) onBtAction();
        else toast("Sin el permiso de «Dispositivos cercanos» no se puede usar el Bluetooth");
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_BT_ENABLE && result == RESULT_OK) handler.postDelayed(this::onBtAction, 400);
    }

    private void showDeviceDialog() {
        final List<BluetoothDevice> devices = bt.pairedDevices();
        final List<String> labels = new ArrayList<>();
        labels.add("＋  Emparejar una TV nueva");
        for (BluetoothDevice d : devices) {
            String n;
            try {
                n = d.getName();
            } catch (SecurityException e) {
                n = null;
            }
            labels.add((n != null ? n : d.getAddress()));
        }
        final boolean connected = bt.isConnected();
        if (connected) labels.add("Desconectar");
        new AlertDialog.Builder(this)
                .setTitle("Conectar la TV por Bluetooth")
                .setItems(labels.toArray(new String[0]), (dlg, which) -> {
                    if (which == 0) {
                        showPairDialog();
                    } else if (connected && which == labels.size() - 1) {
                        bt.disconnect();
                    } else {
                        BluetoothDevice d = devices.get(which - 1);
                        bt.connect(d);
                        toast("Conectando con " + labels.get(which) + "…");
                    }
                })
                .show();
    }

    @android.annotation.SuppressLint("MissingPermission") // solo se llega aquí con el permiso concedido
    private void showPairDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Emparejar una TV nueva")
                .setMessage("1. Toca «Hacer visible» y acepta.\n"
                        + "2. En la TV abre Ajustes › Control remoto y accesorios › Agregar accesorio "
                        + "(o Ajustes › Bluetooth / Conexiones › Buscar dispositivos).\n"
                        + "3. Elige «" + bt.localName() + "» y acepta el código en ambos.\n\n"
                        + "Listo: la próxima vez se conecta solo al abrir la app.")
                .setPositiveButton("Hacer visible", (d, w) -> {
                    bt.start();
                    Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                    i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        toast("No se pudo hacer visible el Bluetooth");
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ================================================================== ENCENDIDO / DETECCIÓN (IR)

    private void onPower(boolean forceSweep) {
        if (!ir.hasEmitter()) {
            toast("Este celular no tiene emisor infrarrojo");
            return;
        }
        if (ir.isSweeping()) {
            ir.stopSweep();
            return;
        }
        if (forceSweep) {
            ir.startSweep(this);
            showSweepBanner(0, ir.sweepSize());
        } else if (ir.powerNeedsSweep()) {
            ir.power(this);
            showSweepBanner(0, ir.sweepSize());
        } else {
            ir.power(this);
        }
    }

    @Override
    public void onProgress(int sent, int total) {
        if (bannerState == Banner.SWEEP) showSweepBanner(sent, total);
    }

    @Override
    public void onFinished(boolean cancelled) {
        stopPowerPulse();
        if (bannerState != Banner.SWEEP) return;
        if (cancelled) {
            hideBanner();
        } else {
            bannerState = Banner.SWEEP_DONE;
            bannerProgress.setVisibility(View.GONE);
            bannerNo.setVisibility(View.VISIBLE);
            bannerText.setText("Se enviaron todos los códigos. Si la TV prendió al final, toca «¡Ya prendió!». "
                    + "Si no, acércate, apunta directo a la TV y vuelve a presionar Encender.");
            bannerYes.setText("¡Ya prendió!");
            bannerNo.setText("Cerrar");
        }
    }

    private void showSweepBanner(int sent, int total) {
        if (bannerState != Banner.SWEEP) startPowerPulse();
        bannerState = Banner.SWEEP;
        revealBanner();
        bannerNo.setVisibility(View.VISIBLE);
        bannerProgress.setVisibility(View.VISIBLE);
        bannerProgress.setMax(Math.max(1, total));
        bannerProgress.setProgress(sent);
        bannerText.setText("Apunta el celular hacia la TV. Probando todas las marcas… (" + sent + "/" + total + ")\n"
                + "Cuando la TV prenda o se apague, toca «¡Ya prendió!».");
        bannerYes.setText("¡Ya prendió!");
        bannerNo.setText("Detener");
    }

    private void startPowerPulse() {
        stopPowerPulse();
        powerPulse = ValueAnimator.ofFloat(1f, 1.1f);
        powerPulse.setDuration(520);
        powerPulse.setRepeatMode(ValueAnimator.REVERSE);
        powerPulse.setRepeatCount(ValueAnimator.INFINITE);
        powerPulse.addUpdateListener(a -> {
            float s = (float) a.getAnimatedValue();
            powerBtn.setScaleX(s);
            powerBtn.setScaleY(s);
        });
        powerPulse.start();
    }

    private void stopPowerPulse() {
        if (powerPulse != null) {
            powerPulse.cancel();
            powerPulse = null;
            powerBtn.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
        }
    }

    private void onBannerYes() {
        switch (bannerState) {
            case SWEEP:
            case SWEEP_DONE:
                stopPowerPulse();
                List<IrEngine.Family> c = ir.hitCandidates();
                if (c.isEmpty()) {
                    showInfo("¡Listo! Encender ya funciona directo con tu TV. Los demás botones se envían a todas las marcas.");
                    refreshMode();
                } else if (c.size() <= 2) {
                    finishDetection(c);
                } else {
                    candidates = c;
                    volumeUps = 0;
                    showRefineReady();
                }
                break;
            case REFINE_READY:
                runRefineTest();
                break;
            case REFINE_ASK:
                volumeUps++;
                candidates = testGroup;
                nextRefineStep();
                break;
            default:
                hideBanner();
        }
    }

    private void onBannerNo() {
        switch (bannerState) {
            case SWEEP:
                ir.stopSweep();
                hideBanner();
                break;
            case REFINE_READY:
                finishDetection(candidates);
                break;
            case REFINE_ASK:
                List<IrEngine.Family> rest = new ArrayList<>(candidates);
                rest.removeAll(testGroup);
                candidates = rest;
                nextRefineStep();
                break;
            default:
                hideBanner();
        }
    }

    private void showRefineReady() {
        bannerState = Banner.REFINE_READY;
        revealBanner();
        bannerProgress.setVisibility(View.GONE);
        bannerNo.setVisibility(View.VISIBLE);
        bannerText.setText("¡Bien! Ahora identifiquemos tu TV para que responda al instante.\n"
                + "Espera a que la TV muestre imagen y toca «Probar»: subiremos el volumen un poquito.");
        bannerYes.setText("Probar");
        bannerNo.setText("Omitir");
    }

    private void runRefineTest() {
        testGroup = new ArrayList<>();
        List<IrEngine.Family> withVol = new ArrayList<>();
        for (IrEngine.Family f : candidates) if (f.has("vol_up")) withVol.add(f);
        int half = (withVol.size() + 1) / 2;
        for (int i = 0; i < half; i++) testGroup.add(withVol.get(i));
        if (testGroup.isEmpty()) {
            finishDetection(candidates);
            return;
        }
        ir.sendKeyTo(testGroup, "vol_up");
        bannerState = Banner.REFINE_ASK;
        bannerText.setText("¿Subió el volumen en la TV?");
        bannerYes.setText("Sí");
        bannerNo.setText("No");
    }

    private void nextRefineStep() {
        if (candidates.size() <= 1) {
            finishDetection(candidates);
            return;
        }
        bannerText.setText("Probando de nuevo…");
        handler.postDelayed(() -> {
            if (bannerState == Banner.REFINE_ASK) runRefineTest();
        }, 700);
    }

    private void finishDetection(List<IrEngine.Family> fams) {
        List<String> ids = new ArrayList<>();
        for (IrEngine.Family f : fams) ids.add(f.id);
        ir.setActive(ids);
        for (int i = 0; i < volumeUps; i++) ir.sendKey("vol_dn");
        volumeUps = 0;
        refreshMode();
        showInfo("¡Listo! Control configurado para: " + ir.modeLabel()
                + ".\nAhora todos los botones responden al instante.");
    }

    private void showInfo(String text) {
        bannerState = Banner.INFO;
        revealBanner();
        bannerProgress.setVisibility(View.GONE);
        bannerText.setText(text);
        bannerYes.setText("Entendido");
        bannerNo.setVisibility(View.GONE);
        handler.postDelayed(() -> {
            if (bannerState == Banner.INFO) hideBanner();
        }, 7000);
    }

    private void revealBanner() {
        if (banner.getVisibility() == View.VISIBLE) return;
        banner.setVisibility(View.VISIBLE);
        banner.setAlpha(0f);
        banner.setTranslationY(-dp(40));
        banner.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private void hideBanner() {
        bannerState = Banner.NONE;
        stopPowerPulse();
        banner.animate().alpha(0f).translationY(-dp(30)).setDuration(180).withEndAction(() -> {
            if (bannerState == Banner.NONE) banner.setVisibility(View.GONE);
            bannerNo.setVisibility(View.VISIBLE);
        }).start();
    }

    private void refreshMode() {
        modeChip.setText(ir.hasEmitter() ? "Infrarrojo: " + ir.modeLabel() : "Sin infrarrojo");
    }

    private void showModeDialog() {
        String[] items = {
                "Universal (todas las marcas)",
                "Detectar mi TV (barrido de encendido)",
                "Elegir marca manualmente…"
        };
        new AlertDialog.Builder(this)
                .setTitle("Infrarrojo: " + ir.modeLabel())
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        ir.resetToUniversal();
                        refreshMode();
                        toast("Modo universal: cada botón se envía a las marcas más comunes");
                    } else if (which == 1) {
                        onPower(true);
                    } else {
                        showBrandDialog();
                    }
                })
                .show();
    }

    private void showBrandDialog() {
        final List<IrEngine.Family> fams = ir.families;
        String[] names = new String[fams.size()];
        for (int i = 0; i < names.length; i++) names[i] = fams.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("Elige la marca")
                .setItems(names, (d, which) -> {
                    List<String> ids = new ArrayList<>();
                    ids.add(fams.get(which).id);
                    ir.setActive(ids);
                    refreshMode();
                    toast("Control configurado: " + fams.get(which).name);
                })
                .show();
    }

    // ================================================================== PROYECTAR

    private void refreshCast() {
        TextView sub = findViewById(R.id.castSub);
        String name = CastFlow.tvName(this);
        if (!CastFlow.serviceEnabled(this)) {
            sub.setText("Toca para activar la conexión automática");
        } else if (name == null) {
            sub.setText("Primera vez: elige tu TV en la lista");
        } else {
            sub.setText(name + " · mantén presionado para opciones");
        }
    }

    private void onCast() {
        if (!CastFlow.serviceEnabled(this)) {
            showCastSetup();
            return;
        }
        // La proyección directa (Miracast / Wi-Fi Direct) necesita el WiFi del celular ENCENDIDO,
        // pero no conectado a ninguna red. Si está apagado, lo encendemos primero.
        if (!wifiRadioOn()) {
            pendingCastAfterWifi = true;
            if (Build.VERSION.SDK_INT >= 29) {
                toast("Enciende el WiFi (no hace falta conectarse a ninguna red)");
                try {
                    startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                }
            } else {
                enableWifiLegacy();
                handler.postDelayed(this::onResumeCastIfReady, 1500);
            }
            return;
        }
        // Buscar TVs alrededor por Wi-Fi Direct (sin red).
        if (!TvScanner.hasPermission(this) && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(TvScanner.neededPermissions(), REQ_SCAN);
            return;
        }
        if (!TvScanner.locationOk(this)) {
            pendingCastAfterWifi = true;
            toast("Activa la Ubicación: Android la pide para buscar dispositivos Wi-Fi Direct cercanos");
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        showScanner();
    }

    /** Abre directamente la lista de Transmitir del sistema (sin el buscador). */
    private void castViaSystemList() {
        if (CastFlow.tvName(this) == null) {
            toast("Toca el nombre de tu TV en la lista. La app lo recordará para la próxima vez.");
        }
        if (!CastFlow.startDirect(this)) {
            toast("No encontré el menú de Transmitir. Ábrelo en el panel rápido del celular.");
        }
    }

    // ------------------------------------------------------------------ buscador de TVs

    private static final int REQ_SCAN = 12;
    private TvScanner scanner;
    private long scanStartedAt;
    private boolean autoPicked;

    private void showScanner() {
        final View overlay = findViewById(R.id.scanOverlay);
        if (overlay.getVisibility() != View.VISIBLE) {
            overlay.setVisibility(View.VISIBLE);
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(220).start();
            findViewById(R.id.scanPower).setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (!ir.hasEmitter()) {
                    toast("Este celular no tiene infrarrojo");
                } else if (ir.canPowerQuickly()) {
                    ir.power(null);
                    toast("Encendiendo la TV… aparecerá en unos segundos");
                } else {
                    hideScanner();
                    onPower(false); // barrido universal de encendido
                }
            });
            findViewById(R.id.scanSystem).setOnClickListener(v -> {
                hideScanner();
                castViaSystemList();
            });
            findViewById(R.id.scanClose).setOnClickListener(v -> hideScanner());
            for (int id : new int[]{R.id.scanPower, R.id.scanSystem, R.id.scanClose}) pressFx(findViewById(id));
        }
        ((RadarView) findViewById(R.id.radar)).setRunning(true);
        ((RadarView) findViewById(R.id.radar)).setDevices(new ArrayList<>());
        ((LinearLayout) findViewById(R.id.scanList)).removeAllViews();
        setScanStatus("Sin red WiFi: el celular busca las TVs y se conecta directo a ellas");
        scanStartedAt = System.currentTimeMillis();
        autoPicked = false;
        if (scanner == null) scanner = new TvScanner(this);
        scanner.start(new TvScanner.Listener() {
            @Override
            public void onFound(List<TvScanner.Found> tvs) {
                showFound(tvs);
            }

            @Override
            public void onError(String message) {
                setScanStatus(message);
            }
        });
        handler.removeCallbacks(scanHint);
        handler.postDelayed(scanHint, 12000);
    }

    private final Runnable scanHint = () -> {
        View overlay = findViewById(R.id.scanOverlay);
        if (overlay.getVisibility() == View.VISIBLE && ((LinearLayout) findViewById(R.id.scanList)).getChildCount() == 0) {
            setScanStatus("Aún no aparece ninguna TV. Revisa:\n"
                    + "• Que la TV esté encendida (botón «Encender TV»).\n"
                    + "• En la TV, abre «Screen Mirroring / Duplicar pantalla / Screen Share / Anyview Cast».\n"
                    + "• Si tu TV solo tiene Chromecast, no se puede sin la misma red WiFi.");
        }
    };

    private void setScanStatus(String s) {
        ((TextView) findViewById(R.id.scanStatus)).setText(s);
    }

    private void showFound(List<TvScanner.Found> tvs) {
        ((RadarView) findViewById(R.id.radar)).setDevices(tvs);
        LinearLayout list = findViewById(R.id.scanList);
        list.removeAllViews();
        int good = 0;
        for (final TvScanner.Found f : tvs) {
            if (f.kind != TvScanner.KIND_OTHER) good++;
            list.addView(foundRow(f));
        }
        setScanStatus(tvs.isEmpty() ? "Buscando…" : good + " televisor(es) encontrados · toca uno para proyectar");

        // Si aparece la TV que ya usaste antes, se conecta sola.
        String saved = CastFlow.tvName(this);
        if (saved != null && !autoPicked) {
            for (TvScanner.Found f : tvs) {
                if (f.name.equalsIgnoreCase(saved)) {
                    autoPicked = true;
                    setScanStatus("Encontré «" + f.name + "»: conectando…");
                    handler.postDelayed(() -> pickTv(f), 700);
                    break;
                }
            }
        }
    }

    private View foundRow(final TvScanner.Found f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_click);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setClickable(true);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_cast);
        int color = f.kind == TvScanner.KIND_SINK ? 0xFF3FBF7F
                : f.kind == TvScanner.KIND_OTHER ? getResources().getColor(R.color.muted) : getResources().getColor(R.color.accent);
        icon.setImageTintList(ColorStateList.valueOf(color));
        row.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(f.name);
        name.setTextColor(getResources().getColor(R.color.ink));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        TextView sub = new TextView(this);
        sub.setText(TvScanner.kindLabel(f.kind));
        sub.setTextColor(getResources().getColor(R.color.muted));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        texts.addView(name);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            pickTv(f);
        });
        pressFx(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        row.setAlpha(0f);
        row.setTranslationY(dp(10));
        row.animate().alpha(1f).translationY(0).setDuration(240).start();
        return row;
    }

    /** Proyecta en la TV elegida: abre la proyección del sistema y la toca automáticamente. */
    private void pickTv(TvScanner.Found f) {
        if (findViewById(R.id.scanOverlay).getVisibility() != View.VISIBLE) return;
        CastFlow.setTvName(this, f.name);
        refreshCast();
        hideScanner();
        // Pequeña pausa para liberar el Wi-Fi Direct antes de que el sistema conecte.
        handler.postDelayed(() -> {
            if (!CastFlow.startDirect(this)) toast("No encontré el menú de Transmitir del celular");
            else toast("Conectando con «" + f.name + "»…");
        }, 600);
    }

    private void hideScanner() {
        handler.removeCallbacks(scanHint);
        if (scanner != null) scanner.stop();
        final View overlay = findViewById(R.id.scanOverlay);
        ((RadarView) findViewById(R.id.radar)).setRunning(false);
        overlay.animate().alpha(0f).setDuration(180).withEndAction(() -> overlay.setVisibility(View.GONE)).start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (scanner != null && findViewById(R.id.scanOverlay).getVisibility() == View.VISIBLE) scanner.stop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (scanner != null && findViewById(R.id.scanOverlay).getVisibility() == View.VISIBLE) showScanner();
    }

    private void onResumeCastIfReady() {
        if (pendingCastAfterWifi && wifiRadioOn() && TvScanner.locationOk(this)) {
            pendingCastAfterWifi = false;
            handler.postDelayed(this::onCast, 600);
        }
    }

    private boolean wifiRadioOn() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            return wm == null || wm.isWifiEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    private void enableWifiLegacy() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wm != null) wm.setWifiEnabled(true);
        } catch (Exception ignored) {
        }
    }

    private void showCastSetup() {
        new AlertDialog.Builder(this)
                .setTitle("Proyectar con un solo toque")
                .setMessage("Para que la TV se conecte sola, activa una vez el permiso de Accesibilidad. "
                        + "La app solo lo usa unos segundos después de tocar «Proyectar», para tocar el nombre "
                        + "de tu TV en el menú de Transmitir (y encenderla por infrarrojo si está apagada).\n\n"
                        + "1. Toca «Activar».\n"
                        + "2. Busca «Control Universal» (en Xiaomi está en «Apps descargadas») y actívalo.\n\n"
                        + "Si Android dice «Configuración restringida»: toca «Info de la app», luego ⋮ (arriba a la "
                        + "derecha) › «Permitir configuración restringida», y vuelve a intentarlo.")
                .setPositiveButton("Activar", (d, w) -> {
                    pendingCastAfterSetup = true;
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        toast("Abre Ajustes › Accesibilidad");
                    }
                })
                .setNeutralButton("Info de la app", (d, w) -> {
                    pendingCastAfterSetup = true;
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                })
                .setNegativeButton("Proyectar a mano", (d, w) -> {
                    if (!CastFlow.start(MainActivity.this, false)) {
                        toast("Abre «Transmitir» en el panel rápido del celular y toca tu TV.");
                    } else {
                        Toast.makeText(MainActivity.this, "Si dice «No se encontraron pantallas»: toca ⋮ (arriba a la "
                                + "derecha) › «Habilitar pantalla inalámbrica»", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void showCastOptions() {
        final String name = CastFlow.tvName(this);
        final boolean power = CastFlow.powerIfMissing(this);
        String[] items = {
                "Forma de proyectar: " + CastFlow.methodLabel(CastFlow.method(this)),
                "Encender la TV si no aparece: " + (power ? "Sí" : "No"),
                name == null ? "TV guardada: ninguna" : "Olvidar la TV guardada («" + name + "»)",
                "Permiso de accesibilidad"
        };
        new AlertDialog.Builder(this)
                .setTitle("Proyectar")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showMethodDialog();
                    } else if (which == 1) {
                        CastFlow.setPowerIfMissing(MainActivity.this, !power);
                        toast(!power ? "Se encenderá la TV si no aparece" : "No se encenderá la TV");
                    } else if (which == 2 && name != null) {
                        CastFlow.setTvName(MainActivity.this, null);
                        refreshCast();
                        toast("La próxima vez elige tu TV en la lista");
                    } else if (which == 3) {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                })
                .show();
    }

    private void showMethodDialog() {
        final String[] methods = {CastFlow.METHOD_PANEL, CastFlow.METHOD_SETTINGS, CastFlow.METHOD_HOME};
        String[] labels = new String[methods.length];
        int checked = 0;
        String cur = CastFlow.method(this);
        for (int i = 0; i < methods.length; i++) {
            labels[i] = CastFlow.methodLabel(methods[i]);
            if (methods[i].equals(cur)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Forma de proyectar")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    CastFlow.setMethod(MainActivity.this, methods[which]);
                    CastFlow.setTvName(MainActivity.this, null);
                    refreshCast();
                    d.dismiss();
                    toast("Listo. La próxima vez elige tu TV en la lista.");
                })
                .show();
    }

    // ================================================================== utilidades

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private long lastToastOnce;

    private void toastOnce(String s) {
        long now = System.currentTimeMillis();
        if (now - lastToastOnce > 4000) {
            lastToastOnce = now;
            toast(s);
        }
    }
}
