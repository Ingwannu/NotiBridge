package me.teamwicked.notibridge.ui.logs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import me.teamwicked.notibridge.NotiBridgeApp
import me.teamwicked.notibridge.data.SendLogEntity

/**
 * Delivery log: newest 200 entries, tap for full detail, long-press to copy a
 * one-line summary, detail dialog offers full copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as NotiBridgeApp
    val logs by app.logRepository.observeRecentLogs().collectAsState(initial = emptyList())
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<SendLogEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("전송 로그") },
                actions = {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "전체 삭제")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "아직 전송 기록이 없습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(logs, key = { it.id }) { log ->
                    LogRow(
                        log = log,
                        onClick = { detail = log },
                        onLongClick = {
                            clipboard.setText(AnnotatedString(summaryOf(log)))
                            scope.launch { snackbar.showSnackbar("요약을 복사했습니다.") }
                        },
                    )
                }
            }
        }
    }

    detail?.let { log ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(if (log.success) "전송 성공" else "전송 실패") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailLine("훅", log.hookName)
                    DetailLine("앱", "${log.appName} (${log.appPackage})")
                    DetailLine("제목", log.title)
                    DetailLine("내용", log.text)
                    DetailLine("요청", "${log.requestMethod} ${log.requestUrl}")
                    DetailLine("요청 Body", log.requestBodyPreview)
                    DetailLine("응답 코드", log.responseCode?.toString() ?: "-")
                    DetailLine("응답 본문", log.responseBody.ifBlank { "-" })
                    if (log.errorMessage.isNotBlank()) DetailLine("오류", log.errorMessage)
                    DetailLine("소요", "${log.durationMillis}ms")
                    DetailLine("시각", formatTime(log.createdAt))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(fullTextOf(log)))
                    scope.launch { snackbar.showSnackbar("전체 내용을 복사했습니다.") }
                }) { Text("전체 복사") }
            },
            dismissButton = {
                TextButton(onClick = { detail = null }) { Text("닫기") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("로그 전체 삭제") },
            text = { Text("전송 로그를 모두 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.logRepository.clear() }
                    confirmClear = false
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("취소") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(
    log: SendLogEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (log.success) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    log.hookName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (log.success) "성공 ${log.responseCode ?: ""}" else "실패 ${log.responseCode ?: ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (log.success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Text(
                "${log.appName} · ${log.title}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                log.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatTime(log.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun summaryOf(log: SendLogEntity): String =
    "[${if (log.success) "성공" else "실패"}] ${log.hookName} <- ${log.appName}: ${log.title} " +
        "(${log.requestMethod} ${log.requestUrl} -> ${log.responseCode ?: log.errorMessage})"

private fun fullTextOf(log: SendLogEntity): String = buildString {
    appendLine("훅: ${log.hookName}")
    appendLine("앱: ${log.appName} (${log.appPackage})")
    appendLine("제목: ${log.title}")
    appendLine("내용: ${log.text}")
    appendLine("요청: ${log.requestMethod} ${log.requestUrl}")
    appendLine("요청 Body: ${log.requestBodyPreview}")
    appendLine("응답 코드: ${log.responseCode ?: "-"}")
    appendLine("응답 본문: ${log.responseBody}")
    appendLine("오류: ${log.errorMessage}")
    appendLine("소요: ${log.durationMillis}ms")
    appendLine("시각: ${formatTime(log.createdAt)}")
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

private fun formatTime(epochMillis: Long): String = timeFormat.format(Date(epochMillis))
