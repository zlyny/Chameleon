package com.example.chameleon.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设备工作模式的**唯一**持有者。
 *
 * 原先由 `MainViewModel` 持有 `MutableStateFlow` 并把「可写版」直接传给
 * `ReaderFlowController`，于是 `DeviceMode` 有两个写入方分处两个类：
 * 模式到底是谁改的、改了几次，只能靠全局搜索才能理清。
 *
 * 收敛成本接口后，只有实现能写值，主界面（模式图标）与读卡流程
 * （ensure*Mode）都只能通过 [update] 修改。
 */
interface DeviceModeStore {

    /** 当前工作模式（未连接 / 未读取时为 [DeviceMode.UNKNOWN]） */
    val mode: StateFlow<DeviceMode>

    /** 更新缓存的模式；调用方负责先确保设备侧真的切换成功了 */
    fun update(mode: DeviceMode)

    /** 断开连接时复位 */
    fun reset()
}

class InMemoryDeviceModeStore : DeviceModeStore {

    private val _mode = MutableStateFlow(DeviceMode.UNKNOWN)
    override val mode: StateFlow<DeviceMode> = _mode.asStateFlow()

    override fun update(mode: DeviceMode) {
        _mode.value = mode
    }

    override fun reset() {
        _mode.value = DeviceMode.UNKNOWN
    }
}
