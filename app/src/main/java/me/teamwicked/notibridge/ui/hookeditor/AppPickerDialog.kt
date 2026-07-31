package me.teamwicked.notibridge.ui.hookeditor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One installed app row in the picker. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * Loads apps off the main thread; package listing can take hundreds of ms on
 * devices with many apps.
 */
@Composable
fun rememberInstalledApps(): List<InstalledApp>? {
    val context = LocalContext.current
    val apps by produceState<List<InstalledApp>?>(initialValue = null, context) {
        value = withContext(Dispatchers.Default) { loadInstalledApps(context) }
    }
    return apps
}

private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val infos = if (android.os.Build.VERSION.SDK_INT >= 33) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
    }
    return infos
        .asSequence()
        .filter { it.packageName != context.packageName } // never forward ourselves
        .map { info ->
            InstalledApp(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName),
                isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
        .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        .toList()
}

/**
 * App filter picker with name/package search. Selecting nothing means
 * "모든 앱", so the dialog also offers a clear-all shortcut.
 */
@Composable
fun AppPickerDialog(
    selected: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val apps = rememberInstalledApps()
    var query by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(selected.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("대상 앱 선택") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("앱 이름 또는 패키지명 검색") },
                    singleLine = true,
                )
                Text(
                    if (checked.isEmpty()) "선택 없음 = 모든 앱의 알림을 감지합니다."
                    else "${checked.size}개 앱 선택됨",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                val filtered = apps.orEmpty().filter {
                    query.isBlank() ||
                        it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
                if (apps == null) {
                    Text("앱 목록을 불러오는 중...")
                } else {
                    LazyColumn(Modifier.height(360.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                Checkbox(
                                    checked = app.packageName in checked,
                                    onCheckedChange = { on ->
                                        checked = if (on) {
                                            checked + app.packageName
                                        } else {
                                            checked - app.packageName
                                        }
                                    },
                                )
                                AppIcon(context, app.packageName)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checked.toList().sorted()) }) { Text("확인") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { checked = emptySet() }) { Text("전체 해제") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

@Composable
private fun AppIcon(context: Context, packageName: String) {
    val icon = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmap(width = 96, height = 96)
                .asImageBitmap()
        }.getOrNull()
    }
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
    }
}
