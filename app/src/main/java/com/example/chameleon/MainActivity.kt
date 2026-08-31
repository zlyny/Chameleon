package com.example.chameleon

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.chameleon.databinding.ActivityMainBinding
import com.example.chameleon.jni.ChameleonNative
import com.example.chameleon.scan.ScanActivity
import kotlinx.coroutines.launch

/** 主界面：连接状态展示、协议命令收发与通信日志 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
    }

    private val viewModel: MainViewModel by viewModels()

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
        if (address == null) {
            // 非正常入口（无设备参数），回到扫描页
            startActivity(Intent(this, ScanActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textNativeVersion.text =
            getString(R.string.native_lib_version_format, ChameleonNative.nativeVersion())

        binding.btnScan.setOnClickListener { backToScanner() }
        binding.btnBattery.setOnClickListener { viewModel.requestBatteryInfo() }
        binding.btnDisconnect.setOnClickListener { viewModel.disconnect() }

        observeViewModel()

        viewModel.connectDevice(address, intent.getStringExtra(EXTRA_DEVICE_NAME))
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { renderConnectionState(it) }
                }
                launch {
                    viewModel.log.collect { renderLog(it) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 状态渲染
    // ------------------------------------------------------------------

    private fun renderConnectionState(state: MainViewModel.ConnectionState) {
        when (state) {
            is MainViewModel.ConnectionState.Connected -> {
                binding.textDeviceName.text = state.name
                binding.textDeviceAddress.text = state.address
                binding.textConnectionState.setText(R.string.state_connected)
                binding.textConnectionState.setTextColor(
                    ContextCompat.getColor(this, R.color.log_rx)
                )
            }

            MainViewModel.ConnectionState.Connecting -> {
                binding.textConnectionState.setText(R.string.state_connecting)
                binding.textConnectionState.setTextColor(
                    ContextCompat.getColor(this, R.color.log_tx)
                )
            }

            MainViewModel.ConnectionState.Disconnected -> {
                binding.textDeviceName.setText(R.string.device_not_connected)
                binding.textDeviceAddress.setText(R.string.device_address_placeholder)
                binding.textConnectionState.setText(R.string.state_disconnected)
                binding.textConnectionState.setTextColor(
                    ContextCompat.getColor(this, R.color.log_info)
                )
            }
        }
        val connected = state is MainViewModel.ConnectionState.Connected
        val connecting = state is MainViewModel.ConnectionState.Connecting
        binding.btnBattery.isEnabled = connected
        binding.btnDisconnect.isEnabled = connected || connecting
    }

    private fun renderLog(entries: List<MainViewModel.LogEntry>) {
        if (entries.isEmpty()) {
            binding.textLog.setText(R.string.log_empty)
            return
        }
        val builder = SpannableStringBuilder()
        entries.forEach { entry ->
            val span = ForegroundColorSpan(
                ContextCompat.getColor(this, entry.kind.colorRes())
            )
            val start = builder.length
            builder.append(entry.text).append('\n')
            builder.setSpan(span, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.textLog.text = builder
        // 日志更新后滚动到底部
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun MainViewModel.LogKind.colorRes(): Int = when (this) {
        MainViewModel.LogKind.TX -> R.color.log_tx
        MainViewModel.LogKind.RX -> R.color.log_rx
        MainViewModel.LogKind.ERROR -> R.color.log_error
        MainViewModel.LogKind.INFO -> R.color.log_info
    }

    /** 返回扫描页重新选择设备；断开由 ViewModel.onCleared 兜底 */
    private fun backToScanner() {
        startActivity(Intent(this, ScanActivity::class.java))
        finish()
    }
}
