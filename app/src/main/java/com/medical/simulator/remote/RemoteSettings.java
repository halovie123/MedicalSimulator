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

    public final String host, deviceId, username, password, caPem;
    public final int port;
    public final boolean tls;

    public RemoteSettings(String host, int port, boolean tls, String deviceId, String username, String password,
                          String caPem) {
        this.host = host.trim();
        this.port = port;
        this.tls = tls;
        this.deviceId = deviceId.trim();
        this.username = username.trim();
        this.password = password;
        this.caPem = caPem == null ? "" : caPem;
    }

    /** Why these settings cannot be used, or null when they can. */
    public String problem() {
        if (host.isEmpty() || host.contains("/") || host.contains(" ")) return "Broker host";
        if (port < 1 || port > 65535) return "Port";
        if (!DEVICE_ID.matcher(deviceId).matches()) return "Pi device id (letters, digits, - _)";
        if (!caPem.isEmpty() && !caPem.contains("BEGIN CERTIFICATE")) return "CA certificate";
        return null;
    }

    public static RemoteSettings load(@NonNull Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new RemoteSettings(p.getString("host", ""), p.getInt("port", 8883), p.getBoolean("tls", true),
                p.getString("device", "pi4-lab1"), p.getString("user", ""), p.getString("pass", ""),
                p.getString("ca", ""));
    }

    public void save(@NonNull Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("host", host).putInt("port", port).putBoolean("tls", tls)
                .putString("device", deviceId).putString("user", username).putString("pass", password)
                .putString("ca", caPem).apply();
    }
}
