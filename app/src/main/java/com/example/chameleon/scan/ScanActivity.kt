package com.example.chameleon.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chameleon.MainActivity
import com.example.chameleon.R
import com.example.chameleon.databinding.ActivityScanBinding
import kotlinx.coroutines.launch

/**
 * 开屏 BLE 扫描页：进入即自动扫描，列表实时展示发现的设备，
 * 点击设备携带地址跳转主界面完成连接。
 */
class ScanActivity : AppCompatActivity() {

    private val viewModel: ScanViewModel by viewModels()

    private lateinit var binding: ActivityScanBinding

    private val adapter = DeviceAdapter { device ->
        openDevice(device)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                viewModel.startScan()
            } else {
                binding.textScanStatus.setText(R.string.permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.listDevices.layoutManager = LinearLayoutManager(this)
        binding.listDevices.adapter = adapter
        binding.btnScanToggle.setOnClickListener { onScanToggleClicked() }

        observeViewModel()

        requestPermissionsAndScan()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scanState.collect { renderScanState(it) }
            }
        }
    }

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

    // ------------------------------------------------------------------
    // 扫描控制与权限
    // ------------------------------------------------------------------

    private fun onScanToggleClicked() {
        if (viewModel.scanState.value is ScanViewModel.ScanState.Scanning) {
            viewModel.stopScan()
        } else {
            requestPermissionsAndScan()
        }
    }

    private fun requestPermissionsAndScan() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            viewModel.startScan()
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

    // ------------------------------------------------------------------
    // 设备选择
    // ------------------------------------------------------------------

    /** 携带设备地址跳转主界面；Peripheral 已在进程级 CentralManager 缓存中 */
    @SuppressLint("MissingPermission")
    private fun openDevice(device: DiscoveredDevice) {
        viewModel.stopScan()
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_DEVICE_ADDRESS, device.address)
            putExtra(MainActivity.EXTRA_DEVICE_NAME, device.name)
        }
        startActivity(intent)
        finish()
    }
}
