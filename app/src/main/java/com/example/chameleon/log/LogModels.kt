package com.example.chameleon.log

/**
 * 通信日志模型（独立于 MainViewModel，供日志页渲染与读卡流程记录共用）。
 * [LogKind] 决定日志页中的显示颜色，见 LogFragment。
 */

/** 日志类型，决定 UI 中的显示颜色 */
enum class LogKind { TX, RX, INFO, ERROR }

data class LogEntry(val id: Long, val kind: LogKind, val text: String)
