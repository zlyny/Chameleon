package com.example.chameleon.scan

import android.content.Context
import com.example.chameleon.R
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException

/**
 * 扫描失败的结构化原因（与 [ReaderError] 同一套思路）。
 *
 * 原先扫描页 ViewModel 直接调 `getString()` 拼好文案，为此必须继承
 * `AndroidViewModel`——整个类只因为 4 句提示就无法在 JVM 上实例化。
 * 改成类型后，ViewModel（现为 `MainViewModel`）只声明失败原因，文案由 [toText] 映射。
 */
sealed interface ScanError {

    /** 缺少扫描权限（BLUETOOTH_SCAN / ACCESS_FINE_LOCATION） */
    data object PermissionDenied : ScanError

    /** 蓝牙未开启或不可用 */
    data object BluetoothDisabled : ScanError

    /** 其它原因；[detail] 为 null 时展示通用文案 */
    data class Unknown(val detail: String?) : ScanError
}

/** 异常 -> 结构化原因（扫描协程的 catch 处调用） */
fun Throwable.toScanError(): ScanError = when (this) {
    is SecurityException -> ScanError.PermissionDenied
    is BluetoothUnavailableException -> ScanError.BluetoothDisabled
    else -> ScanError.Unknown(message)
}

/** 失败原因 -> 展示文案 */
fun ScanError.toText(context: Context): String = when (this) {
    ScanError.PermissionDenied -> context.getString(R.string.permission_denied)
    ScanError.BluetoothDisabled -> context.getString(R.string.bluetooth_disabled)
    is ScanError.Unknown -> detail ?: context.getString(R.string.scan_failed)
}
