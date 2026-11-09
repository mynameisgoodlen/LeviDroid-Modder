package org.levimc.launcher.core.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.ModManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class AiCrashAnalyzer {

    private static final String PREFS_NAME = "ai_analysis_prefs";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final String PROMPT_TEMPLATE =
            "LeviLauncher Modder is an open-source launcher for Minecraft Bedrock Edition on Android, "
                    + "a fork of LeviLaunchroid (LiteLDev). The game runs in the same process as the launcher; "
                    + "the launcher supports installing mods (.levipack / mod APKs); crashes are captured by "
                    + "xCrash and by the engine's own dump files.\n"
                    + "Launcher source code: https://github.com/sang765/LeviDroid-Modder\n"
                    + "Enabled mods:\n{mods}\n"
                    + "Analyze the crash log below: the most likely cause, the specific culprit "
                    + "(mod/library if any), and suggested fixes. Be concise and structured.\n"
                    + "Respond in the language the app is currently using.\n"
                    + "Language: {lang}\n"
                    + "Crash log:\n{log}";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onDone(String response);

        void onError(String message);
    }

    private AiCrashAnalyzer() {
    }

    public static boolean isConfigured(Context context) {
        SharedPreferences prefs = prefs(context);
        return !prefs.getString(KEY_BASE_URL, "").trim().isEmpty()
                && !prefs.getString(KEY_MODEL, "").trim().isEmpty();
    }

    public static String getBaseUrl(Context context) {
        return prefs(context).getString(KEY_BASE_URL, "").trim();
    }

    public static String getApiKey(Context context) {
        return prefs(context).getString(KEY_API_KEY, "").trim();
    }

    public static String getModel(Context context) {
        return prefs(context).getString(KEY_MODEL, "").trim();
    }

    public static void setConfig(Context context, String baseUrl, String apiKey, String model) {
        prefs(context).edit()
                .putString(KEY_BASE_URL, baseUrl.trim())
                .putString(KEY_API_KEY, apiKey.trim())
                .putString(KEY_MODEL, model.trim())
                .apply();
    }

    public static void analyze(Context context, String crashLog, Callback callback) {
        String baseUrl = getBaseUrl(context);
        String model = getModel(context);
        if (baseUrl.isEmpty() || model.isEmpty()) {
            MAIN.post(() -> callback.onError(context.getString(org.levimc.launcher.R.string.ai_not_configured)));
            return;
        }
        String prompt = PROMPT_TEMPLATE
                .replace("{lang}", Locale.getDefault().getDisplayLanguage(Locale.getDefault()))
                .replace("{mods}", enabledModsSummary())
                .replace("{log}", crashLog == null ? "" : crashLog);
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", prompt);
            messages.put(message);
            body.put("messages", messages);

            Request.Builder builder = new Request.Builder()
                    .url(baseUrl.replaceAll("/+$", "") + "/chat/completions")
                    .post(RequestBody.create(body.toString(), JSON));
            String apiKey = getApiKey(context);
            if (!apiKey.isEmpty()) builder.header("Authorization", "Bearer " + apiKey);

            CLIENT.newCall(builder.build()).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    MAIN.post(() -> callback.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }

                @Override
                public void onResponse(okhttp3.Call call, Response response) {
                    String text = extractContent(response);
                    String error = text == null ? "HTTP " + response.code() : null;
                    response.close();
                    if (error != null) {
                        MAIN.post(() -> callback.onError(error));
                    } else {
                        MAIN.post(() -> callback.onDone(text));
                    }
                }
            });
        } catch (Exception e) {
            MAIN.post(() -> callback.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    public interface ModelsCallback {
        void onDone(List<String> models);

        void onError(String message);
    }

    public static void fetchModels(Context context, ModelsCallback callback) {
        String baseUrl = getBaseUrl(context);
        if (baseUrl.isEmpty()) {
            MAIN.post(() -> callback.onError(context.getString(org.levimc.launcher.R.string.ai_not_configured)));
            return;
        }
        Request.Builder builder = new Request.Builder()
                .url(baseUrl.replaceAll("/+$", "") + "/models")
                .get();
        String apiKey = getApiKey(context);
        if (!apiKey.isEmpty()) builder.header("Authorization", "Bearer " + apiKey);
        CLIENT.newCall(builder.build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                MAIN.post(() -> callback.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) {
                List<String> models = extractModelIds(response);
                String error = models == null ? "HTTP " + response.code() : null;
                response.close();
                if (error != null) {
                    MAIN.post(() -> callback.onError(error));
                } else {
                    MAIN.post(() -> callback.onDone(models));
                }
            }
        });
    }

    private static List<String> extractModelIds(Response response) {
        try {
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONObject json = new JSONObject(response.body().string());
            JSONArray data = json.getJSONArray("data");
            List<String> models = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                models.add(data.getJSONObject(i).getString("id"));
            }
            Collections.sort(models);
            return models;
        } catch (Exception e) {
            return null;
        }
    }

    private static String enabledModsSummary() {
        try {
            StringBuilder builder = new StringBuilder();
            for (Mod mod : ModManager.getInstance().getMods()) {
                if (mod == null || !mod.isEnabled()) continue;
                builder.append("- ").append(mod.getDisplayName());
                if (mod.getVersion() != null && !mod.getVersion().isEmpty()) {
                    builder.append(' ').append(mod.getVersion());
                }
                builder.append('\n');
            }
            if (builder.length() == 0) return "None";
            return builder.toString().trim();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private static String extractContent(Response response) {
        try {
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONObject json = new JSONObject(response.body().string());
            JSONArray choices = json.getJSONArray("choices");
            if (choices.length() == 0) return null;
            return choices.getJSONObject(0).getJSONObject("message").optString("content", null);
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
