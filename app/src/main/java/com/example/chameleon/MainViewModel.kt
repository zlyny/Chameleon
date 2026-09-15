package com.example.chameleon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chameleon.ble.BleConstants
import com.example.chameleon.ble.ChameleonBleClient
import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.device.DeviceMode
import com.example.chameleon.device.DeviceModeStore
import com.example.chameleon.data.DumpCard
import com.example.chameleon.data.DumpRepository
import com.example.chameleon.device.KeyType
import com.example.chameleon.log.LogEntry
import com.example.chameleon.log.LogKind
import com.example.chameleon.log.LogStore
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import com.example.chameleon.protocol.ChameleonCommand
import com.example.chameleon.protocol.ChameleonFrame
import com.example.chameleon.protocol.HexUtils
import com.example.chameleon.reader.ReaderError
import com.example.chameleon.reader.ReaderFlowController
import com.example.chameleon.reader.ReaderPhase
import com.example.chameleon.reader.ReaderState
import com.example.chameleon.reader.describeError
import com.example.chameleon.scan.ScanError
import com.example.chameleon.scan.toScanError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.client.distinctByPeripheral

/** 扫描发现的设备（广播名优先，回退系统缓存名） */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

/**
 * 一次性 UI 提示（Snackbar 文案）。
 *
 * 分两种：普通文案（复制 / 导出 / 删除的结果）与 [ReaderError]（读卡流程的
 * 失败原因）。之所以不统一成 `String`：[ReaderError] 要留给 UI 调
 * `toText(context)` 映射，ViewModel 里不能出现 `Context`。
 */
sealed interface UiMessage {
    data class Text(val value: String) : UiMessage

    data class Error(val error: ReaderError) : UiMessage
}

/**
 * 应用级共享 ViewModel：管理 BLE 扫描与连接生命周期、设备工作模式缓存与
 * 通信日志；读卡 / 破解 / Dump / 写模拟卡 / mfkey32 流程委托 [readerFlow]
 * （[ReaderFlowController]），UI 仍统一经本类观察与触发。四个页面
 * 共享同一实例。
 *
 * 线程模型：全部状态流更新与命令收发均运行在主线程调度器上，
 * UI 可直接观察渲染，无需额外线程同步。
 *
 * 依赖经构造注入（见 [com.example.chameleon.di.AppContainer]）：本类不再
 * 持有 `Application`。注意 `connectDevice()` 里仍直接 `new ChameleonBleClient`
 * ——那是「物理层连接」的入口，也是未来做 USB / BLE 自适应的唯一位置。
 */
