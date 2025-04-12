package com.example.aud_player;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder> {

    private List<Playlist> playlists;
    private OnPlaylistClickListener listener;
    private OnPlaylistOptionsClickListener optionsListener;

    public PlaylistAdapter(List<Playlist> playlists) {
        this.playlists = playlists;
    }

    public interface OnPlaylistClickListener {
        void onPlaylistClick(Playlist playlist);
    }

    public interface OnPlaylistOptionsClickListener {
        void onRenamePlaylistClick(Playlist playlist);
        void onDeletePlaylistClick(Playlist playlist);
    }

    public void setOnPlaylistClickListener(OnPlaylistClickListener listener) {
        this.listener = listener;
    }

    public void setOnPlaylistOptionsClickListener(OnPlaylistOptionsClickListener listener) {
        this.optionsListener = listener;
    }

    @NonNull
    @Override
    public PlaylistViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playlist, parent, false);
        return new PlaylistViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlaylistViewHolder holder, int position) {
        Playlist playlist = playlists.get(position);
        holder.playlistNameTextView.setText(playlist.getName());
        
        // Display song count
        String songCountText = playlist.getSize() + " song" + (playlist.getSize() != 1 ? "s" : "");
        holder.songCountTextView.setText(songCountText);
        
        // Format and display creation date
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault());
        String dateCreated = "Created on " + dateFormat.format(new Date(playlist.getDateCreated()));
        holder.dateCreatedTextView.setText(dateCreated);
        
        // Set click listener
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPlaylistClick(playlist);
            }
        });
        
        // Set options menu click listener
        holder.optionsButton.setOnClickListener(v -> {
            showOptionsPopup(v, playlist);
        });
    }

    private void showOptionsPopup(View view, Playlist playlist) {
        Context context = view.getContext();
        android.widget.PopupMenu popup = new android.widget.PopupMenu(context, view);
        popup.inflate(R.menu.playlist_options_menu);
        
        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_rename_playlist) {
                if (optionsListener != null) {
                    optionsListener.onRenamePlaylistClick(playlist);
                }
                return true;
            } else if (itemId == R.id.menu_delete_playlist) {
                if (optionsListener != null) {
                    optionsListener.onDeletePlaylistClick(playlist);
                }
                return true;
            }
            return false;
        });
        
        popup.show();
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    public void updatePlaylists(List<Playlist> newPlaylists) {
        this.playlists = newPlaylists;
        notifyDataSetChanged();
    }

    static class PlaylistViewHolder extends RecyclerView.ViewHolder {
        TextView playlistNameTextView;
        TextView songCountTextView;
        TextView dateCreatedTextView;
        ImageView optionsButton;

        PlaylistViewHolder(View itemView) {
            super(itemView);
            playlistNameTextView = itemView.findViewById(R.id.playlist_name);
            songCountTextView = itemView.findViewById(R.id.playlist_song_count);
            dateCreatedTextView = itemView.findViewById(R.id.playlist_date_created);
            optionsButton = itemView.findViewById(R.id.playlist_options);
        }
    }
} 