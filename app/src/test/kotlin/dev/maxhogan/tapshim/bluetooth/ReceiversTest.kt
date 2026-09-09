package dev.maxhogan.tapshim.bluetooth

import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.maxhogan.tapshim.log.EventLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReceiversTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val address = "AA:BB:CC:DD:EE:FF"
    private val device: BluetoothDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)

    @Before
    fun setUp() {
        EventLog.resetForTest()
        EventLog.init(app)
        EventLog.clear()
        TapSocketService.running.value = false
        shadowOf(app).clearStartedServices()
    }

    @After
    fun tearDown() {
        TapSocketService.running.value = false
        EventLog.clear()
        EventLog.resetForTest()
    }

    private fun a2dp(state: Int, action: String = BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED): Intent =
        Intent(action)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            .putExtra(BluetoothProfile.EXTRA_STATE, state)

    @Test
    fun a2dpConnectedStartsServiceWithAddress() {
        BluetoothConnectionReceiver().onReceive(app, a2dp(BluetoothProfile.STATE_CONNECTED))
        val started = shadowOf(app).nextStartedService
        assertEquals(TapSocketService.ACTION_DEVICE_CONNECTED, started.action)
        assertEquals(address, started.getStringExtra(TapSocketService.EXTRA_ADDRESS))
        assertEquals(TapSocketService::class.java.name, started.component?.className)
    }

    @Test
    fun leAudioConnectedAlsoStartsService() {
        BluetoothConnectionReceiver().onReceive(
            app,
            a2dp(BluetoothProfile.STATE_CONNECTED, BluetoothConnectionReceiver.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED),
        )
        assertEquals(TapSocketService.ACTION_DEVICE_CONNECTED, shadowOf(app).nextStartedService.action)
    }

    @Test
    fun disconnectIsIgnoredWhenServiceNotRunning() {
        BluetoothConnectionReceiver().onReceive(app, a2dp(BluetoothProfile.STATE_DISCONNECTED))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun disconnectForwardedWhenServiceRunning() {
        TapSocketService.running.value = true
        BluetoothConnectionReceiver().onReceive(app, a2dp(BluetoothProfile.STATE_DISCONNECTED))
        val started = shadowOf(app).nextStartedService
        assertEquals(TapSocketService.ACTION_DEVICE_DISCONNECTED, started.action)
        assertEquals(address, started.getStringExtra(TapSocketService.EXTRA_ADDRESS))
    }

    @Test
    fun intermediateStatesDoNothing() {
        BluetoothConnectionReceiver().onReceive(app, a2dp(BluetoothProfile.STATE_CONNECTING))
        BluetoothConnectionReceiver().onReceive(app, a2dp(BluetoothProfile.STATE_DISCONNECTING))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun unrelatedActionAndMissingDeviceAreIgnored() {
        BluetoothConnectionReceiver().onReceive(app, Intent("some.other.ACTION"))
        BluetoothConnectionReceiver().onReceive(
            app,
            Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED).putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED),
        )
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun bootAndPackageReplacedTriggerSync() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(TapSocketService.ACTION_SYNC, shadowOf(app).nextStartedService.action)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(TapSocketService.ACTION_SYNC, shadowOf(app).nextStartedService.action)
        BootReceiver().onReceive(app, Intent("nope"))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun stopOnlySentWhileRunning() {
        TapSocketService.stop(app)
        assertNull(shadowOf(app).nextStartedService)
        TapSocketService.running.value = true
        TapSocketService.stop(app)
        assertEquals(TapSocketService.ACTION_STOP, shadowOf(app).nextStartedService.action)
    }

    @Test
    fun sessionInfoIsPlainData() {
        val info = DeviceSessionInfo(address, "QC Ultra", SessionState.LISTENING, "")
        assertEquals(SessionState.LISTENING, info.state)
        assertEquals(info, info.copy())
    }
}
