package com.medical.simulator.pages;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.medical.simulator.remote.Streams;
import com.medical.simulator.ui.Choice;
import com.medical.simulator.ui.LaneView;
import com.medical.simulator.ui.Texts;
import com.medical.simulator.ui.Ui;
import com.medical.simulator.ui.ValueControl;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * PPG morphology page (Pi: ui/frames/neural_frame.py): fitted Gaussian, LSM-GAN, PSF-GAN and APFT-GAN windows.
 * The models run on the Pi (its worker process); the phone only chooses the source and its settings, asks for a
 * new sequence, plays it on the LEDs and shows the Pi's preview (v3 "pv" parts) or the live output.
 */
public class NeuralPage extends Page {
    private static final String[] MODES = {"Gaussian (fitted)", "LSM-GAN", "PSF-GAN", "APFT-GAN"};
    private static final String[] MODE_KEYS = {"fitted", "lsm", "psf", "apft"};
    private static final String[] PROFILES = {"With notch", "No notch"};
    private static final float SEEK_MAX = 22f;

    private final TextView title, info, sourceTitle, txTitle, seekLabel, status;
    private final Button playBtn, generateBtn;
    private final Choice mode, profile;
    private final ValueControl hr, lo, hi, dc, ac;
    private final SeekBar seek;
    private final LaneView txView;
    private final Preview preview = new Preview();
    private String modeName = MODES[0];
    private boolean busy, playing, canPlay, seekDragging;
    private float seekValue;

