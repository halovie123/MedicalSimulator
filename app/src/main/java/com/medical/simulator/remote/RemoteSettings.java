package com.medical.simulator.remote;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

/**
 * Broker settings of the internet link, kept in the app's private preferences (MODE_PRIVATE: other apps
 * cannot read them; a rooted phone or an adb backup could, which is why allowBackup is off).
 */
public final class RemoteSettings {
    private static final String PREFS = "remote_mqtt";
    private static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final Pattern PIN = Pattern.compile("^[0-9a-f]{64}$");

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");

    /**
     * caPem: a private CA imported from a file; caPin: SHA-256 of a private CA from the pairing QR.
     * host2/port2: an optional second broker (same account) tried when the first cannot be reached, e.g. the
     * Pi's own broker over Tailscale first and a cloud broker second. port2 is 0 when there is none.
     */
    public final String host, deviceId, username, password, caPem, caPin, host2;
    public final int port, port2;
    public final boolean tls;

    public RemoteSettings(String host, int port, boolean tls, String deviceId, String username, String password,
                          String caPem) {
        this(host, port, tls, deviceId, username, password, caPem, "");
    }

    public RemoteSettings(String host, int port, boolean tls, String deviceId, String username, String password,
                          String caPem, String caPin) {
        this(host, port, tls, deviceId, username, password, caPem, caPin, "", 0);
    }

    public RemoteSettings(String host, int port, boolean tls, String deviceId, String username, String password,
                          String caPem, String caPin, String host2, int port2) {
        this.host = host.trim();
        this.host2 = host2 == null ? "" : host2.trim();
        this.port2 = this.host2.isEmpty() ? 0 : port2;
        this.port = port;
        this.tls = tls;
        this.deviceId = deviceId.trim();
        this.username = username.trim();
        this.password = password;
        this.caPem = caPem == null ? "" : caPem;
        this.caPin = caPin == null ? "" : caPin;
    }

    /** Why these settings cannot be used, or null when they can. */
    public String problem() {
        if (!HOST.matcher(host).matches()) return "Broker host";
        if (port < 1 || port > 65535) return "Port";
        if (!host2.isEmpty() && (!HOST.matcher(host2).matches() || port2 < 1 || port2 > 65535))
            return "Second broker";
        if (!DEVICE_ID.matcher(deviceId).matches()) return "Pi device id (letters, digits, - _)";
        if (!caPem.isEmpty() && !caPem.contains("BEGIN CERTIFICATE")) return "CA certificate";
        if (!caPin.isEmpty() && !PIN.matcher(caPin).matches()) return "CA fingerprint";
        return null;
    }

    public static RemoteSettings load(@NonNull Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new RemoteSettings(p.getString("host", ""), p.getInt("port", 8883), p.getBoolean("tls", true),
                p.getString("device", "pi4-lab1"), p.getString("user", ""), p.getString("pass", ""),
                p.getString("ca", ""), p.getString("ca_pin", ""), p.getString("host2", ""), p.getInt("port2", 0));
    }

    /** The broker URIs in the order they are tried. */
    public String[] serverUris() {
        String scheme = tls ? "ssl://" : "tcp://";
        if (host2.isEmpty()) return new String[]{scheme + host + ":" + port};
        return new String[]{scheme + host + ":" + port, scheme + host2 + ":" + port2};
    }

    public void save(@NonNull Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("host", host).putInt("port", port).putBoolean("tls", tls)
                .putString("device", deviceId).putString("user", username).putString("pass", password)
                .putString("ca", caPem).putString("ca_pin", caPin)
                .putString("host2", host2).putInt("port2", port2).apply();
    }
}
