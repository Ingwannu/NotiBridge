package me.teamwicked.notibridge.ui.hookeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.teamwicked.notibridge.model.RegexRule
import me.teamwicked.notibridge.model.RegexSource
import me.teamwicked.notibridge.util.RegexExtractor

/**
 * Editor for one regex extraction rule with a live test playground.
 * Invalid patterns surface immediately and block saving upstream.
 */
@Composable
fun RegexRuleCard(
    rule: RegexRule,
    index: Int,
    onChange: (RegexRule) -> Unit,
    onDelete: () -> Unit,
) {
    var testInput by remember(rule.id) { mutableStateOf("") }
    val error = rule.validationError()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "정규식 규칙 ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "규칙 삭제")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RegexSource.entries.forEach { source ->
                    FilterChip(
                        selected = rule.source == source,
                        onClick = { onChange(rule.copy(source = source)) },
                        label = { Text(source.label) },
                    )
                }
            }

            OutlinedTextField(
                value = rule.pattern,
                onValueChange = { onChange(rule.copy(pattern = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("정규식 (예: (?<금액>[0-9,]+)원)") },
                isError = error != null,
                supportingText = {
                    Text(error ?: "이름 있는 그룹 (?<이름>...) 은 {var_이름} 으로 사용")
                },
            )

            OutlinedTextField(
                value = rule.variableName,
                onValueChange = { onChange(rule.copy(variableName = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("변수 이름 (그룹 이름이 없을 때)") },
                singleLine = true,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("전역 변수로 저장", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "{global.이름} 형태로 다른 훅에서도 재사용",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = rule.isGlobal,
                    onCheckedChange = { onChange(rule.copy(isGlobal = it)) },
                )
            }

            OutlinedTextField(
                value = testInput,
                onValueChange = { testInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("테스트 입력") },
                minLines = 2,
            )

            if (error == null && testInput.isNotBlank()) {
                val captures = remember(rule, testInput) {
                    runCatching { RegexExtractor.test(rule, testInput) }.getOrDefault(emptyList())
                }
                if (captures.isEmpty()) {
                    Text(
                        "매칭 없음",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            captures.forEach { (name, value) ->
                                val token = if (rule.isGlobal) "{global.$name}" else "{var_$name}"
                                Text(
                                    "$token = $value",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
