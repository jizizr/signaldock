package com.jizizr.signaldock

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.ui.theme.SignaldockTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val previewValues = IslandShareValues(
    label = "取餐码",
    code = "A123",
    item = "示例饮品",
    detail = "中杯 · 少冰",
    merchant = "示例餐厅（中心店）",
)

class IslandShareEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SignaldockTheme {
                IslandShareEditorScreen(
                    onBack = ::finish,
                    onSave = { content ->
                        SuperIslandSettingsStore.saveShareContentTemplate(content)
                        Toast.makeText(
                            this,
                            R.string.super_island_share_template_saved,
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun IslandShareEditorScreen(
    onBack: () -> Unit,
    onSave: (String) -> Unit,
) {
    val initialContent = SuperIslandSettingsStore.shareTemplate.content
    var content by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialContent, TextRange(initialContent.length)))
    }
    val preview = renderIslandShare(
        SuperIslandSettingsStore.defaultShareTemplate.copy(content = content.text),
        previewValues,
    )

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(64.dp)
                    .padding(horizontal = 12.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Text(
                    text = stringResource(R.string.super_island_share_template),
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    text = stringResource(R.string.save),
                    onClick = { onSave(content.text) },
                    enabled = content.text.isNotBlank(),
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .scrollEndHaptic()
                    .overScrollVertical(),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.super_island_share_auto_card),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                item {
                    TextField(
                        value = content,
                        onValueChange = { next ->
                            content = applyAtomicIslandShareTokenEdit(content, next)
                        },
                        label = stringResource(R.string.super_island_share_message),
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.super_island_share_insert_variable),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                items(islandShareTemplateTokens.chunked(2).size) { rowIndex ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        islandShareTemplateTokens.chunked(2)[rowIndex].forEach { token ->
                            Button(
                                onClick = {
                                    content = insertIslandShareToken(content, token.value)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(64.dp),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(token.value)
                                    Text(
                                        text = token.example,
                                        style = MiuixTheme.textStyles.footnote1,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            .padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.preview),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = preview.content.ifBlank { "取餐码：A123" },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
                item {
                    TextButton(
                        text = stringResource(R.string.restore_default),
                        onClick = {
                            val default = SuperIslandSettingsStore.defaultShareTemplate.content
                            content = TextFieldValue(default, TextRange(default.length))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
