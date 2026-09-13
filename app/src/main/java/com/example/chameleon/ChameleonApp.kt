package com.example.chameleon

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chameleon.R
import com.example.chameleon.cards.CardsScreen
import com.example.chameleon.cards.CardsViewModel
import com.example.chameleon.device.DeviceMode
import com.example.chameleon.log.LogScreen
import com.example.chameleon.reader.ReaderScreen
import com.example.chameleon.scan.ScanScreen
import com.example.chameleon.scan.ScanViewModel

/**
 * 应用主界面：Scaffold（顶部工具栏 + 底部导航）承载四个页面。
 *
 * 由 `MainActivity` 的 `setContent` 渲染。原先的功能全部保留：
 * - 顶部工具栏显示连接状态副标题，右侧模式图标点击切换读卡器 / 模拟卡；
 * - 连接成功后自动跳到读卡页（点击设备即读卡是既定流程）；
 * - 当前 tab 经 [rememberSaveable] 保留，不再依赖 onSaveInstanceState；
 * - 四个页面不再靠 Fragment show/hide 保活——ViewModel 持有状态，
 *   页面离开组合时会整体丢弃，切回来重新组合（扫描页据此自动重扫，
 *   卡片页由下方 LaunchedEffect 显式重扫）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChameleonApp(mainViewModel: MainViewModel, modifier: Modifier = Modifier) {
    val connectionState by mainViewModel.connectionState.collectAsStateWithLifecycle()
    val deviceMode by mainViewModel.deviceMode.collectAsStateWithLifecycle()

    // 原各 Fragment 的 viewModels() 上提到 Activity 作用域（四个页面现在共一棵组合树）
    val scanViewModel: ScanViewModel = viewModel()
    val cardsViewModel: CardsViewModel = viewModel()

    var currentTab by rememberSaveable { mutableStateOf(Tab.SCAN) }

    // 连接成功后引导进入读卡页
    LaunchedEffect(connectionState) {
        if (connectionState is MainViewModel.ConnectionState.Connected && currentTab == Tab.SCAN) {
            currentTab = Tab.READER
        }
    }

    // 每次进入卡片页重扫卡片库：Reader 页可能刚 Dump 了新卡片
    LaunchedEffect(currentTab) {
        if (currentTab == Tab.CARDS) cardsViewModel.refresh()
    }

    val connected = connectionState is MainViewModel.ConnectionState.Connected
    val showModeIcon = connected && deviceMode != DeviceMode.UNKNOWN

    Scaffold(
        modifier = modifier,
        topBar = {
            ChameleonTopBar(
                subtitle = connectionSubtitle(connectionState),
                showModeIcon = showModeIcon,
                deviceMode = deviceMode,
                onToggleMode = mainViewModel::toggleDeviceMode,
            )
        },
        bottomBar = {
            TabBar(currentTab = currentTab, onTabSelected = { currentTab = it })
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (currentTab) {
                Tab.SCAN -> ScanScreen(scanViewModel = scanViewModel, mainViewModel = mainViewModel)
                Tab.READER -> ReaderScreen(viewModel = mainViewModel)
                Tab.LOG -> LogScreen(viewModel = mainViewModel)
                Tab.CARDS -> CardsScreen(
                    cardsViewModel = cardsViewModel,
                    mainViewModel = mainViewModel,
                )
            }
        }
    }
}

@Composable
private fun connectionSubtitle(state: MainViewModel.ConnectionState): String = stringResource(
    when (state) {
        is MainViewModel.ConnectionState.Connected -> R.string.state_connected
        MainViewModel.ConnectionState.Connecting -> R.string.state_connecting
        MainViewModel.ConnectionState.Disconnected -> R.string.state_disconnected
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChameleonTopBar(
    subtitle: String,
    showModeIcon: Boolean,
    deviceMode: DeviceMode,
    onToggleMode: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        // 工作模式图标：点击在读卡器/模拟卡模式间切换（切换中与读卡流程中由 ViewModel 拦截）
        actions = {
            if (showModeIcon) {
                IconButton(onClick = onToggleMode) {
                    Icon(
                        painter = painterResource(modeIcon(deviceMode)),
                        // drawable 本身是纯白，依靠 LocalContentColor 着色（对齐原 app:iconTint）
                        contentDescription = stringResource(modeDescription(deviceMode)),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun TabBar(currentTab: Tab, onTabSelected: (Tab) -> Unit) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == currentTab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        painter = painterResource(tab.icon),
                        contentDescription = null, // 文字标签已表达含义
                    )
                },
                label = { Text(text = stringResource(tab.label)) },
            )
        }
    }
}

@DrawableRes
private fun modeIcon(mode: DeviceMode): Int = when (mode) {
    DeviceMode.READER -> R.drawable.ic_mode_reader
    DeviceMode.EMULATOR -> R.drawable.ic_mode_emulator
    DeviceMode.UNKNOWN -> R.drawable.ic_mode_reader
}

@StringRes
private fun modeDescription(mode: DeviceMode): Int = when (mode) {
    DeviceMode.READER -> R.string.mode_reader
    DeviceMode.EMULATOR -> R.string.mode_emulator
    DeviceMode.UNKNOWN -> R.string.mode_reader
}

/** 底部导航页：菜单 XML（原 res/menu/bottom_nav.xml）已废弃，改由此枚举描述 */
private enum class Tab(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    SCAN(R.string.tab_scan, R.drawable.ic_tab_scan),
    READER(R.string.tab_reader, R.drawable.ic_tab_reader),
    LOG(R.string.tab_log, R.drawable.ic_tab_log),
    CARDS(R.string.tab_cards, R.drawable.ic_tab_cards),
}
