package org.levimc.launcher.core.news;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import org.levimc.launcher.BuildConfig;
import org.levimc.launcher.R;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.util.TextTranslator;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChangelogManager {
    private static final String PREFS = "launcher_changelog";
    private static final String KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code";
    private static final String ASSET_PATH = "launcher/changelog.json";

    private ChangelogManager() {
    }

    public static void showIfNeeded(Activity activity, Runnable onComplete) {
        long currentVersionCode = getCurrentVersionCode(activity);
        if (!shouldShow(activity, currentVersionCode)) {
            run(onComplete);
            return;
        }

        Changelog changelog = load(activity);
        if (changelog == null || changelog.sections == null || changelog.sections.isEmpty()) {
            markSeen(activity, currentVersionCode);
            run(onComplete);
            return;
        }

        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_changelog_content, null, false);
        TextView version = content.findViewById(R.id.changelog_version);
        TextView headline = content.findViewById(R.id.changelog_headline);
        TextView summary = content.findViewById(R.id.changelog_summary);
        LinearLayout sections = content.findViewById(R.id.changelog_sections);
        List<TextView> translatable = new ArrayList<>();

        version.setText(activity.getString(R.string.changelog_version, BuildConfig.VERSION_NAME));
        headline.setText(changelog.headline);
        headline.setVisibility(TextUtils.isEmpty(changelog.headline) ? View.GONE : View.VISIBLE);
        summary.setText(changelog.summary);
        summary.setVisibility(TextUtils.isEmpty(changelog.summary) ? View.GONE : View.VISIBLE);
        translatable.add(headline);
        translatable.add(summary);

        for (Section section : changelog.sections) {
            if (section == null || section.items == null || section.items.isEmpty()) continue;
            addSection(activity, sections, section, translatable);
        }

        CustomAlertDialog dialog = new CustomAlertDialog(activity)
                .setTitleText(activity.getString(R.string.changelog_title))
                .setCustomView(content)
                .setBlurBackground(true)
                .setShowCloseButton(true)
                .setOnDismissAnimationEndListener(() -> {
                    markSeen(activity, currentVersionCode);
                    run(onComplete);
                });
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        setupTranslateButton(activity, content, translatable);
    }

    private static void setupTranslateButton(Activity activity, View content, List<TextView> translatable) {
        Button button = content.findViewById(R.id.changelog_translate);
        if ("en".equals(Locale.getDefault().getLanguage())) return;
        button.setVisibility(View.VISIBLE);
        List<String> originalTexts = new ArrayList<>(translatable.size());
        for (TextView view : translatable) originalTexts.add(view.getText().toString());
        AtomicBoolean translated = new AtomicBoolean(false);
        AtomicBoolean inFlight = new AtomicBoolean(false);
        button.setOnClickListener(v -> {
            if (!inFlight.compareAndSet(false, true)) return;
            if (!translated.get()) {
                button.setEnabled(false);
                button.setText(R.string.changelog_translating);
                List<String> texts = new ArrayList<>(translatable.size());
                for (TextView view : translatable) texts.add(view.getText().toString());
                TextTranslator.translate(texts, new TextTranslator.Callback() {
                    @Override
                    public void onDone(List<String> translatedLines) {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        inFlight.set(false);
                        if (translatedLines.size() != translatable.size()) {
                            fail();
                            return;
                        }
                        for (int i = 0; i < translatedLines.size(); i++) {
                            translatable.get(i).setText(translatedLines.get(i));
                        }
                        translated.set(true);
                        button.setEnabled(true);
                        button.setText(R.string.changelog_show_original);
                    }

                    @Override
                    public void onError() {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        fail();
                    }

                    private void fail() {
                        inFlight.set(false);
                        button.setEnabled(true);
                        button.setText(R.string.changelog_translate);
                        Toast.makeText(activity, R.string.changelog_translate_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                for (int i = 0; i < translatable.size(); i++) {
                    translatable.get(i).setText(originalTexts.get(i));
                }
                translated.set(false);
                inFlight.set(false);
                button.setEnabled(true);
                button.setText(R.string.changelog_translate);
            }
        });
    }

    private static void addSection(Context context, LinearLayout parent, Section section, List<TextView> translatable) {
        float density = context.getResources().getDisplayMetrics().density;
        TextView title = new TextView(context);
        title.setText(section.title);
        String sectionTitle = section.title == null ? "" : section.title;
        int chipColor = context.getColor(sectionTitle.toLowerCase(Locale.ROOT).contains("fix")
                ? R.color.changelog_fix : R.color.primary);
        title.setTextColor(chipColor);
        title.setTextSize(13);
        title.setTypeface(context.getResources().getFont(R.font.misans), Typeface.BOLD);
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(12 * density);
        chip.setColor(Color.argb(26, Color.red(chipColor), Color.green(chipColor), Color.blue(chipColor)));
        title.setBackground(chip);
        int padH = (int) (10 * density);
        int padV = (int) (4 * density);
        title.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = (int) (14 * density);
        title.setLayoutParams(titleParams);
        parent.addView(title);
        translatable.add(title);

        for (Item item : section.items) {
            if (item == null || TextUtils.isEmpty(item.text)) continue;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (7 * density), 0, 0);

            TextView bullet = new TextView(context);
            bullet.setText("•");
            bullet.setTextColor(context.getColor(R.color.primary));
            bullet.setTextSize(16);
            LinearLayout.LayoutParams bulletParams = new LinearLayout.LayoutParams(
                    (int) (18 * density), LinearLayout.LayoutParams.WRAP_CONTENT);
            bullet.setLayoutParams(bulletParams);

            TextView text = new TextView(context);
            text.setText(item.text);
            text.setTextColor(context.getColor(R.color.text_secondary));
            text.setTextSize(13);
            text.setTypeface(context.getResources().getFont(R.font.misans));
            text.setLineSpacing(0, 1.08f);
            text.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (isWebUrl(item.url)) {
                text.setTextColor(context.getColor(R.color.primary));
                text.setPaintFlags(text.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                text.setOnClickListener(view -> openUrl(context, item.url));
            }
            translatable.add(text);

            row.addView(bullet);
            row.addView(text);
            parent.addView(row);
        }
    }

    private static boolean isWebUrl(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String scheme = Uri.parse(value).getScheme();
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }

    private static void openUrl(Context context, String value) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value)));
        } catch (Exception ignored) {
        }
    }

    private static boolean shouldShow(Context context, long currentVersionCode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_LAST_SEEN_VERSION_CODE)) {
            boolean existingInstallWasUpdated = false;
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                existingInstallWasUpdated = info.lastUpdateTime > info.firstInstallTime + 1000L;
            } catch (Exception ignored) {
            }
            if (!existingInstallWasUpdated) markSeen(context, currentVersionCode);
            return existingInstallWasUpdated;
        }

        long lastSeenVersionCode = prefs.getLong(KEY_LAST_SEEN_VERSION_CODE, 0L);
        if (currentVersionCode > lastSeenVersionCode) return true;
        if (currentVersionCode < lastSeenVersionCode) markSeen(context, currentVersionCode);
        return false;
    }

    private static void markSeen(Context context, long versionCode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_SEEN_VERSION_CODE, versionCode).apply();
    }

    private static long getCurrentVersionCode(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        } catch (Exception ignored) {
            return BuildConfig.VERSION_CODE;
        }
    }

    private static Changelog load(Context context) {
        try (InputStream input = context.getAssets().open(ASSET_PATH)) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            String json = output.toString(StandardCharsets.UTF_8.name());
            Changelog changelog = new Gson().fromJson(json, Changelog.class);
            return changelog != null && changelog.schemaVersion == 1 ? changelog : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void run(Runnable runnable) {
        if (runnable != null) runnable.run();
    }

    private static final class Changelog {
        int schemaVersion;
        String headline;
        String summary;
        List<Section> sections = new ArrayList<>();
    }

    private static final class Section {
        String title;
        List<Item> items = new ArrayList<>();
    }

    private static final class Item {
        String text;
        String url;
    }
}