class MainViewModel(
    private val centralManager: CentralManager,
    private val dumpRepository: DumpRepository,
    private val deviceModeStore: DeviceModeStore,
    private val logStore: LogStore,
) : ViewModel() {

    /** 连接状态 */
    sealed interface ConnectionState {  //sealed(封闭)的核心价值,让编译器知道这个接口总共只有 3 个子类型
        data object Disconnected : ConnectionState  //全局只有一个实例的常量状态,所以用单例

        data object Connecting : ConnectionState

        data class Connected(val name: String, val address: String) : ConnectionState   //数据类,可以设置默认值
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** BLE 扫描状态（原独立 ScanViewModel 的职责，已并入本类） */
    sealed interface ScanState {
        data object Idle : ScanState

        data class Scanning(val devices: List<DiscoveredDevice>) : ScanState

        data class Finished(val devices: List<DiscoveredDevice>) : ScanState

        /** 扫描启动失败（蓝牙未开启 / 权限被拒等），[error] 为结构化原因 */
        data class Failed(val error: ScanError) : ScanState
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** 进行中的扫描协程，用于中途叫停 */
    private var scanJob: Job? = null

    /** 设备工作模式（连接就绪后读取一次并缓存，主界面图标与其挂钩） */
    val deviceMode: StateFlow<DeviceMode> = deviceModeStore.mode

    val log: StateFlow<List<LogEntry>> = logStore.log

    /** 读卡流程控制器（持有 readerState；会话 / 模式缓存 / 日志经构造注入） */
    private val readerFlow = ReaderFlowController(
        scope = viewModelScope,
        sessionProvider = { session },
        deviceModeStore = deviceModeStore,
        dumpRepository = dumpRepository,
        appendLog = logStore::append,
        notify = { showMessage(it) },
    )

    val readerState: StateFlow<ReaderState> get() = readerFlow.readerState

    /**
     * 一次性提示消息：复制日志 / 导出 / 删除等不经 [readerState] 的反馈，
     * 以及读卡流程的成功 / 失败提示，都走这一条通道。由外壳的 SnackbarHost
     * 统一展示，这样提示与「当前显示哪一页」无关。
     *
     * `extraBufferCapacity = 1`：收集器正忙（Snackbar 显示中约 4 秒）时留住
     * 最后一条——只有这期间来 2 条以上才会丢，这是刻意接受的取舍。
     */
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 1)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    /** 弹出一条提示（见 [messages]） */
    fun showMessage(message: UiMessage) {
        _messages.tryEmit(message)
    }

    /** 便捷重载：普通文案直接以字符串上报 */
    fun showMessage(text: String) = showMessage(UiMessage.Text(text))

    private var client: ChameleonBleClient? = null  //ble层
    private var session: ChameleonSession? = null   //会话层

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /**
     * 开始扫描；重复调用会替代进行中的扫描。
     *
     * 扫描在 [BleConstants.SCAN_DURATION] 后自动结束；同一设备只保留首次
     * 扫描结果（`distinctByPeripheral`，与 nRF Toolbox 语义一致）。
     */
    fun startScan() {
        if (_scanState.value is ScanState.Scanning) return  //已在扫，忽略
        scanJob?.cancel()                                   //有旧任务，取消
        _scanState.value = ScanState.Scanning(emptyList())  //进入扫描态，清空列表
        scanJob = viewModelScope.launch {
            try {
                centralManager
                    .scan(timeout = BleConstants.SCAN_DURATION) //蓝牙库的扫描 Flow，自带超时
                    .distinctByPeripheral()             //按设备去重，只留首次
                    .collect { result ->                //每发现一台设备回调一次
                        _scanState.update { state ->
                            // 状态守卫：cancel 是异步生效的，防止已 Finished 又被设回 Scanning
                            if (state is ScanState.Scanning) {
                                ScanState.Scanning(
                                    (state.devices + result.toDiscoveredDevice()).sorted(),
                                )
                            } else {
                                state
                            }
                        }
                    }
                _scanState.update { state ->
                    ScanState.Finished((state as? ScanState.Scanning)?.devices ?: emptyList())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e     //scanJob.cancel 后会跳到这里（异步）
                _scanState.value = ScanState.Failed(e.toScanError())
            }
        }
    }

    /** 停止扫描，保留已发现的设备 */
    fun stopScan() {
        scanJob?.cancel()
        _scanState.update { state ->
            if (state is ScanState.Scanning) ScanState.Finished(state.devices) else state
        }
    }

    // ------------------------------------------------------------------
    // 连接管理
    // ------------------------------------------------------------------

    /**
     * 连接扫描页选定的设备（[address] 为 MAC 地址，设备已在 CentralManager 缓存中）。
     * 连接就绪后自动创建命令会话并读取设备工作模式。
     */
    fun connectDevice(address: String, name: String?) {
        if (client != null) return  //防重入保护
        val displayName = name ?: address

        // 物理层连接入口：目前固定 BLE。接入 USB 时，这里的构造替换为
        // 按可用性选择 UsbCdcTransport / ChameleonBleClient（协议层不受影响）
        val newClient = ChameleonBleClient(centralManager)
        newClient.listener = object : ChameleonBleClient.Listener { // 匿名对象实现监听器
            override fun onReady(mtu: Int) {
                logStore.append(LogKind.INFO, "连接就绪（MTU=$mtu）")
                session = ChameleonSession(newClient)
                _connectionState.value = ConnectionState.Connected(displayName, address)    //将蓝牙回调转换成状态流
                refreshDeviceMode()
            }

            override fun onFrameReceived(frame: ChameleonFrame) {
                logStore.append(LogKind.RX, formatFrameForLog(frame))
            }

            override fun onFrameSent(frame: ChameleonFrame) {
                logStore.append(LogKind.TX, formatFrameForLog(frame))
            }

            override fun onDisconnected(byUser: Boolean) {
                logStore.append(LogKind.INFO, if (byUser) "已断开连接" else "连接已断开")
                session = null
                client = null
                _connectionState.value = ConnectionState.Disconnected
                deviceModeStore.reset()
                readerFlow.reset()  //卡已不可访问，清空读卡页状态
            }

            override fun onError(message: String) {
                logStore.append(LogKind.ERROR, message)
            }
        }
        client = newClient  //配合函数入口的防冲入保护使用
        _connectionState.value = ConnectionState.Connecting
        logStore.append(LogKind.INFO, "正在连接 $displayName（$address）…")
        viewModelScope.launch {
            try {
                newClient.connect(address)
            } catch (e: Exception) {
                logStore.append(LogKind.ERROR, "连接失败：${e.message}")
                client = null
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    /** 断开当前连接 */
    fun disconnect() {
        client?.disconnect()
    }

    /** 查询电池信息（设备级命令，结果写入日志） */
    fun requestBatteryInfo() {
        val s = session ?: return   //取到局部变量中,防止协程运行时 断开连接session失效
        viewModelScope.launch {
            try {
                val (voltageMv, percent) = s.getBatteryInfo()
                logStore.append(LogKind.INFO, "电池：${voltageMv}mV（电量 $percent%）")
            } catch (e: Exception) {
                logStore.append(LogKind.ERROR, "电池查询失败：${describeError(e)}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 读卡流程（委托 ReaderFlowController，KDoc 见对应方法）
    // ------------------------------------------------------------------

    fun readCard() = readerFlow.readCard()

    fun recoverKeys() = readerFlow.recoverKeys()

    fun recoverKeyByNested(sector: Int, keyType: KeyType) =
        readerFlow.recoverKeyByNested(sector, keyType)

    fun dumpCard() = readerFlow.dumpCard()

    fun writeDumpToEmulator(dump: DumpCard) = readerFlow.writeDumpToEmulator(dump)

    fun loadDumpToReader(dump: DumpCard) = readerFlow.loadDumpToReader(dump)

    fun mfkey32() = readerFlow.mfkey32()

    // ------------------------------------------------------------------
    // 设备工作模式
    // ------------------------------------------------------------------

    /** 连接就绪后读取并缓存设备工作模式（主界面模式图标的唯一数据源） */
    private fun refreshDeviceMode() {
        val s = session ?: return
        viewModelScope.launch {
            try {
                val mode = s.getDeviceMode()
                deviceModeStore.update(mode)
                logStore.append(LogKind.INFO, "设备工作模式：" + if (mode == DeviceMode.READER) "读卡器" else "模拟卡")
            } catch (e: Exception) {
                logStore.append(LogKind.ERROR, "读取工作模式失败：${describeError(e)}")
            }
        }
    }

    /**
     * 点击模式图标切换设备工作模式（读卡器 <-> 模拟卡）。
     * 读卡流程进行中拒绝切换避免中断；图标渲染仍由 [deviceMode] 流驱动。
     */
    fun toggleDeviceMode() {
        val s = session ?: run {
            logStore.append(LogKind.ERROR, "设备未连接，无法切换工作模式")
            return
        }
        val current = deviceModeStore.mode.value
        if (current == DeviceMode.UNKNOWN) return
        // 读卡流程进行中（phase != Idle）时拒绝切换：这里的 phase 检查同时兜住了
        // 「同一瞬间连点两下」——第二次点击时设备已进入切换，但 phase 仍是 Idle，
        // 故仍可能连发两条指令。代价可接受（再点一次即恢复一致），换来少一个
        // 需要手工维护的在途标志。
        if (readerState.value.phase != ReaderPhase.Idle) {
            logStore.append(LogKind.INFO, "读卡操作进行中，暂不切换工作模式")
            return
        }
        val target = if (current == DeviceMode.READER) DeviceMode.EMULATOR else DeviceMode.READER

        viewModelScope.launch {
            try {
                logStore.append(LogKind.INFO, "切换为${if (target == DeviceMode.READER) "读卡器" else "模拟卡"}模式…")
                s.changeDeviceMode(target)
                deviceModeStore.update(target)
                logStore.append(LogKind.INFO, "已切换为${if (target == DeviceMode.READER) "读卡器" else "模拟卡"}模式")
            } catch (e: Exception) {
                logStore.append(LogKind.ERROR, "切换工作模式失败：${describeError(e)}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    fun clearLog() {
        logStore.clear()
    }

    override fun onCleared() {
        scanJob?.cancel()   //临终清理
        client?.disconnect()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun ScanResult.toDiscoveredDevice() = DiscoveredDevice(
        address = peripheral.address,
        name = peripheral.name,
        rssi = rssi,
    )

    /**
     * 排序：有名字的在前，其余按信号强度降序。
     *
     * `compareByDescending { it.name != null }` 是在**比较 Boolean**——
     * Kotlin 里 `true > false`，所以 descending 会把 `true`（有名字）排在前面。
     * 这是本文件里最容易看愣的一行：它比较的不是名字本身，而是「有没有名字」。
     * `.thenByDescending { }` 则是第一项相等时的次级排序键（类似 SQL 的 ORDER BY a, b）。
     */
    private fun List<DiscoveredDevice>.sorted(): List<DiscoveredDevice> =
        sortedWith(
            compareByDescending<DiscoveredDevice> { it.name != null }
                .thenByDescending { it.rssi },
        )

    private fun formatFrameForLog(frame: ChameleonFrame): String {
        val builder = StringBuilder(HexUtils.format(frame.encode()))
        builder.append("  [").append(ChameleonCommand.nameOf(frame.cmd))
            .append(" status=0x%04X".format(frame.status)).append(']')
        return builder.toString()
    }

}
