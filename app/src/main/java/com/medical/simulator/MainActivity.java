package com.medical.simulator;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.medical.simulator.ble.RpiBleManager;
import com.medical.simulator.pages.CalibrationPage;
import com.medical.simulator.pages.MonitorPage;
import com.medical.simulator.pages.NeuralPage;
import com.medical.simulator.pages.Page;
import com.medical.simulator.pages.RecordingsPage;
import com.medical.simulator.pages.SignalSetupDialog;
import com.medical.simulator.remote.MqttLink;
import com.medical.simulator.remote.RemoteDialog;
import com.medical.simulator.remote.RemoteSettings;
import com.medical.simulator.remote.Streams;
import com.medical.simulator.ui.Texts;
import com.medical.simulator.ui.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remote control for the Raspberry Pi PPG simulator, laid out like the Pi app: a navigation rail with the same
 * four pages (Monitor, Calibration, Recordings, PPG morphology) and the Signal setup, the page area, and the
 * footer with the output mode and engine counters. Every action goes to the Pi over BLE, or over the internet
 * through an MQTT broker (remote/MqttLink.java, same payloads), and every value shown comes back from it; the
 * phone generates no signal and runs no model.
 */
public class MainActivity extends AppCompatActivity implements Page.Host, MqttLink.Listener {

    private static final long TICK_MS = 50;              // plot redraw, 20 fps
    private static final long THROTTLE_MS = 200;         // slider commands, at most 5 per second per group
    private static final long FOLLOW_PI_AFTER_MS = 2500; // the phone follows a page change made on the Pi

    private static final int CA_MAX_BYTES = 64 * 1024;

    /** The link commands go out on: the one the user connected last. */
    private enum Link { BLE, MQTT }

    private RpiBleManager ble;
    private final MqttLink mqtt = new MqttLink();
    private RemoteDialog remoteDialog;
    private Link link = Link.BLE;
    private boolean piOnline;
    private final Streams streams = new Streams();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, JSONObject> pending = new HashMap<>();
    private final List<Page> pages = new ArrayList<>();
    private final Map<String, ImageView> railItems = new HashMap<>();
    private Page current;
    private String piView = "";
    private long localNavAt;
    private SignalSetupDialog setup;
    private RpiBleManager.ConnectionState state = RpiBleManager.ConnectionState.DISCONNECTED;
    private String deviceName = "";

