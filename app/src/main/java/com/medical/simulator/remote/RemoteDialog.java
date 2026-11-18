package com.medical.simulator.remote;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.medical.simulator.ui.Texts;
import com.medical.simulator.ui.Ui;

/** Broker settings form of the internet link: host, port, TLS, Pi device id, account and an optional private CA. */
public class RemoteDialog {
    public interface OnConnect { void connect(@NonNull RemoteSettings settings); }

    private final Context c;
    private final Runnable pickCa;
    private String caPem = "", caPin = "";
    private TextView caState;

    public RemoteDialog(@NonNull Context context, @NonNull Runnable pickCa) {
        this.c = context;
        this.pickCa = pickCa;
    }

    /** A CA picked by the user (PEM text), shown in the open form. */
    public void setCa(String pem) {
        caPem = pem == null ? "" : pem;
        caPin = "";                       // an imported file, or ✕, replaces the CA paired by QR
        if (caState != null) paintCa();
    }

    private void paintCa() {
        caState.setText(Texts.t(!caPem.isEmpty() ? "ca_loaded" : !caPin.isEmpty() ? "ca_paired" : "ca_none"));
    }

    private EditText field(LinearLayout form, String label, String value, int type) {
        TextView l = Ui.line(c, label, 11, false, Ui.MUTED);
        l.setPadding(0, Ui.dp(c, 6), 0, 0);
        form.addView(l);
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setText(value);
        e.setTextColor(Ui.INK);
        e.setTextSize(13);
        form.addView(e);
        return e;
    }

    public void show(@NonNull RemoteSettings current, @NonNull OnConnect onConnect) {
        caPem = current.caPem;
        caPin = current.caPin;
        LinearLayout form = Ui.column(c);
        int p = Ui.dp(c, 16);
        form.setPadding(p, Ui.dp(c, 4), p, 0);
        EditText host = field(form, Texts.t("broker_host"), current.host, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = field(form, Texts.t("port"), String.valueOf(current.port), InputType.TYPE_CLASS_NUMBER);
        CheckBox tls = new CheckBox(c);
        tls.setText(Texts.t("tls"));
        tls.setTextColor(Ui.INK);
        tls.setChecked(current.tls);
        tls.setOnCheckedChangeListener((b, on) -> {
            String now = port.getText().toString().trim();
            if (on && now.equals("1883")) port.setText("8883");
            if (!on && now.equals("8883")) port.setText("1883");
        });
        form.addView(tls);
        EditText device = field(form, Texts.t("device_id"), current.deviceId, InputType.TYPE_CLASS_TEXT);
        EditText user = field(form, Texts.t("username"), current.username, InputType.TYPE_CLASS_TEXT);
        EditText pass = field(form, Texts.t("password"), current.password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText second = field(form, Texts.t("broker_2"),
                current.host2.isEmpty() ? "" : current.host2 + ":" + current.port2,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout caRow = Ui.row(c);
        caState = Ui.line(c, "", 11, false, Ui.MUTED);
        caRow.addView(caState, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button clear = Ui.outline(c, "✕", 12);
        clear.setOnClickListener(v -> setCa(""));
        caRow.addView(clear, Ui.lp(Ui.dp(c, 36), Ui.dp(c, 32)));
        Button pick = Ui.outline(c, Texts.t("ca_import"), 11);
        pick.setOnClickListener(v -> pickCa.run());
        LinearLayout.LayoutParams pl = Ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(c, 32));
        pl.leftMargin = Ui.dp(c, 6);
        caRow.addView(pick, pl);
        LinearLayout.LayoutParams cl = Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.topMargin = Ui.dp(c, 8);
        form.addView(caRow, cl);
        paintCa();
        ScrollView scroll = new ScrollView(c);
        scroll.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(c)
                .setTitle(Texts.t("link_mqtt"))
                .setView(scroll)
                .setPositiveButton(Texts.t("connect"), null)
                .setNegativeButton(Texts.t("cancel"), null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int portValue;
            try {
                portValue = Integer.parseInt(port.getText().toString().trim());
            } catch (NumberFormatException e) {
                portValue = -1;
            }
            String host2 = second.getText().toString().trim();
            int port2 = portValue;                       // "host" alone: same port as the first broker
            int colon = host2.lastIndexOf(':');
            if (colon > 0) {
                try {
                    port2 = Integer.parseInt(host2.substring(colon + 1));
                } catch (NumberFormatException e) {
                    port2 = -1;
                }
                host2 = host2.substring(0, colon);
            }
            RemoteSettings s = new RemoteSettings(host.getText().toString(), portValue, tls.isChecked(),
                    device.getText().toString(), user.getText().toString(), pass.getText().toString(), caPem, caPin,
                    host2, port2);
            String problem = s.problem();
            if (problem != null) {
                Toast.makeText(c, problem, Toast.LENGTH_SHORT).show();
                return;
            }
            s.save(c);
            dialog.dismiss();
            onConnect.connect(s);
        }));
        dialog.setOnDismissListener(d -> caState = null);
        dialog.show();
    }
}
