package com.example.chameleon

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chameleon.ble.BleCenter
import com.example.chameleon.ble.ChameleonBleClient
import com.example.chameleon.protocol.ChameleonCommand
import com.example.chameleon.protocol.ChameleonFrame
import com.example.chameleon.protocol.HexUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel：接收扫描页选定的设备建立连接，编排协议收发并向 UI 暴露状态流。
 *
 * 后续扩展（Mifare Classic 扇区管理、读卡、破解、写卡）在此层增加
 * 对应的 StateFlow 与方法，BLE / 协议层保持不变。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    /** 连接状态 */
    sealed interface ConnectionState {
        data object Disconnected : ConnectionState

        data object Connecting : ConnectionState

        data class Connected(val name: String, val address: String) : ConnectionState
    }

    /** 日志类型，决定 UI 中的显示颜色 */
    enum class LogKind { TX, RX, INFO, ERROR }

    data class LogEntry(val id: Long, val kind: LogKind, val text: String)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private var client: ChameleonBleClient? = null
    private var nextLogId = 0L

    /** 连接扫描页选定的设备（[address] 为 MAC 地址，设备已在 CentralManager 缓存中）。
     *  幂等：Activity 旋转重建等场景下重复调用不会发起新连接。 */
    fun connectDevice(address: String, name: String?) {
        if (client != null) return
        val displayName = name ?: address

        val newClient = ChameleonBleClient(BleCenter.centralManager)
        newClient.listener = object : ChameleonBleClient.Listener {
            override fun onReady(mtu: Int) {
                appendLog(LogKind.INFO, "连接就绪（MTU=$mtu）")
                _connectionState.value = ConnectionState.Connected(displayName, address)
            }

            override fun onFrameReceived(frame: ChameleonFrame) {
                appendLog(LogKind.RX, formatFrameForLog(frame))
                interpretFrame(frame)
            }

            override fun onDisconnected(byUser: Boolean) {
                appendLog(
                    LogKind.INFO,
                    if (byUser) "已断开连接" else "连接已断开",
                )
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onError(message: String) {
                appendLog(LogKind.ERROR, message)
            }
        }
        client = newClient
        _connectionState.value = ConnectionState.Connecting
        appendLog(LogKind.INFO, "正在连接 $displayName（$address）…")
        viewModelScope.launch {
            try {
                newClient.connect(address)
            } catch (e: Exception) {
                appendLog(LogKind.ERROR, "连接失败：${e.message}")
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    /** 断开当前连接 */
    fun disconnect() {
        client?.disconnect()
    }

    /**
     * 发送 GET_BATTERY_INFO 命令，验证整条 TX -> 设备 -> RX 链路。
     * 发出的帧为 11 EF 04 01 00 00 00 00 FB 00。
     */
    fun requestBatteryInfo() {
        val currentClient = client ?: return
        val frame = ChameleonFrame(ChameleonCommand.GET_BATTERY_INFO, 0, ByteArray(0))
        appendLog(LogKind.TX, "${HexUtils.format(frame.encode())}  [${ChameleonCommand.nameOf(frame.cmd)}]")
        viewModelScope.launch {
            try {
                currentClient.send(frame)
            } catch (e: Exception) {
                appendLog(LogKind.ERROR, "发送失败：${e.message}")
            }
        }
    }

    override fun onCleared() {
        client?.disconnect()
        super.onCleared()
    }

    /** 帧的业务解读（后续扩展为各命令的 payload 解析器） */
    private fun interpretFrame(frame: ChameleonFrame) {
        if (!frame.isSuccess) {
            appendLog(LogKind.ERROR, "命令执行失败，status=0x%04X".format(frame.status))
            return
        }
        when (frame.cmd) {
            ChameleonCommand.GET_BATTERY_INFO -> if (frame.data.size >= 3) {
                val voltageMv = ((frame.data[0].toInt() and 0xFF) shl 8) or
                    (frame.data[1].toInt() and 0xFF)
                val percent = frame.data[2].toInt() and 0xFF
                appendLog(LogKind.RX, "电池：${voltageMv}mV（电量 $percent%）")
            }

            ChameleonCommand.GET_APP_VERSION -> if (frame.data.size >= 2) {
                appendLog(
                    LogKind.RX,
                    "固件版本：%d.%d".format(frame.data[0].toInt() and 0xFF, frame.data[1].toInt() and 0xFF),
                )
            }
        }
    }

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
