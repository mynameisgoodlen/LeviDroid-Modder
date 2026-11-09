package org.levimc.launcher.ui.dialogs;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.ai.AiCrashAnalyzer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.CorePlugin;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.core.spans.CodeBlockSpan;

public final class AiAnalysisUi {

    private static final int MAX_LOG = 12000;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static final Pattern CODE_TOKEN = Pattern.compile(
            "(?<![\\w.])((?:public|private|protected|static|final|void|int|long|boolean|char|float|double"
                    + "|class|interface|return|new|throw|throws|null|true|false|package|import"
                    + "|extends|implements|at)\\b)"
                    + "|(https?://\\S+)"
                    + "|([\\w.]+(?:Exception|Error)\\b)");

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
        CustomAlertDialog loading = new CustomAlertDialog(activity)
                .setTitleText(activity.getString(R.string.ai_crash_title))
                .setCustomView(loadingView(activity))
                .setShowCloseButton(true);
        loading.show();
        AiCrashAnalyzer.analyze(activity, trim(crashLog), new AiCrashAnalyzer.Callback() {
            @Override
            public void onDone(String response) {
                IN_FLIGHT.set(false);
                if (activity.isFinishing() || activity.isDestroyed()) return;
                loading.dismiss();
                showResult(activity, response);
            }

            @Override
            public void onError(String message) {
                IN_FLIGHT.set(false);
                if (activity.isFinishing() || activity.isDestroyed()) return;
                loading.dismiss();
                Toast.makeText(activity, activity.getString(R.string.ai_failed, message), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static LinearLayout loadingView(Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding((int) (20 * density), (int) (10 * density), (int) (20 * density), 0);

        TextView message = new TextView(activity);
        message.setText(R.string.ai_analyzing);
        message.setTextColor(activity.getColor(R.color.on_surface));
        message.setTextSize(14);
        content.addView(message);

        ProgressBar progress = new ProgressBar(activity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) (14 * density);
        progress.setLayoutParams(params);
        content.addView(progress);
        return content;
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
        text.setPadding((int) (12 * density), (int) (12 * density), (int) (12 * density), (int) (12 * density));
        text.setTextSize(14);
        text.setTextColor(activity.getColor(R.color.on_surface));
        text.setTextIsSelectable(true);
        markwon(activity).setMarkdown(text, response == null ? "" : response);
        highlightCodeBlocks(text);
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
                .setShowCloseButton(true)
                .show();
    }

    private static Markwon markwon(Activity activity) {
        return Markwon.builder(activity)
                .usePlugin(CorePlugin.create())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureTheme(MarkwonTheme.Builder builder) {
                        builder.linkColor(activity.getColor(R.color.primary))
                                .codeTextColor(activity.getColor(R.color.changelog_fix))
                                .codeBackgroundColor(Color.TRANSPARENT)
                                .codeBlockTextColor(activity.getColor(R.color.on_surface))
                                .codeBlockBackgroundColor(Color.TRANSPARENT);
                    }
                })
                .build();
    }

    private static void highlightCodeBlocks(TextView text) {
        CharSequence cs = text.getText();
        if (!(cs instanceof Spannable)) return;
        Spannable spannable = (Spannable) cs;
        CodeBlockSpan[] blocks = spannable.getSpans(0, spannable.length(), CodeBlockSpan.class);
        if (blocks.length == 0) return;
        int keyword = text.getContext().getColor(R.color.ai_code_keyword);
        int exception = text.getContext().getColor(R.color.ai_code_exception);
        int link = text.getContext().getColor(R.color.ai_code_link);
        Matcher matcher = CODE_TOKEN.matcher(cs);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            boolean inside = false;
            for (CodeBlockSpan block : blocks) {
                if (spannable.getSpanStart(block) <= start && end <= spannable.getSpanEnd(block)) {
                    inside = true;
                    break;
                }
            }
            if (!inside) continue;
            int color = matcher.group(1) != null ? keyword
                    : matcher.group(2) != null ? link : exception;
            spannable.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static String trim(String log) {
        if (log.length() <= MAX_LOG) return log;
        return log.substring(0, MAX_LOG / 2) + "\n…\n" + log.substring(log.length() - MAX_LOG / 2);
    }
}
