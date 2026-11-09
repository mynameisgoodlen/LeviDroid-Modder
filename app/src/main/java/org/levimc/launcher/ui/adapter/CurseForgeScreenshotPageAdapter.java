package org.levimc.launcher.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.levimc.launcher.R;
import org.levimc.launcher.core.curseforge.models.ContentScreenshot;
import org.levimc.launcher.util.UIHelper;

import java.util.ArrayList;
import java.util.List;

public class CurseForgeScreenshotPageAdapter extends RecyclerView.Adapter<CurseForgeScreenshotPageAdapter.ViewHolder> {

    private final List<ContentScreenshot> screenshots;
    private final List<WebView> webViews = new ArrayList<>();

    public CurseForgeScreenshotPageAdapter(List<ContentScreenshot> screenshots) {
        this.screenshots = screenshots;
    }

    public void stopPlayback() {
        for (WebView web : webViews) {
            if (web.getVisibility() == View.VISIBLE) {
                web.loadUrl("about:blank");
            }
        }
    }

    public void destroyWebViews() {
        for (WebView web : webViews) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
        }
        webViews.clear();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_screenshot_page, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ContentScreenshot screenshot = screenshots.get(position);
        if (screenshot.isVideo()) {
            holder.image.setVisibility(View.GONE);
            holder.web.setVisibility(View.VISIBLE);
            if (!webViews.contains(holder.web)) webViews.add(holder.web);
            holder.web.loadDataWithBaseURL("https://www.youtube.com",
                    "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                            + "<style>body{margin:0;background:#000;display:flex;align-items:center;justify-content:center}"
                            + "iframe{width:100%;aspect-ratio:16/9;border:0}</style></head><body>"
                            + "<iframe src=\"https://www.youtube.com/embed/" + screenshot.youtubeId + "?rel=0&playsinline=1\" "
                            + "allowfullscreen allow=\"encrypted-media; picture-in-picture\"></iframe></body></html>",
                    "text/html", "utf-8", null);
            holder.web.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });
        } else {
            if (holder.web.getVisibility() == View.VISIBLE) {
                holder.web.loadUrl("about:blank");
            }
            holder.web.setVisibility(View.GONE);
            holder.image.setVisibility(View.VISIBLE);
            Glide.with(holder.image)
                    .load(screenshot.fullUrl())
                    .placeholder(R.drawable.bg_cf_icon)
                    .error(R.drawable.bg_cf_icon)
                    .into(holder.image);
        }
    }

    @Override
    public int getItemCount() {
        return screenshots.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView image;
        WebView web;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.screenshot_page_image);
            web = itemView.findViewById(R.id.screenshot_page_web);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setLoadsImagesAutomatically(true);
            UIHelper.applyYoutubeCompatibleUserAgent(web);
        }
    }}
