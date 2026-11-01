package com.jonaalex.controluniversal;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.ConsumerIrManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Motor infrarrojo: carga la base de códigos (assets/codes.json), mantiene el modo
 * (universal o marcas detectadas) y transmite en un hilo aparte.
 */
public class IrEngine {
    private static final String TAG = "IrEngine";
    private static final long GAP_MS = 25;          // silencio entre códigos de distintas marcas
    private static final long SWEEP_GAP_MS = 70;    // silencio entre códigos del barrido de encendido
    private static final int MAX_PENDING = 3;       // evita acumular toques si el envío va lento
    private static final long HIT_WINDOW_MS = 4500; // códigos considerados al tocar "¡Ya prendió!"

    public static class Code {
        int freq;
        int[] pattern;
        int[] toggled; // variante con bit de alternancia (RC5 / RC6), puede ser null
        String family;
    }

    public static class Family {
        public String id;
        public String name;
        public boolean universal;
        final Map<String, Code> keys = new HashMap<>();

        public boolean has(String key) {
            return keys.containsKey(key);
        }
    }

    public interface SweepListener {
        void onProgress(int sent, int total);

        void onFinished(boolean cancelled);
    }

    public interface TxListener {
        void onTransmit(boolean active);
    }

    private final ConsumerIrManager ir;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private final Map<String, Boolean> toggles = new HashMap<>();
    private int[][] carrierRanges;

    public final List<Family> families = new ArrayList<>();
    private final Map<String, Family> byId = new HashMap<>();
    private final List<Code> sweep = new ArrayList<>();

    /** Marcas activas; vacío = modo universal. */
    private final LinkedHashSet<String> active = new LinkedHashSet<>();
    /** Códigos de encendido aprendidos cuando la TV respondió a un código sin familia. */
    private final List<Integer> learnedPower = new ArrayList<>();

    private volatile boolean sweeping;
    private volatile boolean cancelSweep;
    private final List<long[]> sweepLog = Collections.synchronizedList(new ArrayList<long[]>());
    private TxListener txListener;

