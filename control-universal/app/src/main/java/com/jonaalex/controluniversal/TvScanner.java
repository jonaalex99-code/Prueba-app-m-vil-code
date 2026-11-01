package com.jonaalex.controluniversal;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Busca televisores cercanos por Wi-Fi Direct (sin red WiFi ni router).
 * Las TVs con proyección directa (Miracast) se anuncian como "pantalla" o "receptor WFD".
 */
@SuppressLint("MissingPermission")
public final class TvScanner {

    public static final int KIND_SINK = 0;    // receptor de proyección directa confirmado
    public static final int KIND_DISPLAY = 1; // se anuncia como pantalla / TV
    public static final int KIND_NAME = 2;    // por el nombre parece una TV
    public static final int KIND_OTHER = 3;

    public static class Found {
        public String name;
        public String address;
        public int kind;
    }

    public interface Listener {
        void onFound(List<Found> tvs);

        void onError(String message);
    }

    private static final Pattern TV_NAME = Pattern.compile(
            "(?i).*(\\btv\\b|bravia|samsung|\\[lg\\]|\\blg\\b|webos|tcl|hisense|vidaa|roku|android ?tv|google ?tv|"
                    + "mi ?tv|xiaomi|philips|panasonic|sharp|aquos|toshiba|vizio|insignia|smart|display|"
                    + "cast|mirror|miracast|anyview|screen|sony|jvc|rca|haier|kalley|challenger|caixun|hyundai|"
                    + "skyworth|noblex|bgh|philco|daewoo|sanyo|onn|element|westinghouse|pantalla).*");

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiP2pManager mgr;
    private WifiP2pManager.Channel channel;
    private Listener listener;
    private boolean running;

    public TvScanner(Context c) {
        ctx = c.getApplicationContext();
    }

    // ------------------------------------------------------------------ permisos

    public static String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 33) return new String[]{"android.permission.NEARBY_WIFI_DEVICES"};
        return new String[]{"android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"};
    }

    public static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true; // permisos concedidos al instalar
        // Wi-Fi Direct necesita: Android 13+ «dispositivos cercanos»; antes, ubicación precisa.
        String p = neededPermissions()[0];
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /** Antes de Android 13, buscar dispositivos Wi-Fi Direct exige la ubicación encendida. */
    public static boolean locationOk(Context c) {
        if (Build.VERSION.SDK_INT >= 33) return true;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return true;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    // ------------------------------------------------------------------ búsqueda

    public void start(Listener l) {
        listener = l;
        if (running) return;
        mgr = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (mgr == null) {
            l.onError("Este celular no tiene Wi-Fi Direct");
            return;
        }
        channel = mgr.initialize(ctx, Looper.getMainLooper(), null);
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else ctx.registerReceiver(receiver, f);
        running = true;
        discover();
    }

    public void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(rediscover);
        try {
            ctx.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        try {
            mgr.stopPeerDiscovery(channel, null);
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 27 && channel != null) {
            try {
                channel.close();
            } catch (Exception ignored) {
            }
        }
        channel = null;
    }

    private final Runnable rediscover = new Runnable() {
        @Override
        public void run() {
            if (running) discover();
        }
    };

    private void discover() {
        try {
            mgr.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onFailure(int reason) {
                    if (listener != null && reason == WifiP2pManager.P2P_UNSUPPORTED) {
                        listener.onError("Este celular no permite buscar por Wi-Fi Direct");
                    }
                }
            });
        } catch (SecurityException e) {
            if (listener != null) listener.onError("Falta el permiso de dispositivos cercanos");
        }
        // La búsqueda de Wi-Fi Direct se apaga sola; la renovamos.
        main.removeCallbacks(rediscover);
        main.postDelayed(rediscover, 15000);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!running || mgr == null) return;
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(i.getAction())) {
                try {
                    mgr.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                        @Override
                        public void onPeersAvailable(WifiP2pDeviceList list) {
                            publish(list);
                        }
                    });
                } catch (SecurityException ignored) {
                }
            } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(i.getAction())) {
                int st = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (st == WifiP2pManager.WIFI_P2P_STATE_DISABLED && listener != null) {
                    listener.onError("Enciende el WiFi del celular para buscar televisores");
                }
            }
        }
    };

    private void publish(WifiP2pDeviceList list) {
        List<Found> out = new ArrayList<>();
        for (WifiP2pDevice d : list.getDeviceList()) {
            Found f = new Found();
            f.name = d.deviceName != null && !d.deviceName.isEmpty() ? d.deviceName : d.deviceAddress;
            f.address = d.deviceAddress;
            f.kind = classify(d);
            if (f.kind == KIND_OTHER && isPhoneOrPc(d)) continue;
            out.add(f);
        }
        Collections.sort(out, (a, b) -> a.kind != b.kind ? a.kind - b.kind : a.name.compareToIgnoreCase(b.name));
        if (listener != null) listener.onFound(out);
    }

    private static int classify(WifiP2pDevice d) {
        if (Build.VERSION.SDK_INT >= 30) {
            WifiP2pWfdInfo w = d.getWfdInfo();
            if (w != null && w.isEnabled()) {
                int t = w.getDeviceType();
                if (t == WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK || t == WifiP2pWfdInfo.DEVICE_TYPE_SECONDARY_SINK
                        || t == WifiP2pWfdInfo.DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK) {
                    return KIND_SINK;
                }
            }
        }
        String pdt = d.primaryDeviceType;
        if (pdt != null && pdt.startsWith("7-")) return KIND_DISPLAY;            // categoría "Pantalla"
        if (pdt != null && pdt.startsWith("8-")) return KIND_DISPLAY;            // multimedia (TV box)
        if (d.deviceName != null && TV_NAME.matcher(d.deviceName).matches()) return KIND_NAME;
        return KIND_OTHER;
    }

    private static boolean isPhoneOrPc(WifiP2pDevice d) {
        String pdt = d.primaryDeviceType;
        return pdt != null && (pdt.startsWith("10-") || pdt.startsWith("1-") || pdt.startsWith("3-"));
    }

    public static String kindLabel(int k) {
        switch (k) {
            case KIND_SINK:
                return "Proyección directa compatible · sin red";
            case KIND_DISPLAY:
                return "Pantalla / TV con Wi-Fi Direct";
            case KIND_NAME:
                return "Posible TV";
            default:
                return "Otro dispositivo";
        }
    }
}
