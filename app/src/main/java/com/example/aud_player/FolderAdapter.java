package com.example.aud_player;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for the folder view of the song list. Each row shows a folder name
 * (the last path segment of the songs it groups) and the number of songs in it.
 */
public class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.FolderViewHolder> {

    /** A folder grouping of songs. */
    public static class Folder {
        public final String name;
        public final List<AudioFile> songs = new ArrayList<>();

        public Folder(String name) {
            this.name = name;
        }
    }

    private List<Folder> folders = new ArrayList<>();
    private OnFolderClickListener listener;

    public interface OnFolderClickListener {
        void onFolderClick(Folder folder);
    }

    public void setOnFolderClickListener(OnFolderClickListener listener) {
        this.listener = listener;
    }

    /**
     * Groups songs into folders using the MediaStore bucket (parent folder) name.
     * Songs without a resolved folder land in "Others".
     */
    public void updateFolders(List<AudioFile> songs) {
        List<Folder> result = new ArrayList<>();
        java.util.Map<String, Folder> byName = new java.util.LinkedHashMap<>();
        for (AudioFile song : songs) {
            String folderName = resolveFolderName(song);
            Folder folder = byName.get(folderName);
            if (folder == null) {
                folder = new Folder(folderName);
                byName.put(folderName, folder);
                result.add(folder);
            }
            folder.songs.add(song);
        }
        // Sort folders alphabetically for predictable browsing
        result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        this.folders = result;
        notifyDataSetChanged();
    }

    /**
     * Uses the MediaStore BUCKET_DISPLAY_NAME captured at load time. Falls back
     * to the parent path segment of the title (covers manually-built entries),
     * then to "Others".
     */
    private String resolveFolderName(AudioFile song) {
        String bucket = song.getFolderName();
        if (bucket != null && !bucket.isEmpty()) {
            return bucket;
        }
        String title = song.getTitle();
        if (title != null && title.contains("/")) {
            String parent = title.substring(0, title.lastIndexOf('/'));
            if (!parent.isEmpty()) {
                // Use the last segment of the parent path as the folder label
                int slash = parent.lastIndexOf('/');
                return slash >= 0 ? parent.substring(slash + 1) : parent;
            }
        }
        return "Others";
    }

    @NonNull
    @Override
    public FolderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_folder, parent, false);
        return new FolderViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FolderViewHolder holder, int position) {
        Folder folder = folders.get(position);
        holder.folderNameTextView.setText(folder.name);
        holder.songCountTextView.setText(folder.songs.size()
                + (folder.songs.size() == 1 ? " song" : " songs"));
        holder.itemContainer.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFolderClick(folder);
            }
        });
    }

    @Override
    public int getItemCount() {
        return folders.size();
    }

    static class FolderViewHolder extends RecyclerView.ViewHolder {
        TextView folderNameTextView;
        TextView songCountTextView;
        View itemContainer;

        FolderViewHolder(View itemView) {
            super(itemView);
            folderNameTextView = itemView.findViewById(R.id.folderName);
            songCountTextView = itemView.findViewById(R.id.folderSongCount);
            itemContainer = itemView.findViewById(R.id.folderItemContainer);
        }
    }
}
