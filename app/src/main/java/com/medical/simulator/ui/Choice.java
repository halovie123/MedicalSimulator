package com.medical.simulator.ui;

import android.content.Context;
import android.view.Menu;
import android.widget.Button;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;

/** A dropdown button like the Pi's SelectButton: shows the chosen label and "▾", lists the options on tap. */
public class Choice {
    public interface OnChoice { void onChoice(int index); }

    public final Button button;
    private String[] labels;
    private int selected;

    public Choice(@NonNull Context c, @NonNull String[] labels, @NonNull OnChoice listener) {
        this.labels = labels;
        button = Ui.outline(c, "", Ui.TOOL_TEXT);
        button.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(c, button);
            for (int i = 0; i < this.labels.length; i++) menu.getMenu().add(Menu.NONE, i, i, this.labels[i]);
            menu.setOnMenuItemClickListener(item -> {
                select(item.getItemId());
                listener.onChoice(item.getItemId());
                return true;
            });
            menu.show();
        });
        select(0);
    }

    public void setLabels(@NonNull String[] labels) {
        this.labels = labels;
        select(Math.min(selected, labels.length - 1));
    }

    public void select(int index) {
        if (index < 0 || index >= labels.length) return;
        selected = index;
        button.setText(labels[index] + "  ▾");
    }

    public int selected() { return selected; }
}
