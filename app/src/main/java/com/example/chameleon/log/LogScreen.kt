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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chameleon.MainViewModel
import com.example.chameleon.R
import com.example.chameleon.ui.theme.ChameleonTheme
import kotlinx.coroutines.launch

/*
 * ============================================================================
 *  Compose 入门导读（建议从这里开始读本项目的 UI 代码）
 * ============================================================================
 *
 * 本文件是日志页的完整实现，规模适中且用到了 Compose 最核心的一批概念。
 * 读代码前先记住三条与 View 体系的根本差异：
 *
 * 1) **UI 就是函数，没有 `findViewById`**
 *    `@Composable fun Xxx()` 描述「屏幕上应该是什么」，而不是「怎么去改某个 View」。
 *    数据变了，Compose 会重新执行这个函数（称为**重组 recomposition**），
 *    自动算出差异并更新屏幕。你永远不持有 UI 对象的引用。
 *
 * 2) **单向数据流（UDF）：状态向下、事件向上**
 *    父级把「状态」当参数传给子级，子级通过 `onXxx: () -> Unit` 回调把事件交给父级。
 *    子级**绝不自己修改**传进来的状态。这是本文件最重要的结构约定。
 *
 * 3) **状态必须被 `remember` 才活得过重组**
 *    函数会被反复执行，里面 `val x = ...` 每次都会重建。要跨重组保留，用 `remember`。
 *    要跨配置变更（旋转）保留，用 `rememberSaveable`。
 *
 * ---------------------------------------------------------------------------
 * 本文件用到的概念，按出现顺序：
 *
 * | 概念 | 位置 | 一句话解释 |
 * |---|---|---|
 * | 状态提升（有状态/无状态重载） | 下面两个 `LogScreen` | 无状态版可预览、可复用、易测试 |
 * | `collectAsStateWithLifecycle` | 有状态版 | Flow → Compose 状态，且只在界面可见时收集 |
 * | `Modifier` 链式修饰 | 到处都是 | 尺寸/间距/背景等；**顺序敏感**，先写的先应用 |
 * | `Column` / `Row` | `LogScreen` / `LogToolbar` | 纵向 / 横向排列子项 |
 * | `Modifier.weight(1f)` | `LogList` 调用处 | 在 Column/Row 中「吃掉」剩余空间 |
 * | `remember` | `LogList` | 跨重组保留同一个对象（这里是滚动状态） |
 * | `derivedStateOf` | `LogList` | 派生状态：只在结果翻转时通知，避免无效重组 |
 * | `LaunchedEffect(key)` | `LogList` | 进入组合 / key 变化时启动协程；key 不变不重启 |
 * | `LazyColumn` | `LogList` | 只组合可见项的长列表（对应 RecyclerView） |
 * | `key = {}` | `LazyColumn` 的 items | 给每项稳定标识，决定重组与动画是否错位 |
 * | `Surface` / `MaterialTheme` | `LogList` | Material3 容器与主题取色/取字体 |
 * | `@Preview` | 文件末尾 | Android Studio 里直接预览，不跑真机 |
 *
 * ---------------------------------------------------------------------------
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

/**
 * **有状态重载**：负责把 ViewModel 的数据接进来。
 *
 * 这就是「状态提升」的那一半：本函数知道数据从哪来（ViewModel），
 * 但它不做任何布局，只是把状态 + 事件回调转发给下面的无状态版本。
 *
 * 这样拆的好处：无状态版不依赖 ViewModel，可以直接在 `@Preview` 里跑，
 * 也可以在任何地方复用。
 */
@Composable
fun LogScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    // Flow → Compose 状态。`by` 委托让 `entries` 直接就是 List<LogEntry>。
    // 用 `collectAsStateWithLifecycle`（而不是 collectAsState）是因为它只在
    // 界面可见时收集，App 退到后台自动停止，省资源。
    val entries by viewModel.log.collectAsStateWithLifecycle()

    LogScreen(
        entries = entries,
        onClear = viewModel::clearLog,
        // 复制结果等提示统一交给外壳的 SnackbarHost，本页不再自带一套
        onMessage = viewModel::showMessage,
        modifier = modifier,
    )
}

/**
 * **无状态重载**：只接收状态和回调，是真正描述 UI 的地方。
 *
 * 注意参数命名约定：`onXxx: (参数) -> Unit` 表示「发生了 Xxx，交给上层处理」。
 * 本函数不修改 [entries]，也不自己弹提示，一切都通过回调上报。
 */
