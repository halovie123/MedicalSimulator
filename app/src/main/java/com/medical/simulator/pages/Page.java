package com.medical.simulator.pages;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.medical.simulator.ble.RpiBleManager;
import com.medical.simulator.remote.Streams;
import com.medical.simulator.ui.LaneView;
import com.medical.simulator.ui.Texts;
import com.medical.simulator.ui.Ui;

import org.json.JSONObject;

/**
 * One page of the phone app, mirroring one page of the Pi app. A page sends the Pi's page commands through
 * {@link Host} and draws what the Pi reports: v3 UI parts ({@link #onPart}), the v2 status frame
 * ({@link #onStatus}) and the TX/RX stream (redrawn in {@link #tick}).
 */
public abstract class Page {

    public interface Host {
        /** Send now (action buttons). */
        void send(@NonNull JSONObject command);
        /** Merge with the pending command of `group` and send at most every 200 ms (sliders). */
        void sendThrottled(@NonNull String group, @NonNull JSONObject command);
        boolean connected();
        @NonNull Streams streams();
        /** Ask the user where to save a text file (Android's document picker), write it, then run `done`. */
        void saveTextFile(@NonNull String suggestedName, @NonNull String text, @NonNull Runnable done);
    }

    protected final Context c;
    protected final Host host;
    protected final LinearLayout root;
    protected LaneView rxView;
    private TextView rxTitle;

    protected Page(@NonNull Context context, @NonNull Host host) {
        this.c = context;
        this.host = host;
        root = Ui.column(context);
    }

    /** The Pi's name for this page in the "view" command. */
    public abstract String view();

    public View root() { return root; }

    public void onShow() {}

    public void onPart(@NonNull String kind, @NonNull JSONObject part) {
        if (rxView != null && ("rxi".equals(kind) || "rxr".equals(kind))) {
            LaneView.Lane lane = rxView.lane("rxi".equals(kind) ? 0 : 1);
            lane.title = part.optString("t", lane.title);
            lane.detail = part.optString("d", "");
            lane.badge = part.optString("b", "");
            lane.badge2 = part.optString("b2", "");
            lane.message = part.optString("m", "");
            lane.sub = part.optString("s", "");
        }
    }

    public void onStatus(@NonNull RpiBleManager.StatusPacket status) {}

    /** Labels changed language (the Pi's language). */
    public abstract void refreshTexts();

    /**
     * True: the RX lanes draw the AC part only (DC level removed), as on the Pi. The Calibration page keeps the
     * received levels, since there the received DC is checked against the sent level.
     */
    protected boolean rxAcOnly() { return true; }

    /** ~20 times a second while shown: redraw the plots. */
    public void tick() {
        if (rxView == null) return;
        Streams s = host.streams();
        if (s.rxLive(1500)) {
            s.fill(rxView.lane(0), s.rxIr, true, rxAcOnly());
            s.fill(rxView.lane(1), s.rxRed, true, rxAcOnly());
        } else {
            rxView.lane(0).clear();
            rxView.lane(1).clear();
        }
        rxView.setPlaceholder(host.connected() ? "" : Texts.t("disconnected"),
                host.connected() ? "" : Texts.t("scan_hint"));
        rxView.invalidate();
    }

    // ── layout kit (the Pi's ui/page_kit.py) ──

    /** Title and muted subtitle on the left, the actions row on the right. */
    protected LinearLayout pageHeader(TextView title, @Nullable TextView subtitle, LinearLayout actions) {
        LinearLayout bar = Ui.row(c);
        LinearLayout texts = Ui.column(c);
        texts.addView(title);
        if (subtitle != null) {
            subtitle.setMaxLines(2);
            texts.addView(subtitle);
        }
        bar.addView(texts, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(actions);
        LinearLayout.LayoutParams lp = Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 4);
        root.addView(bar, lp);
        return bar;
    }

    protected TextView titleText() { return Ui.line(c, "", 14, true, Ui.INK); }

    protected TextView subtitleText() { return Ui.text(c, "", 9, false, Ui.MUTED); }

    /** A card with a header row (title, then `tools` on the right) over its content. */
    protected LinearLayout section(TextView title, @Nullable LinearLayout tools) {
        LinearLayout card = Ui.card(c, LinearLayout.VERTICAL);
        int p = Ui.dp(c, 5);
        card.setPadding(p, Ui.dp(c, 3), p, p);
        LinearLayout head = Ui.row(c);
        head.setMinimumHeight(Ui.dp(c, 26));
        title.setPadding(Ui.dp(c, 3), 0, Ui.dp(c, 6), 0);
        head.addView(title, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (tools != null) head.addView(tools);
        card.addView(head);
        return card;
    }

    protected TextView cardTitle() { return Ui.line(c, "", 11.5f, true, Ui.INK); }

    /** The RX card used on every page: IR (A4) and RED (A0) lanes of the OPT101 receiver, last 8 s. */
    protected LinearLayout rxCard(@Nullable LinearLayout tools) {
        rxTitle = cardTitle();
        LinearLayout card = section(rxTitle, tools);
        rxView = new LaneView(c);
        rxView.setLaneCount(2);
        rxView.setAxis(-8f, 0f);
        rxView.lane(0).title = "A4  IR";
        rxView.lane(0).color = Ui.IR;
        rxView.lane(1).title = "A0  RED";
        rxView.lane(1).color = Ui.RED;
        LinearLayout holder = Ui.plotHolder(c);
        holder.addView(rxView, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        card.addView(holder, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return card;
    }

    protected void setRxTitle(String text) {
        if (rxTitle != null) rxTitle.setText(text);
    }

    /** A status line under the page, coloured by the Pi's state name. */
    protected TextView statusLine() {
        TextView t = Ui.text(c, "", 9.5f, false, Ui.MUTED);
        t.setMaxLines(2);
        t.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 4);
        root.addView(t, lp);
        return t;
    }

    protected static JSONObject obj(Object... keyValues) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < keyValues.length; i += 2) o.put((String) keyValues[i], keyValues[i + 1]);
        } catch (Exception ignored) {
            // keys are literals
        }
        return o;
    }

    /** {"<page key>": {key: value}} for the page commands of protocol v3. */
    protected static JSONObject pageCommand(String page, Object... keyValues) {
        return obj(page, obj(keyValues));
    }
}
