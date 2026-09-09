package dev.maxhogan.tapshim.log

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Level { INFO, WARN, ERROR }

data class Event(val timeMillis: Long, val level: Level, val message: String) {
    val timeText: String get() = TIME_FORMAT.format(Date(timeMillis))

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    }
}

/**
 * The app's diagnostic memory. Everything the socket service does is recorded
 * here so a user can see what happened on a phone with no adb attached. Kept in
 * memory for the UI and appended to a file so it survives process death.
 */
object EventLog {
    private const val TAG = "TapShim"
    private const val MAX_EVENTS = 300
    private const val FILE_NAME = "events.log"

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events

    @Volatile
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        if (f.exists()) {
            val loaded = f.readLines().mapNotNull { parse(it) }.takeLast(MAX_EVENTS)
            _events.value = loaded
        }
    }

    fun info(message: String) = add(Level.INFO, message)
    fun warn(message: String) = add(Level.WARN, message)
    fun error(message: String, t: Throwable? = null) {
        val full = if (t != null) "$message: ${t.javaClass.simpleName}: ${t.message}" else message
        add(Level.ERROR, full)
    }

    @Synchronized
    fun clear() {
        _events.value = emptyList()
        file?.delete()
    }

    /** Forget the backing file and in-memory events so a test can re-init from disk. */
    @Synchronized
    internal fun resetForTest() {
        file = null
        _events.value = emptyList()
    }

    @Synchronized
    private fun add(level: Level, message: String) {
        val e = Event(System.currentTimeMillis(), level, message)
        when (level) {
            Level.INFO -> Log.i(TAG, message)
            Level.WARN -> Log.w(TAG, message)
            Level.ERROR -> Log.e(TAG, message)
        }
        _events.value = (_events.value + e).takeLast(MAX_EVENTS)
        try {
            file?.appendText(serialize(e))
        } catch (t: Throwable) {
            Log.w(TAG, "could not persist event", t)
        }
    }

    private fun serialize(e: Event): String =
        "${e.timeMillis}\t${e.level}\t${e.message.replace('\n', ' ').replace('\t', ' ')}\n"

    private fun parse(line: String): Event? {
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) return null
        val t = parts[0].toLongOrNull() ?: return null
        val level = runCatching { Level.valueOf(parts[1]) }.getOrNull() ?: return null
        return Event(t, level, parts[2])
    }
}
