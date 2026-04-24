package com.example.aud_player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class SortBottomSheet extends BottomSheetDialogFragment {

    public interface SortListener {
        void onSortSelected(int sortOrder);
        int getCurrentSortOrder();
    }

    private SortListener listener;

    public void setSortListener(SortListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_sort, container, false);

        RadioGroup radioGroup = view.findViewById(R.id.sort_radio_group);

        // Set current selection
        if (listener != null) {
            int currentSort = listener.getCurrentSortOrder();
            int[] radioIds = {
                R.id.sort_name_asc, R.id.sort_name_desc,
                R.id.sort_duration_asc, R.id.sort_duration_desc,
                R.id.sort_date_asc, R.id.sort_date_desc,
                R.id.sort_size_asc, R.id.sort_size_desc
            };
            if (currentSort >= 0 && currentSort < radioIds.length) {
                RadioButton btn = view.findViewById(radioIds[currentSort]);
                btn.setChecked(true);
            }
        }

        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int sortOrder = -1;
            if (checkedId == R.id.sort_name_asc) sortOrder = 0;
            else if (checkedId == R.id.sort_name_desc) sortOrder = 1;
            else if (checkedId == R.id.sort_duration_asc) sortOrder = 2;
            else if (checkedId == R.id.sort_duration_desc) sortOrder = 3;
            else if (checkedId == R.id.sort_date_asc) sortOrder = 4;
            else if (checkedId == R.id.sort_date_desc) sortOrder = 5;
            else if (checkedId == R.id.sort_size_asc) sortOrder = 6;
            else if (checkedId == R.id.sort_size_desc) sortOrder = 7;

            if (sortOrder >= 0 && listener != null) {
                listener.onSortSelected(sortOrder);
            }
            dismiss();
        });

        return view;
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }
}
