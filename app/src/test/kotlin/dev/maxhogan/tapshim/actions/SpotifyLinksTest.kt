package dev.maxhogan.tapshim.actions

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.maxhogan.tapshim.log.EventLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpotifyLinksTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        EventLog.resetForTest()
        EventLog.init(app)
        EventLog.clear()
    }

    @After
    fun tearDown() {
        EventLog.clear()
        EventLog.resetForTest()
    }

    private fun resolvable(intent: Intent, pkg: String) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Handler"
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, info)
    }

    @Test
    fun isInstalledReflectsPackageManager() {
        assertFalse(SpotifyLinks.isInstalled(app.packageManager))
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = SpotifyLinks.PACKAGE })
        assertTrue(SpotifyLinks.isInstalled(app.packageManager))
        assertFalse(SpotifyLinks.isInstalled(app.packageManager, "com.example.other"))
    }

    @Test
    fun intentsTargetTheSpotifyPackage() {
        assertEquals("market://details?id=com.spotify.music", SpotifyLinks.playStoreIntent().dataString)
        assertEquals("https://play.google.com/store/apps/details?id=com.spotify.music", SpotifyLinks.playStoreWebIntent().dataString)
        assertEquals(Intent.ACTION_DELETE, SpotifyLinks.uninstallIntent().action)
        assertEquals("package:com.spotify.music", SpotifyLinks.uninstallIntent().dataString)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, SpotifyLinks.appInfoIntent().action)
        assertEquals("package:com.spotify.music", SpotifyLinks.appInfoIntent().dataString)
    }

    @Test
    fun openInstallPrefersPlayStoreAppThenWeb() {
        SpotifyLinks.openInstall(app)
        assertEquals("market://details?id=com.spotify.music", shadowOf(app).nextStartedActivity.dataString)

        // With activity checking on and no Play Store installed, fall back to the web URL.
        shadowOf(app).checkActivities(true)
        resolvable(SpotifyLinks.playStoreWebIntent(), "com.android.browser")
        SpotifyLinks.openInstall(app)
        assertEquals("https://play.google.com/store/apps/details?id=com.spotify.music", shadowOf(app).nextStartedActivity.dataString)
    }

    @Test
    fun openUninstallFallsBackToAppInfo() {
        SpotifyLinks.openUninstall(app)
        assertEquals(Intent.ACTION_DELETE, shadowOf(app).nextStartedActivity.action)

        shadowOf(app).checkActivities(true)
        resolvable(SpotifyLinks.appInfoIntent(), "com.android.settings")
        SpotifyLinks.openUninstall(app)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app).nextStartedActivity.action)
        assertTrue(EventLog.events.value.any { it.message.contains("Uninstall dialog unavailable") })
    }
}
