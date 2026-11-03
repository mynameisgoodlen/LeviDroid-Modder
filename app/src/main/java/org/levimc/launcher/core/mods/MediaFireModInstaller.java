package org.levimc.launcher.core.mods;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.levimc.launcher.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class MediaFireModInstaller {

    public interface Callback {
        void onWarning(String message, Runnable proceed);
        void onSuccess();
        void onError(String message);
    }

    private static final Pattern DIRECT_LINK = Pattern.compile(
            "href=\"(https://download[0-9]+\\.mediafire\\.com/[^\"]+)\"");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Context context;
    private final FileHandler fileHandler;

    public MediaFireModInstaller(Context context, FileHandler fileHandler) {
        this.context = context;
        this.fileHandler = fileHandler;
    }

    public static String normalizeLink(String input) {
        String link = input == null ? "" : input.trim();
        if (link.isEmpty()) return null;
        String lower = link.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://")) {
            link = link.substring(7);
            lower = lower.substring(7);
        }
        if (!lower.startsWith("https://")) {
            link = "https://" + link;
        }
        String host = hostOf(link);
        if (host == null || !(host.equals("mediafire.com") || host.endsWith(".mediafire.com"))) {
            return null;
        }
        return link;
    }

    public void install(String link, String currentVersionCode, Callback callback) {
        EXECUTOR.execute(() -> {
            File downloadedFile = null;
            try {
                String directUrl = resolveDirectLink(link);
                String fileName = fileNameFromUrl(directUrl);
                File downloadDir = new File(context.getCacheDir(), "mediafire_mod_downloads");
                if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                    throw new IllegalStateException("Could not prepare download folder");
                }
                downloadedFile = new File(downloadDir, safeName(fileName));
                download(directUrl, downloadedFile);

                List<String> minecraftVersions = GitHubModInstaller.peekMinecraftVersions(downloadedFile);
                boolean incompatible = currentVersionCode != null
                        && !minecraftVersions.isEmpty()
                        && !ModNativeLoader.isCompatibleWithMinecraftVersion(minecraftVersions, currentVersionCode);
                File file = downloadedFile;
                if (incompatible) {
                    String warning = context.getString(R.string.github_import_compat_warning,
                            String.join(", ", minecraftVersions), currentVersionCode);
                    context.getMainExecutor().execute(() -> callback.onWarning(warning,
                            () -> context.getMainExecutor().execute(() ->
                                    importFile(file, fileName, minecraftVersions, callback))));
                    return;
                }
                context.getMainExecutor().execute(
                        () -> importFile(file, fileName, minecraftVersions, callback));
            } catch (Exception error) {
                if (downloadedFile != null) downloadedFile.delete();
                deliverError(callback, message(error));
            }
        });
    }

    private String resolveDirectLink(String link) throws IOException {
        String host = hostOf(link);
        if (host != null && host.matches("download[0-9]+\\.mediafire\\.com")) {
            return link;
        }
        Request request = new Request.Builder().url(link).header("User-Agent", USER_AGENT).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException(context.getString(R.string.mediafire_import_failed)
                        + " (HTTP " + response.code() + ")");
            }
            String html = response.body().string();
            if (html.contains("Invalid or Deleted File")) {
                throw new IllegalStateException(context.getString(R.string.mediafire_import_file_unavailable));
            }
            Matcher matcher = DIRECT_LINK.matcher(html);
            if (!matcher.find()) {
                throw new IllegalStateException(context.getString(R.string.mediafire_import_failed));
            }
            return matcher.group(1);
        }
    }

    private void download(String url, File target) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException(context.getString(R.string.mediafire_import_download_failed)
                        + " (HTTP " + response.code() + ")");
            }
            try (InputStream input = response.body().byteStream();
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
        }
    }

    private void importFile(File file, String fileName, List<String> minecraftVersions, Callback callback) {
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fileHandler.processCatalogModDirectly(intent, displayNameFor(fileName), null, null,
                    minecraftVersions,
                    new FileHandler.FileOperationCallback() {
                        @Override
                        public void onSuccess(int processedFiles) {
                            file.delete();
                            callback.onSuccess();
                        }

                        @Override
                        public void onError(String errorMessage) {
                            file.delete();
                            callback.onError(errorMessage);
                        }

                        @Override
                        public void onProgressUpdate(int progress) {
                        }
                    });
        } catch (Exception error) {
            file.delete();
            deliverError(callback, message(error));
        }
    }

    static String fileNameFromUrl(String directUrl) {
        String name = directUrl.substring(directUrl.lastIndexOf('/') + 1);
        try {
            name = URLDecoder.decode(name, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
        }
        return name.isEmpty() ? "mod" : name;
    }

    private static String hostOf(String link) {
        int scheme = link.indexOf("://");
        if (scheme < 0) return null;
        String rest = link.substring(scheme + 3);
        int path = rest.indexOf('/');
        String host = path < 0 ? rest : rest.substring(0, path);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.toLowerCase(Locale.ROOT);
    }

    private static String displayNameFor(String assetName) {
        String name = assetName == null ? "mod" : assetName;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safeName(String value) {
        String safe = value == null ? "mod" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "mod" : safe;
    }

    private void deliverError(Callback callback, String message) {
        context.getMainExecutor().execute(() -> callback.onError(message));
    }

    private static String message(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
