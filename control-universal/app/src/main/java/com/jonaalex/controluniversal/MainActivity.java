package com.jonaalex.controluniversal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements IrEngine.SweepListener {

    private IrEngine ir;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView status, modeChip, bannerText;
    private View banner, irDot;
    private ProgressBar bannerProgress;
    private Button bannerYes, bannerNo;
    private TextView[] tabs;
    private View[] tabLines, tabPages;

    // Estado del afinado de la detección
    private enum Banner { NONE, SWEEP, SWEEP_DONE, REFINE_READY, REFINE_ASK, INFO }

    private Banner bannerState = Banner.NONE;
    private List<IrEngine.Family> candidates = new ArrayList<>();
    private List<IrEngine.Family> testGroup = new ArrayList<>();
    private int volumeUps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ir = new IrEngine(this);

        status = findViewById(R.id.status);
        modeChip = findViewById(R.id.modeChip);
        irDot = findViewById(R.id.irDot);
        banner = findViewById(R.id.banner);
        bannerText = findViewById(R.id.bannerText);
        bannerProgress = findViewById(R.id.bannerProgress);
        bannerYes = findViewById(R.id.bannerYes);
        bannerNo = findViewById(R.id.bannerNo);

        ir.setTxListener(new IrEngine.TxListener() {
            @Override
            public void onTransmit(boolean active) {
                irDot.animate().alpha(active ? 1f : 0.15f).setDuration(active ? 40 : 250).start();
            }
        });

        bindKey(R.id.btnInput, "source");
        bindKey(R.id.btnMute, "mute");
        bindKey(R.id.btnVolUp, "vol_up");
        bindKey(R.id.btnVolDn, "vol_dn");
        bindKey(R.id.btnChUp, "ch_up");
        bindKey(R.id.btnChDn, "ch_dn");
        bindKey(R.id.btnHome, "home");
        bindKey(R.id.btnMenu, "menu");
        bindKey(R.id.btnBack, "back");
        bindKey(R.id.btnUp, "up");
        bindKey(R.id.btnDown, "down");
        bindKey(R.id.btnLeft, "left");
        bindKey(R.id.btnRight, "right");
        bindKey(R.id.btnOk, "ok");

        ImageButton power = findViewById(R.id.btnPower);
        power.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                onPower(false);
            }
        });
        power.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                onPower(true);
                return true;
            }
        });

        TouchpadView pad = findViewById(R.id.touchpad);
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

        buildNumbers();
        setupTabs();

        modeChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModeDialog();
            }
        });
        bannerYes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBannerYes();
            }
        });
        bannerNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBannerNo();
            }
        });

        refreshMode();
        if (!ir.hasEmitter()) {
            status.setText("Este celular no tiene emisor infrarrojo (IR). La app necesita un teléfono con infrarrojo.");
            status.setTextColor(getResources().getColor(R.color.power));
        }
    }

    // ------------------------------------------------------------------ teclas

    private void bindKey(int id, final String key) {
        View v = findViewById(id);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                send(key);
            }
        });
    }

    private void send(String key) {
        if (ir.isSweeping()) {
            toast("Buscando tu TV… toca «¡Ya prendió!» o «Detener» primero");
            return;
        }
        ir.sendKey(key);
    }

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

    // ------------------------------------------------------------------ barrido

    @Override
    public void onProgress(int sent, int total) {
        if (bannerState == Banner.SWEEP) showSweepBanner(sent, total);
    }

    @Override
    public void onFinished(boolean cancelled) {
        if (bannerState != Banner.SWEEP) return;
        if (cancelled) {
            hideBanner();
        } else {
            bannerState = Banner.SWEEP_DONE;
            banner.setVisibility(View.VISIBLE);
            bannerNo.setVisibility(View.VISIBLE);
            bannerProgress.setVisibility(View.GONE);
            bannerText.setText("Se enviaron todos los códigos. Si la TV prendió al final, toca «¡Ya prendió!». "
                    + "Si no, acércate, apunta directo a la TV y vuelve a presionar Encender.");
            bannerYes.setText("¡Ya prendió!");
            bannerNo.setText("Cerrar");
        }
    }

    private void showSweepBanner(int sent, int total) {
        bannerState = Banner.SWEEP;
        banner.setVisibility(View.VISIBLE);
        bannerNo.setVisibility(View.VISIBLE);
        bannerProgress.setVisibility(View.VISIBLE);
        bannerProgress.setMax(Math.max(1, total));
        bannerProgress.setProgress(sent);
        bannerText.setText("Apunta el celular hacia la TV. Probando todas las marcas… (" + sent + "/" + total + ")\n"
                + "Cuando la TV prenda o se apague, toca «¡Ya prendió!».");
        bannerYes.setText("¡Ya prendió!");
        bannerNo.setText("Detener");
    }

    private void onBannerYes() {
        switch (bannerState) {
            case SWEEP:
            case SWEEP_DONE:
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
                // Sí, subió el volumen: la TV está en el grupo probado.
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
                finishDetection(candidates); // omitir el afinado: deja todas las candidatas
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

    // ------------------------------------------------------------------ afinado

    private void showRefineReady() {
        bannerState = Banner.REFINE_READY;
        banner.setVisibility(View.VISIBLE);
        bannerNo.setVisibility(View.VISIBLE);
        bannerProgress.setVisibility(View.GONE);
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
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bannerState == Banner.REFINE_ASK) runRefineTest();
            }
        }, 700);
    }

    private void finishDetection(List<IrEngine.Family> fams) {
        List<String> ids = new ArrayList<>();
        for (IrEngine.Family f : fams) ids.add(f.id);
        ir.setActive(ids);
        // Deshace las subidas de volumen de las pruebas.
        for (int i = 0; i < volumeUps; i++) ir.sendKey("vol_dn");
        volumeUps = 0;
        refreshMode();
        showInfo("¡Listo! Control configurado para: " + ir.modeLabel()
                + ".\nAhora todos los botones responden al instante.");
    }

    private void showInfo(String text) {
        bannerState = Banner.INFO;
        banner.setVisibility(View.VISIBLE);
        bannerProgress.setVisibility(View.GONE);
        bannerText.setText(text);
        bannerYes.setText("Entendido");
        bannerNo.setVisibility(View.GONE);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bannerState == Banner.INFO) hideBanner();
            }
        }, 6000);
    }

    private void hideBanner() {
        bannerState = Banner.NONE;
        banner.setVisibility(View.GONE);
        bannerNo.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ modo

    private void refreshMode() {
        modeChip.setText(ir.modeLabel());
        if (!ir.hasEmitter()) return;
        if (ir.isUniversal()) {
            status.setText("Modo universal: cada botón se envía a las marcas más comunes. "
                    + "Presiona Encender y toca «¡Ya prendió!» para que responda al instante. "
                    + "Mantén presionado Encender para volver a buscar.");
        } else {
            status.setText("Mantén presionado Encender para buscar otra TV.");
        }
    }

    private void showModeDialog() {
        String[] items = {
                "Universal (todas las marcas)",
                "Detectar mi TV (barrido de encendido)",
                "Elegir marca manualmente…"
        };
        new AlertDialog.Builder(this)
                .setTitle("Modo: " + ir.modeLabel())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            ir.resetToUniversal();
                            refreshMode();
                            toast("Modo universal");
                        } else if (which == 1) {
                            onPower(true);
                        } else {
                            showBrandDialog();
                        }
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
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        List<String> ids = new ArrayList<>();
                        ids.add(fams.get(which).id);
                        ir.setActive(ids);
                        refreshMode();
                        toast("Control configurado: " + fams.get(which).name);
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ pestañas y números

    private void setupTabs() {
        tabs = new TextView[]{findViewById(R.id.tabPad), findViewById(R.id.tabArrows), findViewById(R.id.tabNumbers)};
        tabLines = new View[]{findViewById(R.id.tabLine0), findViewById(R.id.tabLine1), findViewById(R.id.tabLine2)};
        tabPages = new View[]{findViewById(R.id.touchpad), findViewById(R.id.dpad), findViewById(R.id.numbers)};
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
        }
        int saved = getSharedPreferences("control", MODE_PRIVATE).getInt("tab", 0);
        selectTab(saved);
    }

    private void selectTab(int idx) {
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == idx;
            tabs[i].setTextColor(getResources().getColor(on ? R.color.accent : R.color.muted));
            tabLines[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            tabPages[i].setVisibility(on ? View.VISIBLE : View.GONE);
        }
        getSharedPreferences("control", MODE_PRIVATE).edit().putInt("tab", idx).apply();
    }

    private void buildNumbers() {
        LinearLayout grid = findViewById(R.id.numbers);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"ok", "0", "back"}};
        int size = dp(64);
        int gap = dp(10);
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = gap / 2;
            lp.bottomMargin = gap / 2;
            grid.addView(line, lp);
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
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, k.equals("ok") ? 18 : 24);
                    tv.setGravity(Gravity.CENTER);
                    b = tv;
                }
                b.setBackgroundResource(R.drawable.bg_round);
                b.setClickable(true);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        send(k);
                    }
                });
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(size, size);
                bp.leftMargin = dp(14);
                bp.rightMargin = dp(14);
                line.addView(b, bp);
            }
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
