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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chameleon.MainViewModel
import com.example.chameleon.R
import com.example.chameleon.data.DumpCard
import com.example.chameleon.data.DumpContent
import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.device.PrngType
import com.example.chameleon.reader.ReaderPhase
import com.example.chameleon.ui.theme.ChameleonTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    /**
     * Android 9 及以下导出需运行时存储权限：请求期间暂存待导出卡片的**文件名**
     * （而非 DumpCard）——用 rememberSaveable 才能在旋转后保住，授权回来
     * 再按文件名从 dumps 里查回卡片。
     */
    var pendingExportFile by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingDump by remember { mutableStateOf<ViewingDump?>(null) }
    var deleting by remember { mutableStateOf<DumpCard?>(null) }

    /** 提示统一交给外壳的 SnackbarHost（ChameleonApp），本页不再自带一套 */
    fun notify(message: String) = mainViewModel.showMessage(message)

    fun exportCard(dump: DumpCard) {
        scope.launch {
            // readBlocks 是文件读取，切到 IO 线程（exportToDownloads 内部自带 IO 切换）
            val content = withContext(Dispatchers.IO) { cardsViewModel.readBlocks(dump) }
            if (content == null) {
                notify(context.getString(R.string.card_read_failed))
                return@launch
            }
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
        val fileName = pendingExportFile ?: return@rememberLauncherForActivityResult
        pendingExportFile = null
        if (!granted) {
            notify(context.getString(R.string.card_export_permission_denied))
            return@rememberLauncherForActivityResult
        }
        // 旋转后 dumps 可能尚未恢复，查不到时静默放弃（提示会让用户困惑）
        dumps.firstOrNull { it.fileName == fileName }?.let(::exportCard)
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
            pendingExportFile = dump.fileName
            exportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            exportCard(dump)
        }
    }

    fun onView(dump: DumpCard) {
        // readBlocks 是文件读取，切到 IO 线程
        scope.launch {
            val content = withContext(Dispatchers.IO) { cardsViewModel.readBlocks(dump) }
            if (content == null) {
                notify(context.getString(R.string.card_read_failed))
                return@launch
            }
            viewingDump = ViewingDump(dump.uidHex, content)
        }
    }

    // 读卡流程的成功 / 失败提示经 MainViewModel 的 Snackbar 通道由外壳统一展示：
    // 本页发起的「写入槽 / 加载」失败时也能立刻看到提示（不依赖本页是否在场）

    // 设备流程（读卡 / 破解 / Dump / 写入槽）进行中禁用卡片操作，防误删或重复发起
    val busy = readerState.phase != ReaderPhase.Idle
    val writing = readerState.phase == ReaderPhase.WritingEmu

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
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
                            // 失败原因由 writeDumpToEmulator 自行上报具体文案
                            // （未连接 / 设备忙 / 元数据非法），这里不要再补一条笼统提示
                            onWrite = { mainViewModel.writeDumpToEmulator(dump) },
                            onLoad = { mainViewModel.loadDumpToReader(dump) },
                            onView = { onView(dump) },
                            onExport = { onExport(dump) },
                            onDelete = { deleting = dump },
                        )
                    }
                }
            }
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

/**
 * 卡片列表项预览。
 *
 * `DumpCardItem` 只吃普通参数（[DumpCard] + 副标题 + 两个布尔 + 五个回调），
 * 不碰 ViewModel，所以能直接造一个假卡片渲染出来。想看「写入中」和
 * 「流程忙碌（按钮全灰）」两种状态，改一下下面两个布尔参数即可。
 */
@Preview(showBackground = true)
@Composable
private fun DumpCardItemPreview() {
    ChameleonTheme {
        DumpCardItem(
            dump = DumpCard(
                fileName = "1E6FE3A6_08_0400_1.eml",
                uidHex = "1E6FE3A6",
                sakHex = "08",
                atqaHex = "0400",
                prng = PrngType.WEAK,
                // 固定时间戳，保证预览每次渲染结果一致（便于对比两次改动）
                savedAtMillis = 1_767_225_600_000L,
                sizeBytes = 1024L * 2,
            ),
            subtitle = "2026-09-15 12:34 · SAK 08 · ATQA 0400 · 64 块",
            busy = false,
            writing = false,
            onWrite = {},
            onLoad = {},
            onView = {},
            onExport = {},
            onDelete = {},
        )
    }
}
