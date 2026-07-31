package me.teamwicked.notibridge.ui.hooks

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.launch
import me.teamwicked.notibridge.NotiBridgeApp
import me.teamwicked.notibridge.model.Hook
import me.teamwicked.notibridge.ui.components.PermissionBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HookListScreen(
    onCreateHook: () -> Unit,
    onEditHook: (String) -> Unit,
    externalPresetUri: Uri? = null,
    onExternalPresetConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as NotiBridgeApp
    // null = still loading; avoids flashing the empty state on cold start.
    val hooks by app.hookRepository.observeHooks().collectAsState(initial = null)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pendingDelete by remember { mutableStateOf<Hook?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { importPreset(context, app, uri, snackbar) }
    }

    // A .notif file opened from another app lands here via VIEW intent.
    androidx.compose.runtime.LaunchedEffect(externalPresetUri) {
        if (externalPresetUri != null) {
            importPreset(context, app, externalPresetUri, snackbar)
            onExternalPresetConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("웹훅") },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = ".notif 가져오기")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateHook) {
                Icon(Icons.Filled.Add, contentDescription = "훅 추가")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            PermissionBanner()

            val hookList = hooks
            if (hookList == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else if (hookList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "아직 훅이 없습니다.\n+ 버튼으로 첫 웹훅을 만들어 보세요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(hookList, key = { it.id }) { hook ->
                        HookCard(
                            hook = hook,
                            onToggle = { enabled ->
                                scope.launch { app.hookRepository.setEnabled(hook.id, enabled) }
                            },
                            onEdit = { onEditHook(hook.id) },
                            onDelete = { pendingDelete = hook },
                            onExport = {
                                scope.launch {
                                    runCatching {
                                        exportPreset(context, app.hookRepository.exportPreset(hook), hook.name)
                                        snackbar.showSnackbar("프리셋을 보냈습니다.")
                                    }.onFailure { e ->
                                        snackbar.showSnackbar("보내기 실패: ${e.message}")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { hook ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("훅 삭제") },
            text = { Text("'${hook.name}' 훅을 삭제할까요? 대기 중인 전송도 함께 사라집니다.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.hookRepository.deleteHook(hook.id) }
                    pendingDelete = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun HookCard(
    hook: Hook,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        hook.name.ifBlank { "(이름 없음)" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        hook.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = hook.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "더보기")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("프리셋 보내기 (.notif)") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("삭제") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip("${hook.method.wireName} · ${hook.contentType.label}")
                InfoChip(
                    if (hook.appPackages.isEmpty()) "모든 앱" else "앱 ${hook.appPackages.size}개",
                )
                InfoChip("${hook.timeoutSeconds}s")
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun exportPreset(context: Context, json: String, hookName: String): Uri {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val safeName = hookName.ifBlank { "hook" }.replace(Regex("[^A-Za-z0-9가-힣._-]"), "_")
    val file = File(dir, "$safeName.notif")
    file.writeText(json)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(share, ".notif 보내기"))
    return uri
}

private suspend fun importPreset(
    context: Context,
    app: NotiBridgeApp,
    uri: Uri,
    snackbar: androidx.compose.material3.SnackbarHostState,
) {
    runCatching {
        val raw = context.contentResolver.openInputStream(uri)
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("파일을 읽을 수 없습니다.")
        val hook = app.hookRepository.importPreset(raw)
        app.hookRepository.saveHook(hook.copy(enabled = false))
        snackbar.showSnackbar("프리셋을 새 훅으로 가져왔습니다: ${hook.name}")
    }.onFailure { e ->
        snackbar.showSnackbar("가져오기 실패: ${e.message}")
    }
}
