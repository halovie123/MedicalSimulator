package com.medical.simulator.pages;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.medical.simulator.ble.RpiBleManager;
import com.medical.simulator.remote.Streams;
import com.medical.simulator.ui.Choice;
import com.medical.simulator.ui.LaneView;
import com.medical.simulator.ui.Texts;
import com.medical.simulator.ui.Ui;
import com.medical.simulator.ui.ValueControl;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Classic / Monitor page (Pi: ui/frames/pathology_frame.py): output status, Record CSV and Start output,
 * the TX plot with the condition and 3-Gaussian controls, the RX plot with the pulse tone, and the heart
 * rate, SpO₂ target and respiration setpoints. There is no PI setpoint, as on the Pi.
 */
public class MonitorPage extends Page {
    private final View dot;
    private final TextView statusTitle, statusDetail, txTitle;
    private final Button recordBtn, runBtn, gaussBtn, beepBtn;
    private final Choice condition;
    private final LaneView txView;
    private final ValueControl hr, spo2, rr;
    private boolean running, recording, beep = true;
    private String txPlaceholder = "", txPlaceholderSub = "";

    public MonitorPage(@NonNull Context context, @NonNull Host host) {
        super(context, host);
        // ── status line and page actions ──
        LinearLayout bar = Ui.row(c);
        dot = new View(c);
        dot.setBackground(Ui.round(c, Ui.FAINT, 0, 4));
        bar.addView(dot, Ui.lp(Ui.dp(c, 8), Ui.dp(c, 8)));
        statusTitle = Ui.line(c, "", 12, true, Ui.INK);
        statusTitle.setPadding(Ui.dp(c, 7), 0, Ui.dp(c, 5), 0);
        bar.addView(statusTitle);
        statusDetail = Ui.line(c, "", 11, false, Ui.MUTED);
        bar.addView(statusDetail, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        recordBtn = Ui.outline(c, "", 11.5f);
        recordBtn.setOnClickListener(v -> host.send(obj("record", !recording)));
        LinearLayout.LayoutParams rp = Ui.lp(Ui.dp(c, 104), Ui.dp(c, 32));
        rp.rightMargin = Ui.dp(c, 6);
        bar.addView(recordBtn, rp);
        runBtn = Ui.primary(c, "");
        runBtn.setOnClickListener(v -> host.send(obj("run", !running)));
        bar.addView(runBtn, Ui.lp(Ui.dp(c, 116), Ui.dp(c, 32)));
        LinearLayout.LayoutParams bp = Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.bottomMargin = Ui.dp(c, 5);
        root.addView(bar, bp);

        // ── TX and RX cards ──
        LinearLayout panels = Ui.row(c);
        panels.setGravity(0);
        LinearLayout txTools = Ui.row(c);
        condition = new Choice(c, Texts.conditions(), i -> host.send(obj("condition", i)));
        txTools.addView(condition.button, Ui.lp(Ui.dp(c, 118), Ui.dp(c, 28)));
        gaussBtn = Ui.outline(c, Texts.t("gauss"), 11);
        gaussBtn.setOnClickListener(v -> host.send(obj("gauss", true)));
        LinearLayout.LayoutParams gp = Ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(c, 28));
        gp.leftMargin = Ui.dp(c, 5);
        txTools.addView(gaussBtn, gp);
        txTitle = cardTitle();
        LinearLayout tx = section(txTitle, txTools);
        txView = new LaneView(c);
        txView.setLaneCount(2);
        txView.setAxis(-8f, 0f);
        txView.lane(0).title = "IR";
        txView.lane(0).color = Ui.IR;
        txView.lane(1).title = "RED";
        txView.lane(1).color = Ui.RED;
        LinearLayout holder = Ui.plotHolder(c);
        holder.addView(txView, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        tx.addView(holder, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams lp = Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.rightMargin = Ui.dp(c, 3);
        panels.addView(tx, lp);

        LinearLayout rxTools = Ui.row(c);
        beepBtn = Ui.outline(c, "", 11);
        beepBtn.setOnClickListener(v -> host.send(obj("beep", !beep)));
        rxTools.addView(beepBtn, Ui.lp(Ui.dp(c, 104), Ui.dp(c, 28)));
        LinearLayout rx = rxCard(rxTools);
        LinearLayout.LayoutParams lp2 = Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp2.leftMargin = Ui.dp(c, 3);
        panels.addView(rx, lp2);
        root.addView(panels, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── setpoints (models/limits.py) ──
        LinearLayout setpoints = Ui.row(c);
        hr = ValueControl.card(c, "", "bpm", 10, 300, 1, 0, 75);
        spo2 = ValueControl.card(c, "", "%", 0, 100, 1, 0, 99);
        rr = ValueControl.card(c, "", "brpm", 1, 150, 1, 0, 16);
        ValueControl[] cards = {hr, spo2, rr};
        String[] keys = {"hr", "spo2", "rr"};
        for (int i = 0; i < cards.length; i++) {
            final String key = keys[i];
            cards[i].setOnValue((value, done) -> host.sendThrottled("mon", obj(key, (int) Math.round(value))));
            LinearLayout.LayoutParams cp = Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cp.leftMargin = i == 0 ? 0 : Ui.dp(c, 3);
            cp.rightMargin = i == cards.length - 1 ? 0 : Ui.dp(c, 3);
            setpoints.addView(cards[i], cp);
        }
        LinearLayout.LayoutParams sp = Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(c, 6);
        root.addView(setpoints, sp);
        refreshTexts();
    }

    @Override public String view() { return "monitor"; }

    @Override
    public void refreshTexts() {
        txTitle.setText(Texts.t("tx_card"));
        setRxTitle(Texts.t("rx_card"));
        hr.setTitle(Texts.t("hr"));
        spo2.setTitle(Texts.t("spo2"));
        rr.setTitle(Texts.t("rr"));
        int selected = condition.selected();
        condition.setLabels(Texts.conditions());
        condition.select(selected);
        paintButtons();
    }

    private void paintButtons() {
        runBtn.setText((running ? "\u25A0\uFE0E  " : "\u25B6\uFE0E  ") + Texts.t(running ? "stop" : "start"));
        Ui.paintPrimary(runBtn, running);
        recordBtn.setText((recording ? "\u25A0\uFE0E  " : "●  ") + Texts.t(recording ? "save_record" : "record"));
        Ui.paintOutline(recordBtn, recording ? Ui.ERROR : Ui.INK, recording ? Ui.ERROR : Ui.BORDER);
        recordBtn.setEnabled(running || recording);
        beepBtn.setText(Texts.t(beep ? "beep_on" : "beep_off"));
        Ui.paintOutline(beepBtn, beep ? Ui.ACCENT : Ui.MUTED, Ui.BORDER);
    }

    @Override
    public void onStatus(@NonNull RpiBleManager.StatusPacket s) {
        if (s.running != null) running = s.running;
        if (s.recording != null) recording = s.recording;
        // origin "android": the Pi's echo of our own change inside its 1 s grace window; cards keep the user's value.
        boolean truth = !"android".equals(s.origin);
        if (truth) {
            if (s.hr != null) hr.setRemote(s.hr);
            if (s.spo2 != null) spo2.setRemote(s.spo2);
            if (s.rr != null) rr.setRemote(s.rr);
        }
        if (s.condition != null) condition.select(s.condition);
        paintButtons();
    }

    @Override
    public void onPart(@NonNull String kind, @NonNull JSONObject part) {
        super.onPart(kind, part);
        if ("mon".equals(kind)) {
            dot.setBackground(Ui.round(c, Ui.kindColor(part.optString("dot")), 0, 4));
            statusTitle.setText(part.optString("h"));
            statusDetail.setText(part.optString("d"));
            statusDetail.setTextColor(Ui.kindColor(part.optString("dk")));
            beep = part.optBoolean("beep", beep);
            spo2.setBadge(part.optBoolean("rm") ? Texts.t("badge_red_manual") : null);
            paintButtons();
        } else if ("tx".equals(kind)) {
            JSONArray lanes = part.optJSONArray("l");
            for (int i = 0; lanes != null && i < Math.min(2, lanes.length()); i++) {
                JSONArray lane = lanes.optJSONArray(i);
                if (lane == null) continue;
                txView.lane(i).title = lane.optString(0, txView.lane(i).title);
                txView.lane(i).detail = lane.optString(1, "");
            }
            JSONArray p = part.optJSONArray("p");
            txPlaceholder = p != null ? p.optString(0, "") : "";
            txPlaceholderSub = p != null ? p.optString(1, "") : "";
        }
    }

    @Override
    public void tick() {
        super.tick();
        Streams s = host.streams();
        if (running && s.txLive(1500)) {
            s.fill(txView.lane(0), s.txIr, false);
            s.fill(txView.lane(1), s.txRed, false);
        } else {
            txView.lane(0).clear();
            txView.lane(1).clear();
        }
        if (!host.connected()) txView.setPlaceholder(Texts.t("disconnected"), Texts.t("scan_hint"));
        else txView.setPlaceholder(txPlaceholder, txPlaceholderSub);
        txView.invalidate();
    }
}
