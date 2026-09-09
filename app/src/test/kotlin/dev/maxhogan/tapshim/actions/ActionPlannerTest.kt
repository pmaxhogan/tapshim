package dev.maxhogan.tapshim.actions

import android.content.ComponentName
import android.content.Intent
import dev.maxhogan.tapshim.protocol.TapPacketParser
import dev.maxhogan.tapshim.protocol.TapPacketParserTest.Companion.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionPlannerTest {

    private val command = TapPacketParser.parse(frame("client-1", "QC Ultra", "Bose"))
    private val source = TapSource(command, "AA:BB:CC:DD:EE:FF")

    private fun planner(installed: Set<String> = setOf(TapConfig.DEFAULT_LAUNCH_PACKAGE)) =
        ActionPlanner { pkg ->
            if (pkg in installed) Intent(Intent.ACTION_MAIN).setPackage(pkg).addCategory(Intent.CATEGORY_LAUNCHER) else null
        }

    @Test
    fun defaultConfigBroadcastsAndLaunchesYouTubeMusic() {
        val plan = planner().plan(TapConfig(), source)
        assertEquals(2, plan.size)

        val broadcast = plan[0] as PlannedAction.SendBroadcast
        assertEquals(TapConfig.BROADCAST_ACTION, broadcast.intent.action)
        assertEquals("client-1", broadcast.intent.getStringExtra(TapConfig.EXTRA_CLIENT_ID))
        assertEquals("QC Ultra", broadcast.intent.getStringExtra(TapConfig.EXTRA_DEVICE_NAME))
        assertEquals("Bose", broadcast.intent.getStringExtra(TapConfig.EXTRA_MANUFACTURER))
        assertEquals("AA:BB:CC:DD:EE:FF", broadcast.intent.getStringExtra(TapConfig.EXTRA_ADDRESS))
        assertEquals(command.rawHex, broadcast.intent.getStringExtra(TapConfig.EXTRA_RAW_HEX))
        assertEquals(false, broadcast.intent.getBooleanExtra(TapConfig.EXTRA_SIMULATED, true))
        assertTrue(broadcast.intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES != 0)

        val launch = plan[1] as PlannedAction.StartActivity
        assertEquals(TapConfig.DEFAULT_LAUNCH_PACKAGE, launch.intent.`package`)
        assertTrue(launch.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun simulatedFlagIsCarried() {
        val plan = planner().plan(TapConfig(launchAppEnabled = false), source.copy(simulated = true, address = null))
        val broadcast = plan.single() as PlannedAction.SendBroadcast
        assertEquals(true, broadcast.intent.getBooleanExtra(TapConfig.EXTRA_SIMULATED, false))
        assertEquals("", broadcast.intent.getStringExtra(TapConfig.EXTRA_ADDRESS))
    }

    @Test
    fun everythingDisabledYieldsEmptyPlan() {
        val config = TapConfig(launchAppEnabled = false, broadcastEnabled = false)
        assertTrue(planner().plan(config, source).isEmpty())
    }

    @Test
    fun missingLaunchAppIsSkippedNotThrown() {
        val plan = planner(installed = emptySet()).plan(TapConfig(broadcastEnabled = false), source)
        val skipped = plan.single() as PlannedAction.Skipped
        assertTrue(skipped.reason.contains(TapConfig.DEFAULT_LAUNCH_PACKAGE))
    }

    @Test
    fun blankLaunchPackageIsSkipped() {
        val plan = planner().plan(TapConfig(broadcastEnabled = false, launchAppPackage = "  "), source)
        assertTrue(plan.single() is PlannedAction.Skipped)
    }

    @Test
    fun openUrlBuildsViewIntent() {
        val config = TapConfig(launchAppEnabled = false, broadcastEnabled = false, openUrlEnabled = true, url = "https://music.youtube.com/")
        val action = planner().plan(config, source).single() as PlannedAction.StartActivity
        assertEquals(Intent.ACTION_VIEW, action.intent.action)
        assertEquals("https://music.youtube.com/", action.intent.data.toString())
        assertTrue(action.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun blankUrlIsSkipped() {
        val config = TapConfig(launchAppEnabled = false, broadcastEnabled = false, openUrlEnabled = true, url = "")
        assertTrue(planner().plan(config, source).single() is PlannedAction.Skipped)
    }

    @Test
    fun openActivityBuildsExplicitIntent() {
        val config = TapConfig(
            launchAppEnabled = false,
            broadcastEnabled = false,
            openActivityEnabled = true,
            activityComponent = "com.example.app/.MainActivity",
        )
        val action = planner().plan(config, source).single() as PlannedAction.StartActivity
        assertEquals(ComponentName("com.example.app", "com.example.app.MainActivity"), action.intent.component)
    }

    @Test
    fun invalidComponentIsSkipped() {
        val config = TapConfig(launchAppEnabled = false, broadcastEnabled = false, openActivityEnabled = true, activityComponent = "nonsense")
        assertTrue(planner().plan(config, source).single() is PlannedAction.Skipped)
    }

    @Test
    fun allFourActionsInOrder() {
        val config = TapConfig(
            openUrlEnabled = true,
            url = "https://example.com",
            openActivityEnabled = true,
            activityComponent = "a.b/.C",
        )
        val plan = planner().plan(config, source)
        assertEquals(
            listOf("SendBroadcast", "StartActivity", "StartActivity", "StartActivity"),
            plan.map { it::class.simpleName },
        )
    }

    @Test
    fun needsActivityStartReflectsConfig() {
        assertTrue(TapConfig().needsActivityStart)
        assertEquals(false, TapConfig(launchAppEnabled = false).needsActivityStart)
        assertTrue(TapConfig(launchAppEnabled = false, openUrlEnabled = true).needsActivityStart)
    }
}
