package org.levimc.launcher.ui.dialogs;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.levimc.launcher.R;

public final class AbiWarningDialog {

    private static final String RELEASES_URL = "https://github.com/sang765/LeviDroid-Modder/releases/latest";

    private AbiWarningDialog() {
    }

    public static void showIfNeeded(Activity activity) {
        String primaryAbi = activity.getApplicationInfo().primaryCpuAbi;
        if (primaryAbi == null || !primaryAbi.startsWith("armeabi") || !is64BitDevice()) return;
        String appName = activity.getString(R.string.app_name);
        new CustomAlertDialog(activity)
                .setTitleText(activity.getString(R.string.abi_warning_title))
                .setMessage(activity.getString(R.string.abi_warning_message, appName, appName))
                .setBlurBackground(true)
                .setPositiveButton(activity.getString(R.string.abi_warning_download), v ->
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))))
                .setNegativeButton(activity.getString(R.string.abi_warning_stay), null)
                .show();
    }

    private static boolean is64BitDevice() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.startsWith("arm64")) return true;
        }
        return false;
    }
}
