package com.jonaalex.controluniversal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Estado del flujo "Proyectar con un toque".
 *
 * La pantalla abre el menú de Transmitir del celular y {@link CastService}
 * (servicio de accesibilidad) toca el nombre de la TV guardada. Si la TV no aparece
 * porque está apagada, el servicio la enciende por infrarrojo y sigue esperando.
 */
public final class CastFlow {

    public static final String METHOD_SETTINGS = "settings"; // Ajustes › Transmitir / Pantalla inalámbrica
    public static final String METHOD_PANEL = "panel";       // Botón "Transmitir" del panel rápido
    public static final String METHOD_HOME = "home";         // Google Home (Chromecast / Google TV)

    static final String HOME_PKG = "com.google.android.apps.chromecast.app";
    private static final String SMART_VIEW_PKG = "com.samsung.android.smartmirroring";

    static final long TIMEOUT_MS = 45000;      // tiempo máximo esperando la TV
    static final long POWER_AFTER_MS = 6000;   // si la TV no aparece en este tiempo, se enciende por IR
    static final long PANEL_TIMEOUT_MS = 5000; // tiempo para encontrar el botón del panel rápido

    /** Textos del botón del panel rápido en distintas marcas / idiomas. */
    static final String[] PANEL_LABELS = {
            "Transmitir", "Transmisión", "Proyectar", "Proyección", "Duplicar pantalla", "Duplicación de pantalla",
            "Pantalla inalámbrica", "Proyección inalámbrica", "Smart View", "Enviar pantalla", "Compartir pantalla",
            "Espejo de pantalla", "Screen cast", "Screencast", "Screen mirroring", "Wireless display",
            "Wireless projection", "Mirror"
    };
    /** Etiquetas cortas que solo se aceptan si coinciden exactamente. */
    static final String[] PANEL_EXACT = {"Cast", "Enviar"};

    /** Página de "Pantalla inalámbrica" de Android: hay que habilitarla en el menú ⋮ para que busque. */
    static final String[] WD_PAGE_HINTS = {
            "pantallas inalámbricas", "pantalla inalámbrica", "wireless display", "wireless displays",
            "para ver los dispositivos", "to see devices"
    };
    static final String[] WD_OVERFLOW = {"Más opciones", "More options", "Opciones"};
    static final String[] WD_ENABLE = {
            "Habilitar pantalla inalámbrica", "Activar pantalla inalámbrica", "Habilitar pantalla",
            "Enable wireless display", "Habilitar proyección inalámbrica"
    };

    /** Pasos extra en Google Home después de tocar la TV. */
    static final String[][] HOME_STEPS = {
            {"Transmitir pantalla", "Transmitir mi pantalla", "Enviar pantalla", "Cast my screen", "Cast screen"},
            {"Iniciar ahora", "Empezar ahora", "Comenzar ahora", "Start now"},
    };

    static volatile boolean active;
    static volatile long startedAt;
    static volatile int step;
    static volatile boolean powerSent;
    static volatile boolean panelPending;
    static volatile String method = METHOD_SETTINGS;

    private CastFlow() {
    }

    // ------------------------------------------------------------------ preferencias

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("control", Context.MODE_PRIVATE);
    }

    public static String tvName(Context c) {
        return prefs(c).getString("tv_name", null);
    }

    public static void setTvName(Context c, String name) {
        prefs(c).edit().putString("tv_name", name).apply();
    }

    public static String method(Context c) {
        String def = "xiaomi".equalsIgnoreCase(Build.MANUFACTURER) || "redmi".equalsIgnoreCase(Build.BRAND)
                || "poco".equalsIgnoreCase(Build.BRAND) ? METHOD_PANEL : METHOD_SETTINGS;
        return prefs(c).getString("cast_method", def);
    }

    public static void setMethod(Context c, String m) {
        prefs(c).edit().putString("cast_method", m).apply();
    }

    public static String methodLabel(String m) {
        if (METHOD_PANEL.equals(m)) return "Botón «Transmitir» del panel rápido (directo, sin WiFi)";
        if (METHOD_HOME.equals(m)) return "Google Home (Chromecast: requiere la misma red WiFi)";
        return "Ajustes de Transmitir del celular (directo, sin WiFi)";
    }

    public static boolean powerIfMissing(Context c) {
        return prefs(c).getBoolean("power_if_missing", true);
    }

    public static void setPowerIfMissing(Context c, boolean v) {
        prefs(c).edit().putBoolean("power_if_missing", v).apply();
    }

    public static boolean serviceEnabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(list)) return false;
        ComponentName me = new ComponentName(c, CastService.class);
        for (String s : list.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ flujo

    /**
     * Inicia la proyección. Devuelve false si no se encontró cómo abrir el menú de Transmitir.
     * @param auto true si el servicio de accesibilidad hará los toques automáticos.
     */
    public static boolean start(Context c, boolean auto) {
        return start(c, auto, null);
    }

    /** Proyección directa (Miracast): nunca usa Google Home, que requiere la misma red. */
    public static boolean startDirect(Context c) {
        String m = method(c);
        if (METHOD_HOME.equals(m)) {
            boolean xiaomi = "xiaomi".equalsIgnoreCase(Build.MANUFACTURER);
            m = xiaomi ? METHOD_PANEL : METHOD_SETTINGS;
        }
        return start(c, true, m);
    }

    public static boolean start(Context c, boolean auto, String forced) {
        String m = forced != null ? forced : method(c);
        Intent i = null;
        boolean usePanel = false;
        if (METHOD_HOME.equals(m)) {
            i = c.getPackageManager().getLaunchIntentForPackage(HOME_PKG);
            if (i == null) {
                i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + HOME_PKG));
                auto = false;
            }
        } else if (METHOD_PANEL.equals(m) && auto) {
            usePanel = true;
        } else {
            i = settingsIntent(c);
            if (i == null && auto) usePanel = true;
        }

        if (auto) {
            method = m;
            step = 0;
            powerSent = false;
            panelPending = usePanel;
            startedAt = SystemClock.uptimeMillis();
            active = true;
        }
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(i);
            } catch (Exception e) {
                if (!auto) return false;
                panelPending = true;
            }
        } else if (!usePanel) {
            return false;
        }
        if (auto) CastService.kick();
        return true;
    }

    static Intent settingsIntent(Context c) {
        String[] actions = {Settings.ACTION_CAST_SETTINGS, "android.settings.WIFI_DISPLAY_SETTINGS"};
        for (String a : actions) {
            Intent i = new Intent(a);
            if (i.resolveActivity(c.getPackageManager()) != null) return i;
        }
        Intent sv = c.getPackageManager().getLaunchIntentForPackage(SMART_VIEW_PKG);
        if (sv != null) return sv;
        return null;
    }

    static void stop() {
        active = false;
        panelPending = false;
    }

    static int totalSteps() {
        return METHOD_HOME.equals(method) ? 1 + HOME_STEPS.length : 1;
    }
}
