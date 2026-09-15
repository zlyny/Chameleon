package com.example.chameleon.di

import android.content.ContentResolver
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.chameleon.MainViewModel
import com.example.chameleon.cards.CardsViewModel
import com.example.chameleon.data.DumpRepository
import com.example.chameleon.device.DeviceModeStore
import com.example.chameleon.device.InMemoryDeviceModeStore
import com.example.chameleon.log.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.environment.android.NativeAndroidEnvironment

/**
 * 依赖容器：负责「谁来创建依赖」，让 ViewModel 只接收、不自己 new。
 *
 * 存在的意义是让两个 ViewModel 脱离 `AndroidViewModel`——它们原先各自
 * 持有一个 `Application` 用来 new `DumpRepository` / 取 `contentResolver` /
 * 调 `getString()`，因此无法在 JVM 单测里实例化。改为构造注入后，
 * ViewModel 里不再出现 `Application`（也没有任何 Android 组件类型）。
 *
 * 后续接入 USB 时，`MainViewModel` 里创建传输实例的那处会改为经本容器
 * 取（USB / BLE 自适应），这是本容器下一个会增长的点。
 */
interface AppContainer {
    val centralManager: CentralManager
    val dumpRepository: DumpRepository
    val contentResolver: ContentResolver
    val deviceModeStore: DeviceModeStore
    val logStore: LogStore
}

class DefaultAppContainer(appContext: Context) : AppContainer {

    private val context = appContext.applicationContext

    /** BLE 内部任务运行的协程作用域（进程级，独立于任何 ViewModel 生命周期） */
    private val bleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 进程内唯一的 [CentralManager]：它是有状态的重量级对象（内部注册
     * BroadcastReceiver），扫描与连接必须共用同一实例，否则重复创建会导致
     * 设备缓存丢失（跨页只传设备地址，需按地址找回 Peripheral）。
     */
    override val centralManager: CentralManager by lazy {
        val environment = NativeAndroidEnvironment.getInstance(
            context,
            // 与 Manifest 中 BLUETOOTH_SCAN 的 neverForLocation 声明一致
            isNeverForLocationFlagSet = true,
        )
        CentralManager.Factory.native(environment, bleScope)
    }

    override val dumpRepository: DumpRepository by lazy { DumpRepository(context) }

    override val contentResolver: ContentResolver
        get() = context.contentResolver

    override val deviceModeStore: DeviceModeStore by lazy { InMemoryDeviceModeStore() }

    override val logStore: LogStore by lazy { LogStore() }
}

/**
 * 两个 ViewModel 的统一工厂：把 [AppContainer] 里的依赖按构造函数塞进去。
 *
 * 之所以不用 Hilt：当前只有 2 个 ViewModel、不到 30 个文件，KSP + 注解处理器
 * 带来的构建成本高于收益。等真正需要按作用域（页面级 / 导航图级）注入时
 * 再切换也不迟。
 */
class ChameleonViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        when (modelClass) {
            MainViewModel::class.java -> MainViewModel(
                centralManager = container.centralManager,
                dumpRepository = container.dumpRepository,
                deviceModeStore = container.deviceModeStore,
                logStore = container.logStore,
            )

            CardsViewModel::class.java -> CardsViewModel(
                dumpRepository = container.dumpRepository,
                contentResolver = container.contentResolver,
            )

            else -> throw IllegalArgumentException("未知的 ViewModel：$modelClass")
        } as T
}

/** 组合树内的容器入口，由 MainActivity 提供 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未提供：需由 MainActivity 通过 CompositionLocalProvider 注入")
}
