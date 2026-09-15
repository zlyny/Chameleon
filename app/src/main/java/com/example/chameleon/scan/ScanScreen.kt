package com.example.chameleon.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chameleon.DiscoveredDevice
import com.example.chameleon.MainViewModel
import com.example.chameleon.R
import com.example.chameleon.ui.theme.ChameleonTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * 扫描连接页 Compose 实现。
 *
 * 相比原 View 版：RecyclerView + ListAdapter + DiffUtil 换成 LazyColumn（设备列表
 * 最多几十项，DiffUtil 的心智负担和 Adapter 文件一并去掉）；「未连接 / 已连接」
 * 两套视图组由 if/else 直接表达，不再是两个 ViewGroup 来回切 visibility。
 *
 * 其余行为与原实现逐条对齐：
 * - 进页即自动扫描（权限就绪后）；断开连接后自动重扫；
 * - 扫描按钮三态文案：开始扫描 / 停止扫描 / 重新扫描设备；
 * - 权限被拒时状态栏显示「蓝牙权限被拒绝」；
 * - 点击设备即停止扫描并发起连接。
 */
@Composable
fun ScanScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionDenied = !result.values.all { it }
        if (!permissionDenied) viewModel.startScan()
    }

    /** 缺失权限时先申请，权限就绪后开始扫描 */
    fun requestPermissionsAndScan() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionDenied = false
            viewModel.startScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        requestPermissionsAndScan()
        // 断开后回到本页可直接重新扫描；drop(1) 跳过首帧（初始未连接态上面已扫过，
        // 否则权限弹窗会因重复 launch 弹出两次）
        snapshotFlow { connectionState }
            .drop(1)
            .distinctUntilChanged()
            .collect { state ->
                if (state is MainViewModel.ConnectionState.Disconnected) requestPermissionsAndScan()
            }
    }

    // 离开本页（切到其它 tab）即停止扫描：BLE 扫描是耗电操作，不该在后台继续跑。
    // 回到本页时上面的 LaunchedEffect(Unit) 会重新触发一次扫描，体感不变。
    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    val connected = connectionState as? MainViewModel.ConnectionState.Connected
    if (connected != null) {
        ConnectedPanel(
            name = connected.name,
            address = connected.address,
            onBattery = viewModel::requestBatteryInfo,
            onDisconnect = viewModel::disconnect,
            modifier = modifier,
        )
    } else {
        ScanPanel(
            state = scanState,
            permissionDenied = permissionDenied,
            onToggle = {
                if (scanState is MainViewModel.ScanState.Scanning) {
                    viewModel.stopScan()
                } else {
                    requestPermissionsAndScan()
                }
            },
            onDeviceClick = { device ->
                viewModel.stopScan()
                viewModel.connectDevice(device.address, device.name)
            },
            modifier = modifier,
        )
    }
}

// ------------------------------------------------------------------
// 未连接：扫描面板
// ------------------------------------------------------------------

@Composable
private fun ScanPanel(
    state: MainViewModel.ScanState,
    permissionDenied: Boolean,
    onToggle: () -> Unit,
    onDeviceClick: (DiscoveredDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val devices = when (state) {
        is MainViewModel.ScanState.Scanning -> state.devices
        is MainViewModel.ScanState.Finished -> state.devices
        else -> emptyList()
    }
    val statusText = when (state) {
        MainViewModel.ScanState.Idle -> stringResource(R.string.state_scan_ready)
        is MainViewModel.ScanState.Scanning ->
            stringResource(R.string.scan_state_scanning, state.devices.size)

        is MainViewModel.ScanState.Finished ->
            stringResource(R.string.scan_state_finished, state.devices.size)

        is MainViewModel.ScanState.Failed -> state.error.toText(context)
    }
    val buttonText = stringResource(
        when (state) {
            MainViewModel.ScanState.Idle -> R.string.btn_scan
            is MainViewModel.ScanState.Scanning -> R.string.btn_stop_scan
            is MainViewModel.ScanState.Finished -> R.string.btn_rescan
            is MainViewModel.ScanState.Failed -> R.string.btn_rescan
        },
    )

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.scan_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = if (permissionDenied) stringResource(R.string.permission_denied) else statusText,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(text = buttonText)
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).weight(1f),
        ) {
            items(items = devices, key = { it.address }) { device ->
                DeviceRow(device = device, onClick = onDeviceClick)
            }
        }

        if (devices.isEmpty()) {
            Text(
                text = stringResource(
                    if (state is MainViewModel.ScanState.Finished) {
                        R.string.scan_no_device
                    } else {
                        R.string.scan_hint
                    },
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, onClick: (DiscoveredDevice) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(device) }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name ?: stringResource(R.string.unknown_device),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.device_item_subtitle,
                    device.address,
                    device.rssi,
                ),
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // 原版用 level-list 的 ic_signal + setImageLevel()，Compose 侧直接按等级选对应 drawable
        Icon(
            painter = painterResource(rssiDrawable(device.rssi.toSignalLevel())),
            contentDescription = stringResource(R.string.rssi_level_desc),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp).size(24.dp),
        )
    }
}

// ------------------------------------------------------------------
// 已连接：设备卡片
// ------------------------------------------------------------------

@Composable
private fun ConnectedPanel(
    name: String,
    address: String,
    onBattery: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = address,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.state_connected),
                    modifier = Modifier.padding(top = 8.dp),
                    color = colorResource(R.color.log_rx),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        Button(onClick = onBattery, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(text = stringResource(R.string.btn_battery))
        }
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(text = stringResource(R.string.btn_disconnect))
        }
    }
}

// ------------------------------------------------------------------
// 工具
// ------------------------------------------------------------------

/** Android 12+ 用运行时 BLE 权限，旧版扫描依赖位置权限 */
private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun rssiDrawable(level: Int): Int = when (level) {
    0 -> R.drawable.ic_signal_level_1
    1 -> R.drawable.ic_signal_level_2
    2 -> R.drawable.ic_signal_level_3
    else -> R.drawable.ic_signal_level_4
}

/** RSSI（dBm）映射为 0~3 的信号等级，阈值参考 nRF Toolbox 的常用分档 */
private fun Int.toSignalLevel(): Int = when {
    this >= -55 -> 3
    this >= -67 -> 2
    this >= -80 -> 1
    else -> 0
}

@Preview(showBackground = true)
@Composable
private fun ScanPanelPreview() {
    ChameleonTheme {
        ScanPanel(
            state = MainViewModel.ScanState.Scanning(
                devices = listOf(
                    DiscoveredDevice("AA:BB:CC:DD:EE:FF", "Chameleon Ultra", -48),
                    DiscoveredDevice("11:22:33:44:55:66", null, -71),
                ),
            ),
            permissionDenied = false,
            onToggle = {},
            onDeviceClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectedPanelPreview() {
    ChameleonTheme {
        ConnectedPanel(
            name = "Chameleon Ultra",
            address = "AA:BB:CC:DD:EE:FF",
            onBattery = {},
            onDisconnect = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
