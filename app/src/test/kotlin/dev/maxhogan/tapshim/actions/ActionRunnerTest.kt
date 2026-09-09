package dev.maxhogan.tapshim.actions

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import dev.maxhogan.tapshim.log.EventLog
import dev.maxhogan.tapshim.log.Level
import dev.maxhogan.tapshim.protocol.TapPacketParser
import dev.maxhogan.tapshim.protocol.TapPacketParserTest.Companion.frame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionRunnerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val command = TapPacketParser.parse(frame("c", "QC Ultra", "Bose"))
    private val source = TapSource(command, "AA:BB:CC:DD:EE:FF")

    @Before
    fun setUp() {
        EventLog.resetForTest()
        EventLog.init(app)
        EventLog.clear()
        ShadowSettings.setCanDrawOverlays(true)
        installFakeApp(TapConfig.DEFAULT_LAUNCH_PACKAGE)
    }

    @After
    fun tearDown() {
        EventLog.clear()
        EventLog.resetForTest()
    }

    private fun installFakeApp(pkg: String) {
        val pm = shadowOf(app.packageManager)
        val info = PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
        pm.installPackage(info)
        val activity = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.MainActivity"
            applicationInfo = info.applicationInfo
        }
        val resolve = ResolveInfo().apply { activityInfo = activity }
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), resolve)
    }

    private fun messages() = EventLog.events.value.map { it.message }

    @Test
    fun defaultConfigSendsBroadcastAndLaunchesApp() {
        ActionRunner(app).run(TapConfig(), source)

        val broadcast = shadowOf(app).broadcastIntents.single()
        assertEquals(TapConfig.BROADCAST_ACTION, broadcast.action)
        assertEquals("QC Ultra", broadcast.getStringExtra(TapConfig.EXTRA_DEVICE_NAME))

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(TapConfig.DEFAULT_LAUNCH_PACKAGE, started.component?.packageName)

        val log = messages()
        assertTrue(log.any { it.startsWith("Handling tap from AA:BB:CC:DD:EE:FF") })
        assertTrue(log.any { it.contains("Broadcast ${TapConfig.BROADCAST_ACTION}: sent") })
        assertTrue(log.any { it.contains("Launch app ${TapConfig.DEFAULT_LAUNCH_PACKAGE}: started") })
    }

    @Test
    fun simulatedTapIsLabelledInLog() {
        ActionRunner(app).run(TapConfig(launchAppEnabled = false), source.copy(simulated = true, address = null))
        assertTrue(messages().any { it.startsWith("Handling simulated tap") })
    }

    @Test
    fun nothingEnabledLogsWarning() {
        ActionRunner(app).run(TapConfig(launchAppEnabled = false, broadcastEnabled = false), source)
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(shadowOf(app).broadcastIntents.isEmpty())
        assertTrue(EventLog.events.value.any { it.level == Level.WARN && it.message.contains("No actions enabled") })
    }

    @Test
    fun missingOverlayPermissionIsWarnedWhenAnActivityWouldStart() {
        ShadowSettings.setCanDrawOverlays(false)
        ActionRunner(app).run(TapConfig(broadcastEnabled = false), source)
        assertTrue(messages().any { it.contains("Display over other apps") })
    }

    @Test
    fun missingOverlayPermissionIsNotWarnedForBroadcastOnly() {
        ShadowSettings.setCanDrawOverlays(false)
        ActionRunner(app).run(TapConfig(launchAppEnabled = false), source)
        assertTrue(messages().none { it.contains("Display over other apps") })
    }

    @Test
    fun uninstalledAppIsSkippedWithReason() {
        val config = TapConfig(broadcastEnabled = false, launchAppPackage = "com.example.missing")
        ActionRunner(app).run(config, source)
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(EventLog.events.value.any { it.level == Level.WARN && it.message.contains("com.example.missing is not installed") })
    }

    @Test
    fun unresolvableActivityIsLoggedAsError() {
        shadowOf(app).checkActivities(true)
        val config = TapConfig(
            launchAppEnabled = false,
            broadcastEnabled = false,
            openActivityEnabled = true,
            activityComponent = "com.example.nope/.Nope",
        )
        ActionRunner(app).run(config, source)
        assertTrue(EventLog.events.value.any { it.level == Level.ERROR && it.message.contains("no activity found") })
    }

    @Test
    fun openUrlStartsViewIntent() {
        val config = TapConfig(launchAppEnabled = false, broadcastEnabled = false, openUrlEnabled = true, url = "https://music.youtube.com/")
        ActionRunner(app).run(config, source)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://music.youtube.com/", started.dataString)
    }

    @Test
    fun explicitActivityStartsWithComponent() {
        val config = TapConfig(
            launchAppEnabled = false,
            broadcastEnabled = false,
            openActivityEnabled = true,
            activityComponent = "com.example.app/.Main",
        )
        ActionRunner(app).run(config, source)
        assertEquals(ComponentName("com.example.app", "com.example.app.Main"), shadowOf(app).nextStartedActivity.component)
    }
}