    private FrameLayout pageHost;
    private ImageView btIcon;
    private TextView connText, modeChip, counters, version, clock;
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), results -> {
                for (boolean granted : results.values()) {
                    if (!granted) {
                        Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                openScan();
            });

    private final ActivityResultLauncher<String[]> caPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onCaPicked);

    private final ActivityResultLauncher<Intent> enableBluetooth =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> openScan());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (current != null) current.tick();
            clock.setText(clockFormat.format(new Date()));
            handler.postDelayed(this, TICK_MS);
        }
    };

    // ─── lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildShell());
        enterFullscreen();
        pages.add(new MonitorPage(this, this));
        pages.add(new CalibrationPage(this, this));
        pages.add(new RecordingsPage(this, this));
        pages.add(new NeuralPage(this, this));
        setup = new SignalSetupDialog(this, this);
        showPage(pages.get(0), false);

        ble = new RpiBleManager(getApplicationContext());
        ble.setRemoteListener(this);
        ble.getState().observe(this, s -> onLinkState(Link.BLE, s));
        ble.getStatusPacket().observe(this, this::onStatus);
        ble.getErrorEvent().observe(this, msg -> {
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
        remoteDialog = new RemoteDialog(this, () -> caPicker.launch(new String[]{"*/*"}));
        mqtt.setListener(this);
        mqtt.getState().observe(this, s -> onLinkState(Link.MQTT, s));
        mqtt.getPiOnline().observe(this, online -> {
            boolean was = piOnline;
            piOnline = online != null && online;
            if (link == Link.MQTT && piOnline && !was && connected()) {
                piView = "";
                send(obj("view", current.view()));   // the Pi came online: show it the phone's page
            }
            paintConnection();
        });
        mqtt.getErrorEvent().observe(this, msg -> {
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
        paintConnection();
        handler.post(tick);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterFullscreen();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        mqtt.setListener(null);
        mqtt.disconnect();
        if (ble != null) {
            ble.setRemoteListener(null);
            ble.disconnectAndClose();
            ble.close();
        }
        super.onDestroy();
    }

    private void enterFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                ctrl.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    // ─── shell: rail, page area, footer (the Pi's ui/ctk_app.py) ─────────

    private View buildShell() {
        LinearLayout root = Ui.row(this);
        root.setGravity(0);
        root.setBackgroundColor(Ui.BG);

        LinearLayout rail = Ui.column(this);
        rail.setBackgroundColor(Ui.PANEL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        btIcon = railButton(rail, "bt", R.drawable.ic_nav_bluetooth, v -> onBluetoothTapped());
        View gap = new View(this);
        rail.addView(gap, Ui.lp(1, Ui.dp(this, 8)));
        String[] views = {"monitor", "calibration", "recordings", "neural"};
        int[] icons = {R.drawable.ic_nav_monitor, R.drawable.ic_nav_calibration, R.drawable.ic_nav_recordings,
                R.drawable.ic_nav_morphology};
        for (int i = 0; i < views.length; i++) {
            final int index = i;
            railButton(rail, views[i], icons[i], v -> showPage(pages.get(index), true));
        }
        rail.addView(Ui.space(this), Ui.lp(1, 0, 1f));
        railButton(rail, "settings", R.drawable.ic_nav_sliders, v -> setup.show());
        root.addView(rail, Ui.lp(Ui.dp(this, 50), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout main = Ui.column(this);
        pageHost = new FrameLayout(this);
        int p = Ui.dp(this, 7);
        pageHost.setPadding(p, p, p, Ui.dp(this, 5));
        main.addView(pageHost, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = Ui.row(this);
        footer.setBackgroundColor(Ui.PANEL);
        footer.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 10), 0);
        connText = Ui.line(this, "", 10, true, Ui.MUTED);
        connText.setOnClickListener(v -> onBluetoothTapped());
        footer.addView(connText);
        modeChip = Ui.line(this, "", 9.5f, true, Ui.MUTED);
        modeChip.setPadding(Ui.dp(this, 6), Ui.dp(this, 1), Ui.dp(this, 6), Ui.dp(this, 1));
        LinearLayout.LayoutParams mp = Ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.leftMargin = Ui.dp(this, 10);
        mp.rightMargin = Ui.dp(this, 8);
        footer.addView(modeChip, mp);
        counters = Ui.line(this, "", 10, false, Ui.MUTED);
        footer.addView(counters, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        version = Ui.line(this, "", 10, false, Ui.MUTED);
        footer.addView(version);
        clock = Ui.line(this, "", 10.5f, true, Ui.INK);
        clock.setPadding(Ui.dp(this, 10), 0, 0, 0);
        footer.addView(clock);
        main.addView(footer, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 24)));
        root.addView(main, Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return root;
    }

    private ImageView railButton(LinearLayout rail, String key, int icon, View.OnClickListener click) {
        ImageView v = new ImageView(this);
        v.setImageResource(icon);
        int pad = Ui.dp(this, 9);
        v.setPadding(pad, pad, pad, pad);
        v.setImageTintList(ColorStateList.valueOf(Ui.MUTED));
        v.setOnClickListener(click);
        LinearLayout.LayoutParams lp = Ui.lp(Ui.dp(this, 42), Ui.dp(this, 42));
        lp.bottomMargin = Ui.dp(this, 3);
        rail.addView(v, lp);
        railItems.put(key, v);
        return v;
    }

    private void paintRail() {
        for (Page page : pages) {
            ImageView v = railItems.get(page.view());
            if (v == null) continue;
            boolean on = page == current;
            v.setImageTintList(ColorStateList.valueOf(on ? Ui.ACCENT : Ui.MUTED));
            v.setBackground(on ? Ui.round(this, Ui.SUBTLE, 0, 8) : null);
        }
    }

    /** Show a page; `local` = the user chose it here, so the Pi is asked to show the same page. */
    private void showPage(Page page, boolean local) {
        if (local) {
            localNavAt = SystemClock.elapsedRealtime();
            if (connected()) send(obj("view", page.view()));
        }
        if (page == current) return;
        current = page;
        streams.clearTx();
        pageHost.removeAllViews();
        pageHost.addView(page.root(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        page.onShow();
        paintRail();
    }

    // ─── BLE connection ─────────────────────────────────────────────────

    private void onBluetoothTapped() {
        if (state == RpiBleManager.ConnectionState.DISCONNECTED) {
            new AlertDialog.Builder(this)
                    .setTitle(Texts.t("link_choose"))
                    .setItems(new String[]{Texts.t("link_ble"), Texts.t("link_mqtt")}, (d, which) -> {
                        if (which == 0) openScan();
                        else remoteDialog.show(RemoteSettings.load(this), this::connectRemote);
                    })
                    .setNegativeButton(Texts.t("cancel"), null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(Texts.t("disconnect_q"))
                .setPositiveButton(Texts.t("disconnect"), (d, w) -> {
                    if (link == Link.MQTT) mqtt.disconnect();
                    else ble.disconnectAndClose();
                })
                .setNegativeButton(Texts.t("cancel"), null)
                .show();
    }

    private void connectRemote(@NonNull RemoteSettings settings) {
        if (ble.isConnected()) ble.disconnectAndClose();
        switchLink(Link.MQTT);
        piOnline = false;
        deviceName = settings.deviceId + (settings.tls ? "" : " · " + Texts.t("tls_off"));
        mqtt.connect(settings);
    }

    /** The CA file picked in the broker form: read as PEM text (small files only). */
    private void onCaPicked(Uri uri) {
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("cannot open");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > CA_MAX_BYTES) throw new IllegalStateException("file too large");
            }
            String pem = new String(out.toByteArray(), StandardCharsets.US_ASCII);
            if (!pem.contains("BEGIN CERTIFICATE")) throw new IllegalStateException("not a PEM certificate");
            remoteDialog.setCa(pem);
        } catch (Exception e) {
            Toast.makeText(this, "CA: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openScan() {
        if (!hasBluetoothPermissions()) {
            List<String> perms = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            } else {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            permissionLauncher.launch(perms.toArray(new String[0]));
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!adapter.isEnabled()) {
            enableBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        DeviceScanDialog dialog = new DeviceScanDialog();
        dialog.setDeviceSelectedListener(this::connectTo);
        dialog.show(getSupportFragmentManager(), "scan");
    }

    private void connectTo(@NonNull BluetoothDevice device) {
        String name;
        try {
            name = device.getName() != null ? device.getName() : device.getAddress();
        } catch (SecurityException e) {
            name = device.getAddress();
        }
        deviceName = name;
        if (mqtt.isConnected() || link == Link.MQTT) mqtt.disconnect();
        switchLink(Link.BLE);
        ble.connectToDevice(device);
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Commands go out on `next` from now; the screen starts from "not connected" until it reports. */
    private void switchLink(Link next) {
        link = next;
        state = RpiBleManager.ConnectionState.DISCONNECTED;
        streams.clearAll();
        pending.clear();
        paintConnection();
    }

    /** A state change of either link; only the active one drives the screen. */
    private void onLinkState(Link which, RpiBleManager.ConnectionState s) {
        if (which != link) return;
        RpiBleManager.ConnectionState before = state;
        state = s == null ? RpiBleManager.ConnectionState.DISCONNECTED : s;
        if (state == RpiBleManager.ConnectionState.CONNECTED && before != state) {
            piView = "";
            if (connected()) send(obj("view", current.view()));   // the Pi shows the page the phone is on
        } else if (state == RpiBleManager.ConnectionState.DISCONNECTED) {
            streams.clearAll();
            pending.clear();
        }
        paintConnection();
    }

    private void paintConnection() {
        int color;
        String text;
        switch (state) {
            case CONNECTED:
                String via = Texts.t(link == Link.MQTT ? "via_mqtt" : "via_ble");
                if (link == Link.MQTT && !piOnline) {
                    color = Ui.WARN;
                    text = "●  " + via + " · " + deviceName + " · " + Texts.t("pi_offline");
                    break;
                }
                color = Ui.OK;
                text = "●  " + Texts.t("connected") + " · " + via + " · " + deviceName;
                if (link == Link.BLE && ble != null && !ble.hasRemotePages()) text += " · " + Texts.t("old_pi");
                break;
            case CONNECTING:
                color = Ui.WARN;
                text = "●  " + Texts.t("connecting");
                break;
            default:
                color = Ui.FAINT;
                text = "●  " + Texts.t("disconnected") + " — " + Texts.t("scan_hint");
                break;
        }
        btIcon.setImageTintList(ColorStateList.valueOf(connected() ? Ui.OK : Ui.MUTED));
        connText.setText(text);
        connText.setTextColor(color);
        if (state != RpiBleManager.ConnectionState.CONNECTED) {
            modeChip.setText("");
            modeChip.setBackground(null);
            counters.setText("");
            version.setText("");
        }
    }

    // ─── Pi -> phone (v3 frames, on the main thread) ─────────────────────

    @Override
    public void onStatus(@NonNull RpiBleManager.StatusPacket packet) {
        for (Page page : pages) page.onStatus(packet);
        setup.onStatus(packet);
    }

    @Override
    public void onStreamFrame(@NonNull String frame) {
        streams.onFrame(frame);
    }

    @Override
    public void onUiPart(@NonNull JSONObject part) {
        String kind = part.optString("k");
        if ("page".equals(kind)) {
            onPagePart(part);
            return;
        }
        // Parts describe the page on the Pi's screen; while the two differ (a moment after a page change)
        // they go to that page's object, not to the one shown here.
        Page target = pageFor(piView);
        if (target != null) target.onPart(kind, part);
    }

    private void onPagePart(JSONObject part) {
        if (Texts.setLanguage(part.optString("lang", "en"))) {
            for (Page page : pages) page.refreshTexts();
            setup.refreshTexts();
            paintConnection();
        }
        piView = part.optString("v", piView);
        Page piPage = pageFor(piView);
        if (piPage != null && piPage != current
                && SystemClock.elapsedRealtime() - localNavAt > FOLLOW_PI_AFTER_MS) {
            showPage(piPage, false);      // someone changed the page on the Pi itself
        }
        String hw = part.optString("hw", "");
        switch (hw) {
            case "hardware":
                modeChip.setText(Texts.t("mode_hardware"));
                modeChip.setTextColor(Ui.OK);
                modeChip.setBackground(Ui.round(this, Ui.OK_BG, 0, 4));
                break;
            case "dry_run":
                modeChip.setText(Texts.t("mode_dry_run"));
                modeChip.setTextColor(Ui.WARN_INK);
                modeChip.setBackground(Ui.round(this, Ui.WARN_BG, 0, 4));
                break;
            case "no_dac":
                modeChip.setText(Texts.t("mode_no_dac"));
                modeChip.setTextColor(Ui.ERROR);
                modeChip.setBackground(Ui.round(this, Ui.ERROR_BG, 0, 4));
                break;
            default:
                break;
        }
        JSONArray st = part.optJSONArray("st");
        JSONArray hz = part.optJSONArray("hz");
        StringBuilder sb = new StringBuilder();
        if (hz != null && hz.length() == 2) {
            sb.append(Texts.t("tx_model")).append(' ').append(hz.optInt(0)).append(" Hz   ")
                    .append(Texts.t("dac_target")).append(' ').append(hz.optInt(1)).append(" Hz   ");
        }
        if (st != null && st.length() == 3) {
            sb.append(Texts.t("buffer")).append(' ').append(st.optInt(0)).append("   ")
                    .append(Texts.t("lost")).append(' ').append(st.optInt(1)).append("   ")
                    .append(Texts.t("clipped")).append(' ').append(st.optInt(2));
        }
        counters.setText(sb.toString());
        version.setText("v" + part.optString("ver", ""));
    }

    private Page pageFor(String view) {
        for (Page page : pages) if (page.view().equals(view)) return page;
        return null;
    }

    // ─── phone -> Pi ────────────────────────────────────────────────────

    @Override
    public boolean connected() {
        if (state != RpiBleManager.ConnectionState.CONNECTED) return false;
        return link == Link.MQTT ? piOnline : ble != null;
    }

    @NonNull
    @Override
    public Streams streams() { return streams; }

    @Override
    public void send(@NonNull JSONObject command) {
        if (!connected()) {
            Toast.makeText(this, Texts.t("scan_hint"), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            command.put("origin", "android");
        } catch (Exception ignored) {
            // literal key
        }
        if (link == Link.MQTT) mqtt.sendCommand(command);
        else ble.sendCommand(command.toString());
    }

    @Override
    public void sendThrottled(@NonNull String group, @NonNull JSONObject command) {
        JSONObject merged = pending.get(group);
        if (merged == null) {
            pending.put(group, command);
            handler.postDelayed(() -> {
                JSONObject out = pending.remove(group);
                if (out != null && connected()) send(out);
            }, THROTTLE_MS);
        } else {
            merge(merged, command);
        }
    }

    /** Later values win; nested page objects ({"cal": {...}}) are merged key by key. */
    private static void merge(JSONObject into, JSONObject from) {
        try {
            for (Iterator<String> it = from.keys(); it.hasNext(); ) {
                String key = it.next();
                Object value = from.get(key);
                Object old = into.opt(key);
                if (value instanceof JSONObject && old instanceof JSONObject) merge((JSONObject) old, (JSONObject) value);
                else into.put(key, value);
            }
        } catch (Exception ignored) {
            // keys come from this app
        }
    }

    private static JSONObject obj(String key, Object value) {
        JSONObject o = new JSONObject();
        try {
            o.put(key, value);
        } catch (Exception ignored) {
            // literal key
        }
        return o;
    }
}
