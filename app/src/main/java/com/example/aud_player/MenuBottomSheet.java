package com.example.aud_player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class MenuBottomSheet extends BottomSheetDialogFragment {

    public interface MenuListener {
        void onSortClicked();
        void onTimerClicked();
        void onABRepeatClicked();
        void onPlaybackModeClicked();
        void onSpeedClicked();
        void onEqualizerClicked();
        void onSettingsClicked();
        void onRefreshClicked();
        void onAddToPlaylistClicked();
        boolean hasSongSelected();
        float getCurrentSpeed();
        int getCurrentPlaybackMode();
    }

    private MenuListener listener;

    public void setMenuListener(MenuListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_menu, container, false);

        // Grid items
        view.findViewById(R.id.menu_sort_btn).setOnClickListener(v -> {
            if (listener != null) listener.onSortClicked();
            dismiss();
        });

        view.findViewById(R.id.menu_timer_btn).setOnClickListener(v -> {
            if (listener != null) listener.onTimerClicked();
            dismiss();
        });

        view.findViewById(R.id.menu_ab_repeat_btn).setOnClickListener(v -> {
            if (listener != null) listener.onABRepeatClicked();
            dismiss();
        });

        view.findViewById(R.id.menu_playback_mode_btn).setOnClickListener(v -> {
            if (listener != null) listener.onPlaybackModeClicked();
            dismiss();
        });

        view.findViewById(R.id.menu_speed_btn).setOnClickListener(v -> {
            if (listener != null) listener.onSpeedClicked();
            dismiss();
        });

        view.findViewById(R.id.menu_equalizer_btn).setOnClickListener(v -> {
            if (listener != null) listener.onEqualizerClicked();
            dismiss();
        });

        view.findViewById(R.id.menu_settings_btn).setOnClickListener(v -> {
            if (listener != null) listener.onSettingsClicked();
            dismiss();
        });

        view.findViewById(R.id.menu_refresh_btn).setOnClickListener(v -> {
            if (listener != null) listener.onRefreshClicked();
            dismiss();
        });

        // Speed label
        if (listener != null) {
            TextView speedLabel = view.findViewById(R.id.menu_speed_label);
            speedLabel.setText(String.format("Speed %.1fx", listener.getCurrentSpeed()));

            // Playback mode icon & label
            TextView modeLabel = view.findViewById(R.id.menu_mode_label);
            int mode = listener.getCurrentPlaybackMode();
            if (mode == 0) modeLabel.setText("Repeat");
            else if (mode == 1) modeLabel.setText("Next");
            else modeLabel.setText("Shuffle");
        }

        LinearLayout addToPlaylistRow = view.findViewById(R.id.menu_add_to_playlist_row);

        // Add to playlist
        if (listener != null && listener.hasSongSelected()) {
            addToPlaylistRow.setVisibility(View.VISIBLE);
            addToPlaylistRow.setOnClickListener(v -> {
                listener.onAddToPlaylistClicked();
                dismiss();
            });
        }

        return view;
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }
}
