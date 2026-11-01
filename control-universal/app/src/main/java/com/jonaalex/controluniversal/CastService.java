package com.jonaalex.controluniversal;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Servicio de accesibilidad que solo actúa mientras el flujo "Proyectar" está activo
 * (máximo 45 s después de tocar el botón): busca el nombre de la TV en la pantalla
 * de Transmitir y lo toca. La primera vez aprende el nombre cuando el usuario lo toca.
 */
public class CastService extends AccessibilityService {

    private static CastService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastClickAt;
    private boolean qsOpened;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
        }
    };

    static void kick() {
        final CastService s = instance;
        if (s == null) return;
        s.qsOpened = false;
        s.handler.removeCallbacks(s.tick);
        s.handler.postDelayed(s.tick, 400);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!CastFlow.active || e == null) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            learnFromClick(e);
        } else {
            // La pantalla cambió: revisar pronto sin esperar al siguiente ciclo.
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, 150);
        }
    }

    // ------------------------------------------------------------------ ciclo

    private void onTick() {
        if (!CastFlow.active) return;
        long elapsed = SystemClock.uptimeMillis() - CastFlow.startedAt;
        String name = CastFlow.tvName(this);

        if (elapsed > CastFlow.TIMEOUT_MS) {
            CastFlow.stop();
            toast(name == null
                    ? "Toca el nombre de tu TV en la lista para que la app lo recuerde."
                    : "No apareció «" + name + "». Revisa que la TV esté encendida. Algunas TVs necesitan abrir "
                            + "«Screen Mirroring / Duplicar pantalla» en la TV. Si tu TV solo tiene Chromecast, "
                            + "necesita estar en la misma red WiFi que el celular.");
            return;
        }

        List<AccessibilityNodeInfo> roots = roots();

        if (CastFlow.panelPending) {
            if (!qsOpened) {
                qsOpened = true;
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            } else {
                AccessibilityNodeInfo tile = findAny(roots, CastFlow.PANEL_LABELS, false);
                if (tile != null && click(tile)) {
                    CastFlow.panelPending = false;
                } else if (elapsed > CastFlow.PANEL_TIMEOUT_MS) {
                    CastFlow.panelPending = false;
                    android.content.Intent i = CastFlow.settingsIntent(this);
                    if (i != null) {
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        startActivity(i);
                    } else {
                        CastFlow.stop();
                        toast("No encontré el botón «Transmitir». Ábrelo en el panel rápido y toca tu TV.");
                        return;
                    }
                }
            }
            schedule(500);
            return;
        }

        if (name != null && SystemClock.uptimeMillis() - lastClickAt > 900) {
            if (CastFlow.step == 0) {
                AccessibilityNodeInfo tv = findTv(roots, name);
                if (tv != null) {
                    if (isConnected(tv)) {
                        CastFlow.stop();
                        toast("Ya estás proyectando en «" + name + "».");
                        return;
                    }
                    if (click(tv)) advance(name);
                } else if (!CastFlow.powerSent && elapsed > CastFlow.POWER_AFTER_MS && CastFlow.powerIfMissing(this)) {
                    CastFlow.powerSent = true;
                    IrEngine ir = IrEngine.get(this);
                    if (ir.hasEmitter() && ir.canPowerQuickly()) {
                        ir.power(null);
                        toast("La TV no aparece: encendiéndola…");
                    }
                }
            } else {
                String[] labels = CastFlow.HOME_STEPS[CastFlow.step - 1];
                AccessibilityNodeInfo n = findAny(roots, labels, false);
                if (n != null && click(n)) advance(name);
            }
        }
        schedule(600);
    }

    private void advance(String name) {
        lastClickAt = SystemClock.uptimeMillis();
        CastFlow.step++;
        if (CastFlow.step >= CastFlow.totalSteps()) {
            CastFlow.stop();
            toast("Conectando con «" + name + "»…");
        }
    }

    private void schedule(long ms) {
        handler.removeCallbacks(tick);
        if (CastFlow.active) handler.postDelayed(tick, ms);
    }

    // ------------------------------------------------------------------ aprendizaje

    private void learnFromClick(AccessibilityEvent e) {
        if (CastFlow.tvName(this) != null || CastFlow.panelPending) return;
        CharSequence pkg = e.getPackageName();
        if (pkg != null && pkg.toString().equals(getPackageName())) return;
        if (SystemClock.uptimeMillis() - CastFlow.startedAt < 800) return;
        if (SystemClock.uptimeMillis() - lastClickAt < 1200) return; // fue un toque de la propia app

        String label = null;
        for (CharSequence t : e.getText()) {
            if (isNameCandidate(t)) {
                label = t.toString().trim();
                break;
            }
        }
        if (label == null) {
            AccessibilityNodeInfo src = e.getSource();
            if (src != null) label = firstText(src, 0);
        }
        if (label == null) return;
        CastFlow.setTvName(this, label);
        if (CastFlow.METHOD_HOME.equals(CastFlow.method)) {
            // En Google Home seguimos con "Transmitir pantalla" automáticamente.
            CastFlow.step = 1;
            lastClickAt = SystemClock.uptimeMillis();
            toast("Recordaré «" + label + "».");
            schedule(800);
        } else {
            CastFlow.stop();
            toast("¡Listo! Recordaré «" + label + "». La próxima vez se conectará solo.");
        }
    }

    private boolean isNameCandidate(CharSequence t) {
        if (t == null) return false;
        String s = t.toString().trim();
        if (s.length() < 2 || s.length() > 60) return false;
        String low = s.toLowerCase(Locale.ROOT);
        for (String l : CastFlow.PANEL_LABELS) if (low.equals(l.toLowerCase(Locale.ROOT))) return false;
        for (String w : IGNORE) if (low.contains(w)) return false;
        return !(low.contains("conectad") || low.contains("disponible") || low.contains("available")
                || low.contains("buscando") || low.contains("searching") || low.equals("ok")
                || low.equals("cancelar") || low.equals("cancel"));
    }

    private static final String[] IGNORE = {
            "activad", "desactivad", "activar", "habilitar", "enable", "ajustes", "settings", "más opciones",
            "more options", "ayuda", "help", "buscar", "search", "atrás", "back", "listo", "done"
    };

    private String firstText(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 6) return null;
        if (isNameCandidate(n.getText())) return n.getText().toString().trim();
        for (int i = 0; i < n.getChildCount(); i++) {
            String s = firstText(n.getChild(i), depth + 1);
            if (s != null) return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ búsqueda en pantalla

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> r = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null && !getPackageName().contentEquals(nz(root.getPackageName()))) r.add(root);
            }
        } catch (Exception ignored) {
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && !getPackageName().contentEquals(nz(active.getPackageName()))) r.add(active);
        return r;
    }

    private static CharSequence nz(CharSequence s) {
        return s == null ? "" : s;
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (TextUtils.isEmpty(t)) t = n.getContentDescription();
        return t == null ? "" : t.toString().trim();
    }

    private AccessibilityNodeInfo findTv(List<AccessibilityNodeInfo> roots, String name) {
        String want = name.toLowerCase(Locale.ROOT);
        for (AccessibilityNodeInfo root : roots) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(name);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                String l = label(n).toLowerCase(Locale.ROOT);
                if (l.equals(want) || (l.startsWith(want) && l.length() <= want.length() + 40)) return n;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findAny(List<AccessibilityNodeInfo> roots, String[] labels, boolean contains) {
        for (String want : labels) {
            String w = want.toLowerCase(Locale.ROOT);
            for (AccessibilityNodeInfo root : roots) {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(want);
                if (found == null) continue;
                for (AccessibilityNodeInfo n : found) {
                    String l = label(n).toLowerCase(Locale.ROOT);
                    if (l.equals(w) || (contains && l.contains(w)) || l.startsWith(w + "\n") || l.startsWith(w + ",")) {
                        return n;
                    }
                }
            }
        }
        return null;
    }

    private boolean isConnected(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo row = clickableAncestor(n);
        String all = collect(row != null ? row : n, 0, new StringBuilder()).toString().toLowerCase(Locale.ROOT);
        boolean negative = all.contains("no conectad") || all.contains("desconectad")
                || all.contains("not connected") || all.contains("disconnected");
        return !negative && (all.contains("conectad") || all.contains("connected")
                || all.contains("transmitiendo") || all.contains("casting"));
    }

    private StringBuilder collect(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || depth > 5) return sb;
        sb.append(label(n)).append(' ');
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), depth + 1, sb);
        return sb;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 8; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo target = clickableAncestor(n);
        if (target == null || !target.isEnabled()) return false;
        lastClickAt = SystemClock.uptimeMillis();
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private void toast(final String s) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show();
            }
        });
    }
}
