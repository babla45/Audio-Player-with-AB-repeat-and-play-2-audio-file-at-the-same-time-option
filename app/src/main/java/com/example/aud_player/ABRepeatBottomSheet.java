package com.example.aud_player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

public class ABRepeatBottomSheet extends BottomSheetDialogFragment {

    public interface ABRepeatListener {
        void onSetPointA();
        void onSetPointB();
        void onClearABPoints();
        void onToggleABRepeat();
        int getPointA();
        int getPointB();
        boolean isABRepeatActive();
    }

    private ABRepeatListener listener;

    public void setABRepeatListener(ABRepeatListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_ab_repeat, container, false);

        TextView statusBadge = view.findViewById(R.id.ab_status_badge);
        TextView pointAValue = view.findViewById(R.id.ab_point_a_value);
        TextView pointBValue = view.findViewById(R.id.ab_point_b_value);
        MaterialButton toggleBtn = view.findViewById(R.id.ab_toggle_btn);

        if (listener != null) {
            // Update status
            boolean active = listener.isABRepeatActive();
            statusBadge.setText(active ? "ON" : "OFF");
            statusBadge.setTextColor(getResources().getColor(active ? R.color.success : R.color.text_tertiary, null));
            toggleBtn.setText(active ? "Disable" : "Enable");

            // Show point values
            int pointA = listener.getPointA();
            int pointB = listener.getPointB();
            pointAValue.setText(pointA >= 0 ? formatTime(pointA) : "--:--");
            pointBValue.setText(pointB >= 0 ? formatTime(pointB) : "--:--");
        }

        view.findViewById(R.id.ab_set_a_btn).setOnClickListener(v -> {
            if (listener != null) {
                listener.onSetPointA();
                // Update display
                int pointA = listener.getPointA();
                pointAValue.setText(pointA >= 0 ? formatTime(pointA) : "--:--");
            }
        });

        view.findViewById(R.id.ab_set_b_btn).setOnClickListener(v -> {
            if (listener != null) {
                listener.onSetPointB();
                int pointB = listener.getPointB();
                pointBValue.setText(pointB >= 0 ? formatTime(pointB) : "--:--");
            }
        });

        view.findViewById(R.id.ab_clear_btn).setOnClickListener(v -> {
            if (listener != null) listener.onClearABPoints();
            dismiss();
        });

        view.findViewById(R.id.ab_toggle_btn).setOnClickListener(v -> {
            if (listener != null) listener.onToggleABRepeat();
            dismiss();
        });

        return view;
    }

    private String formatTime(int milliseconds) {
        int seconds = (milliseconds / 1000) % 60;
        int minutes = (milliseconds / (1000 * 60)) % 60;
        int hours = milliseconds / (1000 * 60 * 60);
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }
}
