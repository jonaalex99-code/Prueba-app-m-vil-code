package com.jonaalex.controluniversal;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * El celular se presenta ante la TV como un mouse + teclado + control multimedia
 * Bluetooth (perfil HID Device, Android 9+). La TV lo empareja como cualquier
 * accesorio inalámbrico y muestra un cursor real.
 */
@SuppressLint("MissingPermission")
public final class BtHid {
    private static final String TAG = "BtHid";

    public static final int ID_KEYBOARD = 1;
    public static final int ID_MOUSE = 2;
    public static final int ID_CONSUMER = 3;
    public static final int ID_MOUSE_WIDE = 4; // mouse "rápido": X/Y de 16 bits

    // Teclas multimedia (HID Consumer Page)
    public static final int CC_HOME = 0x0223;
    public static final int CC_BACK = 0x0224;
    public static final int CC_VOL_UP = 0x00E9;
    public static final int CC_VOL_DN = 0x00EA;
    public static final int CC_MUTE = 0x00E2;
    public static final int CC_PLAY_PAUSE = 0x00CD;
    public static final int CC_MENU = 0x0040;

    // Teclas del teclado (HID Keyboard Page)
    public static final int K_ENTER = 0x28, K_ESC = 0x29, K_BACKSPACE = 0x2A, K_TAB = 0x2B, K_SPACE = 0x2C;
    public static final int K_RIGHT = 0x4F, K_LEFT = 0x50, K_DOWN = 0x51, K_UP = 0x52;
    private static final int MOD_SHIFT = 0x02;

    /** Mouse relativo + teclado + control multimedia. */
    private static final byte[] DESCRIPTOR = bytes(
            // Teclado (ID 1)
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
            0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            // Mouse (ID 2): 3 botones, X, Y, rueda
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0,
            // Control multimedia (ID 3)
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0,
            // Mouse rápido (ID 4): 3 botones, X e Y de 16 bits (hasta ±32767 por paquete), rueda
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE_WIDE, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
            0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
            0xC0, 0xC0);

    public enum State { UNSUPPORTED, OFF, IDLE, REGISTERING, READY, CONNECTING, CONNECTED }

    public interface Listener {
        void onStateChanged(State state, String deviceName);
    }

    private static BtHid instance;

