package com.example.chameleon.reader

import android.content.Context
import com.example.chameleon.R

/**
 * 读卡流程的结构化错误。
 *
 * 原先这类错误是拼好的中文字符串，字面量散落在 `ReaderFlowController`
 * 各处（约 20 处）。缺点：
 * - 无法本地化；
 * - UI 无法按错误类型分支——「可重试」（Nested 未命中）与「必须先重连」
 *   （设备未连接）对用户的引导完全不同；
 * - 同一语义在多处重复书写，改文案要全局搜索。
 *
 * 改成本接口后，产生处只声明**错误类型**，展示文案集中在 [toText] 一处。
 */
sealed interface ReaderError {

    /** 设备未连接 */
    data object NotConnected : ReaderError

    /** 尚未读卡（无 tagInfo），无法进行依赖卡信息的操作 */
    data object CardNotRead : ReaderError

    /** 设备正忙（读卡 / 破解 / 写入进行中） */
    data object DeviceBusy : ReaderError

    /** 没有任何已恢复的密钥，无法继续破解或 dump */
    data object NoKnownKey : ReaderError

    /** 该卡不支持 Mifare Classic */
    data object MifareNotSupported : ReaderError

    /** Hard PRNG，需 hardnested（尚未实现） */
    data object HardPrngUnsupported : ReaderError

    /** PRNG 未知，需重新读卡 */
    data object PrngUnknown : ReaderError

    /** Nested 攻击单次未命中（属正常现象，可重试） */
    data object NestedMiss : ReaderError

    /** Nested 候选密钥验证未通过（属正常现象，可重试） */
    data object NestedVerifyFailed : ReaderError

    /** 卡片库中的 dump 文件读取失败 */
    data object DumpReadFailed : ReaderError

    /** dump 文件名元数据解析失败 */
    data object DumpMetaInvalid : ReaderError

    /** 非 1K 卡（当前仅支持 1K） */
    data object DumpNot1K : ReaderError

    /** 设备没有认证日志（需先写入槽并让读卡器认证） */
    data object NoAuthLog : ReaderError

    /** 认证日志与当前卡片 UID 不符 */
    data object AuthLogUidMismatch : ReaderError

    /** mfkey32 未找到密钥（同组记录不足 2 条） */
    data object Mfkey32NoKey : ReaderError

    /**
     * 某个操作执行失败，[action] 为操作名（如「Dump」）、
     * [detail] 为底层原因（如「设备响应超时」）。
     */
    data class OperationFailed(val action: String, val detail: String) : ReaderError
}

/** 错误 -> 展示文案：UI 层唯一的映射点（新增错误分支时这里会编译报错提醒） */
fun ReaderError.toText(context: Context): String = when (this) {
    ReaderError.NotConnected -> context.getString(R.string.err_not_connected)
    ReaderError.CardNotRead -> context.getString(R.string.err_card_not_read)
    ReaderError.DeviceBusy -> context.getString(R.string.err_device_busy)
    ReaderError.NoKnownKey -> context.getString(R.string.err_no_known_key)
    ReaderError.MifareNotSupported -> context.getString(R.string.err_mifare_not_supported)
    ReaderError.HardPrngUnsupported -> context.getString(R.string.err_hard_prng)
    ReaderError.PrngUnknown -> context.getString(R.string.err_prng_unknown)
    ReaderError.NestedMiss -> context.getString(R.string.err_nested_miss)
    ReaderError.NestedVerifyFailed -> context.getString(R.string.err_nested_verify)
    ReaderError.DumpReadFailed -> context.getString(R.string.err_dump_read)
    ReaderError.DumpMetaInvalid -> context.getString(R.string.err_dump_meta)
    ReaderError.DumpNot1K -> context.getString(R.string.err_dump_not_1k)
    ReaderError.NoAuthLog -> context.getString(R.string.err_no_auth_log)
    ReaderError.AuthLogUidMismatch -> context.getString(R.string.err_auth_log_uid_mismatch)
    ReaderError.Mfkey32NoKey -> context.getString(R.string.err_mfkey32_no_key)
    is ReaderError.OperationFailed ->
        context.getString(R.string.err_operation_failed, action, detail)
}
