package com.medical.simulator.pages;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.medical.simulator.remote.Streams;
import com.medical.simulator.ui.LaneView;
import com.medical.simulator.ui.Texts;
import com.medical.simulator.ui.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Recordings page (Pi: ui/frames/playback_frame.py): the CSV recordings saved on the Pi, played to the LEDs,
 * with the recorded setpoints at the playback position and the live RX next to it. Selecting is the v3 "rec"
 * command; Play/Pause/Resume are the v2 "pb" commands. Upload to DB ("rec": {"up": name}, asked twice) is the only
 * way a recording reaches the database: the Pi keeps every file local until then.
 */
public class RecordingsPage extends Page {
    private static final String[] METRIC_KEYS = {"hr", "spo2", "rr", "condition"};
    private static final int[] METRIC_INDEX = {0, 1, 2, 4};   // the Pi sends hr, spo2, rr, pi, condition

    private final TextView title, subtitle, sessionsTitle, txTitle, info, status;
    private final Button playBtn, uploadBtn;
    private boolean dbAvailable, uploadArmed;
    private final Runnable disarmUpload = () -> { uploadArmed = false; paint(); };
    private final LinearLayout files;
    private final LaneView txView;
    private final TextView[] metricNames = new TextView[4], metricValues = new TextView[4];
    private final List<String[]> chunks = new ArrayList<>();
    private List<String> shownNames = new ArrayList<>();
    private String sync = "", shownSync = "";   // database copy per listed name: s synced, p pending, e error
    private String selected = "";
    private boolean playing;
    private double position, duration;

    public RecordingsPage(@NonNull Context context, @NonNull Host host) {
        super(context, host);
        title = titleText();
        subtitle = subtitleText();
        LinearLayout actions = Ui.row(c);
        uploadBtn = Ui.outline(c, "", 11);
        uploadBtn.setOnClickListener(v -> onUpload());
        LinearLayout.LayoutParams up = Ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(c, 32));
        up.rightMargin = Ui.dp(c, 5);
        actions.addView(uploadBtn, up);
        playBtn = Ui.primary(c, "");
        playBtn.setOnClickListener(v -> togglePlay());
        actions.addView(playBtn, Ui.lp(Ui.dp(c, 104), Ui.dp(c, 32)));
        pageHeader(title, subtitle, actions);

