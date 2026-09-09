package dev.maxhogan.tapshim.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.maxhogan.tapshim.BuildConfig
import dev.maxhogan.tapshim.actions.SpotifyLinks
import dev.maxhogan.tapshim.actions.TapConfig
import dev.maxhogan.tapshim.actions.TapConfigStore
import dev.maxhogan.tapshim.actions.TapSimulator
import dev.maxhogan.tapshim.bluetooth.SessionState
import dev.maxhogan.tapshim.bluetooth.TapSocketService
import dev.maxhogan.tapshim.log.EventLog
import dev.maxhogan.tapshim.log.Level
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var store: TapConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = TapConfigStore.from(this)
        setContent {
            TapShimTheme {
                MainScreen(store)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasBluetoothConnect()) TapSocketService.sync(this)
    }

    private fun hasBluetoothConnect() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun TapShimTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(store: TapConfigStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by store.config.collectAsStateWithLifecycle(initialValue = TapConfig())
    val running by TapSocketService.running.collectAsStateWithLifecycle()
    val sessions by TapSocketService.sessionInfo.collectAsStateWithLifecycle()
    val events by EventLog.events.collectAsStateWithLifecycle()

    var permissionTick by remember { mutableIntStateOf(0) }
    val bluetoothGranted = remember(permissionTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
    val notificationsGranted = remember(permissionTick) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val overlayGranted = remember(permissionTick) { Settings.canDrawOverlays(context) }

    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionTick++
        if (it[Manifest.permission.BLUETOOTH_CONNECT] == true) TapSocketService.sync(context)
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionTick++
    }

    // Re-check grants and installed apps whenever we come back to the screen
    // (after the overlay settings page, the uninstall dialog, the Play Store).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionTick++ }

    var showAppPicker by remember { mutableStateOf(false) }
    var showActivityPicker by remember { mutableStateOf(false) }
    val spotifyInstalled = remember(permissionTick) { SpotifyLinks.isInstalled(context.packageManager) }

    fun update(transform: (TapConfig) -> TapConfig) {
        scope.launch { store.update(transform) }
    }

    if (showAppPicker) {
        AppPickerDialog(
            title = "Pick an app to launch",
            loadApps = { launchableApps(context) },
            onPick = { app ->
                showAppPicker = false
                update { it.copy(launchAppPackage = app.packageName) }
            },
            onDismiss = { showAppPicker = false },
        )
    }
    if (showActivityPicker) {
        ActivityPickerDialog(
            onPick = { pkg, cls ->
                showActivityPicker = false
                update { it.copy(activityComponent = ComponentName(pkg, cls).flattenToShortString()) }
            },
            onDismiss = { showActivityPicker = false },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("TapShim ${BuildConfig.VERSION_NAME}") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Set the Bose shortcut to Spotify, uninstall Spotify, and TapShim will catch the tap instead.",
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionCard("Spotify") {
                Text(
                    if (spotifyInstalled) "Spotify is installed. Pick the Spotify shortcut in the Bose app, then uninstall Spotify so TapShim can take over."
                    else "Spotify is not installed. Good: TapShim can own the headphone connection. Reinstall it only if you need to change the shortcut in the Bose app.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { SpotifyLinks.openInstall(context) }, enabled = !spotifyInstalled) { Text("Install Spotify") }
                    OutlinedButton(onClick = { SpotifyLinks.openUninstall(context) }, enabled = spotifyInstalled) { Text("Uninstall Spotify") }
                }
            }

            SectionCard("Permissions") {
                PermissionRow(
                    "Nearby devices (Bluetooth)",
                    bluetoothGranted,
                    required = true,
                ) { requestPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }
                PermissionRow(
                    "Notifications (shows the listener)",
                    notificationsGranted,
                    required = false,
                ) {
                    if (Build.VERSION.SDK_INT >= 33) requestPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                PermissionRow(
                    "Display over other apps (launch from background)",
                    overlayGranted,
                    required = config.needsActivityStart,
                ) {
                    overlayLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
                    )
                }
                if (config.needsActivityStart && !overlayGranted) {
                    Text(
                        "Without this, Android silently blocks launching an app or URL while TapShim is in the background.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SectionCard("Status") {
                Text(if (running) "Listener service: running" else "Listener service: stopped")
                if (sessions.isEmpty()) {
                    Text("No headphone sessions. Connect the headphones (or reconnect them) to start one.")
                } else {
                    sessions.values.forEach { s ->
                        val color = when (s.state) {
                            SessionState.LISTENING -> MaterialTheme.colorScheme.primary
                            SessionState.GAVE_UP -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text("${s.name}: ${s.state.name.lowercase()} ${s.detail}", color = color)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { TapSocketService.sync(context) }, enabled = bluetoothGranted) { Text("Rescan") }
                    OutlinedButton(onClick = { TapSocketService.stop(context) }, enabled = running) { Text("Stop") }
                    Button(onClick = { TapSimulator.simulate(context) }) { Text("Simulate tap") }
                }
                Text(
                    "Simulate tap runs the actions below as if the headphones had sent one. To test background launching, press Home first and use the adb command in the README.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard("On tap") {
                ToggleRow("Launch an app", config.launchAppEnabled) { v -> update { it.copy(launchAppEnabled = v) } }
                if (config.launchAppEnabled) {
                    val installed = remember(config.launchAppPackage, permissionTick) {
                        SpotifyLinks.isInstalled(context.packageManager, config.launchAppPackage)
                    }
                    InputWithPicker(
                        value = config.launchAppPackage,
                        label = "Package name",
                        placeholder = TapConfig.DEFAULT_LAUNCH_PACKAGE,
                        pickLabel = "Pick app",
                        supporting = if (installed || config.launchAppPackage.isBlank()) null else "Not installed on this phone",
                        onValue = { v -> update { it.copy(launchAppPackage = v) } },
                        onPick = { showAppPicker = true },
                    )
                }

                ToggleRow("Send broadcast for Tasker", config.broadcastEnabled) { v -> update { it.copy(broadcastEnabled = v) } }
                if (config.broadcastEnabled) {
                    Text(
                        "Tasker: Event > System > Intent Received, action ${TapConfig.BROADCAST_ACTION}. " +
                            "Extras: ${TapConfig.EXTRA_CLIENT_ID}, ${TapConfig.EXTRA_DEVICE_NAME}, ${TapConfig.EXTRA_MANUFACTURER}, " +
                            "${TapConfig.EXTRA_ADDRESS}, ${TapConfig.EXTRA_RAW_HEX}, ${TapConfig.EXTRA_SIMULATED}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                ToggleRow("Open a URL", config.openUrlEnabled) { v -> update { it.copy(openUrlEnabled = v) } }
                if (config.openUrlEnabled) {
                    DebouncedTextField(config.url, "URL", "https://music.youtube.com/") { v -> update { it.copy(url = v) } }
                }

                ToggleRow("Open a specific activity", config.openActivityEnabled) { v -> update { it.copy(openActivityEnabled = v) } }
                if (config.openActivityEnabled) {
                    InputWithPicker(
                        value = config.activityComponent,
                        label = "Component (package/class)",
                        placeholder = "com.example.app/.MainActivity",
                        pickLabel = "Pick activity",
                        supporting = null,
                        onValue = { v -> update { it.copy(activityComponent = v) } },
                        onPick = { showActivityPicker = true },
                    )
                }
            }

            SectionCard("Event log") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${events.size} events", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { EventLog.clear() }) { Text("Clear") }
                }
                EventList(events.asReversed())
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, required: Boolean, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(
                when {
                    granted -> "Granted"
                    required -> "Required"
                    else -> "Optional"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!granted && required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!granted) OutlinedButton(onClick = onRequest) { Text("Grant") }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DebouncedTextField(value: String, label: String, placeholder: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onCommit(it)
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InputWithPicker(
    value: String,
    label: String,
    placeholder: String,
    pickLabel: String,
    supporting: String?,
    onValue: (String) -> Unit,
    onPick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var text by remember(value) { mutableStateOf(value) }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onValue(it)
            },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            supportingText = supporting?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onPick) { Text(pickLabel) }
    }
}

@Composable
private fun EventList(events: List<dev.maxhogan.tapshim.log.Event>) {
    if (events.isEmpty()) {
        Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
        items(events) { e ->
            val color = when (e.level) {
                Level.ERROR -> MaterialTheme.colorScheme.error
                Level.WARN -> MaterialTheme.colorScheme.tertiary
                Level.INFO -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                "${e.timeText}  ${e.message}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = color,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
