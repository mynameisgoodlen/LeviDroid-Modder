package org.levimc.launcher.core.discord;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class DiscordOAuth {

    public static final String AUTHORIZE_URL = "https://discord.com/oauth2/authorize";
    public static final String TOKEN_URL = "https://discord.com/api/oauth2/token";
    public static final String USERS_ME_URL = "https://discord.com/api/users/@me";
    public static final String USER_AGENT = "Discord Embedded/1.9.15780";
    public static final String SCOPE = "openid sdk.social_layer_presence";

    private static final Gson GSON = new Gson();

    private DiscordOAuth() {
    }

    public static String newState() {
        return UUID.randomUUID().toString();
    }

    public static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String codeChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String buildAuthorizeUrl(String clientId, String redirectUri, String codeChallenge, String state) {
        return AUTHORIZE_URL
                + "?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&scope=" + encode(SCOPE)
                + "&code_challenge=" + encode(codeChallenge)
                + "&code_challenge_method=S256"
                + "&state=" + encode(state);
    }

    public static DiscordTokens exchangeCode(OkHttpClient client, String clientId, String redirectUri, String code, String codeVerifier) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("code_verifier", codeVerifier)
                .add("redirect_uri", redirectUri)
                .build();
        return postToken(client, body);
    }

    public static DiscordTokens refreshToken(OkHttpClient client, String clientId, String refreshToken) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build();
        return postToken(client, body);
    }

    private static DiscordTokens postToken(OkHttpClient client, RequestBody body) throws IOException {
        Request request = new Request.Builder()
                .url(TOKEN_URL)
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Token request failed: " + response.code() + " " + text);
            }
            DiscordTokens tokens = GSON.fromJson(text, DiscordTokens.class);
            if (tokens == null || tokens.accessToken == null || tokens.refreshToken == null) {
                throw new IOException("Malformed token response");
            }
            tokens.obtainedAt = System.currentTimeMillis();
            return tokens;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
