package com.medical.simulator.remote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.medical.simulator.ble.RpiBleManager;
import com.medical.simulator.ble.RpiBleManager.ConnectionState;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * The Pi over the internet: the same BLE v2/v3 payloads carried by an MQTT broker (Pi side:
 * PPG_simulator_raspi comm/mqtt_remote.py, docs/remote_mqtt.md). Topics ppgsim/&lt;device&gt;/…:
 * cmd (out, QoS 1), client (out, presence ping), status (in, retained), online (in, retained "1"/"0"),
 * stream and ui (in, QoS 0).
 *
 * The broker certificate is checked against the system CAs, or only against the CA the user imported
 * (a private trial CA). Callbacks arrive on Paho's thread and are handed to the main thread.
 */
public class MqttLink {
    private static final String TAG = "MqttLink";
    private static final long PING_MS = 5000;           // the Pi stops streaming 15 s after the last ping
    private static final int KEEPALIVE_S = 30;
    private static final int TIMEOUT_S = 10;

    public interface Listener extends RpiBleManager.RemoteListener {
        void onStatus(@NonNull RpiBleManager.StatusPacket packet);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<ConnectionState> state = new MutableLiveData<>(ConnectionState.DISCONNECTED);
    private final MutableLiveData<Boolean> piOnline = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();
    @Nullable private volatile Listener listener;
    @Nullable private MqttAsyncClient client;
    private RemoteSettings settings;
    private String base = "";
    private boolean wanted;

    private final Runnable ping = new Runnable() {
        @Override public void run() {
            publish(base + "/client", "1", 0);
            main.postDelayed(this, PING_MS);
        }
    };

    public void setListener(@Nullable Listener l) { listener = l; }
    public LiveData<ConnectionState> getState() { return state; }
    /** The Pi's retained "online" flag: false while the broker is reachable but the Pi is not. */
    public LiveData<Boolean> getPiOnline() { return piOnline; }
    public LiveData<String> getErrorEvent() { return error; }
    public String deviceId() { return settings == null ? "" : settings.deviceId; }

    public boolean isConnected() {
        MqttAsyncClient c = client;
        return c != null && c.isConnected();
    }

    public void connect(@NonNull RemoteSettings s) {
        disconnect();
        settings = s;
        base = "ppgsim/" + s.deviceId;
        wanted = true;
        state.setValue(ConnectionState.CONNECTING);
        try {
            String uri = (s.tls ? "ssl://" : "tcp://") + s.host + ":" + s.port;
            MqttAsyncClient c = new MqttAsyncClient(uri, "ppgsim-app-" + UUID.randomUUID().toString().substring(0, 8),
                    new MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setCleanSession(true);
            o.setAutomaticReconnect(true);
            o.setKeepAliveInterval(KEEPALIVE_S);
            o.setConnectionTimeout(TIMEOUT_S);
            if (!s.username.isEmpty()) o.setUserName(s.username);
            if (!s.password.isEmpty()) o.setPassword(s.password.toCharArray());
            if (s.tls && !s.caPem.isEmpty()) o.setSocketFactory(socketFactoryFor(s.caPem));
            c.setCallback(callback);
            client = c;
            c.connect(o, null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken t) { /* connectComplete() subscribes */ }
                @Override public void onFailure(IMqttToken t, Throwable e) {
                    fail("MQTT: " + reason(e));
                }
            });
        } catch (Exception e) {
            fail("MQTT: " + reason(e));
        }
    }

    public void disconnect() {
        wanted = false;
        main.removeCallbacks(ping);
        MqttAsyncClient c = client;
        client = null;
        if (c != null) {
            try {
                if (c.isConnected()) c.disconnect(1000).waitForCompletion(1500);
            } catch (MqttException e) {
                Log.w(TAG, "disconnect: " + e.getMessage());
            }
            try {
                c.close();
            } catch (MqttException ignored) {
                // already closed
            }
        }
        piOnline.postValue(false);
        state.postValue(ConnectionState.DISCONNECTED);
    }

    /** A command JSON for the Pi; a command id lets the Pi drop a redelivered copy. */
    public void sendCommand(@NonNull JSONObject command) {
        try {
            command.put("cid", UUID.randomUUID().toString().substring(0, 12));
        } catch (Exception ignored) {
            // literal key
        }
        publish(base + "/cmd", command.toString(), 1);
    }

    private void publish(String topic, String payload, int qos) {
        MqttAsyncClient c = client;
        if (c == null || !c.isConnected()) return;
        try {
            c.publish(topic, payload.getBytes(StandardCharsets.UTF_8), qos, false);
        } catch (MqttException e) {
            Log.w(TAG, "publish " + topic + ": " + e.getMessage());
        }
    }

    private final MqttCallbackExtended callback = new MqttCallbackExtended() {
        @Override public void connectComplete(boolean reconnect, String serverURI) {
            MqttAsyncClient c = client;
            if (c == null) return;
            try {
                c.subscribe(new String[]{base + "/status", base + "/online", base + "/stream", base + "/ui"},
                        new int[]{1, 1, 0, 0});
            } catch (MqttException e) {
                fail("MQTT subscribe: " + reason(e));
                return;
            }
            state.postValue(ConnectionState.CONNECTED);
            main.removeCallbacks(ping);
            main.post(ping);
        }

        @Override public void connectionLost(Throwable cause) {
            main.removeCallbacks(ping);
            if (wanted) state.postValue(ConnectionState.CONNECTING);   // Paho reconnects on its own
        }

        @Override public void messageArrived(String topic, MqttMessage message) {
            Listener l = listener;
            if (l == null) return;
            byte[] raw = message.getPayload();
            String suffix = topic.substring(topic.lastIndexOf('/') + 1);
            try {
                switch (suffix) {
                    case "stream": {
                        String frame = new String(raw, StandardCharsets.US_ASCII);
                        main.post(() -> l.onStreamFrame(frame));
                        break;
                    }
                    case "ui": {
                        JSONObject part = new JSONObject(new String(raw, StandardCharsets.UTF_8));
                        main.post(() -> l.onUiPart(part));
                        break;
                    }
                    case "status": {
                        RpiBleManager.StatusPacket p = new RpiBleManager.StatusPacket(
                                new JSONObject(new String(raw, StandardCharsets.UTF_8)));
                        main.post(() -> l.onStatus(p));
                        break;
                    }
                    case "online":
                        piOnline.postValue("1".equals(new String(raw, StandardCharsets.US_ASCII).trim()));
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                Log.w(TAG, "ignored " + suffix + " (" + raw.length + " bytes): " + e.getMessage());
            }
        }

        @Override public void deliveryComplete(IMqttDeliveryToken token) { }
    };

    private void fail(String message) {
        Log.w(TAG, message);
        error.postValue(message);
        disconnect();
    }

    private static String reason(Throwable e) {
        if (e == null) return "unknown error";
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String m = cause.getMessage();
        return m != null ? m : cause.getClass().getSimpleName();
    }

    /** TLS that trusts only the given CA (PEM): the trial broker's private CA. */
    private static SSLSocketFactory socketFactoryFor(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate ca = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("broker-ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx.getSocketFactory();
    }
}
