package org.levimc.launcher.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.levimc.launcher.R;
import org.levimc.launcher.ui.activities.UpdateDetailsActivity;
import org.levimc.launcher.ui.activities.UpdateInstallActivity;

import rikka.shizuku.Shizuku;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GithubReleaseUpdater {
    private static final String TAG = "GithubReleaseUpdater";
    private static final String GITHUB_LATEST_API = "https://api.github.com/repos/%s/%s/releases/latest";
    private static final String APK_ASSET_KEYWORD = ".apk";
    private static final long PROGRESS_INTERVAL_MS = 250;
    private static final AtomicBoolean UPDATE_FLOW_ACTIVE = new AtomicBoolean(false);
    private static final AtomicBoolean DOWNLOAD_ACTIVE = new AtomicBoolean(false);
    private final Activity activity;
    private final String owner;
    private final String repo;
    private final OkHttpClient client = new OkHttpClient();
    private volatile Call downloadCall;
    private ActivityResultLauncher<Intent> permissionResultLauncher;

    public interface DownloadListener {
        void onProgress(long downloaded, long total);

        void onComplete(File apkFile);

        void onError(String message);
    }

    public GithubReleaseUpdater(Activity activity, String owner, String repo,
                                ActivityResultLauncher<Intent> permissionResultLauncher) {
        this.activity = activity;
        this.owner = owner;
        this.repo = repo;
        this.permissionResultLauncher = permissionResultLauncher;
    }

    public static int compareVersion(String a, String b) {
        String[] x = tokenizeVersion(a);
        String[] y = tokenizeVersion(b);
        int len = Math.max(x.length, y.length);
        for (int i = 0; i < len; i++) {
            int vi = i < x.length ? Integer.parseInt(x[i]) : 0;
            int vj = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (vi > vj) return 1;
            if (vi < vj) return -1;
        }
        return 0;
    }

    // Tags like "v1.5.27-modder.21" carry non-numeric suffixes that crash a
    // plain dot-split parseInt; compare the embedded numeric tokens instead.
    private static String[] tokenizeVersion(String v) {
        String[] raw = v.split("\\D+");
        int start = 0;
        while (start < raw.length && raw[start].isEmpty()) start++;
        return java.util.Arrays.copyOfRange(raw, start, raw.length);
    }

    public void checkUpdate() {
        if (!UPDATE_FLOW_ACTIVE.compareAndSet(false, true)) {
            activity.runOnUiThread(() ->
                    Toast.makeText(activity, R.string.update_check_in_progress, Toast.LENGTH_SHORT).show());
            return;
        }
        String url = String.format(GITHUB_LATEST_API, owner, repo);
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Request failed: " + e.getMessage());
                releaseUpdateFlow();
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, activity.getString(R.string.update_check_failed, e.getMessage()), Toast.LENGTH_LONG).show());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    String latestVersion = json.getString("tag_name");
                    String releaseTitle = json.optString("name", latestVersion);
                    String releaseNotes = json.optString("body", "");
                    JSONArray assets = json.getJSONArray("assets");
                    String downloadUrl = pickApkUrl(assets);
                    if (downloadUrl == null) {
                        Log.e(TAG, "No APK asset found in release.");
                        releaseUpdateFlow();
                        return;
                    }
                    String localVersion = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
                    if (compareVersion(latestVersion, localVersion) > 0) {
                        openUpdateDetails(releaseTitle, releaseNotes, downloadUrl, false);
                    } else {
                        releaseUpdateFlow();
                        activity.runOnUiThread(() ->
                                Toast.makeText(activity, activity.getString(R.string.already_latest_version, localVersion), Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse error: " + e.getMessage());
                    releaseUpdateFlow();
                    activity.runOnUiThread(() ->
                            Toast.makeText(activity, activity.getString(R.string.update_check_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    public void checkUpdateOnLaunch() {
        if (!UPDATE_FLOW_ACTIVE.compareAndSet(false, true)) return;
        String url = String.format(GITHUB_LATEST_API, owner, repo);
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Request failed: " + e.getMessage());
                releaseUpdateFlow();
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    String latestVersion = json.getString("tag_name");
                    String releaseTitle = json.optString("name", latestVersion);
                    String releaseNotes = json.optString("body", "");
                    JSONArray assets = json.getJSONArray("assets");
                    String downloadUrl = pickApkUrl(assets);
                    if (downloadUrl == null) {
                        Log.e(TAG, "No APK asset found in release.");
                        releaseUpdateFlow();
                        return;
                    }
                    String localVersion = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;

                    if (compareVersion(latestVersion, localVersion) > 0) {
                        openUpdateDetails(releaseTitle, releaseNotes, downloadUrl, true);
                    } else {
                        releaseUpdateFlow();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse error: " + e.getMessage());
                    releaseUpdateFlow();
                }
            }
        });
    }

    public static void releaseUpdateFlow() {
        UPDATE_FLOW_ACTIVE.set(false);
    }

    public void cancelDownload() {
        Call call = downloadCall;
        if (call != null && !call.isDone()) call.cancel();
    }

    public void downloadAndInstall(String url, DownloadListener listener) {
        if (!DOWNLOAD_ACTIVE.compareAndSet(false, true)) {
            notifyListener(listener, () -> listener.onError(activity.getString(R.string.update_check_in_progress)));
            return;
        }
        Request request = new Request.Builder().url(url).build();
        Call call = client.newCall(request);
        downloadCall = call;

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                DOWNLOAD_ACTIVE.set(false);
                if (call.isCanceled()) return;
                notifyListener(listener, () -> listener.onError(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) {
                InputStream is = null;
                FileOutputStream fos = null;
                try {
                    if (!response.isSuccessful()) {
                        String httpError = "HTTP " + response.code();
                        response.close();
                        DOWNLOAD_ACTIVE.set(false);
                        notifyListener(listener, () -> listener.onError(httpError));
                        return;
                    }
                    byte[] buf = new byte[4096];
                    int len;
                    long downloaded = 0;
                    long total = response.body().contentLength();
                    is = response.body().byteStream();
                    File apkFile = new File(activity.getExternalCacheDir(), "update_apk.apk");
                    fos = new FileOutputStream(apkFile);

                    long lastUiUpdate = 0;
                    while ((len = is.read(buf)) > 0) {
                        if (call.isCanceled()) break;
                        fos.write(buf, 0, len);
                        downloaded += len;

                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate > PROGRESS_INTERVAL_MS) {
                            lastUiUpdate = now;
                            final long progress = downloaded;
                            final long contentLength = total;
                            notifyListener(listener, () -> listener.onProgress(progress, contentLength));
                        }
                    }
                    fos.flush();
                    if (call.isCanceled()) {
                        DOWNLOAD_ACTIVE.set(false);
                        return;
                    }
                    DOWNLOAD_ACTIVE.set(false);
                    downloadCall = null;
                    if (listener != null) safeRunUi(() -> listener.onComplete(apkFile));
                    try {
                        installApk(apkFile, true);
                    } catch (Exception installError) {
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            Toast.makeText(activity, activity.getString(R.string.install_failed, installError.getMessage()), Toast.LENGTH_LONG).show();
                        });
                    }
                } catch (Exception e) {
                    DOWNLOAD_ACTIVE.set(false);
                    downloadCall = null;
                    if (call.isCanceled()) return;
                    notifyListener(listener, () -> listener.onError(e.getMessage()));
                } finally {
                    try {
                        if (is != null) is.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        if (fos != null) fos.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void notifyListener(DownloadListener listener, Runnable action) {
        if (listener == null) return;
        safeRunUi(action);
    }

    private void safeRunUi(Runnable action) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            action.run();
        });
    }

    // Releases carry per-ABI APKs plus a universal one; pick the one matching
    // this device and fall back to universal so old single-APK releases keep working.
    private static String pickApkUrl(JSONArray assets) {
        String fallback = null;
        String universal = null;
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        String deviceAbi = supportedAbis.length == 0 ? "" : supportedAbis[0];
        for (int i = 0; i < assets.length(); i++) {
            try {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");
                if (!name.endsWith(APK_ASSET_KEYWORD)) continue;
                String url = asset.getString("browser_download_url");
                if (fallback == null) fallback = url;
                if (name.contains("universal")) universal = url;
                if (!deviceAbi.isEmpty() && name.contains(deviceAbi)) return url;
            } catch (Exception e) {
                Log.e(TAG, "Asset parse error: " + e.getMessage());
            }
        }
        return universal != null ? universal : fallback;
    }

    private void openUpdateDetails(String releaseTitle, String releaseNotes,
                                    String downloadUrl, boolean showAskDialog) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                releaseUpdateFlow();
                return;
            }
            UpdateDetailsActivity.start(activity, releaseTitle, releaseNotes, downloadUrl, showAskDialog);
        });
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public static final String METHOD_SYSTEM = "system";
    public static final String METHOD_SHIZUKU = "shizuku";
    public static final String METHOD_ROOT = "root";

    private static final int REQ_SHIZUKU_PERMISSION = 1001;

    public static String getInstallMethod(Context context) {
        return prefs(context).getString("install_method", METHOD_SYSTEM);
    }

    public static void setInstallMethod(Context context, String method) {
        prefs(context).edit().putString("install_method", method).apply();
    }

    public static int getInstallMethodLabel(Context context) {
        String method = getInstallMethod(context);
        if (METHOD_SHIZUKU.equals(method)) return R.string.install_method_shizuku;
        if (METHOD_ROOT.equals(method)) return R.string.install_method_root;
        return R.string.install_method_system;
    }

    /**
     * Called right after the user picks Shizuku so the permission prompt shows
     * immediately, instead of silently deferring to install time. No listener is
     * registered here — we only want the system dialog; the install path re-checks
     * grant state later.
     */
    public static void ensureShizukuPermission() {
        if (!Shizuku.pingBinder()) return;
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
    }

    private void installApk(File apkFile, boolean interactive) {
        String method = getInstallMethod(activity);
        if (METHOD_SYSTEM.equals(method)) {
            systemInstallApk(apkFile);
        } else if (interactive) {
            activity.runOnUiThread(() -> UpdateInstallActivity.start(activity, apkFile, method));
        } else {
            installInBackground(apkFile, method);
        }
    }

    private void installInBackground(File apkFile, String method) {
        UpdateApkInstaller.Callback callback = new UpdateApkInstaller.Callback() {
            @Override
            public void onSuccess() {
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, R.string.update_installed_restart, Toast.LENGTH_LONG).show());
            }

            @Override
            public void onFailure(int messageRes) {
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, activity.getString(R.string.install_failed, activity.getString(messageRes)), Toast.LENGTH_LONG).show());
            }
        };
        if (METHOD_ROOT.equals(method)) {
            UpdateApkInstaller.installWithRoot(apkFile, callback);
        } else {
            UpdateApkInstaller.installWithShizuku(activity, apkFile, callback);
        }
    }

    private void systemInstallApk(File apkFile) {
        PermissionsHandler handler = PermissionsHandler.getInstance();
        handler.setActivity(activity, permissionResultLauncher);
        handler.requestPermission(PermissionsHandler.PermissionType.UNKNOWN_SOURCES,
                new PermissionsHandler.PermissionResultCallback() {
                    @Override
                    public void onPermissionGranted(PermissionsHandler.PermissionType type) {
                        activity.runOnUiThread(() -> {
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                Uri apkUri;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    apkUri = FileProvider.getUriForFile(
                                            activity,
                                            activity.getPackageName() + ".fileprovider",
                                            apkFile
                                    );
                                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                                } else {
                                    apkUri = Uri.fromFile(apkFile);
                                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                }
                                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                                activity.startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(activity, activity.getString(R.string.install_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                            }
                        });
                    }

                    @Override
                    public void onPermissionDenied(PermissionsHandler.PermissionType type, boolean permanentlyDenied) {
                        activity.runOnUiThread(() ->
                                Toast.makeText(activity, activity.getString(R.string.unknown_sources_permission_message), Toast.LENGTH_LONG).show()
                        );
                    }
                }
        );
    }
}