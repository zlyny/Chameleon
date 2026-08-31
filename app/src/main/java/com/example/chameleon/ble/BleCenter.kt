package com.example.chameleon.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.environment.android.NativeAndroidEnvironment

/**
 * 进程级 BLE 基础设施持有者。
 *
 * CentralManager / NativeAndroidEnvironment 是有状态的重量级对象（内部注册
 * BroadcastReceiver），扫描页与主界面共用同一实例，避免重复创建与设备
 * 缓存丢失（跨页传递设备地址后可按 id 找回 Peripheral）。
 *
 * 由 [com.example.chameleon.ChameleonApplication] 在进程启动时初始化。
 */
object BleCenter {

    /** 进程级协程作用域，CentralManager 的内部任务运行于此 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var centralManager: CentralManager
        private set

    fun initialize(context: Context) {
        if (::centralManager.isInitialized) return
        val environment = NativeAndroidEnvironment.getInstance(
            context,
            // 与 Manifest 中 BLUETOOTH_SCAN 的 neverForLocation 声明一致
            isNeverForLocationFlagSet = true,
        )
        centralManager = CentralManager.Factory.native(environment, scope)
    }
}
