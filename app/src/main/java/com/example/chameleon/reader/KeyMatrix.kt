package com.example.chameleon.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.chameleon.R
import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.device.KeyState
import com.example.chameleon.device.KeyStatus
import com.example.chameleon.device.KeyType
import com.example.chameleon.device.SectorKeys

/**
 * 密钥矩阵（Compose 实现）。
 *
 * 替代原先「在 Fragment 里动态往 GridLayout 塞 32 个 ImageView 并用
 * `arrayOfNulls<ImageView>(32)` 手工缓存引用」的写法，收益：
 * - **不再有视图引用缓存**：状态变化前要手工回写每个 ImageView，重建/错位风险归零；
 * - **单元格宽度自适应**：原先固定 36dp，列宽合计约 324dp，在 360dp 屏上
 *   （扣掉根 padding 16dp + 卡片 padding 32dp，可用约 296dp）会横向溢出；
 *   现在每个单元格 `weight(1f) + aspectRatio(1f)`，自动适配任意屏宽并保持正方形；
 * - **补齐无障碍描述**：原先 32 个 ImageView 没有任何 contentDescription。
 *
 * 两组各 9 列 × 3 行的布局保持不变（16 个扇区拆成 0-7 / 8-15 两组）。
 * 由 [ReaderScreen] 提供状态，`recoverKeyByNested` 作为点击回调。
 */
@Composable
fun KeyMatrix(
    sectors: List<SectorKeys>,
    onKeyClick: (sector: Int, keyType: KeyType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(ChameleonSession.MF1_SECTOR_COUNT / COLUMNS_PER_GROUP) { group ->
            if (group > 0) Spacer(modifier = Modifier.height(GROUP_GAP))
            KeyMatrixGroup(
                sectors = sectors,
                firstSector = group * COLUMNS_PER_GROUP,
                onKeyClick = onKeyClick,
            )
        }
    }
}

/** 一组矩阵：列号行 + KeyA 行 + KeyB 行，首列为行标签 / 扇区号表头 */
@Composable
private fun KeyMatrixGroup(
    sectors: List<SectorKeys>,
    firstSector: Int,
    onKeyClick: (sector: Int, keyType: KeyType) -> Unit,
) {
    val cells = COLUMNS_PER_GROUP + 1 // 首列是行标签 / 表头占位

    // 列号行
    MatrixRow(cells) { index ->
        if (index > 0) {
            Text(
                text = (firstSector + index - 1).toString(),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }

    for ((row, keyType) in listOf("A" to KeyType.A, "B" to KeyType.B)) {
        MatrixRow(cells) { index ->
            if (index == 0) {
                Text(text = row, style = MaterialTheme.typography.bodyMedium)
            } else {
                val sector = firstSector + index - 1
                KeyCell(
                    sector = sector,
                    keyType = keyType,
                    state = sectors.getOrNull(sector)?.let {
                        if (keyType == KeyType.A) it.keyA else it.keyB
                    },
                    onKeyClick = onKeyClick,
                )
            }
        }
    }
}

/** 一行单元格：每个格子等宽（`weight(1f)`）且为正方形（`aspectRatio(1f)`） */
@Composable
private fun MatrixRow(cellCount: Int, content: @Composable (index: Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        repeat(cellCount) { index ->
            Box(
                modifier = Modifier.weight(1f).aspectRatio(1f),
                contentAlignment = Alignment.Center,
                content = { content(index) },
            )
        }
    }
}

@Composable
private fun KeyCell(
    sector: Int,
    keyType: KeyType,
    state: KeyState?,
    onKeyClick: (sector: Int, keyType: KeyType) -> Unit,
) {
    val status = state?.status ?: KeyStatus.UNKNOWN
    Icon(
        painter = painterResource(iconFor(status)),
        // 这些 drawable 自带配色（绿/蓝/红/灰），不要被 LocalContentColor 覆盖
        tint = Color.Unspecified,
        contentDescription = contentDescriptionFor(status, sector, keyType),
        modifier = Modifier
            .size(CELL_ICON_SIZE)
            // 只有未破解（红叉）的位可点击发起 Nested 攻击，与原 View 版一致
            .clickable(enabled = status == KeyStatus.MISSING) { onKeyClick(sector, keyType) },
    )
}

@Composable
private fun contentDescriptionFor(status: KeyStatus, sector: Int, keyType: KeyType): String {
    val label = if (keyType == KeyType.A) "A" else "B"
    return when (status) {
        KeyStatus.VERIFIED -> stringResource(R.string.key_cell_verified)
        KeyStatus.FOUND -> stringResource(R.string.key_cell_found)
        KeyStatus.MISSING -> stringResource(R.string.key_cell_missing, sector, label)
        KeyStatus.UNKNOWN -> stringResource(R.string.key_cell_unknown)
    }
}

/** 密钥位图标：蓝勾=VERIFIED（Dump 验证）/ 绿勾=FOUND / 红叉=MISSING / 灰圈=UNKNOWN */
private fun iconFor(status: KeyStatus): Int = when (status) {
    KeyStatus.VERIFIED -> R.drawable.ic_key_verified
    KeyStatus.FOUND -> R.drawable.ic_key_found
    KeyStatus.MISSING -> R.drawable.ic_key_missing
    KeyStatus.UNKNOWN -> R.drawable.ic_key_unknown
}

private val CELL_ICON_SIZE = 24.dp
private val GROUP_GAP = 8.dp

/** 每组矩阵的扇区列数（首列是行标签，故每行 COLUMNS_PER_GROUP + 1 格） */
private const val COLUMNS_PER_GROUP = ChameleonSession.MF1_SECTOR_COUNT / 2
