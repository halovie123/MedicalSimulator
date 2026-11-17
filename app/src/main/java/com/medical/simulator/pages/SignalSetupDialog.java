package com.medical.simulator.pages;

import android.app.Dialog;
import android.content.Context;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.medical.simulator.ble.RpiBleManager;
import com.medical.simulator.ui.Texts;
import com.medical.simulator.ui.Ui;
import com.medical.simulator.ui.ValueControl;

/**
 * The amplitude part of the Pi's Signal setup window: AC and DC of each channel (0-1500 mV) and the noise level,
 * sent as the v2 setpoint keys. Values follow the Pi's status frame unless the user is changing them.
 */
public class SignalSetupDialog extends Dialog {
    private final ValueControl acIr, acRed, dcIr, dcRed, noise;
    private final TextView title, note;
    private final Button close;

    public SignalSetupDialog(@NonNull Context c, @NonNull Page.Host host) {
        super(c);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = Ui.card(c, LinearLayout.VERTICAL);
        int p = Ui.dp(c, 12);
        root.setPadding(p, p, p, p);
        LinearLayout head = Ui.row(c);
        title = Ui.line(c, "", 15, true, Ui.INK);
        head.addView(title, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        close = Ui.outline(c, "", 12);
        close.setOnClickListener(v -> dismiss());
        head.addView(close, Ui.lp(Ui.dp(c, 90), Ui.dp(c, 30)));
        root.addView(head);
        note = Ui.text(c, "", 10, false, Ui.MUTED);
        note.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 6));
        root.addView(note);
        acIr = ValueControl.inline(c, "", 0, 1500, 1, 0, 45);
        acRed = ValueControl.inline(c, "", 0, 1500, 1, 0, 20);
        dcIr = ValueControl.inline(c, "", 0, 1500, 1, 0, 1500);
        dcRed = ValueControl.inline(c, "", 0, 1500, 1, 0, 1500);
        noise = ValueControl.inline(c, "", 0, 1, 0.01, 2, 0);
        ValueControl[][] rows = {{acIr, acRed, noise}, {dcIr, dcRed, null}};
        String[][] keys = {{"ac_ir_mv", "ac_red_mv", "noise"}, {"dc_ir_mv", "dc_red_mv", null}};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = Ui.row(c);
            for (int k = 0; k < 3; k++) {
                ValueControl control = rows[r][k];
                LinearLayout.LayoutParams lp = Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(Ui.dp(c, 4), Ui.dp(c, 3), Ui.dp(c, 4), Ui.dp(c, 3));
                if (control == null) {
                    row.addView(Ui.space(c), lp);
                    continue;
                }
                final String key = keys[r][k];
                control.setOnValue((value, done) -> host.sendThrottled("setup", Page.obj(key, value)));
                row.addView(control, lp);
            }
            root.addView(row);
        }
        setContentView(root);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(Ui.dp(c, 640), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        refreshTexts();
    }

    public void refreshTexts() {
        title.setText(Texts.t("settings"));
        note.setText(Texts.t("setup_note"));
        acIr.setTitle(Texts.t("ac_ir"));
        acRed.setTitle(Texts.t("ac_red"));
        dcIr.setTitle(Texts.t("dc_ir"));
        dcRed.setTitle(Texts.t("dc_red"));
        noise.setTitle(Texts.t("noise"));
        close.setText(Texts.t("close"));
    }

    public void onStatus(@NonNull RpiBleManager.StatusPacket s) {
        if ("android".equals(s.origin)) return;
        if (s.acIrMv != null) acIr.setRemote(s.acIrMv);
        if (s.acRedMv != null) acRed.setRemote(s.acRedMv);
        if (s.dcIrMv != null) dcIr.setRemote(s.dcIrMv);
        if (s.dcRedMv != null) dcRed.setRemote(s.dcRedMv);
        if (s.noise != null) noise.setRemote(s.noise);
    }
}
