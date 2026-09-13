package com.example.chameleon.log

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chameleon.MainViewModel
import com.example.chameleon.R
import kotlinx.coroutines.launch

/**
 * 日志页 Compose 实现。
 *
 * 相比原 View 版的改进：
 * - **LazyColumn**：原版每次日志更新都用 `SpannableStringBuilder` 重建全部
 *   200 条日志的 span，BLE notify 密集时是明显的卡顿源；这里只渲染可见项。
 * - **每条一色**：`LogEntry.kind` 决定整条颜色，无需再拼 AnnotatedString。
 * - **跟随尾部滚动**：停在底部时自动跟随新日志；用户上翻查看历史时停止跟随，
 *   不会被新日志强行拽回底部（原版是无条件 `fullScroll(FOCUS_DOWN)`）。
 * - **SelectionContainer**：保留原 `textIsSelectable` 的选中复制能力。
 *
 * 颜色仍读 `R.color.log_*`（values / values-night 各一份），Compose 与 View
 * 页面共用同一份色值定义。
 */
@Composable
fun LogScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val entries by viewModel.log.collectAsStateWithLifecycle()
    LogScreen(entries = entries, onClear = viewModel::clearLog, modifier = modifier)
}

@Composable
fun LogScreen(
    entries: List<LogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LogToolbar(
                onCopy = {
                    if (entries.isEmpty()) {
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.log_copy_empty))
                        }
                        return@LogToolbar
                    }
                    val clipboard =
                        ContextCompat.getSystemService(context, ClipboardManager::class.java)
                            ?: return@LogToolbar
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            context.getString(R.string.log_title),
                            entries.joinToString("\n") { it.text },
                        ),
                    )
                    // Android 13+ 复制纯文本时系统会显示统一的复制成功提示，
                    // 再弹 Snackbar 会重复；Android 12 及以下自行提示
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.log_copied, entries.size),
                            )
                        }
                    }
                },
                onClear = onClear,
            )
            LogList(entries = entries, modifier = Modifier.weight(1f).fillMaxWidth())
        }

        // Snackbar 落在日志页自身区域内（位于底部导航栏之上），无需再 setAnchorView
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun LogToolbar(onCopy: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.log_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onCopy) {
            Text(text = stringResource(R.string.btn_copy_log))
        }
        TextButton(onClick = onClear) {
            Text(text = stringResource(R.string.btn_clear_log))
        }
    }
}

@Composable
private fun LogList(entries: List<LogEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        Text(
            text = stringResource(R.string.log_empty),
            modifier = modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val listState = rememberLazyListState()

    // 是否停在尾部（距末尾 TAIL_THRESHOLD 项以内）：用户上翻查看历史时为 false，
    // 此时不再被新日志拽回底部。derivedStateOf 只在布尔值翻转时通知，不会每帧重组
    val atTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - TAIL_THRESHOLD - 1
        }
    }

    // 进入页面（或清空后日志由空转非空）时定位到最新一条，
    // 与原 View 版进入即展示最新日志的行为保持一致
    LaunchedEffect(Unit) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    LaunchedEffect(entries.size) {
        if (atTail) listState.scrollToItem(entries.lastIndex)
    }

    Surface(
        modifier = modifier.padding(top = 8.dp),
        shape = RoundedCornerShape(8.dp),
        color = colorResource(R.color.log_background),
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(count = entries.size, key = { entries[it].id }) { index ->
                    Text(
                        text = entries[index].text,
                        color = colorResource(entries[index].kind.colorRes()),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = LOG_FONT_SIZE,
                            lineHeight = LOG_LINE_HEIGHT,
                        ),
                    )
                }
            }
        }
    }
}

private fun LogKind.colorRes(): Int = when (this) {
    LogKind.TX -> R.color.log_tx
    LogKind.RX -> R.color.log_rx
    LogKind.ERROR -> R.color.log_error
    LogKind.INFO -> R.color.log_info
}

private val LOG_FONT_SIZE = 12.sp
private val LOG_LINE_HEIGHT = 16.sp

/** 距末尾多少项以内仍视为“停留在底部”，继续跟随新日志 */
private const val TAIL_THRESHOLD = 3
