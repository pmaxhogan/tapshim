package dev.maxhogan.tapshim.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.maxhogan.tapshim.R
import dev.maxhogan.tapshim.TapShimApp
import dev.maxhogan.tapshim.actions.ActionRunner
import dev.maxhogan.tapshim.actions.TapConfigStore
import dev.maxhogan.tapshim.actions.TapSource
import dev.maxhogan.tapshim.log.EventLog
import dev.maxhogan.tapshim.protocol.TapStreamReader
import dev.maxhogan.tapshim.protocol.toHex
import dev.maxhogan.tapshim.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

enum class SessionState { CONNECTING, LISTENING, RETRYING, GAVE_UP }

data class DeviceSessionInfo(val address: String, val name: String, val state: SessionState, val detail: String = "")

/**
 * Foreground service that plays Spotify's role: for each connected A2DP device
 * it opens an insecure RFCOMM socket to the tap service UUID and reads tap
 * frames until the device goes away.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked in onStartCommand before any device call
class TapSocketService : LifecycleService() {

    private val sessions = HashMap<String, Session>()
    private var lastStartId = 0
    private lateinit var configStore: TapConfigStore
    private lateinit var runner: ActionRunner

    private inner class Session(val device: BluetoothDevice) {
        var job: Job? = null

        @Volatile
        var socket: BluetoothSocket? = null

        @Volatile
        var reader: TapStreamReader? = null

        fun close() {
            reader?.stop()
            try {
                socket?.close()
            } catch (_: IOException) {
            }
            job?.cancel()
        }
    }

    override fun onCreate() {
        super.onCreate()
        configStore = TapConfigStore.from(this)
        runner = ActionRunner(this)
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasConnectPermission()) {
            EventLog.error("BLUETOOTH_CONNECT not granted; cannot listen")
            stopIfIdle()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_DEVICE_CONNECTED -> intent.getStringExtra(EXTRA_ADDRESS)?.let { startSession(it) }
            ACTION_DEVICE_DISCONNECTED -> intent.getStringExtra(EXTRA_ADDRESS)?.let { endSession(it, "device disconnected") }
            ACTION_SYNC -> syncWithConnectedDevices()
            ACTION_STOP -> {
                EventLog.info("Service stopped by user")
                sessions.keys.toList().forEach { endSession(it, "service stopped") }
                stopSelf()
            }
            else -> syncWithConnectedDevices()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sessions.keys.toList().forEach { endSession(it, "service destroyed") }
        running.value = false
        publish()
        super.onDestroy()
    }

    // ---- sessions ---------------------------------------------------------

    private fun startSession(address: String) {
        if (sessions.containsKey(address)) {
            EventLog.info("Already have a session for $address")
            return
        }
        val adapter = bluetoothAdapter() ?: run {
            EventLog.error("No Bluetooth adapter")
            return
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            EventLog.error("Invalid Bluetooth address '$address'")
            return
        }
        val device = adapter.getRemoteDevice(address)
        val session = Session(device)
        sessions[address] = session
        session.job = lifecycleScope.launch(Dispatchers.IO) {
            try {
                runSession(session)
            } catch (_: CancellationException) {
            } finally {
                // The job may already be cancelled here; NonCancellable lets the
                // bookkeeping run anyway so the service can wind down.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    sessions.remove(address)
                    publish()
                    stopIfIdle()
                }
            }
        }
        publish()
    }

    private fun endSession(address: String, reason: String) {
        val s = sessions[address] ?: return
        EventLog.info("Ending session for ${s.label()}: $reason")
        s.close()
    }

    private suspend fun runSession(session: Session) {
        val device = session.device
        val label = session.label()
        val advertised = try {
            device.uuids?.map { it.uuid }
        } catch (e: SecurityException) {
            EventLog.error("$label: cannot read SDP records", e)
            null
        }
        val maxAttempts = when {
            advertised == null -> {
                EventLog.info("$label: no cached SDP records; will try connecting anyway")
                MAX_ATTEMPTS_UNKNOWN
            }
            TAP_SERVICE_UUID in advertised -> {
                EventLog.info("$label: advertises the tap service")
                MAX_ATTEMPTS_SUPPORTED
            }
            else -> {
                EventLog.info("$label: tap service not in cached SDP records (${advertised.size} known); trying briefly")
                MAX_ATTEMPTS_UNSUPPORTED
            }
        }

        var failures = 0
        while (failures < maxAttempts) {
            update(session, SessionState.CONNECTING, "attempt ${failures + 1}/$maxAttempts")
            val socket = try {
                device.createInsecureRfcommSocketToServiceRecord(TAP_SERVICE_UUID)
            } catch (e: IOException) {
                EventLog.error("$label: could not create socket", e)
                null
            }
            if (socket == null) {
                failures++
                if (!backoff(session, failures, maxAttempts)) break
                continue
            }
            session.socket = socket
            var tapsThisConnection = 0
            try {
                EventLog.info("$label: connecting to ${TAP_SERVICE_UUID.toString().uppercase()}")
                socket.connect()
                EventLog.info("$label: connected, listening for taps")
                update(session, SessionState.LISTENING)
                val reader = TapStreamReader(
                    socket.inputStream,
                    onCommand = { cmd ->
                        tapsThisConnection++
                        EventLog.info("$label: tap received")
                        val config = runBlocking { configStore.current() }
                        runner.run(config, TapSource(cmd, device.address))
                    },
                    onError = { e -> EventLog.warn("$label: bad frame [${e.raw.toHex()}]: ${e.message}") },
                    onGarbage = { g -> EventLog.warn("$label: discarded ${g.size} byte(s) before header [${g.toHex()}]") },
                )
                session.reader = reader
                reader.run()
                if (session.job?.isActive != true) return
                if (tapsThisConnection > 0) {
                    EventLog.info("$label: stream closed by device after $tapsThisConnection tap(s); reconnecting")
                    failures = 0
                } else {
                    EventLog.warn("$label: stream closed by device before any tap")
                    failures++
                }
            } catch (e: IOException) {
                // A cancelled session closes its own socket; that IOException is expected.
                if (session.job?.isActive != true) return
                EventLog.warn("$label: socket error: ${e.message}")
                failures++
            } finally {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                session.socket = null
                session.reader = null
            }
            if (session.job?.isActive != true) return
            if (failures == 0) {
                update(session, SessionState.RETRYING, "reconnecting")
                delay(RECONNECT_AFTER_EOF_MS)
            } else if (!backoff(session, failures, maxAttempts)) {
                break
            }
        }
        EventLog.error("$label: giving up after $maxAttempts failed attempt(s). Reconnect the headphones to retry.")
        update(session, SessionState.GAVE_UP, "gave up after $maxAttempts attempts")
        delay(GAVE_UP_LINGER_MS)
    }

    /** Wait before the next attempt. Returns false when the budget is exhausted. */
    private suspend fun backoff(session: Session, failures: Int, maxAttempts: Int): Boolean {
        if (failures >= maxAttempts) return false
        val ms = (BACKOFF_BASE_MS shl (failures - 1)).coerceAtMost(BACKOFF_MAX_MS)
        update(session, SessionState.RETRYING, "retry in ${ms / 1000}s (${failures}/$maxAttempts failed)")
        EventLog.info("${session.label()}: retrying in ${ms / 1000}s")
        delay(ms)
        return true
    }

    private fun syncWithConnectedDevices() {
        val adapter = bluetoothAdapter() ?: run {
            EventLog.error("No Bluetooth adapter")
            stopIfIdle()
            return
        }
        adapter.getProfileProxy(
            this,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        val devices = proxy.connectedDevices
                        EventLog.info("Sync: ${devices.size} A2DP device(s) connected")
                        lifecycleScope.launch {
                            devices.forEach { startSession(it.address) }
                            stopIfIdle()
                        }
                    } catch (e: SecurityException) {
                        EventLog.error("Sync failed", e)
                    } finally {
                        adapter.closeProfileProxy(profile, proxy)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            },
            BluetoothProfile.A2DP,
        )
    }

    private fun stopIfIdle() {
        if (sessions.isEmpty()) {
            // stopSelfResult only stops if no newer start command has arrived,
            // so a device that connected while a sync was in flight is kept.
            if (stopSelfResult(lastStartId)) {
                EventLog.info("No devices to listen to; service idle, stopping")
            }
        } else {
            updateNotification()
        }
    }

    // ---- plumbing ---------------------------------------------------------

    private fun goForeground(): Boolean {
        val notification = buildNotification()
        return try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            EventLog.error("Android refused to start the foreground service from the background", e)
            false
        } catch (e: SecurityException) {
            EventLog.error("Missing a permission needed for the foreground service", e)
            false
        }
    }

    private fun updateNotification() {
        getSystemService(android.app.NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (sessions.size) {
            0 -> getString(R.string.notif_idle)
            else -> sessions.values.joinToString(", ") { it.label() }
        }
        return NotificationCompat.Builder(this, TapShimApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun update(session: Session, state: SessionState, detail: String = "") {
        val info = DeviceSessionInfo(session.device.address, session.label(), state, detail)
        val current = sessionStates.value.toMutableMap()
        current[session.device.address] = info
        sessionStates.value = current
    }

    private fun publish() {
        val current = sessionStates.value.filterKeys { it in sessions }
        sessionStates.value = current
        updateNotification()
    }

    private fun Session.label(): String = deviceLabel(device)

    private fun deviceLabel(device: BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: device.address
    } catch (_: SecurityException) {
        device.address
    }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothAdapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

    companion object {
        /** SDP UUID Spotify's Android app connects to; the headphones are the RFCOMM server. */
        val TAP_SERVICE_UUID: UUID = UUID.fromString("9B26D8C0-A8ED-440B-95B0-C4714A518BCC")

        const val ACTION_DEVICE_CONNECTED = "dev.maxhogan.tapshim.action.DEVICE_CONNECTED"
        const val ACTION_DEVICE_DISCONNECTED = "dev.maxhogan.tapshim.action.DEVICE_DISCONNECTED"
        const val ACTION_SYNC = "dev.maxhogan.tapshim.action.SYNC"
        const val ACTION_STOP = "dev.maxhogan.tapshim.action.STOP"
        const val EXTRA_ADDRESS = "address"

        private const val NOTIFICATION_ID = 1
        private const val MAX_ATTEMPTS_SUPPORTED = 6
        private const val MAX_ATTEMPTS_UNKNOWN = 4
        private const val MAX_ATTEMPTS_UNSUPPORTED = 2
        private const val BACKOFF_BASE_MS = 1_000L
        private const val BACKOFF_MAX_MS = 16_000L
        private const val RECONNECT_AFTER_EOF_MS = 1_500L
        private const val GAVE_UP_LINGER_MS = 5_000L

        val running = MutableStateFlow(false)
        private val sessionStates = MutableStateFlow<Map<String, DeviceSessionInfo>>(emptyMap())
        val sessionInfo: StateFlow<Map<String, DeviceSessionInfo>> = sessionStates

        fun deviceConnected(context: Context, address: String) =
            start(context, Intent(context, TapSocketService::class.java).setAction(ACTION_DEVICE_CONNECTED).putExtra(EXTRA_ADDRESS, address))

        fun deviceDisconnected(context: Context, address: String) {
            if (!running.value) return
            start(context, Intent(context, TapSocketService::class.java).setAction(ACTION_DEVICE_DISCONNECTED).putExtra(EXTRA_ADDRESS, address))
        }

        fun sync(context: Context) = start(context, Intent(context, TapSocketService::class.java).setAction(ACTION_SYNC))

        fun stop(context: Context) {
            if (!running.value) return
            start(context, Intent(context, TapSocketService::class.java).setAction(ACTION_STOP))
        }

        private fun start(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                EventLog.error("Could not start service for ${intent.action}", e)
            }
        }
    }
}
