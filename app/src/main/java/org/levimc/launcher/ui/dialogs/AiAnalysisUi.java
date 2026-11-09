package org.levimc.launcher.ui.dialogs;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.ai.AiCrashAnalyzer;

import java.util.concurrent.atomic.AtomicBoolean;

public final class AiAnalysisUi {

    private static final int MAX_LOG = 12000;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private AiAnalysisUi() {
    }

    public static void analyze(Activity activity, String crashLog) {
        if (TextUtils.isEmpty(crashLog)) {
            Toast.makeText(activity, R.string.game_crash_log_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!AiCrashAnalyzer.isConfigured(activity)) {
            Toast.makeText(activity, R.string.ai_not_configured, Toast.LENGTH_LONG).show();
            return;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        Toast.makeText(activity, R.string.ai_analyzing, Toast.LENGTH_SHORT).show();
        AiCrashAnalyzer.analyze(activity, trim(crashLog), new AiCrashAnalyzer.Callback() {
            @Override
            public void onDone(String response) {
                IN_FLIGHT.set(false);
                if (activity.isFinishing() || activity.isDestroyed()) return;
                showResult(activity, response);
            }

            @Override
            public void onError(String message) {
                IN_FLIGHT.set(false);
                if (activity.isFinishing() || activity.isDestroyed()) return;
                Toast.makeText(activity, activity.getString(R.string.ai_failed, message), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void showResult(Activity activity, String response) {
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (320 * density));
        scrollParams.topMargin = (int) (14 * density);
        scroll.setLayoutParams(scrollParams);
        scroll.setBackgroundColor(activity.getColor(R.color.surface_variant));

        TextView text = new TextView(activity);
        text.setText(response == null ? "" : response);
        text.setTextIsSelectable(true);
        text.setTextColor(activity.getColor(R.color.on_surface));
        text.setTextSize(14);
        text.setPadding((int) (12 * density), (int) (12 * density), (int) (12 * density), (int) (12 * density));
        scroll.addView(text);
        content.addView(scroll);

        Button copy = new Button(activity);
        copy.setText(R.string.minecraft_loading_copy_log);
        copy.setAllCaps(false);
        copy.setTextSize(13);
        copy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.primary)));
        copy.setTextColor(activity.getColor(R.color.on_primary));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        copyParams.gravity = Gravity.END;
        copyParams.topMargin = (int) (10 * density);
        copy.setLayoutParams(copyParams);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    activity.getString(R.string.ai_result_title), text.getText()));
            Toast.makeText(activity, R.string.minecraft_loading_log_copied, Toast.LENGTH_SHORT).show();
        });
        content.addView(copy);

        new CustomAlertDialog(activity)
                .setTitleText(activity.getString(R.string.ai_result_title))
                .setCustomView(content)
                .setNegativeButton(activity.getString(R.string.game_crash_close), v -> { })
                .show();
    }

    private static String trim(String log) {
        if (log.length() <= MAX_LOG) return log;
        return log.substring(0, MAX_LOG / 2) + "\n…\n" + log.substring(log.length() - MAX_LOG / 2);
    }
}
