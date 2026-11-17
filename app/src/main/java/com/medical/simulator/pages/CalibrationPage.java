package com.medical.simulator.pages;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.medical.simulator.remote.Streams;
import com.medical.simulator.ui.LaneView;
import com.medical.simulator.ui.Texts;
import com.medical.simulator.ui.Ui;
import com.medical.simulator.ui.ValueControl;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Calibration & acquisition page (Pi: ui/frames/calibration_frame.py): per-channel test level ± sine on the
 * DACs, a tolerance, Start/Stop and the receive-path SpO₂ calibration; one dot per RX lane blinks green while
 * the OPT101 lane reads the sent level within the tolerance. The Pi does the matching; the phone shows it.
 */
public class CalibrationPage extends Page {
    // Slider ranges of the Pi page: levels up to the OPT101 output ceiling (2400 mV).
    private static final double LEVEL_MAX_MV = 2400, SWING_MAX_MV = 1200;
    private static final long BLINK_MS = 500;

    private final TextView title, subtitle, controlsTitle, delta, txTitle, txRange, status;
    private final Button runBtn, spo2Btn;
    private final ValueControl freq, ir, red, swing, tolerance;
    private final LaneView txView;
    private final TextView[] dots = new TextView[2], dotTexts = new TextView[2];
    private final String[] dotStates = {"idle", "idle"};
    private boolean running, sweeping;

    public CalibrationPage(@NonNull Context context, @NonNull Host host) {
        super(context, host);
        title = titleText();
        subtitle = subtitleText();
        LinearLayout actions = Ui.row(c);
        spo2Btn = Ui.outline(c, "", 11.5f);
        spo2Btn.setOnClickListener(v -> host.send(pageCommand("cal", "spo2", true)));
        LinearLayout.LayoutParams sp = Ui.lp(Ui.dp(c, 126), Ui.dp(c, 32));
        sp.rightMargin = Ui.dp(c, 6);
        actions.addView(spo2Btn, sp);
        runBtn = Ui.primary(c, "");
        runBtn.setOnClickListener(v -> host.send(pageCommand("cal", "run", !running)));
        actions.addView(runBtn, Ui.lp(Ui.dp(c, 96), Ui.dp(c, 32)));
        pageHeader(title, subtitle, actions);

        // ── test signal controls ──
        LinearLayout tools = Ui.row(c);
        delta = Ui.line(c, "", 10, true, Ui.INK);
        tools.addView(delta);
        controlsTitle = cardTitle();
        LinearLayout controls = section(controlsTitle, tools);
        LinearLayout row = Ui.row(c);
        freq = ValueControl.inline(c, "", 1, 5, 0.5, 1, 1);
        ir = ValueControl.inline(c, "", 0, LEVEL_MAX_MV, 10, 0, 1000);
        red = ValueControl.inline(c, "", 0, LEVEL_MAX_MV, 10, 0, 1000);
        swing = ValueControl.inline(c, "", 0, SWING_MAX_MV, 10, 0, 0);
        tolerance = ValueControl.inline(c, "", 1, 100, 1, 0, 20);
        ValueControl[] all = {freq, ir, red, swing, tolerance};
        String[] keys = {"f", "ir", "red", "sw", "tol"};
        for (int i = 0; i < all.length; i++) {
            final String key = keys[i];
            all[i].setOnValue((value, done) -> host.sendThrottled("cal", pageCommand("cal", key, value)));
            LinearLayout.LayoutParams lp = Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = Ui.dp(c, 3);
            lp.rightMargin = Ui.dp(c, 3);
            row.addView(all[i], lp);
        }
        controls.addView(row);
        LinearLayout.LayoutParams cp = Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = Ui.dp(c, 5);
        root.addView(controls, cp);

        // ── TX (1/3) and RX (2/3) ──
        LinearLayout panels = Ui.row(c);
        panels.setGravity(0);
        txTitle = cardTitle();
        LinearLayout tx = section(txTitle, null);
        txView = new LaneView(c);
        txView.setLaneCount(2);
        txView.setAxis(-8f, 0f);
        txView.lane(0).title = "IR";
        txView.lane(0).detail = "mV";
        txView.lane(0).color = Ui.IR;
        txView.lane(1).title = "RED";
        txView.lane(1).detail = "mV";
        txView.lane(1).color = Ui.RED;
        LinearLayout holder = Ui.plotHolder(c);
        holder.addView(txView, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        tx.addView(holder, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        txRange = Ui.line(c, "", 9.5f, false, Ui.MUTED);
        txRange.setPadding(Ui.dp(c, 4), Ui.dp(c, 3), 0, 0);
        tx.addView(txRange);
        LinearLayout.LayoutParams tl = Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        tl.rightMargin = Ui.dp(c, 3);
        panels.addView(tx, tl);

        LinearLayout dotsRow = Ui.row(c);
        for (int i = 0; i < 2; i++) {
            dots[i] = Ui.text(c, "●", 15, false, Ui.FAINT);
            dots[i].setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 2), 0);
            dotsRow.addView(dots[i]);
            dotTexts[i] = Ui.line(c, i == 0 ? "IR" : "RED", 10, true, Ui.MUTED);
            dotsRow.addView(dotTexts[i]);
        }
        LinearLayout rx = rxCard(dotsRow);
        LinearLayout.LayoutParams rl = Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f);
        rl.leftMargin = Ui.dp(c, 3);
        panels.addView(rx, rl);
        root.addView(panels, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        status = statusLine();
        refreshTexts();
    }

