package com.example.aud_player;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class AudioListActivity extends AppCompatActivity {

    /** Multi-select mode: pick several songs, confirm, return them all. */
    public static final String EXTRA_MULTI_SELECT = "extra_multi_select";
    /** Result extra: the picked URIs (ArrayList&lt;Uri&gt;) in multi-select mode. */
    public static final String EXTRA_PICKED_URIS = "extra_picked_uris";

    private RecyclerView recyclerView;
    private View emptyView;
    private TextView emptyText;
    private EditText searchEditText;
    private View clearSearchButton, searchContainer;
    private View confirmBar;
    private TextView selectedCountText, confirmButton;

    /** The whole MediaStore library (never filtered). */
    private final List<AudioFile> audioFiles = new ArrayList<>();
    /** What the list currently shows: audioFiles, filtered by the search box. */
    private final List<AudioFile> shownFiles = new ArrayList<>();
    private AudioAdapter adapter;

    private boolean isSelectingSecondFile = false;
    /** true when the caller wants several songs at once (editor Merge tab). */
    private boolean multiSelect = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_list);

        recyclerView = findViewById(R.id.audioRecyclerView);
        emptyView = findViewById(R.id.emptyView);
        emptyText = findViewById(R.id.emptyText);
        searchEditText = findViewById(R.id.audioListSearchEditText);
        clearSearchButton = findViewById(R.id.audioListClearSearch);
        searchContainer = findViewById(R.id.audioListSearchContainer);
        confirmBar = findViewById(R.id.audioListConfirmBar);
        selectedCountText = findViewById(R.id.audioListSelectedCount);
        confirmButton = findViewById(R.id.audioListConfirmBtn);

        // Get intent extras to determine if we're selecting primary or secondary audio
        if (getIntent() != null) {
            isSelectingSecondFile = getIntent().getBooleanExtra("select_second_file", false);
            multiSelect = getIntent().getBooleanExtra(EXTRA_MULTI_SELECT, false);
        }

        // Setup RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // One adapter for the whole screen: the search box re-fills its list
        // instead of rebuilding it (keeps scroll state and avoids re-inflating)
        adapter = new AudioAdapter(audioFiles);
        adapter.setOnItemClickListener(audioFile -> {
            // Return the selected file to the caller (MainActivity, or the
            // audio editor's "+" button)
            Intent resultIntent = new Intent();
            resultIntent.setData(audioFile.getUri());
            setResult(RESULT_OK, resultIntent);
            finish();
        });
        recyclerView.setAdapter(adapter);

        setupSearch();
        setupMultiSelect();

        // Load audio files
        loadAudioFiles();
    }

    /** Merge picker: rows toggle a selection and the confirm bar adds them all. */
    private void setupMultiSelect() {
        if (!multiSelect) return;
        adapter.setSelectionMode(true);
        adapter.setOnSelectionChangedListener(count -> {
            selectedCountText.setText(count == 0
                    ? "No songs selected"
                    : count + (count == 1 ? " song selected" : " songs selected"));
            confirmButton.setEnabled(count > 0);
            confirmButton.setAlpha(count > 0 ? 1f : 0.45f);
        });
        confirmBar.setVisibility(View.VISIBLE);
        selectedCountText.setText("No songs selected");
        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.45f);
        confirmButton.setOnClickListener(v -> {
            List<Uri> picked = adapter.getSelectedUris();
            if (picked.isEmpty()) return;
            Intent resultIntent = new Intent();
            resultIntent.putParcelableArrayListExtra(EXTRA_PICKED_URIS,
                    new ArrayList<>(picked));
            // the first pick also travels as the plain data URI, so a caller
            // that only knows the single-pick contract still works
            resultIntent.setData(picked.get(0));
            setResult(RESULT_OK, resultIntent);
            finish();
        });
    }

    /** Wire the search box: live filtering as the user types. */
    private void setupSearch() {
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
        clearSearchButton.setOnClickListener(v -> searchEditText.setText(""));
        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private void loadAudioFiles() {
        // Query for all audio files
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.ARTIST
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

        Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                MediaStore.Audio.Media.TITLE + " ASC");

        if (cursor != null) {
            audioFiles.clear();

            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
            int artistColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String title = cursor.getString(titleColumn);
                long duration = cursor.getLong(durationColumn);
                long size = cursor.getLong(sizeColumn);
                long dateAdded = cursor.getLong(dateAddedColumn);

                // Try to get display name with extension
                String displayName = title;
                int displayNameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
                if (displayNameColumn != -1) {
                    String fullName = cursor.getString(displayNameColumn);
                    if (fullName != null && !fullName.isEmpty()) {
                        displayName = fullName;
                    }
                }

                String durationFormatted = formatDuration(duration);

                Uri contentUri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                // Use display name instead of title
                AudioFile audioFile = new AudioFile(
                        displayName, durationFormatted, contentUri, id, size, dateAdded);
                audioFile.setArtist(artistColumn >= 0 ? cursor.getString(artistColumn) : null);
                audioFiles.add(audioFile);
            }
            cursor.close();
        }

        // A search box is pointless with nothing to search
        searchContainer.setVisibility(audioFiles.isEmpty() ? View.GONE : View.VISIBLE);
        applyFilter();
    }

    /**
     * Fill the list with the songs matching the search box (all of them when
     * it is empty), and keep the empty view in sync.
     */
    private void applyFilter() {
        String query = searchEditText.getText().toString().trim()
                .toLowerCase(Locale.ROOT);

        shownFiles.clear();
        if (query.isEmpty()) {
            shownFiles.addAll(audioFiles);
        } else {
            for (AudioFile f : audioFiles) {
                // same matching as the main library list
                if (f.matchesSearch(query)) shownFiles.add(f);
            }
        }

        adapter.updateList(shownFiles);
        // matched words are highlighted in the titles, like the main list
        adapter.setSearchQuery(query);
        clearSearchButton.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);

        if (shownFiles.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyText.setText(audioFiles.isEmpty()
                    ? getString(R.string.no_audio_files)
                    : "No song matches \"" + searchEditText.getText().toString().trim() + "\"");
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
            if (!query.isEmpty()) {
                // results start at the top, not where the user had scrolled to
                recyclerView.scrollToPosition(0);
            }
        }
    }

    private String formatDuration(long milliseconds) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(minutes);
        return String.format("%d:%02d", minutes, seconds);
    }
} 