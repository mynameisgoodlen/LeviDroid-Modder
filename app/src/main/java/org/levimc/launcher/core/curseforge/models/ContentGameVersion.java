package org.levimc.launcher.core.curseforge.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class ContentGameVersion {
    @SerializedName("id")
    public int id;
    @SerializedName("versions")
    public List<String> versions;
}
