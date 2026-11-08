package org.levimc.launcher.core.curseforge.models;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

public class ContentScreenshot implements Serializable {
    @SerializedName("id")
    public int id;
    @SerializedName("title")
    public String title;
    @SerializedName("thumbnailUrl")
    public String thumbnailUrl;
    @SerializedName("url")
    public String url;
    @SerializedName("imageUrl")
    public String imageUrl;
    public transient String youtubeId;

    public boolean isVideo() {
        return youtubeId != null;
    }

    public static ContentScreenshot video(String youtubeId, String title) {
        ContentScreenshot screenshot = new ContentScreenshot();
        screenshot.youtubeId = youtubeId;
        screenshot.title = title;
        screenshot.thumbnailUrl = "https://img.youtube.com/vi/" + youtubeId + "/hqdefault.jpg";
        return screenshot;
    }

    public String fullUrl() {
        if (url != null) return url;
        if (imageUrl != null) return imageUrl;
        return thumbnailUrl;
    }
}
