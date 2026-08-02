package me.teamwicked.notibridge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.teamwicked.notibridge.NotiBridgeApp
import me.teamwicked.notibridge.data.SettingsRepository
import me.teamwicked.notibridge.service.KeepAliveService
import me.teamwicked.notibridge.util.PermissionUtils

/**
 * Runtime settings: background service control, permission shortcuts, and the
 * max concurrent delivery knob.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as NotiBridgeApp
    val settings = app.settingsRepository

    var serviceRunning by remember { mutableStateOf(KeepAliveService.isEnabledByUser(context)) }
    var hasAccess by remember { mutableStateOf(PermissionUtils.hasNotificationAccess(context)) }
    var batteryOk by remember { mutableStateOf(PermissionUtils.isIgnoringBatteryOptimizations(context)) }
    var maxConcurrent by remember { mutableStateOf(settings.maxConcurrentDeliveries) }
    var dedupeSeconds by remember { mutableStateOf(settings.dedupeWindowMs / 1000f) }
    var notifySuccess by remember { mutableStateOf(me.teamwicked.notibridge.service.ResultNotifier.notifySuccessEnabled(context)) }
    var notifyFailure by remember { mutableStateOf(me.teamwicked.notibridge.service.ResultNotifier.notifyFailureEnabled(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceRunning = KeepAliveService.isEnabledByUser(context)
                hasAccess = PermissionUtils.hasNotificationAccess(context)
                batteryOk = PermissionUtils.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("설정") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CrashLogCard()

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("백그라운드 서비스", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (serviceRunning) "포그라운드 서비스 동작 중" else "서비스 중지됨",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "알림 감지 연결을 유지하고 재연결을 돕습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = serviceRunning,
                            onCheckedChange = { on ->
                                if (on) {
                                    KeepAliveService.startByUser(context)
                                } else {
                                    KeepAliveService.stop(context)
                                }
                                serviceRunning = on
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            KeepAliveService.stop(context)
                            KeepAliveService.startByUser(context)
                            serviceRunning = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("서비스 수동 재시작") }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("권한", style = MaterialTheme.typography.titleMedium)
                    PermissionRow(
                        label = "알림 접근 권한",
                        granted = hasAccess,
                        actionLabel = "설정 열기",
                        onAction = {
                            context.startActivity(PermissionUtils.notificationAccessSettingsIntent())
                        },
                    )
                    PermissionRow(
                        label = "배터리 최적화 해제",
                        granted = batteryOk,
                        actionLabel = if (batteryOk) "설정 열기" else "해제하기",
                        onAction = {
                            context.startActivity(PermissionUtils.batteryOptimizationIntent(context))
                        },
                    )
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("전송 설정", style = MaterialTheme.typography.titleMedium)
                    Text("동시에 처리할 최대 훅 수: $maxConcurrent")
                    Slider(
                        value = maxConcurrent.toFloat(),
                        onValueChange = { maxConcurrent = it.toInt() },
                        onValueChangeFinished = {
                            settings.maxConcurrentDeliveries = maxConcurrent
                        },
                        valueRange = SettingsRepository.MIN_CONCURRENT.toFloat()..SettingsRepository.MAX_CONCURRENT.toFloat(),
                        steps = SettingsRepository.MAX_CONCURRENT - SettingsRepository.MIN_CONCURRENT - 1,
                    )
                    Text(
                        "같은 알림 중복 전송 방지 시간: ${dedupeSeconds.toInt()}초",
                    )
                    Slider(
                        value = dedupeSeconds,
                        onValueChange = { dedupeSeconds = it },
                        onValueChangeFinished = {
                            settings.dedupeWindowMs = (dedupeSeconds * 1000).toLong()
                        },
                        valueRange = 0f..300f,
                        steps = 29,
                    )
                    Text(
                        "실패한 전송은 10초부터 시작해 최대 15분 간격으로 자동 재시도됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("결과 알림", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("실패 시 알림", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "최종 실패한 전송을 시스템 알림으로 표시",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = notifyFailure,
                            onCheckedChange = {
                                notifyFailure = it
                                me.teamwicked.notibridge.service.ResultNotifier.setNotifyFailure(context, it)
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("성공 시 알림", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "매 전송 성공을 시스템 알림으로 표시",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = notifySuccess,
                            onCheckedChange = {
                                notifySuccess = it
                                me.teamwicked.notibridge.service.ResultNotifier.setNotifySuccess(context, it)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (granted) "허용됨" else "허용되지 않음",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}
