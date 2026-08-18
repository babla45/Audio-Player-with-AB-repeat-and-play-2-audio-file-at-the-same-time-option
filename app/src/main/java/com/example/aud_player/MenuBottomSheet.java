package com.example.aud_player;

import android.os.Bundle;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.DragEvent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import android.animation.Animator;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.TextUtils;
import android.content.res.ColorStateList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

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
        void onPitchClicked();
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
        this.menuRoot = view;

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

        view.findViewById(R.id.menu_pitch_btn).setOnClickListener(v -> {
            if (listener != null) listener.onPitchClicked();
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
                R.id.menu_pitch_btn,
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
                    ImageView icon = (ImageView) child;
                    ViewGroup.LayoutParams lp = child.getLayoutParams();
                    lp.width = iconSize;
                    lp.height = iconSize;
                    child.setLayoutParams(lp);
                    icon.setImageTintList(ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.text_primary)));
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

        // Apply saved order (if any) before setting up drag handlers
        applySavedOrder(root);

        // Enable long-press drag to swap tiles without changing their click behavior
        for (int tileId : tileIds) {
            View tile = root.findViewById(tileId);
            if (tile == null) continue;

            // Long press starts drag; pass the view as local state
            tile.setOnLongClickListener(v -> {
                CharSequence label = "menu_tile";
                ClipData.Item item = new ClipData.Item(String.valueOf(tileId));
                String[] mimeTypes = {ClipDescription.MIMETYPE_TEXT_PLAIN};
                ClipData dragData = new ClipData(label, mimeTypes, item);
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                // animate to indicate lift
                v.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.95f).setDuration(120).start();
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    v.startDragAndDrop(dragData, shadow, v, 0);
                } else {
                    v.startDrag(dragData, shadow, v, 0);
                }
                return true;
            });

            // Allow swapping when another tile is dropped onto this one
            tile.setOnDragListener((view, event) -> {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        return true;
                    case DragEvent.ACTION_DRAG_ENTERED:
                        view.setAlpha(0.7f);
                        view.animate().scaleX(1.03f).scaleY(1.03f).setDuration(80).start();
                        return true;
                    case DragEvent.ACTION_DRAG_EXITED:
                        view.setAlpha(1f);
                        view.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                        return true;
                    case DragEvent.ACTION_DROP: {
                        Object localState = event.getLocalState();
                        if (localState instanceof View) {
                            View dragged = (View) localState;
                            View target = (View) view;
                            if (dragged != target) {
                                swapTiles(dragged, target);
                                // persist new ordering
                                saveCurrentOrder(root);
                            }
                            // restore animated dragged view
                            dragged.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start();
                        }
                        view.setAlpha(1f);
                        return true;
                    }
                    case DragEvent.ACTION_DRAG_ENDED: {
                        view.setAlpha(1f);
                        // reset any view that was dragged (localState may be present)
                        Object localState = event.getLocalState();
                        if (localState instanceof View) {
                            View dragged = (View) localState;
                            dragged.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start();
                        }
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        return true;
                    }
                    default:
                        return false;
                }
            });
        }
    }

    private void swapTiles(View a, View b) {
        try {
            if (a == null || b == null) return;
            ViewGroup parentA = (ViewGroup) a.getParent();
            ViewGroup parentB = (ViewGroup) b.getParent();
            if (parentA == null || parentB == null) return;

            int indexA = parentA.indexOfChild(a);
            int indexB = parentB.indexOfChild(b);

            // Remove both first to avoid index shifts
            parentA.removeView(a);
            parentB.removeView(b);

            if (parentA == parentB) {
                // Same parent: insert in swapped order
                if (indexA < indexB) {
                    parentA.addView(b, indexA);
                    parentA.addView(a, indexB);
                } else {
                    parentA.addView(a, indexB);
                    parentA.addView(b, indexA);
                }
            } else {
                // Different parents: restore each at the other's index
                parentA.addView(b, indexA);
                parentB.addView(a, indexB);
            }
        } catch (Exception e) {
            // Fail silently to avoid breaking menu
        }
        // After a swap, persist the new order if we have the root reference
        if (this.menuRoot != null) {
            saveCurrentOrder(this.menuRoot);
        }
    }

    private View menuRoot;

    private void saveCurrentOrder(View root) {
        try {
            if (root == null) return;
            SharedPreferences prefs = root.getContext().getSharedPreferences("audio_player_prefs", Context.MODE_PRIVATE);
            List<String> names = new ArrayList<>();
            int[] rowIds = new int[] { R.id.menu_row_1, R.id.menu_row_2, R.id.menu_row_3, R.id.menu_row_4 };
            for (int rowId : rowIds) {
                ViewGroup row = root.findViewById(rowId);
                if (row == null) continue;
                for (int i = 0; i < row.getChildCount(); i++) {
                    View child = row.getChildAt(i);
                    if (child == null) continue;
                    int cid = child.getId();
                    if (cid == View.NO_ID) continue;
                    String name = root.getContext().getResources().getResourceEntryName(cid);
                    names.add(name);
                }
            }
            String joined = TextUtils.join(",", names);
            prefs.edit().putString("menu_tile_order", joined).apply();
        } catch (Exception ignored) {}
    }

    private void applySavedOrder(View root) {
        try {
            SharedPreferences prefs = root.getContext().getSharedPreferences("audio_player_prefs", Context.MODE_PRIVATE);
            String saved = prefs.getString("menu_tile_order", null);
            if (saved == null) return;

            List<String> names = Arrays.asList(saved.split(","));
            List<View> ordered = new ArrayList<>();
            for (String name : names) {
                int id = root.getContext().getResources().getIdentifier(name, "id", root.getContext().getPackageName());
                if (id == 0) continue;
                View v = root.findViewById(id);
                if (v != null) ordered.add(v);
            }
            if (ordered.isEmpty()) return;

            // Clear rows and re-add in saved order, 4 items per row
            int[] rowIds = new int[] { R.id.menu_row_1, R.id.menu_row_2, R.id.menu_row_3, R.id.menu_row_4 };
            int perRow = 4;
            int idx = 0;
            for (int rowId : rowIds) {
                ViewGroup row = root.findViewById(rowId);
                if (row == null) continue;
                row.removeAllViews();
                for (int i = 0; i < perRow && idx < ordered.size(); i++, idx++) {
                    View v = ordered.get(idx);
                    // Detach from old parent if necessary
                    ViewGroup old = (ViewGroup) v.getParent();
                    if (old != null) old.removeView(v);
                    row.addView(v);
                }
            }
            // Any remaining tiles append to last row
            while (idx < ordered.size()) {
                View v = ordered.get(idx++);
                ViewGroup lastRow = root.findViewById(rowIds[rowIds.length - 1]);
                if (lastRow != null) {
                    ViewGroup old = (ViewGroup) v.getParent();
                    if (old != null) old.removeView(v);
                    lastRow.addView(v);
                }
            }
        } catch (Exception ignored) {}
    }

    private int dpToPx(int dp) {
        return Math.round(dp * requireContext().getResources().getDisplayMetrics().density);
    }
}
