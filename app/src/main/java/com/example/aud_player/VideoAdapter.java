package com.example.aud_player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.collection.LruCache;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    private final List<VideoFile> videoFiles = new ArrayList<>();
    private OnItemClickListener listener;
    private boolean selectionMode = false;
    private final Set<String> selectedUris = new LinkedHashSet<>();
    private OnSelectionChangedListener selectionListener;
    private String searchQuery = "";

    private final ExecutorService thumbExecutor = Executors.newSingleThreadExecutor();
    private final LruCache<Uri, Bitmap> thumbCache = new LruCache<Uri, Bitmap>(60) {
        @Override
        protected int sizeOf(@NonNull Uri key, @NonNull Bitmap value) {
            return 1;
        }
    };

    public interface OnItemClickListener {
        void onItemClick(VideoFile videoFile);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(VideoFile videoFile);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount);
    }

    public VideoAdapter(List<VideoFile> files) {
        if (files != null) {
            this.videoFiles.addAll(files);
        }
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.longClickListener = listener;
    }

    private OnItemLongClickListener longClickListener;

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionListener = listener;
    }

    public void setSelectionMode(boolean enabled) {
        if (this.selectionMode != enabled) {
            this.selectionMode = enabled;
            if (!enabled) {
                selectedUris.clear();
                if (selectionListener != null) selectionListener.onSelectionChanged(0);
            }
            notifyDataSetChanged();
        }
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void toggleSelection(int position) {
        if (position < 0 || position >= videoFiles.size()) return;
        String uriStr = videoFiles.get(position).getUri().toString();
        if (selectedUris.contains(uriStr)) {
            selectedUris.remove(uriStr);
        } else {
            selectedUris.add(uriStr);
        }
        notifyItemChanged(position);
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selectedUris.size());
        }
    }

    public int getSelectedCount() {
        return selectedUris.size();
    }

    public ArrayList<Uri> getSelectedUris() {
        ArrayList<Uri> list = new ArrayList<>();
        for (String s : selectedUris) {
            list.add(Uri.parse(s));
        }
        return list;
    }

    public void setSearchQuery(String query) {
        this.searchQuery = query != null ? query.toLowerCase(Locale.ROOT) : "";
        notifyDataSetChanged();
    }

    public void updateList(List<VideoFile> newList) {
        this.videoFiles.clear();
        if (newList != null) {
            this.videoFiles.addAll(newList);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoFile file = videoFiles.get(position);
        boolean isSelected = selectedUris.contains(file.getUri().toString());

        // Title with highlight
        holder.titleText.setText(highlightSearchMatch(file.getTitle(), searchQuery, holder.itemView.getContext()));

        // Subtitle (duration • size)
        String subtitle = file.getDuration() + "  •  " + file.getFormattedSize();
        holder.subtitleText.setText(subtitle);

        if (selectionMode) {
            holder.checkOrOptions.setVisibility(View.VISIBLE);
            holder.checkOrOptions.setImageResource(
                    isSelected ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
            holder.checkOrOptions.setColorFilter(
                    isSelected ? ContextCompat.getColor(holder.itemView.getContext(), R.color.accent_primary2)
                            : ContextCompat.getColor(holder.itemView.getContext(), R.color.text_tertiary));
            holder.innerLayout.setBackgroundResource(
                    isSelected ? R.drawable.bg_song_item_playing : R.drawable.bg_song_item);
        } else {
            holder.checkOrOptions.setVisibility(View.GONE);
            holder.innerLayout.setBackgroundResource(R.drawable.bg_song_item);
        }

        holder.innerLayout.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (selectionMode) {
                toggleSelection(pos);
            } else if (listener != null) {
                listener.onItemClick(videoFiles.get(pos));
            }
        });

        holder.innerLayout.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    longClickListener.onItemLongClick(videoFiles.get(pos));
                    return true;
                }
            }
            return false;
        });

        // Thumbnail
        loadThumbnail(holder, file.getUri());
    }

    private void loadThumbnail(VideoViewHolder holder, Uri uri) {
        Bitmap cached = thumbCache.get(uri);
        if (cached != null) {
            holder.thumbnailView.setImageBitmap(cached);
            holder.thumbnailView.setVisibility(View.VISIBLE);
            holder.defaultIcon.setVisibility(View.GONE);
            return;
        }

        holder.thumbnailView.setVisibility(View.GONE);
        holder.defaultIcon.setVisibility(View.VISIBLE);

        Context ctx = holder.itemView.getContext().getApplicationContext();
        thumbExecutor.execute(() -> {
            Bitmap thumb = null;
            try {
                if ("file".equals(uri.getScheme())) {
                    // Plain paths (e.g. processed videos under aud_player/) are
                    // not MediaStore rows, so decode a frame from the file.
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(uri.getPath());
                    thumb = mmr.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    mmr.release();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    thumb = ctx.getContentResolver().loadThumbnail(uri, new Size(96, 96), null);
                } else {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(ctx, uri);
                    thumb = mmr.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    mmr.release();
                }
            } catch (Throwable ignored) {
            }

            if (thumb != null) {
                thumbCache.put(uri, thumb);
                final Bitmap finalThumb = thumb;
                holder.itemView.post(() -> {
                    int pos = holder.getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && pos < videoFiles.size()
                            && videoFiles.get(pos).getUri().equals(uri)) {
                        holder.thumbnailView.setImageBitmap(finalThumb);
                        holder.thumbnailView.setVisibility(View.VISIBLE);
                        holder.defaultIcon.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    private static CharSequence highlightSearchMatch(String text, String query, Context context) {
        if (text == null || query == null || query.isEmpty()) return text;
        String lower = text.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(query);
        if (idx < 0) return text;
        SpannableString ss = new SpannableString(text);
        int highlightColor = ContextCompat.getColor(context, R.color.accent_primary);
        ss.setSpan(new ForegroundColorSpan(highlightColor), idx, idx + query.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new StyleSpan(Typeface.BOLD), idx, idx + query.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    @Override
    public int getItemCount() {
        return videoFiles.size();
    }

    static class VideoViewHolder extends RecyclerView.ViewHolder {
        final View innerLayout;
        final ImageView defaultIcon;
        final ImageView thumbnailView;
        final TextView titleText;
        final TextView subtitleText;
        final ImageView checkOrOptions;

        VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            innerLayout = itemView.findViewById(R.id.innerLayout);
            defaultIcon = itemView.findViewById(R.id.videoDefaultIcon);
            thumbnailView = itemView.findViewById(R.id.videoThumbnail);
            titleText = itemView.findViewById(R.id.videoTitle);
            subtitleText = itemView.findViewById(R.id.videoSubtitle);
            checkOrOptions = itemView.findViewById(R.id.videoOptionsOrCheck);
        }
    }
}
