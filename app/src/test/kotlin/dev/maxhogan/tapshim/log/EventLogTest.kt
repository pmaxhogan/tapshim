package dev.maxhogan.tapshim.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventLogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file get() = File(context.filesDir, "events.log")

    @Before
    fun setUp() {
        EventLog.resetForTest()
        file.delete()
        EventLog.init(context)
    }

    @After
    fun tearDown() {
        EventLog.resetForTest()
        file.delete()
    }

    @Test
    fun recordsEventsWithLevels() {
        EventLog.info("hello")
        EventLog.warn("careful")
        EventLog.error("boom", IllegalStateException("bad state"))
        val events = EventLog.events.value
        assertEquals(listOf(Level.INFO, Level.WARN, Level.ERROR), events.map { it.level })
        assertEquals("hello", events[0].message)
        assertEquals("boom: IllegalStateException: bad state", events[2].message)
        assertTrue(events[0].timeText.matches(Regex("\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d")))
    }

    @Test
    fun errorWithoutThrowableKeepsMessage() {
        EventLog.error("plain")
        assertEquals("plain", EventLog.events.value.single().message)
    }

    @Test
    fun persistsAndReloadsAcrossInit() {
        EventLog.info("first")
        EventLog.warn("second\twith tab\nand newline")
        assertTrue(file.exists())

        EventLog.resetForTest()
        assertTrue(EventLog.events.value.isEmpty())
        EventLog.init(context)

        val reloaded = EventLog.events.value
        assertEquals(2, reloaded.size)
        assertEquals("first", reloaded[0].message)
        assertEquals(Level.WARN, reloaded[1].level)
        assertEquals("second with tab and newline", reloaded[1].message)
    }

    @Test
    fun initIsIdempotent() {
        EventLog.info("once")
        EventLog.init(context)
        assertEquals(1, EventLog.events.value.size)
    }

    @Test
    fun clearWipesMemoryAndFile() {
        EventLog.info("x")
        EventLog.clear()
        assertTrue(EventLog.events.value.isEmpty())
        assertFalse(file.exists())
        // Logging after clear recreates the file.
        EventLog.info("y")
        assertTrue(file.exists())
    }

    @Test
    fun capsInMemoryEventsAtThreeHundred() {
        repeat(350) { EventLog.info("event $it") }
        val events = EventLog.events.value
        assertEquals(300, events.size)
        assertEquals("event 50", events.first().message)
        assertEquals("event 349", events.last().message)
    }

    @Test
    fun reloadSkipsMalformedLinesAndKeepsLastThreeHundred() {
        EventLog.resetForTest()
        val lines = buildString {
            append("garbage line\n")
            append("notanumber\tINFO\tx\n")
            append("123\tNOPE\tx\n")
            append("123\tINFO\n")
            repeat(320) { append("${1000 + it}\tINFO\tmsg $it\n") }
        }
        file.writeText(lines)
        EventLog.init(context)
        val events = EventLog.events.value
        assertEquals(300, events.size)
        assertEquals("msg 20", events.first().message)
        assertEquals(1020L, events.first().timeMillis)
    }
}
