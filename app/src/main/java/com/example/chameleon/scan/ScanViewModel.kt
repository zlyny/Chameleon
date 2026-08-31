package com.example.chameleon.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chameleon.R
import com.example.chameleon.ble.BleCenter
import com.example.chameleon.ble.BleConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.client.distinctByPeripheral
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException

/** 扫描页发现的设备（广播名优先，回退系统缓存名） */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

/**
 * 扫描页 ViewModel：基于 CentralManager.scan() Flow 驱动设备列表。
 *
 * 扫描在 [BleConstants.SCAN_DURATION] 后自动结束；同一设备只保留首次
 * 扫描结果（distinctByPeripheral，与 nRF Toolbox 语义一致）。
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface ScanState {
        data object Idle : ScanState

        data class Scanning(val devices: List<DiscoveredDevice>) : ScanState

        data class Finished(val devices: List<DiscoveredDevice>) : ScanState

        /** 扫描启动失败（蓝牙未开启 / 权限被拒等），[message] 面向用户展示 */
        data class Failed(val message: String) : ScanState
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private var scanJob: Job? = null

    /** 开始扫描；重复调用会替代进行中的扫描 */
    fun startScan() {
        if (_scanState.value is ScanState.Scanning) return
        scanJob?.cancel()
        _scanState.value = ScanState.Scanning(emptyList())
        scanJob = viewModelScope.launch {
            try {
                BleCenter.centralManager
                    .scan(timeout = BleConstants.SCAN_DURATION)
                    .distinctByPeripheral()
                    .collect { result ->
                        _scanState.update { state ->
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
                _scanState.value = ScanState.Failed(scanErrorMessage(e))
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

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }

    private fun scanErrorMessage(e: Exception): String {
        val app = getApplication<Application>()
        return when (e) {
            is SecurityException -> app.getString(R.string.permission_denied)
            is BluetoothUnavailableException -> app.getString(R.string.bluetooth_disabled)
            else -> e.message ?: app.getString(R.string.scan_failed)
        }
    }

    private fun ScanResult.toDiscoveredDevice() = DiscoveredDevice(
        address = peripheral.address,
        name = peripheral.name,
        rssi = rssi,
    )

    /** 排序：有名字的在前，同名/无名之间按信号强度降序 */
    private fun List<DiscoveredDevice>.sorted(): List<DiscoveredDevice> =
        sortedWith(compareByDescending<DiscoveredDevice> { it.name != null }.thenByDescending { it.rssi })
}
