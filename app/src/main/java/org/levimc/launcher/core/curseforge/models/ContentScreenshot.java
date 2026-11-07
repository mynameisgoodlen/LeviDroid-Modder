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

    public String fullUrl() {
        if (imageUrl != null) return imageUrl;
        if (url != null) return url;
        return thumbnailUrl;
    }
}
