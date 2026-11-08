package org.levimc.launcher.core.curseforge.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class VersionListResponse {
    @SerializedName("data")
    public List<ContentGameVersion> data;
}
