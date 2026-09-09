package dev.maxhogan.tapshim.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.maxhogan.tapshim.log.EventLog

/** After boot or an app update, pick up any headphones that are already connected. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                EventLog.info("${intent.action}: syncing with connected devices")
                TapSocketService.sync(context)
            }
        }
    }
}
