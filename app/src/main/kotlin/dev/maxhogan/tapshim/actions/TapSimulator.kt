package dev.maxhogan.tapshim.actions

import android.content.Context
import dev.maxhogan.tapshim.protocol.TapCommand
import dev.maxhogan.tapshim.protocol.TapPacketParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs the configured actions as if the headphones had just sent a tap. */
object TapSimulator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A frame shaped like the real thing so the log and broadcast look identical. */
    fun fakeCommand(): TapCommand {
        val payload = "tapshim-simulated\u0000Simulated headphones\u0000TapShim\u0000".toByteArray(Charsets.US_ASCII)
        return TapPacketParser.parse(byteArrayOf(TapPacketParser.HEADER, payload.size.toByte()) + payload)
    }

    fun simulate(context: Context, onDone: () -> Unit = {}) {
        scope.launch {
            try {
                val config = TapConfigStore.from(context).current()
                withContext(Dispatchers.Main) {
                    ActionRunner(context).run(config, TapSource(fakeCommand(), address = null, simulated = true))
                }
            } finally {
                onDone()
            }
        }
    }
}
