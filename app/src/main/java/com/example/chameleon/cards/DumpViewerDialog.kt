package com.example.chameleon.cards

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.chameleon.R
import com.example.chameleon.device.DumpContent

/**
 * 卡片数据查看对话框（Compose）。
 *
 * 用 Compose 自带的 [AlertDialog] 而非 View 的 `MaterialAlertDialogBuilder`，
 * 原因见 [com.example.chameleon.cards.CardsFragment.showDumpViewer] 的说明——
 * 对话框窗口缺少 ViewTree owner，ComposeView 无法在其中建立组合。
 *
 * Compose 对话框在自己的窗口里按屏幕约束测量，内容区能拿到有界高度，
 * 因此 [DumpViewerContent] 里的 `LazyColumn` 正常工作（无需在 View 侧定高度）。
 */
@Composable
fun DumpViewerDialog(uidHex: String, content: DumpContent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.card_view_title, uidHex)) },
        text = { DumpViewerContent(content) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
    )
}
