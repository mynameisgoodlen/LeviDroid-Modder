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

import java.util.List;

public class CurseForgeScreenshotThumbAdapter extends RecyclerView.Adapter<CurseForgeScreenshotThumbAdapter.ViewHolder> {

    private final List<ContentScreenshot> screenshots;
    private final OnThumbClickListener listener;
    private int selected = 0;

    public interface OnThumbClickListener {
        void onThumbClick(int position);
    }

    public CurseForgeScreenshotThumbAdapter(List<ContentScreenshot> screenshots, OnThumbClickListener listener) {
        this.screenshots = screenshots;
        this.listener = listener;
    }

    public void setSelected(int position) {
        if (position == selected) return;
        int old = selected;
        selected = position;
        notifyItemChanged(old);
        notifyItemChanged(selected);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_screenshot_thumb, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ContentScreenshot screenshot = screenshots.get(position);
        Glide.with(holder.image)
                .asBitmap()
                .load(screenshot.thumbnailUrl != null ? screenshot.thumbnailUrl : screenshot.fullUrl())
                .centerCrop()
                .placeholder(R.drawable.bg_cf_icon)
                .error(R.drawable.bg_cf_icon)
                .into(holder.image);
        holder.itemView.setSelected(position == selected);
        holder.itemView.setOnClickListener(v -> listener.onThumbClick(position));
    }

    @Override
    public int getItemCount() {
        return screenshots.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView image;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.screenshot_thumb);
        }
    }
}
