package com.example.aud_player;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class VideoListActivity extends AppCompatActivity {

    public static final String EXTRA_MULTI_SELECT = "extra_multi_select";
    public static final String EXTRA_PICKED_URIS = "extra_picked_uris";

    private RecyclerView recyclerView;
    private View emptyView;
    private TextView emptyText;
    private EditText searchEditText;
    private View clearSearchButton;
    private View confirmBar;
    private TextView selectedCountText;
    private TextView confirmButton;

    private final List<VideoFile> allVideos = new ArrayList<>();
    private final List<VideoFile> shownVideos = new ArrayList<>();
    private VideoAdapter adapter;
    private boolean multiSelect = false;

    private final ActivityResultLauncher<Intent> browsePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (multiSelect) {
                        ArrayList<Uri> uris = new ArrayList<>();
                        if (data.getClipData() != null) {
                            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                                uris.add(data.getClipData().getItemAt(i).getUri());
                            }
                        } else if (data.getData() != null) {
                            uris.add(data.getData());
                        }
                        if (!uris.isEmpty()) {
                            Intent resultIntent = new Intent();
                            resultIntent.putParcelableArrayListExtra(EXTRA_PICKED_URIS, uris);
                            resultIntent.setData(uris.get(0));
                            setResult(RESULT_OK, resultIntent);
                            finish();
                        }
                    } else {
                        Uri uri = data.getData();
                        if (uri != null) {
                            Intent resultIntent = new Intent();
                            resultIntent.setData(uri);
                            setResult(RESULT_OK, resultIntent);
                            finish();
                        }
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_list);

        if (getIntent() != null) {
            multiSelect = getIntent().getBooleanExtra(EXTRA_MULTI_SELECT, false);
        }

        recyclerView = findViewById(R.id.videoRecyclerView);
        emptyView = findViewById(R.id.videoEmptyView);
        emptyText = findViewById(R.id.videoEmptyText);
        searchEditText = findViewById(R.id.videoListSearchEditText);
        clearSearchButton = findViewById(R.id.videoListClearSearch);
        confirmBar = findViewById(R.id.videoListConfirmBar);
        selectedCountText = findViewById(R.id.videoListSelectedCount);
        confirmButton = findViewById(R.id.videoListConfirmBtn);
        View browseBtn = findViewById(R.id.videoListBrowseBtn);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VideoAdapter(allVideos);
        adapter.setOnItemClickListener(videoFile -> {
            Intent resultIntent = new Intent();
            resultIntent.setData(videoFile.getUri());
            setResult(RESULT_OK, resultIntent);
            finish();
        });
        recyclerView.setAdapter(adapter);

        if (browseBtn != null) {
            browseBtn.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("video/*");
                if (multiSelect) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                browsePickerLauncher.launch(intent);
            });
        }

        setupSearch();
        setupMultiSelect();
        loadVideoFiles();
    }

    private void setupMultiSelect() {
        if (!multiSelect) return;
        adapter.setSelectionMode(true);
        confirmBar.setVisibility(View.VISIBLE);
        adapter.setOnSelectionChangedListener(count -> {
            selectedCountText.setText(count == 0 ? "No videos selected"
                    : count + (count == 1 ? " video selected" : " videos selected"));
            confirmButton.setEnabled(count > 0);
            confirmButton.setAlpha(count > 0 ? 1.0f : 0.45f);
        });

        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.45f);
        confirmButton.setOnClickListener(v -> {
            ArrayList<Uri> uris = adapter.getSelectedUris();
            if (uris.isEmpty()) return;
            Intent intent = new Intent();
            intent.putParcelableArrayListExtra(EXTRA_PICKED_URIS, uris);
            intent.setData(uris.get(0));
            setResult(RESULT_OK, intent);
            finish();
        });
    }

    private void setupSearch() {
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s == null ? "" : s.toString().trim();
                clearSearchButton.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                filterVideos(query);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        clearSearchButton.setOnClickListener(v -> {
            searchEditText.setText("");
            filterVideos("");
        });
    }

    private void filterVideos(String query) {
        shownVideos.clear();
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            shownVideos.addAll(allVideos);
        } else {
            for (VideoFile f : allVideos) {
                if (f.matchesSearch(lower)) {
                    shownVideos.add(f);
                }
            }
        }
        adapter.setSearchQuery(query);
        adapter.updateList(shownVideos);
        updateEmptyState(query);
    }

    private void updateEmptyState(String query) {
        if (shownVideos.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            if (allVideos.isEmpty()) {
                emptyText.setText("No video files found on device");
            } else {
                emptyText.setText("No video matches \"" + query + "\"");
            }
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void loadVideoFiles() {
        allVideos.clear();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DATA
        };

        String sortOrder = MediaStore.Video.Media.DATE_ADDED + " DESC";
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder)) {

            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE);
                int nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                int durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
                int sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE);
                int dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED);
                int dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String title = titleCol >= 0 ? cursor.getString(titleCol) : null;
                    if (title == null || title.trim().isEmpty()) {
                        title = nameCol >= 0 ? cursor.getString(nameCol) : "video";
                    }
                    long duration = durCol >= 0 ? cursor.getLong(durCol) : 0;
                    long size = sizeCol >= 0 ? cursor.getLong(sizeCol) : 0;
                    long dateAdded = dateCol >= 0 ? cursor.getLong(dateCol) : 0;
                    String path = dataCol >= 0 ? cursor.getString(dataCol) : null;

                    Uri contentUri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);

                    allVideos.add(new VideoFile(title, duration, contentUri, id, size, dateAdded, path));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not load video library", Toast.LENGTH_SHORT).show();
        }

        shownVideos.clear();
        shownVideos.addAll(allVideos);
        adapter.updateList(shownVideos);
        updateEmptyState("");
    }
}
