package dev.maxhogan.tapshim.actions

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.maxhogan.tapshim.log.EventLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TapSimulatorTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        EventLog.resetForTest()
        EventLog.init(app)
        EventLog.clear()
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After
    fun tearDown() {
        EventLog.clear()
        EventLog.resetForTest()
    }

    @Test
    fun fakeCommandLooksLikeTheRealThing() {
        val cmd = TapSimulator.fakeCommand()
        assertEquals("tapshim-simulated", cmd.clientId)
        assertEquals("Simulated headphones", cmd.deviceName)
        assertEquals("TapShim", cmd.manufacturer)
        assertEquals(0x01.toByte(), cmd.raw[0])
        assertEquals(cmd.raw.size - 2, cmd.raw[1].toInt())
    }

    @Test
    fun simulateRunsConfiguredActionsAndCallsBack() {
        val latch = CountDownLatch(1)
        TapSimulator.simulate(app) { latch.countDown() }
        val deadline = System.currentTimeMillis() + 10_000
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("simulate did not finish", latch.await(0, TimeUnit.MILLISECONDS))
        shadowOf(Looper.getMainLooper()).idle()

        val broadcast = shadowOf(app).broadcastIntents.single()
        assertEquals(TapConfig.BROADCAST_ACTION, broadcast.action)
        assertEquals(true, broadcast.getBooleanExtra(TapConfig.EXTRA_SIMULATED, false))
        assertTrue(EventLog.events.value.any { it.message.startsWith("Handling simulated tap") })
    }
}
