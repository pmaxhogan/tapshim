package dev.maxhogan.tapshim.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.net.toUri
import dev.maxhogan.tapshim.log.EventLog

/**
 * The Bose app only lets you pick the Spotify shortcut while Spotify is
 * installed, and TapShim can only own the socket once Spotify is gone. These
 * helpers take the user to the right system screens for that dance.
 */
object SpotifyLinks {
    const val PACKAGE = "com.spotify.music"

    fun isInstalled(pm: PackageManager, pkg: String = PACKAGE): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun playStoreIntent(pkg: String = PACKAGE): Intent =
        Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun playStoreWebIntent(pkg: String = PACKAGE): Intent =
        Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** System uninstall confirmation. Needs REQUEST_DELETE_PACKAGES (declared in the manifest). */
    fun uninstallIntent(pkg: String = PACKAGE): Intent =
        Intent(Intent.ACTION_DELETE, "package:$pkg".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appInfoIntent(pkg: String = PACKAGE): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openInstall(context: Context) {
        try {
            context.startActivity(playStoreIntent())
        } catch (_: ActivityNotFoundException) {
            context.startActivity(playStoreWebIntent())
        }
    }

    /** Uninstall dialog, or the app info screen if the dialog cannot be shown. */
    fun openUninstall(context: Context) {
        try {
            context.startActivity(uninstallIntent())
        } catch (e: ActivityNotFoundException) {
            EventLog.warn("Uninstall dialog unavailable, opening app info instead: ${e.message}")
            context.startActivity(appInfoIntent())
        }
    }
}