    @Override public String view() { return "calibration"; }

    @Override
    public void refreshTexts() {
        title.setText(Texts.t("cal_title"));
        subtitle.setText(Texts.t("cal_subtitle"));
        controlsTitle.setText(Texts.t("cal_controls"));
        delta.setText(Texts.t("delta"));
        freq.setTitle(Texts.t("frequency"));
        ir.setTitle(Texts.t("ir_level"));
        red.setTitle(Texts.t("red_level"));
        swing.setTitle(Texts.t("swing"));
        tolerance.setTitle(Texts.t("tolerance"));
        txTitle.setText(Texts.t("cal_tx"));
        setRxTitle(Texts.t("cal_rx"));
        spo2Btn.setText(Texts.t("spo2_cal"));
        paint();
    }

    private void paint() {
        runBtn.setText((running ? "\u25A0\uFE0E  " : "\u25B6\uFE0E  ") + Texts.t(running ? "cal_stop" : "cal_start"));
        Ui.paintPrimary(runBtn, running);
        spo2Btn.setEnabled(!sweeping);
    }

    @Override
    public void onPart(@NonNull String kind, @NonNull JSONObject part) {
        super.onPart(kind, part);
        if ("cal".equals(kind)) {
            running = part.optBoolean("run");
            sweeping = part.optBoolean("sweep");
            freq.setRemote(part.optDouble("f", freq.value()));
            ir.setRemote(part.optDouble("ir", ir.value()));
            red.setRemote(part.optDouble("red", red.value()));
            swing.setRemote(part.optDouble("sw", swing.value()));
            tolerance.setRemote(part.optDouble("tol", tolerance.value()));
            JSONArray d = part.optJSONArray("dots");
            for (int i = 0; d != null && i < Math.min(2, d.length()); i++) {
                JSONArray one = d.optJSONArray(i);
                if (one == null) continue;
                dotStates[i] = one.optString(0, "idle");
                dotTexts[i].setText(one.optString(1, ""));
                boolean ok = "ok".equals(dotStates[i]) || "blink".equals(dotStates[i]);
                dotTexts[i].setTextColor(ok ? Ui.OK : Ui.MUTED);
            }
            txRange.setText(part.optString("tr", ""));
            paint();
        } else if ("calst".equals(kind)) {
            status.setText(part.optString("s"));
            status.setTextColor(Ui.kindColor(part.optString("c")));
        }
    }

    @Override
    public void tick() {
        super.tick();
        // The Pi blinks a passing dot every 0.5 s; the phone blinks on its own clock instead of the Pi's frames.
        boolean on = (SystemClock.elapsedRealtime() / BLINK_MS) % 2 == 0;
        for (int i = 0; i < 2; i++) {
            String state = dotStates[i];
            int color = Ui.FAINT;
            if ("ok".equals(state) || "blink".equals(state)) color = on ? Ui.OK : Ui.PANEL;
            else if ("error".equals(state)) color = Ui.ERROR;
            dots[i].setTextColor(color);
        }
        Streams s = host.streams();
        if (running && s.txLive(1500)) {
            s.fill(txView.lane(0), s.txIr, false);
            s.fill(txView.lane(1), s.txRed, false);
        } else {
            txView.lane(0).clear();
            txView.lane(1).clear();
        }
        txView.setPlaceholder(host.connected() ? Texts.t("cal_idle") : Texts.t("disconnected"), "");
        txView.invalidate();
    }
}
