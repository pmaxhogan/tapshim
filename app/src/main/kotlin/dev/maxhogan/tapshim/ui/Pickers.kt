package dev.maxhogan.tapshim.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val label: String, val packageName: String)
data class ActivityEntry(val label: String, val className: String, val exported: Boolean)

/** Every app with a launcher entry, sorted by label. */
suspend fun launchableApps(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(intent, 0)
        .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/** Every installed app that declares at least one activity, sorted by label. */
suspend fun appsWithActivities(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
        .filter { !it.activities.isNullOrEmpty() }
        .map { AppEntry(it.applicationInfo?.loadLabel(pm)?.toString() ?: it.packageName, it.packageName) }
        .sortedBy { it.label.lowercase() }
}

/** Activities of one package; exported ones first since only those can be started. */
suspend fun activitiesOf(context: Context, packageName: String): List<ActivityEntry> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val info = try {
        pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
    } catch (_: PackageManager.NameNotFoundException) {
        return@withContext emptyList()
    }
    (info.activities ?: emptyArray())
        .map { ActivityEntry(it.loadLabel(pm).toString(), it.name, it.exported) }
        .sortedWith(compareByDescending<ActivityEntry> { it.exported }.thenBy { it.className })
}

@Composable
fun AppPickerDialog(
    title: String,
    loadApps: suspend () -> List<AppEntry>,
    onPick: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = loadApps() }
    val shown = apps?.filter {
        query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Filter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    shown == null -> Text("Loading...", Modifier.padding(top = 12.dp))
                    shown.isEmpty() -> Text("No matches", Modifier.padding(top = 12.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                        items(shown, key = { it.packageName }) { app ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(app) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(app.label)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Two steps: pick the app, then one of its activities. */
@Composable
fun ActivityPickerDialog(onPick: (packageName: String, className: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var chosenApp by remember { mutableStateOf<AppEntry?>(null) }
    val app = chosenApp
    if (app == null) {
        AppPickerDialog(
            title = "Pick an app",
            loadApps = { appsWithActivities(context) },
            onPick = { chosenApp = it },
            onDismiss = onDismiss,
        )
        return
    }
    var activities by remember(app) { mutableStateOf<List<ActivityEntry>?>(null) }
    LaunchedEffect(app) { activities = activitiesOf(context, app.packageName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            val list = activities
            when {
                list == null -> Text("Loading...")
                list.isEmpty() -> Text("No activities")
                else -> LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                    items(list, key = { it.className }) { a ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app.packageName, a.className) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(a.className.removePrefix(app.packageName).ifEmpty { a.className })
                            Text(
                                if (a.exported) a.label else "${a.label} (not exported, may refuse to open)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (a.exported) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = { TextButton(onClick = { chosenApp = null }) { Text("Back") } },
    )
}
