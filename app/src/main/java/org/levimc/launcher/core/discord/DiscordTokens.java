package org.levimc.launcher.core.discord;

import com.google.gson.annotations.SerializedName;

public class DiscordTokens {

    @SerializedName("access_token")
    public String accessToken;

    @SerializedName("refresh_token")
    public String refreshToken;

    @SerializedName("token_type")
    public String tokenType;

    @SerializedName("expires_in")
    public long expiresIn;

    public long obtainedAt;

    public boolean isExpired() {
        return System.currentTimeMillis() > obtainedAt + expiresIn * 1000 - EXPIRY_SKEW_MS;
    }

    public String bearerToken() {
        String type = tokenType == null || tokenType.isEmpty() ? "Bearer" : tokenType;
        return type + " " + accessToken;
    }

    private static final long EXPIRY_SKEW_MS = 60000;
}
