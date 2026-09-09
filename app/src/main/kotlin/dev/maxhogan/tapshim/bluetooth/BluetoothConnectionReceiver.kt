package dev.maxhogan.tapshim.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import dev.maxhogan.tapshim.log.EventLog

/**
 * Manifest-registered so it fires even when the app process is dead. Receiving
 * a BLUETOOTH_CONNECT-gated broadcast is one of the documented exemptions that
 * lets an app start a foreground service from the background.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED && action != ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED) return

        val device: BluetoothDevice? = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        val address = device?.address ?: return
        val profile = if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) "A2DP" else "LE Audio"

        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                EventLog.info("$profile connected: $address")
                TapSocketService.deviceConnected(context, address)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                EventLog.info("$profile disconnected: $address")
                TapSocketService.deviceDisconnected(context, address)
            }
        }
    }

    companion object {
        // BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED (API 33); spelled out to keep minSdk 31.
        const val ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED = "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED"
    }
}
