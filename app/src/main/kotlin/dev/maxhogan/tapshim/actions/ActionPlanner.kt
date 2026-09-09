package dev.maxhogan.tapshim.actions

import android.content.ComponentName
import android.content.Intent
import androidx.core.net.toUri
import dev.maxhogan.tapshim.protocol.TapCommand

/** Where a tap came from, for the broadcast extras and the event log. */
data class TapSource(
    val command: TapCommand,
    val address: String?,
    val simulated: Boolean = false,
)

sealed class PlannedAction {
    abstract val label: String
    abstract val intent: Intent

    data class StartActivity(override val label: String, override val intent: Intent) : PlannedAction()
    data class SendBroadcast(override val label: String, override val intent: Intent) : PlannedAction()
    data class Skipped(override val label: String, val reason: String) : PlannedAction() {
        override val intent: Intent get() = Intent()
    }
}

/**
 * Pure function from config + tap to the list of intents to fire. Kept free of
 * Context so it can be unit tested; the only environment dependency is how a
 * package name resolves to its launch intent.
 */
class ActionPlanner(private val launchIntentFor: (String) -> Intent?) {

    fun plan(config: TapConfig, source: TapSource): List<PlannedAction> {
        val out = ArrayList<PlannedAction>(4)

        if (config.broadcastEnabled) {
            val intent = Intent(TapConfig.BROADCAST_ACTION)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(TapConfig.EXTRA_CLIENT_ID, source.command.clientId)
                .putExtra(TapConfig.EXTRA_DEVICE_NAME, source.command.deviceName)
                .putExtra(TapConfig.EXTRA_MANUFACTURER, source.command.manufacturer)
                .putExtra(TapConfig.EXTRA_ADDRESS, source.address ?: "")
                .putExtra(TapConfig.EXTRA_RAW_HEX, source.command.rawHex)
                .putExtra(TapConfig.EXTRA_SIMULATED, source.simulated)
            out.add(PlannedAction.SendBroadcast("Broadcast ${TapConfig.BROADCAST_ACTION}", intent))
        }

        if (config.launchAppEnabled) {
            val pkg = config.launchAppPackage.trim()
            when {
                pkg.isEmpty() -> out.add(PlannedAction.Skipped("Launch app", "no package configured"))
                else -> {
                    val intent = launchIntentFor(pkg)
                    if (intent == null) {
                        out.add(PlannedAction.Skipped("Launch app", "$pkg is not installed or has no launcher activity"))
                    } else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        out.add(PlannedAction.StartActivity("Launch app $pkg", intent))
                    }
                }
            }
        }

        if (config.openUrlEnabled) {
            val url = config.url.trim()
            if (url.isEmpty()) {
                out.add(PlannedAction.Skipped("Open URL", "no URL configured"))
            } else {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                out.add(PlannedAction.StartActivity("Open URL $url", intent))
            }
        }

        if (config.openActivityEnabled) {
            val flat = config.activityComponent.trim()
            val component = ComponentName.unflattenFromString(flat)
            if (component == null) {
                out.add(PlannedAction.Skipped("Open activity", "invalid component '$flat' (want pkg/cls)"))
            } else {
                val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                out.add(PlannedAction.StartActivity("Open activity ${component.flattenToShortString()}", intent))
            }
        }

        return out
    }
}
