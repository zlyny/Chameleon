package com.example.chameleon

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chameleon.ble.BleCenter
import com.example.chameleon.ble.ChameleonBleClient
import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.device.DeviceMode
import com.example.chameleon.device.DumpCard
import com.example.chameleon.device.DumpRepository
import com.example.chameleon.device.KeyType
import com.example.chameleon.log.LogEntry
import com.example.chameleon.log.LogKind
import com.example.chameleon.protocol.ChameleonCommand
import com.example.chameleon.protocol.ChameleonFrame
import com.example.chameleon.protocol.HexUtils
import com.example.chameleon.reader.ReaderFlowController
import com.example.chameleon.reader.ReaderPhase
import com.example.chameleon.reader.ReaderState
import com.example.chameleon.reader.describeError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 应用级共享 ViewModel：管理 BLE 连接生命周期、设备工作模式缓存与通信
 * 日志；读卡 / 破解 / Dump / 写模拟卡 / mfkey32 流程委托 [readerFlow]
 * （[ReaderFlowController]），UI 仍统一经本类观察与触发。四个页面
 * Fragment 共享同一实例。
 *
 * 线程模型：全部状态流更新与命令收发均运行在主线程调度器上，
 * UI 可直接观察渲染，无需额外线程同步。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    /** 连接状态 */
    sealed interface ConnectionState {  //sealed(封闭)的核心价值,让编译器知道这个接口总共只有 3 个子类型
        data object Disconnected : ConnectionState  //全局只有一个实例的常量状态,所以用单例

        data object Connecting : ConnectionState

        data class Connected(val name: String, val address: String) : ConnectionState   //数据类,可以设置默认值
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** 设备工作模式（连接就绪后读取一次并缓存，主界面图标与其挂钩） */
    private val _deviceMode = MutableStateFlow(DeviceMode.UNKNOWN)  //下划线开头是私有的可写版，只有 ViewModel 内部能改
    val deviceMode: StateFlow<DeviceMode> = _deviceMode.asStateFlow()       //公开版本只读

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    /** dump 卡片库（app 专属目录，卡片管理页与 dump 流程共用） */
    private val dumpRepository = DumpRepository(application)

    /** 读卡流程控制器（持有 readerState；会话 / 模式缓存 / 日志经构造注入） */
    private val readerFlow = ReaderFlowController(
        scope = viewModelScope,
        sessionProvider = { session },
        deviceMode = _deviceMode,
        dumpRepository = dumpRepository,
        appendLog = ::appendLog,
    )

    val readerState: StateFlow<ReaderState> get() = readerFlow.readerState

    private var client: ChameleonBleClient? = null  //ble层
    private var session: ChameleonSession? = null   //会话层
    private var nextLogId = 0L      //日志序号自增

    /** 模式切换在途标志（主线程读写，防重复点击双发切换指令） */
    private var modeSwitchInFlight = false

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

        val newClient = ChameleonBleClient(BleCenter.centralManager)
        newClient.listener = object : ChameleonBleClient.Listener { // 匿名对象实现监听器
            override fun onReady(mtu: Int) {
                appendLog(LogKind.INFO, "连接就绪（MTU=$mtu）")
                session = ChameleonSession(newClient)
                _connectionState.value = ConnectionState.Connected(displayName, address)    //将蓝牙回调转换成状态流
                refreshDeviceMode()
            }

            override fun onFrameReceived(frame: ChameleonFrame) {
                appendLog(LogKind.RX, formatFrameForLog(frame))
            }

            override fun onFrameSent(frame: ChameleonFrame) {
                appendLog(LogKind.TX, formatFrameForLog(frame))
            }

            override fun onDisconnected(byUser: Boolean) {
                appendLog(LogKind.INFO, if (byUser) "已断开连接" else "连接已断开")
                session = null
                client = null
                _connectionState.value = ConnectionState.Disconnected
                _deviceMode.value = DeviceMode.UNKNOWN
                readerFlow.reset()  //卡已不可访问，清空读卡页状态
            }

            override fun onError(message: String) {
                appendLog(LogKind.ERROR, message)
            }
        }
        client = newClient  //配合函数入口的防冲入保护使用
        _connectionState.value = ConnectionState.Connecting
        appendLog(LogKind.INFO, "正在连接 $displayName（$address）…")
        viewModelScope.launch {
            try {
                newClient.connect(address)
            } catch (e: Exception) {
                appendLog(LogKind.ERROR, "连接失败：${e.message}")
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
                appendLog(LogKind.INFO, "电池：${voltageMv}mV（电量 $percent%）")
            } catch (e: Exception) {
                appendLog(LogKind.ERROR, "电池查询失败：${describeError(e)}")
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

    fun writeDumpToEmulator(dump: DumpCard): Boolean = readerFlow.writeDumpToEmulator(dump)

    fun loadDumpToReader(dump: DumpCard) = readerFlow.loadDumpToReader(dump)

    fun mfkey32() = readerFlow.mfkey32()

    fun consumeLastError() = readerFlow.consumeLastError()

    fun consumeLastSuccess() = readerFlow.consumeLastSuccess()

    // ------------------------------------------------------------------
    // 设备工作模式
    // ------------------------------------------------------------------

    /** 连接就绪后读取并缓存设备工作模式（主界面模式图标的唯一数据源） */
    private fun refreshDeviceMode() {
        val s = session ?: return
        viewModelScope.launch {
            try {
                val mode = s.getDeviceMode()
                _deviceMode.value = mode
                appendLog(LogKind.INFO, "设备工作模式：" + if (mode == DeviceMode.READER) "读卡器" else "模拟卡")
            } catch (e: Exception) {
                appendLog(LogKind.ERROR, "读取工作模式失败：${describeError(e)}")
            }
        }
    }

    /**
     * 点击模式图标切换设备工作模式（读卡器 <-> 模拟卡）。
     * 读卡流程进行中拒绝切换避免中断；图标渲染仍由 [deviceMode] 流驱动。
     */
    fun toggleDeviceMode() {
        val s = session ?: run {
            appendLog(LogKind.ERROR, "设备未连接，无法切换工作模式")
            return
        }
        val current = _deviceMode.value
        if (current == DeviceMode.UNKNOWN || modeSwitchInFlight) return
        if (readerState.value.phase != ReaderPhase.Idle) {
            appendLog(LogKind.INFO, "读卡操作进行中，暂不切换工作模式")
            return
        }
        val target = if (current == DeviceMode.READER) DeviceMode.EMULATOR else DeviceMode.READER

        modeSwitchInFlight = true
        viewModelScope.launch {
            try {
                appendLog(LogKind.INFO, "切换为${if (target == DeviceMode.READER) "读卡器" else "模拟卡"}模式…")
                s.changeDeviceMode(target)
                _deviceMode.value = target
                appendLog(LogKind.INFO, "已切换为${if (target == DeviceMode.READER) "读卡器" else "模拟卡"}模式")
            } catch (e: Exception) {
                appendLog(LogKind.ERROR, "切换工作模式失败：${describeError(e)}")
            } finally {
                modeSwitchInFlight = false
            }
        }
    }

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    fun clearLog() {
        _log.value = emptyList()
    }

    override fun onCleared() {
        client?.disconnect()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun formatFrameForLog(frame: ChameleonFrame): String {
        val builder = StringBuilder(HexUtils.format(frame.encode()))
        builder.append("  [").append(ChameleonCommand.nameOf(frame.cmd))
            .append(" status=0x%04X".format(frame.status)).append(']')
        return builder.toString()
    }

    private fun appendLog(kind: LogKind, text: String) {
        _log.update { entries ->
            (entries + LogEntry(nextLogId++, kind, text)).takeLast(MAX_LOG_ENTRIES)
        }
    }

    private companion object {
        const val MAX_LOG_ENTRIES = 200
    }
}