    public NeuralPage(@NonNull Context context, @NonNull Host host) {
        super(context, host);
        title = titleText();
        info = subtitleText();
        LinearLayout actions = Ui.row(c);
        playBtn = Ui.primary(c, "");
        playBtn.setOnClickListener(v -> host.send(pageCommand("nn", "play", !playing)));
        actions.addView(playBtn, Ui.lp(Ui.dp(c, 128), Ui.dp(c, 32)));
        pageHeader(title, info, actions);

        // ── waveform source ──
        LinearLayout tools = Ui.row(c);
        mode = new Choice(c, modeLabels(), i -> host.send(pageCommand("nn", "mode", MODES[i])));
        tools.addView(mode.button, Ui.lp(Ui.dp(c, 150), Ui.dp(c, 28)));
        profile = new Choice(c, profileLabels(), i -> host.send(pageCommand("nn", "prof", PROFILES[i])));
        LinearLayout.LayoutParams pp = Ui.lp(Ui.dp(c, 124), Ui.dp(c, 28));
        pp.leftMargin = Ui.dp(c, 5);
        tools.addView(profile.button, pp);
        generateBtn = Ui.outline(c, "", 11);
        generateBtn.setOnClickListener(v -> host.send(pageCommand("nn", "gen", true)));
        LinearLayout.LayoutParams gp = Ui.lp(Ui.dp(c, 104), Ui.dp(c, 28));
        gp.leftMargin = Ui.dp(c, 5);
        tools.addView(generateBtn, gp);
        sourceTitle = cardTitle();
        LinearLayout source = section(sourceTitle, tools);
        LinearLayout row = Ui.row(c);
        hr = ValueControl.inline(c, "", 40, 180, 1, 0, 75);
        lo = ValueControl.inline(c, "", 60, 100, 1, 0, 70);
        hi = ValueControl.inline(c, "", 60, 100, 1, 0, 80);
        dc = ValueControl.inline(c, "", 0, 1500, 1, 0, 1500);
        ac = ValueControl.inline(c, "", 0, 1500, 1, 0, 45);
        ValueControl[] all = {hr, lo, hi, dc, ac};
        String[] keys = {"hr", "lo", "hi", "dc", "ac"};
        for (int i = 0; i < all.length; i++) {
            final String key = keys[i];
            all[i].setOnValue((value, done) -> {
                if (done) host.send(pageCommand("nn", key, value));   // the Pi re-renders or drops its window
            });
            LinearLayout.LayoutParams lp = Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = Ui.dp(c, 4);
            lp.rightMargin = Ui.dp(c, 4);
            row.addView(all[i], lp);
        }
        source.addView(row);
        LinearLayout.LayoutParams sl = Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.bottomMargin = Ui.dp(c, 5);
        root.addView(source, sl);

        // ── TX (preview or live) and RX ──
        LinearLayout plots = Ui.row(c);
        plots.setGravity(0);
        LinearLayout txTools = Ui.row(c);
        seekLabel = Ui.line(c, "", 10, false, Ui.MUTED);
        txTools.addView(seekLabel);
        seek = new SeekBar(c);
        seek.setMax(Math.round(SEEK_MAX * 10));
        seek.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.INK));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) host.sendThrottled("nnseek", pageCommand("nn", "seek", p / 10.0));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { seekDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seekDragging = false;
                host.send(pageCommand("nn", "seek", s.getProgress() / 10.0));
            }
        });
        txTools.addView(seek, Ui.lp(Ui.dp(c, 110), Ui.dp(c, 28)));
        txTitle = cardTitle();
        LinearLayout tx = section(txTitle, txTools);
        txView = new LaneView(c);
        txView.setLaneCount(2);
        txView.lane(0).color = Ui.IR;
        txView.lane(1).color = Ui.PLOT_TEXT;
        txView.lane(0).detail = "mV";
        txView.lane(1).detail = "mV";
        LinearLayout holder = Ui.plotHolder(c);
        holder.addView(txView, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        tx.addView(holder, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams tl = Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        tl.rightMargin = Ui.dp(c, 3);
        plots.addView(tx, tl);
        LinearLayout rx = rxCard(null);
        LinearLayout.LayoutParams rl = Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rl.leftMargin = Ui.dp(c, 3);
        plots.addView(rx, rl);
        root.addView(plots, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        status = statusLine();
        layoutControls();
        refreshTexts();
    }

    @Override public String view() { return "neural"; }

    private static String[] modeLabels() {
        String[] out = new String[MODE_KEYS.length];
        for (int i = 0; i < out.length; i++) out[i] = Texts.t(MODE_KEYS[i]);
        return out;
    }

    private static String[] profileLabels() {
        return new String[]{Texts.t("with_notch"), Texts.t("no_notch")};
    }

    @Override
    public void refreshTexts() {
        title.setText(Texts.t("nn_title"));
        sourceTitle.setText(Texts.t("source"));
        mode.setLabels(modeLabels());
        profile.setLabels(profileLabels());
        generateBtn.setText(Texts.t("generate"));
        hr.setTitle(Texts.t("nn_hr"));
        lo.setTitle(Texts.t("hr_min"));
        hi.setTitle(Texts.t("hr_max"));
        dc.setTitle(Texts.t("dc"));
        ac.setTitle(Texts.t("ac"));
        seekLabel.setText(Texts.t("seek") + "  ");
        txTitle.setText(Texts.t("nn_tx"));
        setRxTitle(Texts.t("nn_rx"));
        paint();
    }

    /** APFT-GAN takes an HR range (min, max) instead of one requested HR, as on the Pi. */
    private void layoutControls() {
        boolean range = "APFT-GAN".equals(modeName);
        hr.setVisibility(range ? View.GONE : View.VISIBLE);
        lo.setVisibility(range ? View.VISIBLE : View.GONE);
        hi.setVisibility(range ? View.VISIBLE : View.GONE);
    }

    private void paint() {
        playBtn.setText((playing ? "\u25A0\uFE0E  " : "\u25B6\uFE0E  ") + Texts.t(playing ? "stop_output" : "play_led"));
        Ui.paintPrimary(playBtn, playing);
        playBtn.setEnabled(playing || canPlay);
        generateBtn.setEnabled(!busy);
        boolean gan = !MODES[0].equals(modeName);
        profile.button.setEnabled(!gan);
        boolean hrOn = !busy && !"LSM-GAN".equals(modeName);
        hr.setEnabled(hrOn);
        lo.setEnabled(hrOn);
        hi.setEnabled(hrOn);
    }

    @Override
    public void onPart(@NonNull String kind, @NonNull JSONObject part) {
        super.onPart(kind, part);
        switch (kind) {
            case "nn": {
                String m = part.optString("mode", modeName);
                for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(m)) mode.select(i);
                if (!m.equals(modeName)) {
                    modeName = m;
                    layoutControls();
                }
                profile.select("No notch".equals(part.optString("prof")) ? 1 : 0);
                hr.setRemote(part.optDouble("hr", hr.value()));
                lo.setRemote(part.optDouble("lo", lo.value()));
                hi.setRemote(part.optDouble("hi", hi.value()));
                dc.setRemote(part.optDouble("dc", dc.value()));
                ac.setRemote(part.optDouble("ac", ac.value()));
                seekValue = (float) part.optDouble("seek", seekValue);
                if (!seekDragging) seek.setProgress(Math.round(seekValue * 10));
                busy = part.optBoolean("busy");
                playing = part.optBoolean("play");
                canPlay = part.optBoolean("can");
                paint();
                break;
            }
            case "nnst":
                status.setText(part.optString("s"));
                status.setTextColor(Ui.kindColor(part.optString("c")));
                break;
            case "nni":
                info.setText(part.optString("i"));
                break;
            case "tx": {
                JSONArray lanes = part.optJSONArray("l");
                for (int i = 0; lanes != null && i < Math.min(2, lanes.length()); i++) {
                    JSONArray lane = lanes.optJSONArray(i);
                    if (lane != null) txView.lane(i).title = lane.optString(0, "");
                }
                break;
            }
            case "pv":
                preview.add(part);
                break;
            default:
                break;
        }
    }

    @Override
    public void tick() {
        super.tick();
        Streams s = host.streams();
        if (playing && s.txLive(1500)) {
            txView.setAxis(-8f, 0f);
            s.fill(txView.lane(0), s.txIr, false);
            s.fill(txView.lane(1), s.txRed, false);     // the real reference at the same clip time
        } else {
            txView.setAxis(seekValue, seekValue + 8f);
            preview.fill(txView, seekValue);
        }
        txView.setPlaceholder(host.connected() ? "" : Texts.t("disconnected"), "");
        txView.invalidate();
    }

    /** The Pi's previewed window, sent as chunks of 0-999 values per lane (comm/ble_remote.py preview_parts). */
    private static final class Preview {
        int id = -1, lanes;
        int[][][] chunks = new int[0][][];
        double[] lo = new double[0], hi = new double[0], span = new double[0];
        final float[] xs = new float[400], ys = new float[400];

        void add(JSONObject p) {
            int pid = p.optInt("id"), n = p.optInt("n"), l = p.optInt("l"), c = p.optInt("c"), m = p.optInt("m");
            if (pid != id || n != lanes) {
                id = pid;
                lanes = n;
                chunks = new int[n][][];
                lo = new double[n];
                hi = new double[n];
                span = new double[n];
            }
            if (l < 0 || l >= n || m <= 0 || c < 0 || c >= m) return;
            if (chunks[l] == null || chunks[l].length != m) chunks[l] = new int[m][];
            JSONArray v = p.optJSONArray("v");
            int[] values = new int[v == null ? 0 : v.length()];
            for (int i = 0; i < values.length; i++) values[i] = v.optInt(i);
            chunks[l][c] = values;
            lo[l] = p.optDouble("lo");
            hi[l] = p.optDouble("hi");
            span[l] = p.optDouble("t", 8);
        }

        void fill(LaneView view, float start) {
            for (int l = 0; l < view.laneCount(); l++) {
                LaneView.Lane lane = view.lane(l);
                if (l >= lanes || chunks[l] == null) { lane.clear(); continue; }
                int total = 0;
                for (int[] chunk : chunks[l]) {
                    if (chunk == null) { total = -1; break; }
                    total += chunk.length;
                }
                if (total < 2) { lane.clear(); continue; }   // wait until the whole lane has arrived
                int k = 0;
                for (int[] chunk : chunks[l]) {
                    for (int value : chunk) {
                        if (k >= xs.length) break;
                        xs[k] = start + (float) (span[l] * k / (total - 1));
                        ys[k] = (float) (lo[l] + value / 999.0 * (hi[l] - lo[l]));
                        k++;
                    }
                }
                lane.setData(xs, ys, k);
            }
        }
    }
}