        LinearLayout body = Ui.row(c);
        body.setGravity(0);
        sessionsTitle = cardTitle();
        LinearLayout drawer = section(sessionsTitle, null);
        files = Ui.column(c);
        ScrollView scroll = new ScrollView(c);
        scroll.addView(files);
        drawer.addView(scroll, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams dl = Ui.lp(Ui.dp(c, 140), LinearLayout.LayoutParams.MATCH_PARENT);
        dl.rightMargin = Ui.dp(c, 6);
        body.addView(drawer, dl);

        LinearLayout right = Ui.column(c);
        LinearLayout plots = Ui.row(c);
        plots.setGravity(0);
        LinearLayout txTools = Ui.row(c);
        info = Ui.line(c, "", 9.5f, false, Ui.MUTED);
        info.setMaxWidth(Ui.dp(c, 150));        // the file name keeps its room in the card title
        txTools.addView(info);
        txTitle = cardTitle();
        LinearLayout tx = section(txTitle, txTools);
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
        LinearLayout.LayoutParams tl = Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        tl.rightMargin = Ui.dp(c, 3);
        plots.addView(tx, tl);
        LinearLayout rx = rxCard(null);
        LinearLayout.LayoutParams rl = Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rl.leftMargin = Ui.dp(c, 3);
        plots.addView(rx, rl);
        right.addView(plots, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout metrics = Ui.row(c);
        for (int i = 0; i < 4; i++) {
            LinearLayout card = Ui.card(c, LinearLayout.VERTICAL);
            int p = Ui.dp(c, 7);
            card.setPadding(p, Ui.dp(c, 4), p, Ui.dp(c, 4));
            metricNames[i] = Ui.line(c, "", 10, false, Ui.MUTED);
            metricValues[i] = Ui.line(c, "—", 15, true, Ui.INK);
            card.addView(metricNames[i]);
            card.addView(metricValues[i]);
            LinearLayout.LayoutParams mp = Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            mp.leftMargin = i == 0 ? 0 : Ui.dp(c, 3);
            mp.rightMargin = i == 3 ? 0 : Ui.dp(c, 3);
            metrics.addView(card, mp);
        }
        LinearLayout.LayoutParams ml = Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ml.topMargin = Ui.dp(c, 6);
        right.addView(metrics, ml);
        body.addView(right, Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        root.addView(body, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        status = statusLine();
        refreshTexts();
    }

    @Override public String view() { return "recordings"; }

    @Override
    public void refreshTexts() {
        title.setText(Texts.t("rec_title"));
        subtitle.setText(Texts.t("rec_subtitle"));
        sessionsTitle.setText(Texts.t("sessions"));
        setRxTitle(Texts.t("rec_rx"));
        for (int i = 0; i < 4; i++) metricNames[i].setText(Texts.t(METRIC_KEYS[i]));
        status.setText(Texts.t("scope"));
        shownNames = new ArrayList<>();      // rebuild the list (its empty text is translated too)
        paint();
    }

    private void togglePlay() {
        if (selected.isEmpty()) return;
        if (playing) host.send(obj("pb", "pause"));
        else if (position > 0 && position < duration) host.send(obj("pb", "resume"));
        else host.send(obj("pb", "start", "pb_file", selected));
    }

    /** First tap asks, the second (within 4 s) sends the consent for this one file. */
    private void onUpload() {
        if (selected.isEmpty()) return;
        if (!uploadArmed) {
            uploadArmed = true;
            uploadBtn.postDelayed(disarmUpload, 4000);
        } else {
            uploadArmed = false;
            uploadBtn.removeCallbacks(disarmUpload);
            host.send(pageCommand("rec", "up", selected));
        }
        paint();
    }

    /** The database state of the selected file from the Pi's "sy" letters: s, p, e, l (local) or '-'. */
    private char selectedSync() {
        List<String> names = new ArrayList<>();
        for (String[] chunk : chunks) if (chunk != null) names.addAll(Arrays.asList(chunk));
        int i = names.indexOf(selected);
        return i >= 0 && i < sync.length() ? sync.charAt(i) : '-';
    }

    private void paintUpload() {
        char state = selectedSync();
        String key;
        boolean enabled = false;
        int color = Ui.INK;
        if (selected.isEmpty()) key = "upload";
        else if (!dbAvailable) { key = "no_db"; color = Ui.MUTED; }
        else if (state == 's') { key = "in_db"; color = Ui.OK; }
        else if (state == 'p') { key = "uploading"; color = Ui.MUTED; }
        else if (state == 'e') { key = "upload_failed"; color = Ui.ERROR; }
        else { key = uploadArmed ? "upload_confirm" : "upload"; enabled = true; color = uploadArmed ? Ui.ACCENT : Ui.INK; }
        uploadBtn.setText(Texts.t(key));
        uploadBtn.setEnabled(enabled);
        uploadBtn.setTextColor(color);
    }

    private void paint() {
        txTitle.setText(selected.isEmpty() ? Texts.t("select") : selected);
        paintUpload();
        boolean ended = duration > 0 && position >= duration;
        playBtn.setText((playing ? "\u2016  " : "\u25B6\uFE0E  ") + Texts.t(playing ? "pause" : ended ? "replay" : "play"));
        playBtn.setEnabled(!selected.isEmpty());
        List<String> names = new ArrayList<>();
        for (String[] chunk : chunks) if (chunk != null) names.addAll(Arrays.asList(chunk));
        if (names.equals(shownNames) && sync.equals(shownSync) && files.getChildCount() > 0) {
            highlight();
            return;
        }
        shownNames = names;
        shownSync = sync;
        files.removeAllViews();
        if (names.isEmpty()) {
            TextView empty = Ui.text(c, Texts.t("empty"), 10, false, Ui.MUTED);
            empty.setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), 0);
            files.addView(empty);
        }
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            Button b = Ui.outline(c, "☰  " + name + syncMark(i), 11);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setTag(name);
            b.setOnClickListener(v -> {
                selected = name;
                host.streams().clearTx();
                host.send(pageCommand("rec", "sel", name));
                paint();
            });
            LinearLayout.LayoutParams lp = Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 32));
            lp.bottomMargin = Ui.dp(c, 2);
            files.addView(b, lp);
        }
        highlight();
    }

    /** The Pi's database mark after a file name (Pi: SYNC_MARKS in ui/frames/playback_frame.py). */
    private String syncMark(int index) {
        if (index >= sync.length()) return "";
        switch (sync.charAt(index)) {
            case 's': return "   ✓ DB";
            case 'p': return "   … DB";
            case 'e': return "   ✗ DB";
            default: return "";
        }
    }

    private void highlight() {
        for (int i = 0; i < files.getChildCount(); i++) {
            if (!(files.getChildAt(i) instanceof Button)) continue;
            Button b = (Button) files.getChildAt(i);
            boolean on = selected.equals(b.getTag());
            b.setBackground(Ui.round(c, on ? Ui.SUBTLE : Ui.PANEL, 0, 6));
            b.setTextColor(on ? Ui.ACCENT : Ui.INK);
        }
    }

    @Override
    public void onPart(@NonNull String kind, @NonNull JSONObject part) {
        super.onPart(kind, part);
        if ("ls".equals(kind)) {
            int index = part.optInt("c"), count = Math.max(1, part.optInt("m", 1));
            while (chunks.size() > count) chunks.remove(chunks.size() - 1);
            while (chunks.size() < count) chunks.add(null);
            JSONArray f = part.optJSONArray("f");
            String[] names = new String[f == null ? 0 : f.length()];
            for (int i = 0; i < names.length; i++) names[i] = f.optString(i);
            if (index >= 0 && index < count) chunks.set(index, names);
            paint();
        } else if ("sy".equals(kind)) {
            sync = part.optString("s", "");
            paint();
        } else if ("rec".equals(kind)) {
            String sel = part.optString("sel", "");
            dbAvailable = part.optBoolean("db", false);
            if (!sel.equals(selected)) {
                host.streams().clearTx();
                uploadArmed = false;
            }
            selected = sel;
            playing = part.optBoolean("play");
            position = part.optDouble("pos", 0);
            duration = part.optDouble("dur", 0);
            info.setText(part.optString("i", ""));
            JSONArray m = part.optJSONArray("m");
            for (int i = 0; i < 4; i++) metricValues[i].setText(m != null ? m.optString(METRIC_INDEX[i], "—") : "—");
            paint();
        }
    }

    @Override
    public void tick() {
        super.tick();
        Streams s = host.streams();
        if (s.txIr.size() > 1) {         // kept while paused, as the Pi keeps its last frame
            s.fill(txView.lane(0), s.txIr, false);
            s.fill(txView.lane(1), s.txRed, false);
        } else {
            txView.lane(0).clear();
            txView.lane(1).clear();
        }
        txView.setPlaceholder(!host.connected() ? Texts.t("disconnected")
                : (selected.isEmpty() ? Texts.t("none") : ""), "");
        txView.invalidate();
    }
}
