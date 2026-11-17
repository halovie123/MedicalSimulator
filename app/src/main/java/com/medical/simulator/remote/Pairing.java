package com.medical.simulator.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * The Pi's pairing QR (PPG_simulator_raspi comm/pairing.py) and what the phone remembers about the Pi:
 *
 *   ppgsim://pair?v=1&n=&lt;BLE name&gt;&a=&lt;BLE address&gt;&d=&lt;device&gt;&h=&lt;host&gt;&p=&lt;port&gt;&t=1&u=&lt;user&gt;&k=&lt;pass&gt;&f=&lt;CA sha256&gt;
 *
 * Every field is checked before anything is saved; a code with neither a Bluetooth address nor a complete
 * internet part is refused. The last link that connected ("ble" or "mqtt") is remembered so the app can
 * reconnect by itself when it starts; an explicit Disconnect forgets it.
 */
public final class Pairing {
    private static final String PREFS = "pairing";
    private static final Pattern MAC = Pattern.compile("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$");
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");

    public static final String LINK_BLE = "ble", LINK_MQTT = "mqtt";

    public final String bleName, bleAddress;
    @Nullable public final RemoteSettings internet;

    private Pairing(String bleName, String bleAddress, @Nullable RemoteSettings internet) {
        this.bleName = bleName;
        this.bleAddress = bleAddress;
        this.internet = internet;
    }

    /** A scanned QR text, or an IllegalArgumentException saying what is wrong with it. */
    public static Pairing parse(@NonNull String text) {
        Uri u = Uri.parse(text.trim());
        if (!"ppgsim".equals(u.getScheme()) || !"pair".equals(u.getHost()))
            throw new IllegalArgumentException("not a simulator pairing code");
        if (!"1".equals(u.getQueryParameter("v")))
            throw new IllegalArgumentException("pairing code version not supported");
        String name = nz(u.getQueryParameter("n"));
        String address = nz(u.getQueryParameter("a")).toUpperCase(java.util.Locale.US);
        if (!address.isEmpty() && !MAC.matcher(address).matches())
            throw new IllegalArgumentException("Bluetooth address");
        RemoteSettings internet = null;
        String device = nz(u.getQueryParameter("d"));
        if (!device.isEmpty()) {
            String host = nz(u.getQueryParameter("h"));
            if (!HOST.matcher(host).matches()) throw new IllegalArgumentException("broker host");
            int port;
            try {
                port = Integer.parseInt(nz(u.getQueryParameter("p")));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("broker port");
            }
            String pin = nz(u.getQueryParameter("f")).toLowerCase(java.util.Locale.US);
            if (!pin.isEmpty() && !HEX64.matcher(pin).matches()) throw new IllegalArgumentException("CA fingerprint");
            boolean tls = !"0".equals(u.getQueryParameter("t"));
            internet = new RemoteSettings(host, port, tls, device, nz(u.getQueryParameter("u")),
                    nz(u.getQueryParameter("k")), "", pin);
            String problem = internet.problem();
            if (problem != null) throw new IllegalArgumentException(problem);
        }
        if (address.isEmpty() && internet == null)
            throw new IllegalArgumentException("the code holds no way to connect");
        return new Pairing(name, address, internet);
    }

    private static String nz(@Nullable String s) { return s == null ? "" : s.trim(); }

    /** Name shown on the one-tap "Connect to …" entry. */
    public String label() {
        if (internet != null) return internet.deviceId;
        return bleName.isEmpty() ? bleAddress : bleName;
    }

    public void save(@NonNull Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("ble_name", bleName).putString("ble_address", bleAddress).apply();
        if (internet != null) internet.save(c);
    }

    /** The remembered Pi: its Bluetooth address and/or the saved broker settings; null when nothing is known. */
    @Nullable
    public static Pairing load(@NonNull Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String address = p.getString("ble_address", "");
        RemoteSettings saved = RemoteSettings.load(c);
        RemoteSettings internet = saved.problem() == null && !saved.host.isEmpty() ? saved : null;
        if (address.isEmpty() && internet == null) return null;
        return new Pairing(p.getString("ble_name", ""), address, internet);
    }

    /** Remember a Bluetooth device the user picked in the scan list. */
    public static void rememberBle(@NonNull Context c, String name, String address) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("ble_name", name).putString("ble_address", address).apply();
    }

    public static void setLastLink(@NonNull Context c, @Nullable String link) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_link", link == null ? "" : link).apply();
    }

    @NonNull
    public static String lastLink(@NonNull Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_link", "");
    }
}
