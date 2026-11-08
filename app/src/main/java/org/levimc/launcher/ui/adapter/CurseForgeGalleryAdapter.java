package org.levimc.launcher.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.levimc.launcher.R;
import org.levimc.launcher.core.curseforge.models.ContentScreenshot;

import java.util.ArrayList;
import java.util.List;

public class CurseForgeGalleryAdapter extends RecyclerView.Adapter<CurseForgeGalleryAdapter.ViewHolder> {

    private List<ContentScreenshot> screenshots = new ArrayList<>();
    private final OnScreenshotClickListener listener;

    public interface OnScreenshotClickListener {
        void onScreenshotClick(int position);
    }

    public CurseForgeGalleryAdapter(OnScreenshotClickListener listener) {
        this.listener = listener;
    }

    public List<ContentScreenshot> getScreenshots() {
        return screenshots;
    }

    public void setScreenshots(List<ContentScreenshot> items) {
        this.screenshots = items != null ? items : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_curseforge_screenshot, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ContentScreenshot screenshot = screenshots.get(position);
        String thumb = screenshot.thumbnailUrl != null ? screenshot.thumbnailUrl : screenshot.fullUrl();
        Glide.with(holder.image)
                .asBitmap()
                .load(thumb)
                .centerCrop()
                .placeholder(R.drawable.bg_cf_icon)
                .error(R.drawable.bg_cf_icon)
                .into(holder.image);
        holder.videoBadge.setVisibility(screenshot.isVideo() ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(v -> listener.onScreenshotClick(position));
    }

    @Override
    public int getItemCount() {
        return screenshots.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView image;
        ImageView videoBadge;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.screenshot_image);
            videoBadge = itemView.findViewById(R.id.screenshot_video_badge);
        }
    }
}
