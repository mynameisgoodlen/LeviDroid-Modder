package org.levimc.launcher.core.minecraft

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.levimc.launcher.core.crash.CrashReporter
import org.levimc.launcher.core.discord.DiscordRpcManager
import org.levimc.launcher.core.packages.PackageService
import org.levimc.launcher.core.news.NewsNotificationHelper
import org.levimc.launcher.settings.FeatureSettings
import org.levimc.launcher.ui.dialogs.LogcatOverlayManager
import org.levimc.launcher.util.PersonalizationManager

class LauncherApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        FeatureSettings.init(applicationContext)
        CrashReporter.init(this)
        val processName = Application.getProcessName()
        if (processName.endsWith(":crash")) return

        PackageService.init(this)
        NewsNotificationHelper.initialize(this)
        LogcatOverlayManager.init(this)
        PersonalizationManager.startWallpaperAccentTracking(this)

        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        DiscordRpcManager.getInstance().connectIfLoggedIn(this)
    }

    companion object {
        @JvmStatic
        lateinit var context: Context
            private set

        @JvmStatic
        lateinit var preferences: SharedPreferences
            private set
    }
}