    public static synchronized BtHid get(Context c) {
        if (instance == null) instance = new BtHid(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler tx;
    private final Executor cbExec = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private final BluetoothAdapter adapter;

    private BluetoothHidDevice hid;
    private BluetoothDevice device;     // TV conectada
    private BluetoothDevice wanted;     // TV a la que queremos conectarnos
    private volatile State state = State.IDLE;
    private boolean registered;

    // Movimiento acumulado (se envía lo más rápido que el enlace permite)
    private final Object moveLock = new Object();
    private float accX, accY;
    private int accWheel;
    private int buttons;
    private boolean moveScheduled;

    private BtHid(Context c) {
        ctx = c;
        HandlerThread t = new HandlerThread("hid-tx");
        t.start();
        tx = new Handler(t.getLooper());
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        if (Build.VERSION.SDK_INT < 28 || adapter == null) state = State.UNSUPPORTED;
    }

    // ------------------------------------------------------------------ estado

    public void addListener(Listener l) {
        listeners.add(l);
        l.onStateChanged(state, deviceName());
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public State state() {
        if (state != State.UNSUPPORTED && adapter != null && !adapter.isEnabled()) return State.OFF;
        return state;
    }

    public boolean isConnected() {
        return state == State.CONNECTED && device != null;
    }

    public boolean isSupported() {
        return state != State.UNSUPPORTED;
    }

    private volatile String cachedName;
    private volatile BluetoothDevice cachedFor;

    public String deviceName() {
        BluetoothDevice d = device != null ? device : wanted;
        if (d == null) return null;
        if (d.equals(cachedFor) && cachedName != null) return cachedName;
        String n;
        try {
            n = d.getName();
        } catch (Exception e) {
            n = null;
        }
        cachedName = n != null ? n : d.getAddress();
        cachedFor = d;
        return cachedName;
    }

    public boolean adapterEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    public static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return c.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }

    public String localName() {
        try {
            return adapter != null ? adapter.getName() : "este celular";
        } catch (SecurityException e) {
            return "este celular";
        }
    }

    private void setState(final State s) {
        state = s;
        final String name = deviceName();
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : new ArrayList<>(listeners)) l.onStateChanged(state(), name);
            }
        });
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("control", Context.MODE_PRIVATE);
    }

    public String savedAddress() {
        return prefs().getString("bt_tv", null);
    }

    /** TVs ya emparejadas (primero las que se identifican como audio/video). */
    public List<BluetoothDevice> pairedDevices() {
        List<BluetoothDevice> tvs = new ArrayList<>();
        List<BluetoothDevice> rest = new ArrayList<>();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                BluetoothClass bc = d.getBluetoothClass();
                if (bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO) tvs.add(d);
                else rest.add(d);
            }
        } catch (Exception ignored) {
        }
        tvs.addAll(rest);
        return tvs;
    }

    // ------------------------------------------------------------------ registro y conexión

    /** Registra el celular como mouse/teclado. Si hay una TV guardada, se reconecta. */
    @TargetApi(28)
    public void start() {
        if (!isSupported() || !hasPermission(ctx) || !adapterEnabled()) {
            setState(state);
            return;
        }
        if (hid != null) {
            if (registered) connectWanted();
            else register();
            return;
        }
        watchBonds();
        String saved = savedAddress();
        if (saved != null && wanted == null) {
            try {
                wanted = adapter.getRemoteDevice(saved);
            } catch (Exception ignored) {
            }
        }
        setState(State.REGISTERING);
        boolean ok = adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                hid = (BluetoothHidDevice) proxy;
                register();
            }

            @Override
            public void onServiceDisconnected(int profile) {
                hid = null;
                registered = false;
                device = null;
                setState(State.IDLE);
            }
        }, BluetoothProfile.HID_DEVICE);
        if (!ok) setState(State.UNSUPPORTED);
    }

    @TargetApi(28)
    private void register() {
        if (hid == null) return;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "Control Universal", "Mouse y teclado", "Control Universal",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
        try {
            if (callback == null) callback = new HidCallback();
            // Sin QoS: el ajuste de calidad de servicio limitaba el ancho de banda y atascaba el enlace.
            boolean ok = hid.registerApp(sdp, null, null, cbExec, (BluetoothHidDevice.Callback) callback);
            if (!ok) {
                Log.w(TAG, "registerApp devolvió false");
                setState(State.UNSUPPORTED);
            }
        } catch (Exception e) {
            Log.w(TAG, "registerApp", e);
            setState(State.UNSUPPORTED);
        }
    }

    private Object callback; // BluetoothHidDevice.Callback (solo existe en Android 9+)

    @TargetApi(28)
    private class HidCallback extends BluetoothHidDevice.Callback {
        @Override
        public void onAppStatusChanged(BluetoothDevice plugged, boolean reg) {
            registered = reg;
            if (!reg) {
                device = null;
                setState(State.IDLE);
                return;
            }
            setState(State.READY);
            if (wanted == null && plugged != null) wanted = plugged;
            connectWanted();
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice d, int s) {
            if (s == BluetoothProfile.STATE_CONNECTED) {
                device = d;
                wanted = d;
                prefs().edit().putString("bt_tv", d.getAddress()).apply();
                updateWide();
                setState(State.CONNECTED);
            } else if (s == BluetoothProfile.STATE_CONNECTING) {
                wanted = d;
                setState(State.CONNECTING);
            } else if (s == BluetoothProfile.STATE_DISCONNECTED) {
                if (d.equals(device)) device = null;
                setState(registered ? State.READY : State.IDLE);
            }
        }

        @Override
        public void onGetReport(BluetoothDevice d, byte type, byte id, int bufferSize) {
            if (hid == null) return;
            int len = id == ID_KEYBOARD ? 8 : id == ID_MOUSE ? 4 : id == ID_MOUSE_WIDE ? 6 : 2;
            hid.replyReport(d, type, id, new byte[len]);
        }

        @Override
        public void onSetReport(BluetoothDevice d, byte type, byte id, byte[] data) {
            if (hid != null) hid.reportError(d, BluetoothHidDevice.ERROR_RSP_SUCCESS);
        }
    }

    @TargetApi(28)
    private void connectWanted() {
        if (hid == null || wanted == null || isConnected()) return;
        try {
            setState(State.CONNECTING);
            if (!hid.connect(wanted)) setState(State.READY);
        } catch (Exception e) {
            setState(State.READY);
        }
    }

    /** Conectarse a una TV ya emparejada. */
    public void connect(BluetoothDevice d) {
        wanted = d;
        prefs().edit().putString("bt_tv", d.getAddress()).apply();
        if (hid == null || !registered) start();
        else connectWanted();
    }

    @TargetApi(28)
    public void disconnect() {
        if (hid != null && device != null) {
            try {
                hid.disconnect(device);
            } catch (Exception ignored) {
            }
        }
        prefs().edit().remove("bt_tv").apply();
        wanted = null;
    }

    @TargetApi(28)
    public void shutdown() {
        if (hid != null) {
            try {
                hid.unregisterApp();
            } catch (Exception ignored) {
            }
            try {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            } catch (Exception ignored) {
            }
        }
        hid = null;
        registered = false;
        device = null;
        state = isSupported() ? State.IDLE : State.UNSUPPORTED;
    }

    // ------------------------------------------------------------------ envío

    @TargetApi(28)
    private boolean report(int id, byte[] data) {
        BluetoothHidDevice h = hid;
        BluetoothDevice d = device;
        if (h == null || d == null) return true; // sin conexión: no reintentar
        try {
            return h.sendReport(d, id, data);
        } catch (Exception e) {
            Log.w(TAG, "sendReport", e);
            return true;
        }
    }

    /** Movimiento relativo del cursor (en "cuentas" HID; se acumulan las fracciones). */
    public void move(float dx, float dy) {
        if (!isConnected()) return;
        synchronized (moveLock) {
            // Si el enlace va atrasado, no acumulamos movimiento sin límite (evita que se "congele").
            accX = Math.max(-MAX_BACKLOG, Math.min(MAX_BACKLOG, accX + dx));
            accY = Math.max(-MAX_BACKLOG, Math.min(MAX_BACKLOG, accY + dy));
            scheduleMove();
        }
    }

    public void wheel(int ticks) {
        if (!isConnected() || ticks == 0) return;
        synchronized (moveLock) {
            accWheel += ticks;
            scheduleMove();
        }
    }

    /** Presiona (true) o suelta (false) un botón: 1 = izquierdo, 2 = derecho, 4 = central. */
    public void button(int mask, boolean down) {
        if (!isConnected()) return;
        synchronized (moveLock) {
            buttons = down ? (buttons | mask) : (buttons & ~mask);
            scheduleMove();
        }
    }

    public void click(final int mask) {
        if (!isConnected()) return;
        button(mask, true);
        tx.postDelayed(new Runnable() {
            @Override
            public void run() {
                button(mask, false);
            }
        }, 30);
    }

    /** Intervalo entre reportes del mouse: ~100 por segundo, con el movimiento acumulado. */
    private volatile long mouseIntervalMs = 10;
    private volatile int maxPerReport = 127;
    private long lastMouseReport;
    private int failStreak;                  // fallos seguidos del enlace
    private static final float MAX_BACKLOG = 3000f;
    private final java.util.concurrent.atomic.AtomicInteger statSent = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger statFailed = new java.util.concurrent.atomic.AtomicInteger();

    /** Calibración: tiempo entre paquetes del mouse (ms) y movimiento máximo por paquete (1–127). */
    public void setMouseTuning(long intervalMs, int maxPer) {
        mouseIntervalMs = Math.max(2, Math.min(100, intervalMs));
        maxPerReport = Math.max(1, Math.min(127, maxPer));
    }

    // ------------------------------------------------------------------ modo rápido (16 bits)

    private volatile boolean wide;
    private volatile boolean forceWide;
    private boolean bondReceiverOn;

    /** true si la TV conectada usa el mouse rápido de 16 bits. */
    public boolean isWide() {
        return wide;
    }

    public boolean isForceWide() {
        return forceWide;
    }

    /** Fuerza el modo rápido aunque la TV se haya emparejado con una versión anterior. */
    public void setForceWide(boolean on) {
        forceWide = on;
        prefs().edit().putBoolean("bt_force_wide2", on).apply();
        updateWide();
    }

    private void updateWide() {
        BluetoothDevice d = device;
        wide = forceWide || (d != null && prefs().getStringSet("bt_wide", java.util.Collections.<String>emptySet())
                .contains(d.getAddress()));
    }

    /**
     * Las TVs guardan la descripción del mouse al emparejarse. Las que se emparejen desde ahora
     * ya conocen el mouse rápido; las anteriores siguen con el normal hasta volver a emparejar.
     */
    private void watchBonds() {
        if (bondReceiverOn) return;
        bondReceiverOn = true;
        forceWide = prefs().getBoolean("bt_force_wide2", false);
        android.content.IntentFilter f = new android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                if (d == null) return;
                java.util.Set<String> set = new java.util.HashSet<>(
                        prefs().getStringSet("bt_wide", java.util.Collections.<String>emptySet()));
                if (st == BluetoothDevice.BOND_BONDED) set.add(d.getAddress());
                else if (st == BluetoothDevice.BOND_NONE) set.remove(d.getAddress());
                else return;
                prefs().edit().putStringSet("bt_wide", set).apply();
                updateWide();
            }
        };
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(r, f);
    }

    /** Estadísticas desde la última llamada: {enviados, fallidos, movimiento pendiente}. */
    public int[] takeStats() {
        int pending;
        synchronized (moveLock) {
            pending = (int) (Math.abs(accX) + Math.abs(accY));
        }
        return new int[]{statSent.getAndSet(0), statFailed.getAndSet(0), pending};
    }

    private void scheduleMove() {
        if (moveScheduled) return;
        moveScheduled = true;
        // El primer reporte sale de inmediato; los siguientes a ritmo constante, juntando
        // todo el movimiento del intervalo en un solo reporte (más grande y sin saturar el enlace).
        long wait = Math.max(0, mouseIntervalMs - (SystemClock.uptimeMillis() - lastMouseReport));
        tx.postDelayed(flushMove, wait);
    }

    private final Runnable flushMove = new Runnable() {
        @Override
        public void run() {
            final boolean w16 = wide;
            byte[] r = new byte[w16 ? 6 : 4];
            int x, y, w;
            synchronized (moveLock) {
                int m = w16 ? Math.min(32000, maxPerReport * 16) : maxPerReport;
                x = Math.max(-m, Math.min(m, Math.round(accX)));
                y = Math.max(-m, Math.min(m, Math.round(accY)));
                w = clampWheel(accWheel);
                accX -= x;
                accY -= y;
                accWheel -= w;
                r[0] = (byte) buttons;
                if (w16) {
                    r[1] = (byte) x;
                    r[2] = (byte) (x >> 8);
                    r[3] = (byte) y;
                    r[4] = (byte) (y >> 8);
                    r[5] = (byte) w;
                } else {
                    r[1] = (byte) x;
                    r[2] = (byte) y;
                    r[3] = (byte) w;
                }
            }
            boolean ok = report(w16 ? ID_MOUSE_WIDE : ID_MOUSE, r);
            lastMouseReport = SystemClock.uptimeMillis();
            (ok ? statSent : statFailed).incrementAndGet();
            boolean again, big;
            synchronized (moveLock) {
                if (!ok) {
                    failStreak++;
                    if (failStreak <= 20) {
                        // El enlace estaba ocupado: devolvemos el movimiento para no perderlo.
                        accX += x;
                        accY += y;
                        accWheel += w;
                    } else {
                        // Demasiados fallos seguidos: descartamos lo pendiente para no trabar todo.
                        accX = accY = 0;
                        accWheel = 0;
                    }
                } else {
                    failStreak = 0;
                }
                again = Math.abs(accX) >= 0.5f || Math.abs(accY) >= 0.5f || accWheel != 0;
                int lim = w16 ? maxPerReport * 16 : maxPerReport;
                big = Math.abs(accX) > lim || Math.abs(accY) > lim; // deslizamiento rápido pendiente
                moveScheduled = again;
            }
            if (again) {
                // Reintento con espera creciente si el enlace está ocupado (no saturarlo).
                long delay = ok ? (big ? Math.min(4, mouseIntervalMs) : mouseIntervalMs)
                        : Math.min(120, 8L << Math.min(4, failStreak));
                tx.postDelayed(this, delay);
            }
        }
    };

    private static int clamp(int v) {
        return Math.max(-127, Math.min(127, v));
    }

    private static int clampWheel(int v) {
        return Math.max(-3, Math.min(3, v));
    }

    /** Pulsa una tecla multimedia (Inicio, Atrás, volumen…). */
    public void consumer(final int usage) {
        if (!isConnected()) return;
        tx.post(new Runnable() {
            @Override
            public void run() {
                report(ID_CONSUMER, new byte[]{(byte) usage, (byte) (usage >> 8)});
                SystemClock.sleep(25);
                report(ID_CONSUMER, new byte[]{0, 0});
            }
        });
    }

    /** Pulsa una tecla del teclado (código HID) con modificador opcional. */
    public void key(final int code, final int mods) {
        if (!isConnected()) return;
        tx.post(new Runnable() {
            @Override
            public void run() {
                pressKey(code, mods);
            }
        });
    }

    private void pressKey(int code, int mods) {
        report(ID_KEYBOARD, new byte[]{(byte) mods, 0, (byte) code, 0, 0, 0, 0, 0});
        SystemClock.sleep(12);
        report(ID_KEYBOARD, new byte[8]);
        SystemClock.sleep(12);
    }

    /** Escribe texto como si se tecleara (distribución de teclado de EE. UU.). */
    public void type(final String text) {
        if (!isConnected() || text == null || text.isEmpty()) return;
        final String clean = simplify(text);
        tx.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < clean.length(); i++) {
                    int[] k = charToKey(clean.charAt(i));
                    if (k != null) pressKey(k[0], k[1]);
                }
            }
        });
    }

    public void backspace(final int count) {
        if (!isConnected() || count <= 0) return;
        tx.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < count; i++) pressKey(K_BACKSPACE, 0);
            }
        });
    }

    /** Quita acentos (á→a, ñ→n) porque la TV usa distribución de teclado inglesa. */
    private static String simplify(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('¿', '?').replace('¡', '!').replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'').replace('–', '-').replace('—', '-');
    }

    private static final String SHIFT_NUM = ")!@#$%^&*(";
    private static final String PLAIN_SYM = "-=[]\\;'`,./";
    private static final String SHIFT_SYM = "_+{}|:\"~<>?";
    private static final int[] SYM_CODE = {0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};

    static int[] charToKey(char c) {
        if (c >= 'a' && c <= 'z') return new int[]{0x04 + (c - 'a'), 0};
        if (c >= 'A' && c <= 'Z') return new int[]{0x04 + (c - 'A'), MOD_SHIFT};
        if (c >= '1' && c <= '9') return new int[]{0x1E + (c - '1'), 0};
        if (c == '0') return new int[]{0x27, 0};
        if (c == ' ') return new int[]{K_SPACE, 0};
        if (c == '\n') return new int[]{K_ENTER, 0};
        if (c == '\t') return new int[]{K_TAB, 0};
        int i = SHIFT_NUM.indexOf(c);
        if (i >= 0) return new int[]{i == 0 ? 0x27 : 0x1E + i - 1, MOD_SHIFT};
        i = PLAIN_SYM.indexOf(c);
        if (i >= 0) return new int[]{SYM_CODE[i], 0};
        i = SHIFT_SYM.indexOf(c);
        if (i >= 0) return new int[]{SYM_CODE[i], MOD_SHIFT};
        return null;
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }
}
