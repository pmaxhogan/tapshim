package dev.maxhogan.tapshim.actions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TapConfigStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store by lazy {
        TapConfigStore(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("test.preferences_pb") })
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun fromContextUsesTheAppSingleton() {
        val a = TapConfigStore.from(ApplicationProvider.getApplicationContext())
        val b = TapConfigStore.from(ApplicationProvider.getApplicationContext())
        // Both wrap the same process-wide DataStore, so reads agree.
        runBlocking { assertEquals(a.current(), b.current()) }
    }

    @Test
    fun defaultsWhenNothingStored() = runBlocking {
        assertEquals(TapConfig(), store.current())
    }

    @Test
    fun updateRoundTripsEveryField() = runBlocking {
        val wanted = TapConfig(
            launchAppEnabled = false,
            launchAppPackage = "com.example.player",
            broadcastEnabled = false,
            openUrlEnabled = true,
            url = "https://example.com/x",
            openActivityEnabled = true,
            activityComponent = "a.b/.C",
        )
        store.update { wanted }
        assertEquals(wanted, store.current())
        assertEquals(wanted, store.config.first())
    }

    @Test
    fun updateReceivesCurrentValue() = runBlocking {
        store.update { it.copy(url = "one") }
        store.update { it.copy(openUrlEnabled = true) }
        val c = store.current()
        assertEquals("one", c.url)
        assertEquals(true, c.openUrlEnabled)
        // untouched fields keep their defaults
        assertEquals(TapConfig.DEFAULT_LAUNCH_PACKAGE, c.launchAppPackage)
    }
}
