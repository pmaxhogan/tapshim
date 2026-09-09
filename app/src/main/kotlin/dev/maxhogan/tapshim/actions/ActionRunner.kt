package dev.maxhogan.tapshim.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.provider.Settings
import dev.maxhogan.tapshim.log.EventLog

/** Executes a [ActionPlanner] plan against the real system and logs each step. */
class ActionRunner(private val context: Context) {
    private val planner = ActionPlanner { pkg -> context.packageManager.getLaunchIntentForPackage(pkg) }

    fun run(config: TapConfig, source: TapSource) {
        val who = if (source.simulated) "simulated tap" else "tap from ${source.address ?: "unknown"}"
        EventLog.info(
            "Handling $who: clientId=${source.command.clientId} device=${source.command.deviceName} " +
                "maker=${source.command.manufacturer} raw=[${source.command.rawHex}]",
        )
        val plan = planner.plan(config, source)
        if (plan.isEmpty()) {
            EventLog.warn("No actions enabled; nothing to do")
            return
        }
        val needsOverlay = plan.any { it is PlannedAction.StartActivity }
        if (needsOverlay && !Settings.canDrawOverlays(context)) {
            EventLog.warn("'Display over other apps' is not granted; Android will block launching from the background")
        }
        for (action in plan) {
            when (action) {
                is PlannedAction.Skipped -> EventLog.warn("${action.label}: skipped, ${action.reason}")
                is PlannedAction.SendBroadcast -> try {
                    context.sendBroadcast(action.intent)
                    EventLog.info("${action.label}: sent")
                } catch (t: Throwable) {
                    EventLog.error("${action.label}: failed", t)
                }
                is PlannedAction.StartActivity -> try {
                    context.startActivity(action.intent)
                    EventLog.info("${action.label}: started")
                } catch (e: ActivityNotFoundException) {
                    EventLog.error("${action.label}: no activity found", e)
                } catch (e: SecurityException) {
                    EventLog.error("${action.label}: not allowed", e)
                } catch (t: Throwable) {
                    EventLog.error("${action.label}: failed", t)
                }
            }
        }
    }
}
