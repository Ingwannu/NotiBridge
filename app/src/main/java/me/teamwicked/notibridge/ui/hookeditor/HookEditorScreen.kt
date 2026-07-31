package me.teamwicked.notibridge.ui.hookeditor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.teamwicked.notibridge.NotiBridgeApp
import me.teamwicked.notibridge.model.ContentType
import me.teamwicked.notibridge.model.ExcludeFilter
import me.teamwicked.notibridge.model.Hook
import me.teamwicked.notibridge.model.HttpMethod
import me.teamwicked.notibridge.model.NotificationPayload
import me.teamwicked.notibridge.model.RegexRule
import me.teamwicked.notibridge.net.SendOutcome
import me.teamwicked.notibridge.util.TemplateEngine

/**
 * Full hook editor: request shape, body template, regex extraction, exclude
 * filters and the live "테스트 전송" playground.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HookEditorScreen(
    hookId: String?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as NotiBridgeApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf(hookId == null) }
    var hook by remember { mutableStateOf(Hook()) }
    var validationErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<SendOutcome?>(null) }
    var testRunning by remember { mutableStateOf(false) }

    // Load once. Without the loaded guard, a user who starts typing before
    // the DB read returns would have their input overwritten by the load.
    LaunchedEffect(hookId) {
        if (hookId != null) {
            if (!loaded) {
                app.hookRepository.findHook(hookId)?.let { hook = it }
            }
            loaded = true
        }
    }

    val bodyFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                    ?: error("파일을 읽을 수 없습니다.")
                require(bytes.size <= 1_000_000) { "Body 파일은 1MB 이하여야 합니다." }
                val name = queryDisplayName(context, uri) ?: "body.bin"
                hook = hook.copy(
                    bodyFileName = name,
                    bodyFileBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT),
                )
            }.onFailure { e -> snackbar.showSnackbar("파일 선택 실패: ${e.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (hookId == null) "새 훅" else "훅 편집") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle("기본")
            OutlinedTextField(
                value = hook.name,
                onValueChange = { hook = hook.copy(name = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("훅 이름") },
                singleLine = true,
            )
            OutlinedTextField(
                value = hook.url,
                onValueChange = { hook = hook.copy(url = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("웹훅 URL (http/https)") },
                singleLine = true,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("활성화", Modifier.weight(1f))
                Switch(
                    checked = hook.enabled,
                    onCheckedChange = { hook = hook.copy(enabled = it) },
                )
            }

            SectionTitle("요청 설정")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HttpMethod.entries.forEach { method ->
                    FilterChip(
                        selected = hook.method == method,
                        onClick = { hook = hook.copy(method = method) },
                        label = { Text(method.wireName) },
                    )
                }
            }

            EnumDropdown(
                label = "Content-Type",
                options = ContentType.entries,
                selected = hook.contentType,
                optionLabel = { it.label },
                onSelected = { hook = hook.copy(contentType = it) },
            )

            Text("타임아웃: ${hook.timeoutSeconds}초 (1~120)")
            Slider(
                value = hook.timeoutSeconds.toFloat(),
                onValueChange = { hook = hook.copy(timeoutSeconds = it.toInt().coerceIn(1, 120)) },
                valueRange = 1f..120f,
                steps = 118,
            )

            HeaderEditor(
                headers = hook.headers,
                onChange = { hook = hook.copy(headers = it) },
            )

            OutlinedTextField(
                value = hook.authHeaderName,
                onValueChange = { hook = hook.copy(authHeaderName = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("인증 헤더 이름 (예: Authorization)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = hook.authToken,
                onValueChange = { hook = hook.copy(authToken = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("인증 토큰 (비워두면 전송 안 함)") },
                singleLine = true,
            )

            if (hook.method.hasBody) {
                SectionTitle("Body")
                OutlinedTextField(
                    value = hook.bodyTemplate,
                    onValueChange = { hook = hook.copy(bodyTemplate = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Body 템플릿") },
                    minLines = 4,
                    supportingText = {
                        Text(TemplateEngine.knownTokens().joinToString("  "))
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { bodyFileLauncher.launch(arrayOf("*/*")) }) {
                        Text(if (hook.bodyFileName.isBlank()) "외부 파일 선택" else "파일: ${hook.bodyFileName}")
                    }
                    if (hook.bodyFileName.isNotBlank()) {
                        TextButton(onClick = {
                            hook = hook.copy(bodyFileName = "", bodyFileBase64 = "")
                        }) { Text("파일 제거") }
                    }
                }
                Text(
                    "파일이 선택되면 템플릿 대신 파일 내용이 Body로 전송됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("대상 앱")
            OutlinedButton(onClick = { showAppPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (hook.appPackages.isEmpty()) "모든 앱 (탭하여 특정 앱만 선택)"
                    else "선택된 앱 ${hook.appPackages.size}개",
                )
            }

            SectionTitle("정규식 데이터 추출")
            hook.regexRules.forEachIndexed { index, rule ->
                RegexRuleCard(
                    rule = rule,
                    index = index,
                    onChange = { updated ->
                        hook = hook.copy(
                            regexRules = hook.regexRules.map { if (it.id == updated.id) updated else it },
                        )
                    },
                    onDelete = {
                        hook = hook.copy(regexRules = hook.regexRules.filterNot { it.id == rule.id })
                    },
                )
            }
            OutlinedButton(
                onClick = { hook = hook.copy(regexRules = hook.regexRules + RegexRule()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("정규식 규칙 추가")
            }

            SectionTitle("전송 제외 필터")
            hook.excludeFilters.forEachIndexed { index, filter ->
                ExcludeFilterCard(
                    filter = filter,
                    index = index,
                    onChange = { updated ->
                        hook = hook.copy(
                            excludeFilters = hook.excludeFilters.map {
                                if (it.id == updated.id) updated else it
                            },
                        )
                    },
                    onDelete = {
                        hook = hook.copy(excludeFilters = hook.excludeFilters.filterNot { it.id == filter.id })
                    },
                )
            }
            OutlinedButton(
                onClick = { hook = hook.copy(excludeFilters = hook.excludeFilters + ExcludeFilter()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("제외 필터 추가")
            }

            SectionTitle("웹훅 테스트")
            OutlinedButton(
                enabled = !testRunning,
                onClick = {
                    scope.launch {
                        testRunning = true
                        testResult = null
                        testResult = runCatching {
                            // publishGlobals = false: a test send must not leak
                            // its captures into the shared global store.
                            app.webhookSender.send(
                                hook,
                                NotificationPayload.sample(),
                                publishGlobals = false,
                            )
                        }.getOrElse { e ->
                            SendOutcome(false, null, "", e.message ?: "오류", 0, "")
                        }
                        testRunning = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testRunning) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Send, contentDescription = null)
                }
                Text(if (testRunning) "전송 중..." else "테스트 알림으로 실제 전송")
            }
            testResult?.let { result ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.success) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "상태: ${result.responseCode ?: "-"} · ${result.elapsedMillis}ms",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (result.errorMessage.isNotBlank()) {
                            Text("오류: ${result.errorMessage}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (result.responseBody.isNotBlank()) {
                            Text(
                                "응답 본문:\n${result.responseBody.take(2_000)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (validationErrors.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        validationErrors.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val errors = hook.validationErrors()
                    validationErrors = errors
                    if (errors.isNotEmpty()) {
                        scope.launch { snackbar.showSnackbar("저장할 수 없습니다. 오류를 확인하세요.") }
                        return@Button
                    }
                    scope.launch {
                        runCatching { app.hookRepository.saveHook(hook) }
                            .onSuccess { onDone() }
                            .onFailure { e -> snackbar.showSnackbar("저장 실패: ${e.message}") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("저장")
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            selected = hook.appPackages,
            onDismiss = { showAppPicker = false },
            onConfirm = {
                hook = hook.copy(appPackages = it)
                showAppPicker = false
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun <T> EnumDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    // Inline chip picker. We deliberately avoid dialogs/dropdowns here: on
    // some OEM devices the material3 popup path is what crashed the editor.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.take(3).forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelected(option) },
                    label = { Text(optionLabel(option)) },
                )
            }
        }
        if (options.size > 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.drop(3).forEach { option ->
                    FilterChip(
                        selected = selected == option,
                        onClick = { onSelected(option) },
                        label = { Text(optionLabel(option)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderEditor(
    headers: Map<String, String>,
    onChange: (Map<String, String>) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("사용자 지정 HTTP 헤더", style = MaterialTheme.typography.titleSmall)
        headers.forEach { (name, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$name: $value", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { onChange(headers - name) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "헤더 삭제")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.weight(1f),
                label = { Text("헤더 이름") },
                singleLine = true,
            )
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                modifier = Modifier.weight(1f),
                label = { Text("값") },
                singleLine = true,
            )
        }
        OutlinedButton(
            enabled = newName.isNotBlank(),
            onClick = {
                onChange(headers + (newName.trim() to newValue))
                newName = ""
                newValue = ""
            },
        ) { Text("헤더 추가") }
    }
}

@Composable
private fun ExcludeFilterCard(
    filter: ExcludeFilter,
    index: Int,
    onChange: (ExcludeFilter) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "제외 필터 ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "필터 삭제")
                }
            }
            OutlinedTextField(
                value = filter.containsText,
                onValueChange = { onChange(filter.copy(containsText = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("포함하면 제외할 텍스트") },
                singleLine = true,
            )
            OutlinedTextField(
                value = filter.regex,
                onValueChange = { onChange(filter.copy(regex = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("매칭되면 제외할 정규식") },
                isError = filter.validationError() != null,
                supportingText = { filter.validationError()?.let { Text(it) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter.matchTitle,
                    onClick = { onChange(filter.copy(matchTitle = !filter.matchTitle)) },
                    label = { Text("제목") },
                )
                FilterChip(
                    selected = filter.matchText,
                    onClick = { onChange(filter.copy(matchText = !filter.matchText)) },
                    label = { Text("내용") },
                )
            }
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}
