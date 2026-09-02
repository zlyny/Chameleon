package com.example.chameleon

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.chameleon.cards.CardsFragment
import com.example.chameleon.databinding.ActivityMainBinding
import com.example.chameleon.device.DeviceMode
import com.example.chameleon.log.LogFragment
import com.example.chameleon.reader.ReaderFragment
import com.example.chameleon.scan.ScanFragment
import kotlinx.coroutines.launch

/**
 * 应用主界面：单 Activity + 底部导航承载四个页面
 * （扫描连接 / 读卡 / 日志 / 卡片管理），Fragment 以 show/hide 切换，
 * 各页状态在切换与重建后均保留。
 *
 * 顶部工具栏实时展示连接状态与设备工作模式小图标（与 MainViewModel.deviceMode 挂钩）。
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var binding: ActivityMainBinding

    private var currentTabId = R.id.tab_scan

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentTabId = savedInstanceState?.getInt(KEY_CURRENT_TAB) ?: R.id.tab_scan
        setupFragments(hasSavedState = savedInstanceState != null)
        binding.bottomNav.selectedItemId = currentTabId
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId != currentTabId) switchTab(item.itemId)
            true
        }

        observeViewModel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_CURRENT_TAB, currentTabId)
        super.onSaveInstanceState(outState)
    }

    // ------------------------------------------------------------------
    // 页面切换（show/hide 保留各页状态）
    // ------------------------------------------------------------------

    private fun setupFragments(hasSavedState: Boolean) {
        val fm = supportFragmentManager
        fm.beginTransaction().apply {
            TABS.forEach { (tabId, tag, create) ->
                var fragment = fm.findFragmentByTag(tag)
                if (fragment == null && !hasSavedState) {
                    fragment = create()
                    add(R.id.fragmentContainer, fragment, tag)
                }
                fragment?.let { if (tabId == currentTabId) show(it) else hide(it) }
            }
        }.commitNow()
    }

    private fun switchTab(tabId: Int) {
        currentTabId = tabId
        val fm = supportFragmentManager
        fm.beginTransaction().apply {
            TABS.forEach { (tabId_, tag, _) ->
                fm.findFragmentByTag(tag)?.let { if (tabId_ == tabId) show(it) else hide(it) }
            }
        }.commit()
    }

    // ------------------------------------------------------------------
    // 顶部状态渲染
    // ------------------------------------------------------------------

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.connectionState.collect { renderConnectionState(it) } }
                launch { viewModel.deviceMode.collect { renderDeviceMode(it) } }
            }
        }
    }

    private fun renderConnectionState(state: MainViewModel.ConnectionState) {
        binding.toolbar.subtitle = when (state) {
            is MainViewModel.ConnectionState.Connected -> state.name
            MainViewModel.ConnectionState.Connecting -> getString(R.string.state_connecting)
            MainViewModel.ConnectionState.Disconnected -> getString(R.string.state_disconnected)
        }
        renderModeIconVisibility()

        // 连接成功后引导进入读卡页：点击设备即读卡是既定流程
        if (state is MainViewModel.ConnectionState.Connected && currentTabId == R.id.tab_scan) {
            binding.bottomNav.selectedItemId = R.id.tab_reader
        }
    }

    private fun renderDeviceMode(mode: DeviceMode) {
        when (mode) {
            DeviceMode.READER -> {
                binding.imageModeIcon.setImageResource(R.drawable.ic_mode_reader)
                binding.imageModeIcon.contentDescription = getString(R.string.mode_reader)
            }

            DeviceMode.EMULATOR -> {
                binding.imageModeIcon.setImageResource(R.drawable.ic_mode_emulator)
                binding.imageModeIcon.contentDescription = getString(R.string.mode_emulator)
            }

            DeviceMode.UNKNOWN -> Unit
        }
        renderModeIconVisibility()
    }

    private fun renderModeIconVisibility() {
        val connected = viewModel.connectionState.value is MainViewModel.ConnectionState.Connected
        val modeKnown = viewModel.deviceMode.value != DeviceMode.UNKNOWN
        binding.imageModeIcon.isVisible = connected && modeKnown
    }

    private companion object {
        const val KEY_CURRENT_TAB = "current_tab"

        /** 底部导航 Tab 定义：菜单项 ID -> Fragment 标签与构造器 */
        val TABS: List<Triple<Int, String, () -> Fragment>> = listOf(
            Triple(R.id.tab_scan, ScanFragment::class.java.name) { ScanFragment() },
            Triple(R.id.tab_reader, ReaderFragment::class.java.name) { ReaderFragment() },
            Triple(R.id.tab_log, LogFragment::class.java.name) { LogFragment() },
            Triple(R.id.tab_cards, CardsFragment::class.java.name) { CardsFragment() },
        )
    }
}