    public IrEngine(Context ctx) {
        ir = (ConsumerIrManager) ctx.getSystemService(Context.CONSUMER_IR_SERVICE);
        prefs = ctx.getSharedPreferences("control", Context.MODE_PRIVATE);
        try {
            if (ir != null && ir.hasIrEmitter()) {
                ConsumerIrManager.CarrierFrequencyRange[] r = ir.getCarrierFrequencies();
                if (r != null && r.length > 0) {
                    carrierRanges = new int[r.length][2];
                    for (int i = 0; i < r.length; i++) {
                        carrierRanges[i][0] = r[i].getMinFrequency();
                        carrierRanges[i][1] = r[i].getMaxFrequency();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudieron leer las frecuencias", e);
        }
        load(ctx);
        restore();
    }

    public boolean hasEmitter() {
        try {
            return ir != null && ir.hasIrEmitter();
        } catch (Exception e) {
            return false;
        }
    }

    public void setTxListener(TxListener l) {
        txListener = l;
    }

    // ------------------------------------------------------------------ carga

    private void load(Context ctx) {
        try {
            InputStream in = ctx.getAssets().open("codes.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(bos.toString("UTF-8"));

            JSONArray fams = root.getJSONArray("families");
            for (int i = 0; i < fams.length(); i++) {
                JSONObject f = fams.getJSONObject(i);
                Family fam = new Family();
                fam.id = f.getString("id");
                fam.name = f.getString("name");
                fam.universal = f.optBoolean("universal");
                JSONObject keys = f.getJSONObject("keys");
                JSONArray names = keys.names();
                if (names != null) {
                    for (int k = 0; k < names.length(); k++) {
                        String key = names.getString(k);
                        Code c = parseCode(keys.getJSONObject(key));
                        c.family = fam.id;
                        fam.keys.put(key, c);
                    }
                }
                families.add(fam);
                byId.put(fam.id, fam);
            }

            JSONArray sw = root.getJSONArray("sweep");
            for (int i = 0; i < sw.length(); i++) {
                JSONObject o = sw.getJSONObject(i);
                Code c = parseCode(o);
                c.family = o.optString("fam", null);
                sweep.add(c);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error al cargar codes.json", e);
        }
    }

    private static Code parseCode(JSONObject o) throws Exception {
        Code c = new Code();
        c.freq = o.getInt("f");
        c.pattern = toInts(o.getJSONArray("p"));
        if (o.has("t")) c.toggled = toInts(o.getJSONArray("t"));
        return c;
    }

    private static int[] toInts(JSONArray a) throws Exception {
        int[] r = new int[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = a.getInt(i);
        return r;
    }

    // ------------------------------------------------------------------ modo

    private void restore() {
        String a = prefs.getString("active", "");
        for (String id : a.split(",")) if (byId.containsKey(id)) active.add(id);
        String lp = prefs.getString("learned_power", "");
        for (String s : lp.split(",")) {
            try {
                if (!s.isEmpty()) learnedPower.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void persist() {
        StringBuilder a = new StringBuilder();
        for (String id : active) a.append(a.length() > 0 ? "," : "").append(id);
        StringBuilder lp = new StringBuilder();
        for (Integer i : learnedPower) lp.append(lp.length() > 0 ? "," : "").append(i);
        prefs.edit().putString("active", a.toString()).putString("learned_power", lp.toString()).apply();
    }

    public boolean isUniversal() {
        return active.isEmpty();
    }

    public List<Family> activeFamilies() {
        List<Family> r = new ArrayList<>();
        for (String id : active) r.add(byId.get(id));
        return r;
    }

    public void setActive(List<String> ids) {
        active.clear();
        for (String id : ids) if (byId.containsKey(id)) active.add(id);
        learnedPower.clear();
        persist();
    }

    public void resetToUniversal() {
        active.clear();
        learnedPower.clear();
        persist();
    }

    public Family family(String id) {
        return byId.get(id);
    }

    public String modeLabel() {
        if (active.isEmpty()) return "Universal";
        if (active.size() == 1) return byId.get(active.iterator().next()).name;
        return active.size() + " marcas";
    }

    // ------------------------------------------------------------------ envío

    /** Envía una tecla a las marcas activas (o a las principales en modo universal). */
    public boolean sendKey(String key) {
        if (sweeping) return false;
        List<Family> targets = new ArrayList<>();
        if (active.isEmpty()) {
            for (Family f : families) if (f.universal && f.has(key)) targets.add(f);
        } else {
            for (String id : active) {
                Family f = byId.get(id);
                if (f != null && f.has(key)) targets.add(f);
            }
        }
        if (targets.isEmpty()) return false;
        return enqueue(targets, key);
    }

    /** Envía una tecla solo a las familias indicadas (usado al afinar la detección). */
    public boolean sendKeyTo(List<Family> targets, String key) {
        if (sweeping) return false;
        List<Family> t = new ArrayList<>();
        for (Family f : targets) if (f.has(key)) t.add(f);
        return !t.isEmpty() && enqueue(t, key);
    }

    private boolean enqueue(final List<Family> targets, final String key) {
        if (pending.get() >= MAX_PENDING) return false;
        pending.incrementAndGet();
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    signal(true);
                    for (Family f : targets) {
                        Code c = f.keys.get(key);
                        if (c == null) continue;
                        int[] p = c.pattern;
                        if (c.toggled != null) {
                            Boolean t = toggles.get(f.id);
                            boolean tog = t != null && t;
                            if (tog) p = c.toggled;
                            toggles.put(f.id, !tog);
                        }
                        transmit(c.freq, p, GAP_MS);
                    }
                } finally {
                    signal(false);
                    pending.decrementAndGet();
                }
            }
        });
        return true;
    }

    /** true si al presionar Encender se hará el barrido completo. */
    public boolean powerNeedsSweep() {
        return active.isEmpty() && learnedPower.isEmpty();
    }

    /** Botón de encendido: rápido si ya se detectó la TV; si no, barrido completo. */
    public void power(SweepListener l) {
        if (!active.isEmpty()) {
            sendKey("power");
        } else if (!learnedPower.isEmpty()) {
            final List<Integer> idx = new ArrayList<>(learnedPower);
            pending.incrementAndGet();
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        signal(true);
                        for (int i : idx) {
                            if (i >= 0 && i < sweep.size()) {
                                Code c = sweep.get(i);
                                transmit(c.freq, c.pattern, SWEEP_GAP_MS);
                            }
                        }
                    } finally {
                        signal(false);
                        pending.decrementAndGet();
                    }
                }
            });
        } else {
            startSweep(l);
        }
    }

    public boolean isSweeping() {
        return sweeping;
    }

    public int sweepSize() {
        return sweep.size();
    }

    public void startSweep(final SweepListener l) {
        if (sweeping) return;
        sweeping = true;
        cancelSweep = false;
        sweepLog.clear();
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final int total = sweep.size();
                int i = 0;
                signal(true);
                try {
                    for (; i < total && !cancelSweep; i++) {
                        Code c = sweep.get(i);
                        sweepLog.add(new long[]{SystemClock.uptimeMillis(), i});
                        transmit(c.freq, c.pattern, SWEEP_GAP_MS);
                        final int sent = i + 1;
                        if (l != null) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    l.onProgress(sent, total);
                                }
                            });
                        }
                    }
                } finally {
                    signal(false);
                    sweeping = false;
                    final boolean cancelled = cancelSweep;
                    if (l != null) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                l.onFinished(cancelled);
                            }
                        });
                    }
                }
            }
        });
    }

    public void stopSweep() {
        cancelSweep = true;
    }

    /**
     * El usuario vio prender la TV: detiene el barrido y devuelve las marcas
     * cuyos códigos se enviaron en los últimos segundos (las candidatas).
     */
    public List<Family> hitCandidates() {
        cancelSweep = true;
        long now = SystemClock.uptimeMillis();
        LinkedHashSet<String> fams = new LinkedHashSet<>();
        List<Integer> idx = new ArrayList<>();
        synchronized (sweepLog) {
            for (int k = sweepLog.size() - 1; k >= 0; k--) {
                long[] e = sweepLog.get(k);
                if (now - e[0] > HIT_WINDOW_MS && idx.size() >= 4) break;
                idx.add(0, (int) e[1]);
            }
        }
        for (int i : idx) {
            String f = sweep.get(i).family;
            if (f != null) fams.add(f);
        }
        List<Family> r = new ArrayList<>();
        for (String id : fams) r.add(byId.get(id));
        if (r.isEmpty()) {
            // La TV respondió a un código de la lista extendida: recordamos esos códigos de encendido.
            learnedPower.clear();
            learnedPower.addAll(idx);
            active.clear();
            persist();
        }
        return r;
    }

    // ------------------------------------------------------------------ bajo nivel

    private void signal(final boolean on) {
        final TxListener l = txListener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                l.onTransmit(on);
            }
        });
    }

    private int fixFreq(int f) {
        if (carrierRanges == null) return f;
        int best = f;
        int bestDist = Integer.MAX_VALUE;
        for (int[] r : carrierRanges) {
            if (f >= r[0] && f <= r[1]) return f;
            int c = f < r[0] ? r[0] : r[1];
            int d = Math.abs(c - f);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    private void transmit(int freq, int[] pattern, long gapMs) {
        if (ir == null) return;
        long durMs = 0;
        for (int v : pattern) durMs += v;
        durMs /= 1000;
        long t0 = SystemClock.uptimeMillis();
        try {
            ir.transmit(fixFreq(freq), pattern);
        } catch (Exception e) {
            Log.w(TAG, "Fallo al transmitir", e);
        }
        long elapsed = SystemClock.uptimeMillis() - t0;
        // Algunos teléfonos regresan antes de terminar de emitir: esperamos lo que falte.
        long wait = Math.max(0, durMs - elapsed) + gapMs;
        SystemClock.sleep(wait);
    }
}
