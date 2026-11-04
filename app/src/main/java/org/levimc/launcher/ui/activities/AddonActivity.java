package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.animation.DynamicAnim;

public class AddonActivity extends BaseActivity {

    private static final String MAGICLOADER_PACKAGE = "com.magicloader.app";
    private static final String MAGICLOADER_COMPAT_URL =
            "https://github.com/KoshakMineDEV/magic-compatibility-list";

    private final ActivityResultLauncher<String[]> apkPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) installApk(uri);
            });

    private PackageManager packageManager;
    private TextView magicloaderStatus;
    private Button magicloaderPrimary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_addon);
        setupNavBar();

        packageManager = getPackageManager();
        magicloaderStatus = findViewById(R.id.addon_magicloader_status);
        magicloaderPrimary = findViewById(R.id.addon_magicloader_primary);
        Button magicloaderCompat = findViewById(R.id.addon_magicloader_compat);

        magicloaderCompat.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(MAGICLOADER_COMPAT_URL))));

        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateMagicloaderState();
    }

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_addon);
        findViewById(R.id.nav_tab_addon).setOnClickListener(v -> {});
    }

    private void updateMagicloaderState() {
        PackageInfo info = getInstalledPackage(MAGICLOADER_PACKAGE);
        if (info == null) {
            magicloaderStatus.setText(R.string.addon_not_installed);
            magicloaderPrimary.setText(R.string.addon_install_apk);
            magicloaderPrimary.setOnClickListener(v -> pickApk());
        } else {
            magicloaderStatus.setText(getString(R.string.addon_installed_version, versionOf(info)));
            magicloaderPrimary.setText(R.string.addon_open);
            magicloaderPrimary.setOnClickListener(v -> openApp(MAGICLOADER_PACKAGE));
        }
    }

    private PackageInfo getInstalledPackage(String packageName) {
        try {
            return packageManager.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String versionOf(PackageInfo info) {
        return info.versionName != null ? info.versionName : "?";
    }

    private void pickApk() {
        apkPicker.launch(new String[]{
                "application/vnd.android.package-archive",
                "application/octet-stream"});
    }

    private void installApk(Uri uri) {
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.addon_install_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openApp(String packageName) {
        Intent launch = packageManager.getLaunchIntentForPackage(packageName);
        if (launch != null) startActivity(launch);
    }
}
