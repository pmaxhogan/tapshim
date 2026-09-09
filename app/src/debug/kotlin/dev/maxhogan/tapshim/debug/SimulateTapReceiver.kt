package dev.maxhogan.tapshim.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.maxhogan.tapshim.actions.TapSimulator
import dev.maxhogan.tapshim.bluetooth.TapSocketService
import dev.maxhogan.tapshim.log.EventLog

/**
 * Debug builds only. Lets adb drive the app without headphones:
 *
 *   # run the configured actions as if a tap arrived
 *   adb shell am broadcast -a dev.maxhogan.tapshim.debug.SIMULATE_TAP \
 *     -n dev.maxhogan.tapshim.debug/dev.maxhogan.tapshim.debug.SimulateTapReceiver
 *
 *   # pretend a device with this address just connected over A2DP
 *   adb shell am broadcast -a dev.maxhogan.tapshim.debug.CONNECT --es address AA:BB:CC:DD:EE:FF \
 *     -n dev.maxhogan.tapshim.debug/dev.maxhogan.tapshim.debug.SimulateTapReceiver
 */
class SimulateTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SIMULATE_TAP -> {
                EventLog.info("adb: simulate tap requested")
                val pending = goAsync()
                TapSimulator.simulate(context.applicationContext) { pending.finish() }
            }
            ACTION_CONNECT -> {
                val address = intent.getStringExtra("address") ?: return
                EventLog.info("adb: pretend $address connected")
                TapSocketService.deviceConnected(context, address)
            }
            ACTION_DISCONNECT -> {
                val address = intent.getStringExtra("address") ?: return
                EventLog.info("adb: pretend $address disconnected")
                TapSocketService.deviceDisconnected(context, address)
            }
        }
    }

    companion object {
        const val ACTION_SIMULATE_TAP = "dev.maxhogan.tapshim.debug.SIMULATE_TAP"
        const val ACTION_CONNECT = "dev.maxhogan.tapshim.debug.CONNECT"
        const val ACTION_DISCONNECT = "dev.maxhogan.tapshim.debug.DISCONNECT"
    }
}
