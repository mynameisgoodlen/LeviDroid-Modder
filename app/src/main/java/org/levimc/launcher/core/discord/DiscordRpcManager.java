package org.levimc.launcher.core.discord;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;
import org.levimc.launcher.BuildConfig;
import org.levimc.launcher.R;
import org.levimc.launcher.core.minecraft.MinecraftActivityState;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.settings.FeatureSettings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class DiscordRpcManager {

    public interface LoginCallback {
        void onSuccess(String username);

        void onError(Exception error);
    }

    private static final String TAG = "DiscordRpc";
    private static final String STATUS = "online";
    private static final String LAUNCHER_NAME = "LeviLauncher Modder";
    private static final String LAUNCHER_URL = "https://github.com/sang765/LeviDroid-Modder";
    private static final String GAME_NAME = "Minecraft: Bedrock Edition";
    private static final String GAME_ASSET_KEY = "logo";
    private static final long PRESENCE_DEBOUNCE_MS = 12000;
    private static final long FIRST_RECONNECT_DELAY_MS = 5000;
    private static final long MAX_RECONNECT_DELAY_MS = 60000;
    private static final long GAME_POLL_INTERVAL_MS = 5000;
    private static final long SERVER_HINT_TTL_MS = 300000;

    public static final String SCREEN_INSTANCES = "instances";
    public static final String SCREEN_CONTENT = "content";
    public static final String SCREEN_CURSEFORGE = "curseforge";
    public static final String SCREEN_FRIENDS = "friends";
    public static final String SCREEN_GAME = "game";

    private static final DiscordRpcManager INSTANCE = new DiscordRpcManager();

    public static DiscordRpcManager getInstance() {
        return INSTANCE;
    }

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "DiscordRpc");
        thread.setDaemon(true);
        return thread;
    });
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Context appContext;
    private LoopbackCallbackServer callbackServer;
    private LoginCallback pendingLoginCallback;
    private String pendingState;
    private String pendingVerifier;
    private String pendingRedirectUri;
    private volatile boolean loginFlowActive;

    private DiscordTokens tokens;
    private volatile String username;
    private DiscordGateway gateway;
    private String activeScreenId;
    private volatile String serverHint;
    private volatile long serverHintAt;
    private long gameStartTimestamp;
    private ScheduledFuture<?> gamePollFuture;
    private int reconnectAttempt;
    private ScheduledFuture<?> reconnectFuture;
    private PresencePayload pendingPresence;
    private boolean presenceFlushScheduled;
    private long lastPresenceSentAt;

    private DiscordRpcManager() {
        executor.allowCoreThreadTimeOut(true);
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(BuildConfig.DISCORD_CLIENT_ID);
    }

    public boolean isLoggedIn() {
        return username != null;
    }

    public String getUsername() {
        return username;
    }

    public void beginLogin(Context context, LoginCallback callback) {
        if (!isConfigured()) {
            notifyError(callback, new IllegalStateException("Discord client id is not configured"));
            return;
        }
        if (loginFlowActive) {
            notifyError(callback, new IllegalStateException("Discord login already in progress"));
            return;
        }
        loginFlowActive = true;
        ensureContext(context);
        executor.execute(() -> {
            LoopbackCallbackServer server = new LoopbackCallbackServer();
            try {
                server.start((state, code, error, errorDescription) ->
                        handleOAuthCallback(state, code, error, errorDescription));
            } catch (IOException e) {
                loginFlowActive = false;
                notifyError(callback, e);
                return;
            }
            callbackServer = server;
            pendingState = DiscordOAuth.newState();
            pendingVerifier = DiscordOAuth.generateCodeVerifier();
            pendingLoginCallback = callback;
            pendingRedirectUri = server.getRedirectUri();
            String url = DiscordOAuth.buildAuthorizeUrl(
                    BuildConfig.DISCORD_CLIENT_ID,
                    pendingRedirectUri,
                    DiscordOAuth.codeChallenge(pendingVerifier),
                    pendingState);
            mainHandler.post(() -> openBrowser(url));
        });
    }

    public void cancelLogin() {
        executor.execute(() -> {
            LoginCallback callback = pendingLoginCallback;
            if (callback != null) {
                failLogin(callback, new IOException("Login cancelled"));
            } else {
                stopCallbackServer();
                loginFlowActive = false;
            }
        });
    }

    public void logout(Context context) {
        ensureContext(context);
        executor.execute(() -> {
            disconnectGateway();
            DiscordAuthStore.clear(authDir());
            tokens = null;
            username = null;
            pendingPresence = null;
        });
    }

    public void connectIfLoggedIn(Context context) {
        if (!isEnabled()) return;
        ensureContext(context);
        executor.execute(() -> {
            if (tokens == null) {
                tokens = DiscordAuthStore.load(authDir());
            }
            if (tokens == null) {
                username = null;
                return;
            }
            connectGateway();
        });
    }

    public void updatePresence(PresencePayload presence) {
        if (!isEnabled()) return;
        executor.execute(() -> {
            pendingPresence = presence;
            requestPresenceFlush();
        });
    }

    public void clearPresence() {
        if (!isEnabled()) return;
        executor.execute(() -> {
            pendingPresence = null;
            requestPresenceFlush();
        });
    }

    public void reportScreen(String screenId, int detailsRes) {
        reportScreen(screenId, detailsRes, null);
    }

    public void reportScreen(String screenId, int detailsRes, String state) {
        if (appContext == null || !isEnabled()) return;
        executor.execute(() -> {
            activeScreenId = screenId;
            PresencePayload payload = newLauncherPresence();
            payload.setDetails(appContext.getString(detailsRes));
            if (state != null && !state.isEmpty()) {
                payload.setState(state);
            }
            pendingPresence = payload;
            requestPresenceFlush();
        });
    }

    public void clearScreen(String screenId) {
        executor.execute(() -> {
            if (!screenId.equals(activeScreenId)) return;
            activeScreenId = null;
            pendingPresence = isEnabled() ? buildHomePresence() : null;
            requestPresenceFlush();
        });
    }

    public void onEnabledChanged(Context context) {
        ensureContext(context);
        executor.execute(() -> {
            if (isEnabled()) {
                if (tokens == null) {
                    tokens = DiscordAuthStore.load(authDir());
                }
                if (tokens != null) {
                    connectGateway();
                }
            } else {
                disconnectGateway();
                pendingPresence = null;
                activeScreenId = null;
            }
        });
    }

    public void setGameServerHint(String hint) {
        serverHint = hint;
        serverHintAt = System.currentTimeMillis();
    }

    private boolean isEnabled() {
        return isConfigured() && FeatureSettings.getInstance().isDiscordRpcEnabled();
    }

    private PresencePayload newLauncherPresence() {
        PresencePayload payload = new PresencePayload(BuildConfig.DISCORD_CLIENT_ID);
        payload.setName(LAUNCHER_NAME);
        payload.setLargeImage(GAME_ASSET_KEY);
        payload.setLargeText(LAUNCHER_NAME);
        payload.addButton("Get LeviLauncher", LAUNCHER_URL);
        return payload;
    }

    private PresencePayload newGamePresence() {
        PresencePayload payload = new PresencePayload(BuildConfig.DISCORD_CLIENT_ID);
        payload.setName(GAME_NAME);
        payload.setLargeImage(GAME_ASSET_KEY);
        payload.setLargeText(GAME_NAME);
        payload.setStartTimestamp(gameStartTimestamp);
        String hint = serverHint;
        if (hint != null && System.currentTimeMillis() - serverHintAt > SERVER_HINT_TTL_MS) {
            serverHint = null;
            hint = null;
        }
        String versionLabel = currentVersionLabel();
        if (hint != null) {
            payload.setDetails(appContext.getString(R.string.rpc_playing_on, hint));
        } else if (versionLabel != null) {
            payload.setDetails(appContext.getString(R.string.rpc_playing_instance, versionLabel));
        } else {
            payload.setDetails(GAME_NAME);
        }
        payload.addButton("Get LeviLauncher", LAUNCHER_URL);
        return payload;
    }

    private String currentVersionLabel() {
        GameVersion version = VersionManager.get(appContext).getSelectedVersion();
        if (version == null) return null;
        return version.displayName != null && !version.displayName.isEmpty()
                ? version.displayName : version.directoryName;
    }

    private void startGamePoll() {
        if (gamePollFuture != null) return;
        gamePollFuture = executor.scheduleWithFixedDelay(
                this::pollGameState, GAME_POLL_INTERVAL_MS, GAME_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopGamePoll() {
        if (gamePollFuture != null) {
            gamePollFuture.cancel(false);
            gamePollFuture = null;
        }
    }

    private void pollGameState() {
        if (appContext == null || !isEnabled() || gateway == null) return;
        boolean running = MinecraftActivityState.isRunning();
        if (running && !SCREEN_GAME.equals(activeScreenId)) {
            activeScreenId = SCREEN_GAME;
            gameStartTimestamp = System.currentTimeMillis();
            pendingPresence = newGamePresence();
            requestPresenceFlush();
        } else if (!running && SCREEN_GAME.equals(activeScreenId)) {
            activeScreenId = null;
            serverHint = null;
            pendingPresence = buildHomePresence();
            requestPresenceFlush();
        }
    }

    private PresencePayload buildHomePresence() {
        PresencePayload payload = newLauncherPresence();
        payload.setDetails(appContext.getString(R.string.rpc_launcher_home));
        return payload;
    }

    private void openBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Exception e) {
            executor.execute(() -> {
                LoginCallback callback = pendingLoginCallback;
                if (callback != null) {
                    failLogin(callback, new IllegalStateException("No browser available for Discord login", e));
                } else {
                    loginFlowActive = false;
                    stopCallbackServer();
                }
            });
        }
    }

    private void handleOAuthCallback(String state, String code, String error, String errorDescription) {
        executor.execute(() -> {
            LoginCallback callback = pendingLoginCallback;
            if (callback == null || pendingState == null || !pendingState.equals(state)) return;
            if (error != null) {
                failLogin(callback, new IOException(error + (errorDescription != null ? ": " + errorDescription : "")));
                return;
            }
            if (code == null) {
                failLogin(callback, new IOException("Missing authorization code"));
                return;
            }
            try {
                DiscordTokens exchanged = DiscordOAuth.exchangeCode(
                        httpClient,
                        BuildConfig.DISCORD_CLIENT_ID,
                        pendingRedirectUri,
                        code,
                        pendingVerifier);
                tokens = exchanged;
                username = fetchUsername(exchanged);
                DiscordAuthStore.save(authDir(), exchanged);
                pendingLoginCallback = null;
                pendingState = null;
                pendingVerifier = null;
                pendingRedirectUri = null;
                stopCallbackServer();
                loginFlowActive = false;
                reconnectAttempt = 0;
                connectGateway();
                mainHandler.post(() -> callback.onSuccess(username));
            } catch (Exception e) {
                failLogin(callback, e);
            }
        });
    }

    private void failLogin(LoginCallback callback, Exception error) {
        pendingLoginCallback = null;
        pendingState = null;
        pendingVerifier = null;
        pendingRedirectUri = null;
        loginFlowActive = false;
        stopCallbackServer();
        notifyError(callback, error);
    }

    private void stopCallbackServer() {
        if (callbackServer != null) {
            callbackServer.stop();
            callbackServer = null;
        }
    }

    private String fetchUsername(DiscordTokens authTokens) throws IOException {
        Request request = new Request.Builder()
                .url(DiscordOAuth.USERS_ME_URL)
                .header("Authorization", authTokens.bearerToken())
                .header("User-Agent", DiscordOAuth.USER_AGENT)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("users/@me failed: " + response.code() + " " + body);
            }
            JSONObject json = new JSONObject(body);
            String globalName = json.optString("global_name", "");
            return globalName.isEmpty() ? json.optString("username", "") : globalName;
        }
    }

    private void connectGateway() {
        disconnectGateway();
        if (tokens == null) return;
        if (tokens.isExpired()) {
            try {
                tokens = DiscordOAuth.refreshToken(httpClient, BuildConfig.DISCORD_CLIENT_ID, tokens.refreshToken);
                DiscordAuthStore.save(authDir(), tokens);
            } catch (IOException e) {
                Log.w(TAG, "Discord token refresh failed; re-login required", e);
                tokens = null;
                username = null;
                return;
            }
        }
        DiscordGateway next = new DiscordGateway(httpClient, tokens.bearerToken(), new GatewayListener());
        gateway = next;
        next.connect(null);
        startGamePoll();
    }

    private void handleGatewayClose(int code, boolean resumable) {
        if (tokens == null) return;
        if (code == 4004) {
            try {
                tokens = DiscordOAuth.refreshToken(httpClient, BuildConfig.DISCORD_CLIENT_ID, tokens.refreshToken);
                DiscordAuthStore.save(authDir(), tokens);
                scheduleReconnect(null, 1000);
            } catch (IOException | IllegalStateException e) {
                Log.w(TAG, "Discord token refresh failed; re-login required", e);
                tokens = null;
                username = null;
            }
            return;
        }
        if (code == 1000) return;
        DiscordGateway.Session resume = resumable && gateway != null ? gateway.getCurrentSession() : null;
        long delay = Math.min(MAX_RECONNECT_DELAY_MS, FIRST_RECONNECT_DELAY_MS << Math.min(reconnectAttempt, 4));
        reconnectAttempt++;
        scheduleReconnect(resume, delay);
    }

    private void scheduleReconnect(DiscordGateway.Session resume, long delay) {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
        }
        reconnectFuture = executor.schedule(() -> {
            reconnectFuture = null;
            if (tokens == null) return;
            DiscordGateway next = new DiscordGateway(httpClient, tokens.bearerToken(), new GatewayListener());
            gateway = next;
            next.connect(resume);
            startGamePoll();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void disconnectGateway() {
        stopGamePoll();
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
        if (gateway != null) {
            gateway.disconnect();
            gateway = null;
        }
    }

    private void requestPresenceFlush() {
        if (gateway == null) return;
        if (presenceFlushScheduled) return;
        long elapsed = System.currentTimeMillis() - lastPresenceSentAt;
        if (elapsed >= PRESENCE_DEBOUNCE_MS) {
            flushPresence();
            return;
        }
        presenceFlushScheduled = true;
        executor.schedule(this::flushPresence, PRESENCE_DEBOUNCE_MS - elapsed, TimeUnit.MILLISECONDS);
    }

    private void flushPresence() {
        presenceFlushScheduled = false;
        if (gateway == null) return;
        DiscordGateway.Session session = gateway.getCurrentSession();
        if (session == null) return;
        List<Map<String, Object>> activities = new ArrayList<>();
        if (pendingPresence != null) {
            pendingPresence.setSessionId(session.getSessionId());
            activities.add(pendingPresence.toActivityMap());
        }
        gateway.sendPresence(activities, STATUS);
        lastPresenceSentAt = System.currentTimeMillis();
    }

    private void notifyError(LoginCallback callback, Exception error) {
        mainHandler.post(() -> {
            if (callback != null) callback.onError(error);
        });
    }

    private void ensureContext(Context context) {
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
    }

    private File authDir() {
        return new File(appContext.getFilesDir(), "discord");
    }

    private class GatewayListener implements DiscordGateway.Listener {
        @Override
        public void onReady(String readyUsername, String sessionId, String resumeGatewayUrl) {
            executor.execute(() -> {
                reconnectAttempt = 0;
                lastPresenceSentAt = 0;
                Log.i(TAG, "Discord RPC ready for " + readyUsername);
                if (activeScreenId == null && pendingPresence == null) {
                    pendingPresence = buildHomePresence();
                }
                requestPresenceFlush();
            });
        }

        @Override
        public void onDisconnected(int code, boolean resumable) {
            executor.execute(() -> handleGatewayClose(code, resumable));
        }

        @Override
        public void onError(Throwable throwable) {
            Log.w(TAG, "Discord gateway error", throwable);
        }
    }
}
