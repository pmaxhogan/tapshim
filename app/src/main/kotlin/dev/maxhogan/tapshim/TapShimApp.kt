package dev.maxhogan.tapshim

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.maxhogan.tapshim.log.EventLog

class TapShimApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "tapshim_listener"
    }
}
