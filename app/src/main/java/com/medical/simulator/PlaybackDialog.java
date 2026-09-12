package com.medical.simulator;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Dialog listing saved PPG recordings (CSV files) from the recordings
 * directory, newest first. Selecting one starts playback.
 */
public class PlaybackDialog extends DialogFragment {

    public interface OnPlaybackFileListener {
        void onPlaybackFileSelected(@NonNull File file);
    }

    @Nullable private File dir;
    @Nullable private OnPlaybackFileListener listener;

    private RecyclerView      rvRecordings;
    private TextView          tvEmpty;

    public void setRecordingDir(@Nullable File dir) {
        this.dir = dir;
    }

    public void setOnPlaybackFileListener(@Nullable OnPlaybackFileListener l) {
        this.listener = l;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NORMAL, R.style.Theme_MedicalSimulator_Dialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_playback_file, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvRecordings = view.findViewById(R.id.rvRecordings);
        tvEmpty      = view.findViewById(R.id.tvPlaybackEmpty);

        view.findViewById(R.id.btnClose).setOnClickListener(v -> dismiss());

        List<File> files = listRecordings();
        if (files.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
        }

        rvRecordings.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvRecordings.setAdapter(new RecordingAdapter(files, file -> {
            dismiss();
            if (listener != null) listener.onPlaybackFileSelected(file);
        }));
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
    }

    @Nullable
    private List<File> listRecordings() {
        if (dir == null || !dir.exists()) return Collections.emptyList();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(".csv"));
        if (files == null || files.length == 0) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    // ─── RecyclerView Adapter ─────────────────────────────────────────────────

    interface OnRecordingClickListener {
        void onRecordingClick(@NonNull File file);
    }

    static class RecordingAdapter extends RecyclerView.Adapter<RecordingAdapter.ViewHolder> {

        private final List<File> files;
        private final OnRecordingClickListener listener;
        private final SimpleDateFormat metaFormat =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        RecordingAdapter(@NonNull List<File> files, OnRecordingClickListener listener) {
            this.files = files;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_playback_file, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int position) {
            File file = files.get(position);
            h.tvName.setText(file.getName());

            long sizeKb = Math.max(1, file.length() / 1024);
            h.tvMeta.setText(String.format(Locale.US, "%d KB · %s",
                    sizeKb, metaFormat.format(new Date(file.lastModified()))));

            h.itemView.setOnClickListener(v -> listener.onRecordingClick(file));
        }

        @Override
        public int getItemCount() { return files.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView tvName;
            final TextView tvMeta;

            ViewHolder(@NonNull View v) {
                super(v);
                tvName = v.findViewById(R.id.tvFileName);
                tvMeta = v.findViewById(R.id.tvFileMeta);
            }
        }
    }
}
