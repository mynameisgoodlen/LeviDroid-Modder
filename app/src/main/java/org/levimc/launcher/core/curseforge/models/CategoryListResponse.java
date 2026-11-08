package org.levimc.launcher.core.curseforge.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class CategoryListResponse {
    @SerializedName("data")
    public List<ContentCategory> data;
}
