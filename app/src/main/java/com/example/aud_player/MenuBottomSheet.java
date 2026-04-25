package com.example.aud_player;

import android.os.Bundle;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.TextUtils;

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
        void onBrowseClicked();
        void onMixerToggleClicked();
        boolean hasSongSelected();
        boolean isMixerEnabled();
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

        view.findViewById(R.id.menu_browse_btn).setOnClickListener(v -> {
            if (listener != null) listener.onBrowseClicked();
            dismiss();
        });

        view.findViewById(R.id.menu_mixer_toggle_btn).setOnClickListener(v -> {
            if (listener != null) listener.onMixerToggleClicked();
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
            speedLabel.setText(String.format("%.1fx", listener.getCurrentSpeed()));

            // Playback mode icon & label
            TextView modeLabel = view.findViewById(R.id.menu_mode_label);
            int mode = listener.getCurrentPlaybackMode();
            if (mode == 0) modeLabel.setText("Repeat");
            else if (mode == 1) modeLabel.setText("Next");
            else modeLabel.setText("Shuffle");

            TextView mixerToggleLabel = view.findViewById(R.id.menu_mixer_toggle_label);
            ImageView mixerToggleIcon = view.findViewById(R.id.menu_mixer_toggle_icon);
            boolean mixerEnabled = listener.isMixerEnabled();
            mixerToggleLabel.setText(mixerEnabled ? "Mixer On" : "Mixer Off");
            mixerToggleIcon.setImageResource(mixerEnabled ? R.drawable.ic_mixer_on : R.drawable.ic_mixer_off);
        }

        normalizeMenuGridAppearance(view);

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
            // Standard behavior: tapping outside the sheet dismisses it.
            dialog.setCanceledOnTouchOutside(true);
        }
        setCancelable(true);
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

    private void normalizeMenuGridAppearance(View root) {
        final int tileMinHeight = dpToPx(54);
        final int iconSize = dpToPx(24);
        final int[] tileIds = new int[] {
                R.id.menu_sort_btn,
                R.id.menu_timer_btn,
                R.id.menu_ab_repeat_btn,
                R.id.menu_playback_mode_btn,
                R.id.menu_speed_btn,
                R.id.menu_equalizer_btn,
                R.id.menu_settings_btn,
                R.id.menu_refresh_btn,
                R.id.menu_add_to_playlist_btn,
                R.id.menu_exit_btn,
                R.id.menu_browse_btn,
                R.id.menu_mixer_toggle_btn
        };

        for (int tileId : tileIds) {
            View tile = root.findViewById(tileId);
            if (!(tile instanceof LinearLayout)) {
                continue;
            }
            LinearLayout tileLayout = (LinearLayout) tile;
            tileLayout.setMinimumHeight(tileMinHeight);
            int compactPadding = dpToPx(10);
            tileLayout.setPadding(compactPadding, compactPadding, compactPadding, compactPadding);
            tileLayout.setGravity(android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.CENTER_VERTICAL);

            for (int i = 0; i < tileLayout.getChildCount(); i++) {
                View child = tileLayout.getChildAt(i);
                if (child instanceof ImageView) {
                    ViewGroup.LayoutParams lp = child.getLayoutParams();
                    lp.width = iconSize;
                    lp.height = iconSize;
                    child.setLayoutParams(lp);
                } else if (child instanceof TextView) {
                    TextView label = (TextView) child;
                    label.setSingleLine(true);
                    label.setMaxLines(1);
                    label.setEllipsize(TextUtils.TruncateAt.END);
                    label.setGravity(android.view.Gravity.CENTER);
                    label.setTextSize(12f);
                }
            }
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * requireContext().getResources().getDisplayMetrics().density);
    }
}
