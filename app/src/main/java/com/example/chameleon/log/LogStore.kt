package com.example.chameleon.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通信日志模型（独立于 MainViewModel，供日志页渲染与读卡流程记录共用）。
 * [LogKind] 决定日志页中的显示颜色，见 LogScreen。
 */

/** 日志类型，决定 UI 中的显示颜色 */
enum class LogKind { TX, RX, INFO, ERROR }

data class LogEntry(val id: Long, val kind: LogKind, val text: String)

/**
 * 通信日志缓冲：容量上限内保留最近若干条，供日志页展示。
 *
 * 原先这份职责夹在 `MainViewModel` 里（`nextLogId` / `MAX_LOG_ENTRIES` /
 * `appendLog`）。抽出来的收益主要是**封装**：日志的序号分配与容量裁剪
 * 不再混在连接管理代码中间。
 *
 * 说明：每次 append 仍需产出一个不可变快照供 StateFlow 发射，所以不是
 * 严格的 O(1)——但上限只有 200 条，复制开销可以忽略，这里不引入更复杂
 * 的容器。
 */
class LogStore(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<LogEntry>(capacity)
    private var nextId = 0L

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    /** 追加一条日志；超出容量时丢弃最旧的 */
    fun append(kind: LogKind, text: String) {
        while (entries.size >= capacity) {
            entries.removeFirst()
        }
        entries.addLast(LogEntry(nextId++, kind, text))
        _log.value = entries.toList()
    }

    fun clear() {
        entries.clear()
        _log.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
