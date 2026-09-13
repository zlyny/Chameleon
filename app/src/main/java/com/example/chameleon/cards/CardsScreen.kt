package com.example.chameleon.cards

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chameleon.MainViewModel
import com.example.chameleon.R
import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.device.DumpCard
import com.example.chameleon.device.DumpContent
import com.example.chameleon.reader.ReaderPhase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 卡片管理页 Compose 实现。
 *
 * 相比原 View 版：RecyclerView + ListAdapter + DiffUtil 换成 LazyColumn；
 * 「查看」对话框不再需要 Fragment 里手动 add/remove ComposeView 宿主，
 * 而是直接在本组合里由 [viewingDump] 状态驱动 [DumpViewerDialog] 显示——
 * 宿主天然在 Activity 视图树内，不存在 ViewTree owner 缺失问题。
 *
 * 其余行为与原实现逐条对齐，见各分支注释。
 */
@Composable
fun CardsScreen(
    cardsViewModel: CardsViewModel,
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dumps by cardsViewModel.dumps.collectAsStateWithLifecycle()
    val readerState by mainViewModel.readerState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    /** Android 9 及以下导出需运行时存储权限：请求期间暂存待导出卡片 */
    var pendingExport by remember { mutableStateOf<DumpCard?>(null) }
    var viewingDump by remember { mutableStateOf<ViewingDump?>(null) }
    var deleting by remember { mutableStateOf<DumpCard?>(null) }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun exportCard(dump: DumpCard) {
        val content = cardsViewModel.readBlocks(dump) ?: run {
            notify(context.getString(R.string.card_read_failed))
            return
        }
        scope.launch {
            runCatching { cardsViewModel.exportToDownloads(dump, content) }
                .onSuccess { name ->
                    notify(context.getString(R.string.card_export_done, name))
                }
                .onFailure { e ->
                    notify(
                        context.getString(
                            R.string.card_export_failed,
                            e.message ?: "未知错误",
                        ),
                    )
                }
        }
    }

    val exportPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val dump = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (granted) {
            exportCard(dump)
        } else {
            notify(context.getString(R.string.card_export_permission_denied))
        }
    }

    fun onExport(dump: DumpCard) {
        // Android 10+ 经 MediaStore 写 Download 无需任何权限；
        // 更早版本需先取得写存储权限（manifest 已声明 maxSdkVersion=28）
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingExport = dump
            exportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            exportCard(dump)
        }
    }

    fun onView(dump: DumpCard) {
        val content = cardsViewModel.readBlocks(dump) ?: run {
            notify(context.getString(R.string.card_read_failed))
            return
        }
        viewingDump = ViewingDump(dump.uidHex, content)
    }

    // 写入槽的完成提示由本页消费（lastError 归读卡页消费，互不重复）
    LaunchedEffect(readerState.lastSuccess) {
        readerState.lastSuccess?.let {
            snackbarHostState.showSnackbar(it)
            mainViewModel.consumeLastSuccess()
        }
    }

    // 设备流程（读卡 / 破解 / Dump / 写入槽）进行中禁用卡片操作，防误删或重复发起
    val busy = readerState.phase != ReaderPhase.Idle
    val writing = readerState.phase == ReaderPhase.WritingEmu

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = stringResource(R.string.cards_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.cards_hint),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (dumps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = stringResource(R.string.cards_empty),
                        modifier = Modifier.align(Alignment.Center),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(items = dumps, key = { it.fileName }) { dump ->
                        DumpCardItem(
                            dump = dump,
                            subtitle = dump.subtitle(timeFormat),
                            busy = busy,
                            writing = writing,
                            onWrite = {
                                if (!mainViewModel.writeDumpToEmulator(dump)) {
                                    notify(context.getString(R.string.card_write_start_failed))
                                }
                            },
                            onLoad = { mainViewModel.loadDumpToReader(dump) },
                            onView = { onView(dump) },
                            onExport = { onExport(dump) },
                            onDelete = { deleting = dump },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
    }

    viewingDump?.let { target ->
        DumpViewerDialog(
            uidHex = target.uidHex,
            content = target.content,
            onDismiss = { viewingDump = null },
        )
    }

    deleting?.let { dump ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(text = stringResource(R.string.card_delete_confirm_title)) },
            text = {
                Text(text = stringResource(R.string.card_delete_confirm_msg, dump.uidHex))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (cardsViewModel.delete(dump)) {
                        notify(context.getString(R.string.card_deleted, dump.uidHex))
                    }
                    deleting = null
                }) {
                    Text(text = stringResource(R.string.btn_card_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** 正在查看的卡片数据（弹出对话框期间持有） */
private data class ViewingDump(val uidHex: String, val content: DumpContent)

/** 列表项副标题：SAK / ATQA / 块数 / 保存时间 */
@Composable
private fun DumpCard.subtitle(timeFormat: SimpleDateFormat): String = stringResource(
    R.string.card_item_subtitle,
    sakHex,
    atqaHex,
    sizeBytes.toInt() / 2 / ChameleonSession.MF1_BLOCK_SIZE,
    timeFormat.format(Date(savedAtMillis)),
)

@Composable
private fun DumpCardItem(
    dump: DumpCard,
    subtitle: String,
    busy: Boolean,
    writing: Boolean,
    onWrite: () -> Unit,
    onLoad: () -> Unit,
    onView: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "UID ${dump.uidHex}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 主操作：写入槽（模拟该卡） / 加载（回读卡页继续破解）
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DumpCardButton(
                    text = stringResource(
                        if (writing) R.string.btn_card_writing else R.string.btn_card_write,
                    ),
                    enabled = !busy,
                    tonal = true,
                    onClick = onWrite,
                    modifier = Modifier.weight(1f),
                )
                DumpCardButton(
                    text = stringResource(R.string.btn_card_Load),
                    enabled = !busy,
                    tonal = true,
                    onClick = onLoad,
                    modifier = Modifier.weight(1f),
                )
            }

            // 次操作：查看 / 导出 / 删除
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DumpCardButton(
                    text = stringResource(R.string.btn_card_view),
                    enabled = !busy,
                    onClick = onView,
                    modifier = Modifier.weight(1f),
                )
                DumpCardButton(
                    text = stringResource(R.string.btn_card_export),
                    enabled = !busy,
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                )
                DumpCardButton(
                    text = stringResource(R.string.btn_card_delete),
                    enabled = !busy,
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DumpCardButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
    if (tonal) {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    } else {
        TextButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    }
}
