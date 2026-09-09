package dev.maxhogan.tapshim.actions

/** What to do when the headphones send a tap. Every action is independent. */
data class TapConfig(
    val launchAppEnabled: Boolean = true,
    val launchAppPackage: String = DEFAULT_LAUNCH_PACKAGE,
    val broadcastEnabled: Boolean = true,
    val openUrlEnabled: Boolean = false,
    val url: String = "",
    val openActivityEnabled: Boolean = false,
    /** Flattened component, e.g. "com.example.app/com.example.app.SomeActivity". */
    val activityComponent: String = "",
) {
    val needsActivityStart: Boolean
        get() = launchAppEnabled || openUrlEnabled || openActivityEnabled

    companion object {
        const val DEFAULT_LAUNCH_PACKAGE = "com.google.android.apps.youtube.music"

        /** Broadcast action Tasker (Event > System > Intent Received) can subscribe to. */
        const val BROADCAST_ACTION = "dev.maxhogan.tapshim.TAP"
        const val EXTRA_CLIENT_ID = "clientId"
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_MANUFACTURER = "manufacturer"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_RAW_HEX = "rawHex"
        const val EXTRA_SIMULATED = "simulated"
    }
}