@Composable
fun LogScreen(
    entries: List<LogEntry>,
    onClear: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // LocalContext.current 是「组合局部值（CompositionLocal）」：
    // 类似隐式参数，由上层提供，这里直接取到 Context 而不用层层传递。
    val context = LocalContext.current

    // Column = 纵向排列（对比：Row 横向，Box 层叠）。
    // modifier 由调用方传入再叠加 fillMaxSize —— 让调用方决定外层尺寸，
    // 是好习惯（本文件末尾的 Preview 就靠它加 padding）。
    Column(modifier = modifier.fillMaxSize()) {
        LogToolbar(
            onCopy = {
                // 复制是「一次性动作」，不属于 UI 状态，所以直接在这里做；
                // 结果（提示文案）通过 onMessage 上报给外壳弹 Snackbar。
                if (entries.isEmpty()) {
                    onMessage(context.getString(R.string.log_copy_empty))
                    return@LogToolbar // 具名返回：跳出这个 lambda，不是跳出外层函数
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
                    onMessage(context.getString(R.string.log_copied, entries.size))
                }
            },
            onClear = onClear,
        )
        // weight(1f) = 把 Column 剩余的高度全给列表（Toolbar 先按内容高度占位）。
        // 等价于 LinearLayout 的 layout_weight，但只在这一个 Modifier 上写。
        LogList(entries = entries, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * 顶部工具条。
 *
 * 这类「纯粹把入参渲染出来、不含业务逻辑」的小组件叫**哑组件**，
 * 是 Compose 里最好写、也最该多写的部分（易复用、易预览）。
 */
@Composable
private fun LogToolbar(onCopy: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.log_title),
            // 标题占满剩余宽度，于是两个按钮被「挤」到右侧。
            // 这是 Row 里实现「左边标题、右边按钮」的标准写法。
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

/**
 * 日志列表——本文件技术含量最高的部分。
 *
 * Compose 里「在 UI 上做一件会变的事」通常需要三件套，这里全都用上了：
 * 1. `remember` 保存**状态**（滚动位置）；
 * 2. `derivedStateOf` 由状态**派生**出判断结果；
 * 3. `LaunchedEffect` 在合适的时机执行**副作用**（滚动动画/定位）。
 */
@Composable
private fun LogList(entries: List<LogEntry>, modifier: Modifier = Modifier) {
    // 空态直接在组合里 `return` 另一套 UI。Compose 里「用 if 切换 UI」是完全正常的
    // ——不再是 View 体系里的 setVisibility(GONE)。
    if (entries.isEmpty()) {
        Text(
            text = stringResource(R.string.log_empty),
            modifier = modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    // remember：本函数会被重组很多次，但列表的滚动位置必须保持住，
    // 所以状态对象要用 remember 记住；没有它，每次重组都会跳回顶部。
    // 这个 state 同时是「读取滚动位置」和「程序化滚动」的入口。
    val listState = rememberLazyListState()

    // 四种日志色只解析一次（values / values-night 各一份，跟随深浅模式）
    val colors = LogColors(
        tx = colorResource(R.color.log_tx),
        rx = colorResource(R.color.log_rx),
        error = colorResource(R.color.log_error),
        info = colorResource(R.color.log_info),
    )

    /**
     * 是否停在尾部（距末尾 TAIL_THRESHOLD 项以内）：用户上翻查看历史时为 false，
     * 此时不再被新日志拽回底部。
     *
     * 为什么用 `derivedStateOf` 而不是直接写个表达式？
     * 因为滚动时 `layoutInfo` 每帧都在变，直接读会导致每帧重组；
     * `derivedStateOf` 只在**结果真正翻转**时通知，把「连续变化」削成「布尔跳变」。
     */
    val atTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - TAIL_THRESHOLD - 1
        }
    }

    // 进入页面（或清空后日志由空转非空）时定位到最新一条，
    // 与原 View 版进入即展示最新日志的行为保持一致。
    // key = Unit 表示「只在进入组合时跑一次」。
    // 另外：LaunchedEffect 的 lambda 是协程环境，所以能调 scrollToItem 这个挂起函数。
    LaunchedEffect(Unit) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    // key = entries.size：每来一条新日志就重新判断一次是否要跟随到最新。
    // 注意读的是 atTail（派生状态），所以用户翻看历史时不会被强行拽回底部。
    LaunchedEffect(entries.size) {
        if (atTail) listState.scrollToItem(entries.lastIndex)
    }

    // Surface = Material3 的「容器」：负责背景色、圆角形状，并按 shape 裁切内容。
    Surface(
        modifier = modifier.padding(top = 8.dp),
        shape = RoundedCornerShape(8.dp),
        color = colorResource(R.color.log_background),
    ) {
        // SelectionContainer 让内部文字可长按选中（对应 View 的 textIsSelectable）
        SelectionContainer {
            // LazyColumn：只组合可见的那几项（对标 RecyclerView，但不用写 Adapter）。
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // key 很关键：它是每项在重组时的「身份证」。
                // 用 LogEntry.id（自增且唯一）而不是下标，插入/删除时才不会错位。
                items(count = entries.size, key = { entries[it].id }) { index ->
                    Text(
                        text = entries[index].text,
                        color = colors.of(entries[index].kind),
                        style = LOG_TEXT_STYLE,
                    )
                }
            }
        }
    }
}

/** 日志配色（按类型取色，避免在每条 item 里重复解析资源） */
private class LogColors(val tx: Color, val rx: Color, val error: Color, val info: Color) {
    fun of(kind: LogKind): Color = when (kind) {
        LogKind.TX -> tx
        LogKind.RX -> rx
        LogKind.ERROR -> error
        LogKind.INFO -> info
    }
}

private val LOG_TEXT_STYLE = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

/** 距末尾多少项以内仍视为“停留在底部”，继续跟随新日志 */
private const val TAIL_THRESHOLD = 3

@Preview(showBackground = true)
@Composable
private fun LogScreenPreview() {
    ChameleonTheme {
        LogScreen(
            entries = listOf(
                LogEntry(1, LogKind.TX, "11 EF 03 EA 00 00 00 00 13 00  [GET_DEVICE_MODE status=0x0000]"),
                LogEntry(2, LogKind.RX, "11 EF 03 EA 00 68 01 00 17 01 00  [GET_DEVICE_MODE status=0x0068]"),
                LogEntry(3, LogKind.INFO, "设备工作模式：读卡器"),
                LogEntry(4, LogKind.ERROR, "连接失败：GATT 133"),
            ),
            onClear = {},
            onMessage = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
