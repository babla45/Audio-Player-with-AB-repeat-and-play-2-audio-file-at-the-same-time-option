package com.example.aud_player;

import android.os.Bundle;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class MenuBottomSheet extends BottomSheetDialogFragment {
    private static final float HEADER_SWIPE_CLOSE_THRESHOLD_DP = 22f;

    public interface MenuListener {
        void onSortClicked();
        void onTimerClicked();
        void onABRepeatClicked();
        void onPlaybackModeClicked(View anchorView);
        void onSpeedClicked();
        void onEqualizerClicked();
        void onSettingsClicked();
        void onRefreshClicked();
        void onExitAppClicked();
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

        View topBar = view.findViewById(R.id.menu_top_bar);
        View title = view.findViewById(R.id.menu_title);
        topBar.setOnClickListener(v -> dismiss());
        title.setOnClickListener(v -> dismiss());
        setupHeaderSwipeToClose(topBar);
        setupHeaderSwipeToClose(title);

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
            if (listener != null) listener.onPlaybackModeClicked(v);
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

        view.findViewById(R.id.menu_exit_btn).setOnClickListener(v -> {
            if (listener != null) listener.onExitAppClicked();
            dismiss();
        });

        View addToPlaylistButton = view.findViewById(R.id.menu_add_to_playlist_btn);
        boolean canAddToPlaylist = listener != null && listener.hasSongSelected();
        addToPlaylistButton.setEnabled(canAddToPlaylist);
        addToPlaylistButton.setAlpha(canAddToPlaylist ? 1f : 0.45f);
        addToPlaylistButton.setOnClickListener(v -> {
            if (listener != null && listener.hasSongSelected()) {
                listener.onAddToPlaylistClicked();
                dismiss();
            }
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

        return view;
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            dialog.setCanceledOnTouchOutside(false);
        }
        if (!(dialog instanceof BottomSheetDialog)) {
            return;
        }

        BottomSheetDialog bottomSheetDialog = (BottomSheetDialog) dialog;
        View bottomSheet = bottomSheetDialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet == null) {
            return;
        }

        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
        // Prevent whole-sheet drag conflicts. Only top bar tap closes the sheet.
        behavior.setDraggable(false);
        behavior.setHideable(true);
        behavior.setSkipCollapsed(true);
    }

    private void setupHeaderSwipeToClose(View target) {
        if (target == null) return;

        final float thresholdPx = HEADER_SWIPE_CLOSE_THRESHOLD_DP
                * requireContext().getResources().getDisplayMetrics().density;
        final float[] downY = new float[1];
        final float[] downX = new float[1];

        target.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = event.getRawY();
                    downX[0] = event.getRawX();
                    return false;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    float deltaY = event.getRawY() - downY[0];
                    float deltaX = event.getRawX() - downX[0];
                    boolean verticalSwipe = Math.abs(deltaY) > Math.abs(deltaX) * 1.1f;
                    if (verticalSwipe && deltaY >= thresholdPx) {
                        dismiss();
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });
    }
}
