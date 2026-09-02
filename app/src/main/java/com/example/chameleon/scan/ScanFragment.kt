package com.example.chameleon.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chameleon.MainViewModel
import com.example.chameleon.R
import com.example.chameleon.databinding.FragmentScanBinding
import kotlinx.coroutines.launch

/**
 * 扫描连接页：负责 BLE 设备扫描、连接、断开与重新扫描。
 *
 * 未连接时展示扫描列表；连接成功后切换为已连接卡片
 * （提供电池查询与断开操作），并隐藏扫描 UI。
 */
class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val scanViewModel: ScanViewModel by viewModels()
    private val mainViewModel: MainViewModel by activityViewModels()

    private val adapter = DeviceAdapter { device ->
        scanViewModel.stopScan()
        mainViewModel.connectDevice(device.address, device.name)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                scanViewModel.startScan()
            } else {
                binding.textScanStatus.setText(R.string.permission_denied)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.listDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.listDevices.adapter = adapter
        binding.btnScanToggle.setOnClickListener { onScanToggleClicked() }
        binding.btnBattery.setOnClickListener { mainViewModel.requestBatteryInfo() }
        binding.btnDisconnect.setOnClickListener { mainViewModel.disconnect() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { scanViewModel.scanState.collect { renderScanState(it) } }
                launch { mainViewModel.connectionState.collect { renderConnectionState(it) } }
            }
        }

        // 进入页面即自动扫描（权限就绪后）
        requestPermissionsAndScan()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------
    // 扫描状态渲染
    // ------------------------------------------------------------------

    private fun renderScanState(state: ScanViewModel.ScanState) {
        val devices = when (state) {
            is ScanViewModel.ScanState.Scanning -> state.devices
            is ScanViewModel.ScanState.Finished -> state.devices
            else -> emptyList()
        }
        adapter.submitList(devices)

        when (state) {
            ScanViewModel.ScanState.Idle -> {
                binding.textScanStatus.setText(R.string.state_scan_ready)
                binding.btnScanToggle.setText(R.string.btn_scan)
            }

            is ScanViewModel.ScanState.Scanning -> {
                binding.textScanStatus.text =
                    getString(R.string.scan_state_scanning, state.devices.size)
                binding.btnScanToggle.setText(R.string.btn_stop_scan)
            }

            is ScanViewModel.ScanState.Finished -> {
                binding.textScanStatus.text =
                    getString(R.string.scan_state_finished, state.devices.size)
                binding.btnScanToggle.setText(R.string.btn_rescan)
            }

            is ScanViewModel.ScanState.Failed -> {
                binding.textScanStatus.text = state.message
                binding.btnScanToggle.setText(R.string.btn_rescan)
            }
        }

        val showEmptyHint = state is ScanViewModel.ScanState.Finished && devices.isEmpty()
        binding.textScanHint.setText(if (showEmptyHint) R.string.scan_no_device else R.string.scan_hint)
        binding.textScanHint.isVisible = devices.isEmpty()
    }

    private fun renderConnectionState(state: MainViewModel.ConnectionState) {
        val connected = state is MainViewModel.ConnectionState.Connected
        binding.layoutScan.isVisible = !connected
        binding.layoutConnected.isVisible = connected
        if (connected && state is MainViewModel.ConnectionState.Connected) {
            binding.textDeviceName.text = state.name
            binding.textDeviceAddress.text = state.address
        }
        binding.btnDisconnect.isEnabled = connected ||
            state is MainViewModel.ConnectionState.Connecting
        if (!connected) {
            // 断开后回到本页可直接重新扫描
            requestPermissionsAndScan()
        }
    }

    // ------------------------------------------------------------------
    // 扫描控制与权限
    // ------------------------------------------------------------------

    private fun onScanToggleClicked() {
        if (scanViewModel.scanState.value is ScanViewModel.ScanState.Scanning) {
            scanViewModel.stopScan()
        } else {
            requestPermissionsAndScan()
        }
    }

    private fun requestPermissionsAndScan() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            scanViewModel.startScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
