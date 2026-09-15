package com.example.chameleon

import com.example.chameleon.device.DeviceMode
import com.example.chameleon.device.InMemoryDeviceModeStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [InMemoryDeviceModeStore] 的单测。
 *
 * 它存在的背景：原先 `DeviceMode` 的 `MutableStateFlow` 由 MainViewModel 持有
 * 并**以可写形式**传给 ReaderFlowController，同一份状态有两个写入方分处两个类。
 * 收敛成 [com.example.chameleon.device.DeviceModeStore] 后，写入口只剩 [update]。
 * 这里守住「reset 必须回到 UNKNOWN」这条约束——复位漏了会让主界面的模式
 * 图标在断开后仍显示上一次的模式。
 */
class DeviceModeStoreTest {

    @Test
    fun 初始值为UNKNOWN() {
        assertEquals(DeviceMode.UNKNOWN, InMemoryDeviceModeStore().mode.value)
    }

    @Test
    fun update覆盖当前模式() {
        val store = InMemoryDeviceModeStore()

        store.update(DeviceMode.READER)
        assertEquals(DeviceMode.READER, store.mode.value)

        store.update(DeviceMode.EMULATOR)
        assertEquals(DeviceMode.EMULATOR, store.mode.value)
    }

    @Test
    fun reset回到UNKNOWN() {
        val store = InMemoryDeviceModeStore().apply { update(DeviceMode.EMULATOR) }

        store.reset()

        assertEquals(DeviceMode.UNKNOWN, store.mode.value)
    }
}
