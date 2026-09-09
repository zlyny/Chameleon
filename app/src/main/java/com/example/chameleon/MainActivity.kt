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
 * 顶部工具栏实时展示连接状态与设备工作模式小图标（与 MainViewModel.deviceMode 挂钩）；
 * 点击模式图标可在读卡器/模拟卡模式间切换（设备需已连接）。
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var binding: ActivityMainBinding   //开启ViewBinding后根据activity_main.xml自动生成ActivityMainBinding,不用手写 findViewById()

    private var currentTabId = R.id.tab_scan

    override fun onCreate(savedInstanceState: Bundle?) {    //Bundle是键值对容器
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)   //将布局加载到binding
        setContentView(binding.root)                            //设置到屏幕上

        currentTabId = savedInstanceState?.getInt(KEY_CURRENT_TAB) ?: R.id.tab_scan //提取存储的状态值,首次启动为空,就用默认值
        setupFragments(hasSavedState = savedInstanceState != null)
        binding.bottomNav.selectedItemId = currentTabId             //选中tab栏
        binding.bottomNav.setOnItemSelectedListener { item ->       //切换回调
            if (item.itemId != currentTabId) switchTab(item.itemId)
            true
        }

        // 点击模式图标切换读卡器/模拟卡模式（切换中与读卡流程中由 ViewModel 拦截）
        binding.btnModeSwitch.setOnClickListener { viewModel.toggleDeviceMode() }   //界面不直接改模式,而是让 ViewModel 去处理(内部会做检查)

        observeViewModel()
    }

    override fun onSaveInstanceState(outState: Bundle) {    //在系统杀掉页面前调用
        outState.putInt(KEY_CURRENT_TAB, currentTabId)      //手动保存当前tab页
        super.onSaveInstanceState(outState)
    }

    // ------------------------------------------------------------------
    // 页面切换（show/hide 保留各页状态）
    // ------------------------------------------------------------------

    private fun setupFragments(hasSavedState: Boolean) {
        val fm = supportFragmentManager     //系统提供的 Fragment 管理器
        fm.beginTransaction().apply {       //开启一个事务,你可以往里面攒多个操作(多个 add/show/hide),最后一次性提交
            TABS.forEach { (tabId, tag, create) ->
                var fragment = fm.findFragmentByTag(tag)
                if (fragment == null && !hasSavedState) {
                    fragment = create()
                    add(R.id.fragmentContainer, fragment, tag)
                }
                fragment?.let { if (tabId == currentTabId) show(it) else hide(it) } //fragment非空执行,刷新每个fragment的显示开关
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
        lifecycleScope.launch {     //lifecycleScope是与 Activity 生命周期绑定的协程作用域
            repeatOnLifecycle(Lifecycle.State.STARTED) {    //花括号里的代码只在页面可见时运行,
                launch { viewModel.connectionState.collect { renderConnectionState(it) } }  //launch是并行启动任务,collect是订阅StateFlow
                launch { viewModel.deviceMode.collect { renderDeviceMode(it) } }    //每当状态值变化就执行renderDeviceMode
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
            binding.bottomNav.selectedItemId = R.id.tab_reader  //这会触发bottomNav监听器
        }
    }

    private fun renderDeviceMode(mode: DeviceMode) {
        when (mode) {
            DeviceMode.READER -> {
                binding.btnModeSwitch.setIconResource(R.drawable.ic_mode_reader)
                binding.btnModeSwitch.contentDescription = getString(R.string.mode_reader)
            }

            DeviceMode.EMULATOR -> {
                binding.btnModeSwitch.setIconResource(R.drawable.ic_mode_emulator)
                binding.btnModeSwitch.contentDescription = getString(R.string.mode_emulator)
            }

            DeviceMode.UNKNOWN -> Unit  //Unit是`没有值`,什么都不做
        }
        renderModeIconVisibility()
    }

    private fun renderModeIconVisibility() {
        val connected = viewModel.connectionState.value is MainViewModel.ConnectionState.Connected
        val modeKnown = viewModel.deviceMode.value != DeviceMode.UNKNOWN    //value是直接读取 Flow 里"此刻的值"
        binding.btnModeSwitch.isVisible = connected && modeKnown
    }

    private companion object {  //类内的"静态区",这些成员属于类本身而不属于某个对象
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
