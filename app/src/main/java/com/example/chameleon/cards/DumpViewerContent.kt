package com.example.chameleon.cards

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chameleon.R
import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.data.DumpContent
import com.example.chameleon.ui.theme.ChameleonTheme

/**
 * 卡片数据查看对话框（Compose）。
 *
 * 用 Compose 自带的 [AlertDialog] 而非 View 的 `MaterialAlertDialogBuilder`：
 * 对话框窗口缺少 ViewTreeLifecycleOwner / SavedStateRegistryOwner，ComposeView
 * 在其中无法建立组合（详见 README「开发提醒」第 12 条）。
 *
 * Compose 对话框在自己的窗口里按屏幕约束测量，内容区能拿到有界高度，
 * 因此下面的 [DumpViewerContent] 里 `LazyColumn` 正常工作（无需在 View 侧定高度）。
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

/**
 * 卡片数据查看对话框的内容区（Compose 实现）。
 *
 * 相比原 View 版（手工拼 `SpannableStringBuilder` 塞进单个 TextView）：
 * - **LazyColumn**：原版把 64 块全部一次性渲染进一个 TextView，这里只渲染可见块；
 * - **图例常驻底部**：原版图例贴在滚动内容末尾，必须滑到底才看得到；
 * - **横向可平移**：原版每行约 54 字符会在窄屏上折行错位，这里保持单行不换行，
 *   超出视口时横向滚动（见 [Modifier.horizontalScroll]）。
 *
 * 着色规则与原实现完全一致：trailer 的 KeyA/KeyB 绿、访问控制位 [6:10] 琥珀、
 * 未知字节（XX）红色；其余数据字节沿用主题默认色。
 */
@Composable
fun DumpViewerContent(content: DumpContent, modifier: Modifier = Modifier) {
    val colors = DumpColors(
        key = colorResource(R.color.key_found),
        control = colorResource(R.color.dump_control),
        unknown = colorResource(R.color.log_error),
    )

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                repeat(content.blockCount / ChameleonSession.MF1_BLOCKS_PER_SECTOR) { sector ->
                    item(key = "header_$sector") {
                        SectorHeader(content = content, sector = sector, colors = colors)
                    }
                    // 每扇区 4 块连续编号，key 用绝对块号保证全表唯一
                    items(
                        count = ChameleonSession.MF1_BLOCKS_PER_SECTOR,
                        key = { "block_${sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR + it}" },
                    ) { indexInSector ->
                        BlockRow(
                            content = content,
                            block = sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR + indexInSector,
                            colors = colors,
                        )
                    }
                }
            }
        }

        // 图例常驻：不随数据滚动，任何时候都能看到三种颜色的含义
        HorizontalDivider()
        DumpLegend(colors = colors)
    }
}

/** 一个扇区标题行：全部块未知=未破解（红色）、个别块未知=部分未读取 */
@Composable
private fun SectorHeader(content: DumpContent, sector: Int, colors: DumpColors) {
    val firstBlock = sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR
    val knownFlags = (0 until ChameleonSession.MF1_BLOCKS_PER_SECTOR)
        .map { content.isBlockKnown(firstBlock + it) }
    // 注意 isBlockKnown 语义是“整块全部已知”，故“未破解”= 所有块都不完整
    val (titleRes, color) = when {
        knownFlags.all { !it } -> R.string.card_view_sector_locked to colors.unknown
        knownFlags.any { !it } -> R.string.card_view_sector_partial to Color.Unspecified
        else -> R.string.card_view_sector_ok to Color.Unspecified
    }
    Text(
        text = stringResource(titleRes, sector),
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** 一块数据的十六进制行：`B07  AA BB … XX`，逐字节着色 */
@Composable
private fun BlockRow(content: DumpContent, block: Int, colors: DumpColors) {
    val line = remember(content, block, colors) { buildBlockLine(content, block, colors) }
    Text(text = line, style = DUMP_TEXT_STYLE, softWrap = false)
}

private fun buildBlockLine(content: DumpContent, block: Int, colors: DumpColors): AnnotatedString =
    buildAnnotatedString {
        append("B%02d  ".format(block))
        val isTrailer =
            block % ChameleonSession.MF1_BLOCKS_PER_SECTOR ==
                ChameleonSession.MF1_TRAILER_BLOCK_IN_SECTOR
        for (b in 0 until ChameleonSession.MF1_BLOCK_SIZE) {
            if (b > 0) append(' ')
            val index = block * ChameleonSession.MF1_BLOCK_SIZE + b
            val start = length
            append(if (content.known[index]) "%02X".format(content.bytes[index]) else "XX")
            val color = when {
                // 未知字节：红色 XX，直观区分“未读取”与“真实数据 00”
                !content.known[index] -> colors.unknown

                // trailer 访问控制位区域：琥珀色
                isTrailer && b >= ChameleonSession.MF1_TRAILER_ACCESS_OFFSET &&
                    b < ChameleonSession.MF1_TRAILER_KEY_B_OFFSET -> colors.control

                // trailer 密钥区（密钥矩阵回填值）：绿色
                isTrailer -> colors.key

                else -> Color.Unspecified // 数据字节沿用主题默认色
            }
            if (color != Color.Unspecified) addStyle(SpanStyle(color = color), start, length)
        }
    }

@Composable
private fun DumpLegend(colors: DumpColors) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.card_view_legend),
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LegendItem(symbol = "■", label = stringResource(R.string.card_view_legend_key), color = colors.key)
            LegendItem(
                symbol = "■",
                label = stringResource(R.string.card_view_legend_control),
                color = colors.control,
            )
            LegendItem(
                symbol = "XX",
                label = stringResource(R.string.card_view_legend_unknown),
                color = colors.unknown,
            )
        }
    }
}

@Composable
private fun LegendItem(symbol: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = symbol, color = color, style = MaterialTheme.typography.bodySmall)
        Text(
            text = label,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 查看器的三种强调色（由 resources 解析，跟随深浅模式，与 View 配色同源） */
private class DumpColors(val key: Color, val control: Color, val unknown: Color)

private val DUMP_TEXT_STYLE = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

@Preview(showBackground = true)
@Composable
private fun DumpViewerContentPreview() {
    val total = ChameleonSession.MF1_SECTOR_COUNT *
        ChameleonSession.MF1_BLOCKS_PER_SECTOR *
        ChameleonSession.MF1_BLOCK_SIZE
    ChameleonTheme {
        DumpViewerContent(
            content = DumpContent(
                bytes = ByteArray(total) { (it % 256).toByte() },
                // 每隔几个字节标记未知，用于预览红色 XX 与 trailer 着色
                known = BooleanArray(total) { it % 7 != 0 },
            ),
        )
    }
}
