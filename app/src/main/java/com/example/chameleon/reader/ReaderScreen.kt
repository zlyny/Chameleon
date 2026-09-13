package com.example.chameleon.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chameleon.MainViewModel
import com.example.chameleon.R
import com.example.chameleon.device.KeyType
import com.example.chameleon.device.SectorKeys
import com.example.chameleon.device.TagInfo

/**
 * 读卡页 Compose 实现（[KeyMatrix] 之前已单独迁移，这里把它并入同一个组合，
 * 页面不再需要一个独立的 ComposeView 宿主）。
 *
 * 按钮可用性 = 已连接 && 空闲 && 各自前置条件；进行中动词化文案（正在读卡…等），
 * 与原 View 版 renderButtons 逻辑一致。
 */
@Composable
fun ReaderScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.readerState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // lastError 是「单次事件」：展示后立刻消费，否则旋转/重新订阅会重复弹
    LaunchedEffect(state.lastError) {
        state.lastError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeLastError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            TagInfoCard(state.tagInfo)

            Row(modifier = Modifier.padding(top = 16.dp)) {
                // 读卡：读取卡号并检测 PRNG（模拟卡模式时自动切换为读卡器模式）
                ReaderButton(
                    text = stringResource(
                        if (state.phase is ReaderPhase.Reading) R.string.btn_reading else R.string.btn_read,
                    ),
                    enabled = connectionState is MainViewModel.ConnectionState.Connected &&
                        state.phase == ReaderPhase.Idle,
                    onClick = viewModel::readCard,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                ReaderButton(
                    text = stringResource(
                        if (state.phase is ReaderPhase.Mfkey32) {
                            R.string.btn_mfkey32_running
                        } else {
                            R.string.btn_mfkey32
                        },
                    ),
                    enabled = connectionState is MainViewModel.ConnectionState.Connected &&
                        state.phase == ReaderPhase.Idle,
                    onClick = viewModel::mfkey32,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            KeysCard(sectors = state.sectors, onKeyClick = viewModel::recoverKeyByNested)

            Row(modifier = Modifier.padding(top = 16.dp)) {
                // FOUND 与 VERIFIED（Dump 验证）均视为有可用密钥
                val hasKey = state.sectors.any { it.keyA.isFound || it.keyB.isFound }
                ReaderButton(
                    text = stringResource(
                        if (state.phase is ReaderPhase.Recovering) {
                            R.string.btn_recovering
                        } else {
                            R.string.btn_recover_keys
                        },
                    ),
                    enabled = connectionState is MainViewModel.ConnectionState.Connected &&
                        state.phase == ReaderPhase.Idle && state.sectors.isNotEmpty(),
                    onClick = viewModel::recoverKeys,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                )
                ReaderButton(
                    text = stringResource(
                        if (state.phase is ReaderPhase.Dumping) {
                            R.string.btn_dumping
                        } else {
                            R.string.btn_dump
                        },
                    ),
                    enabled = connectionState is MainViewModel.ConnectionState.Connected &&
                        state.phase == ReaderPhase.Idle && hasKey,
                    onClick = viewModel::dumpCard,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            // state 是委托属性，无法智能转换，先取局部变量再判空
            val dumpLocation = state.dumpLocation
            if (dumpLocation != null) {
                Text(
                    text = stringResource(R.string.dump_saved_at, dumpLocation),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 落在读卡页自身区域内（位于底部导航栏之上），无需 setAnchorView
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
    }
}

/** 操作按钮：不可用或进行中时文案已动词化（如「正在读卡…」） */
@Composable
private fun ReaderButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text = text)
    }
}

// ------------------------------------------------------------------
// 标签信息卡片
// ------------------------------------------------------------------

@Composable
private fun TagInfoCard(tag: TagInfo?) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.reader_tag_info_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            InfoRow(
                label = stringResource(R.string.label_uid),
                value = tag?.uidHex?.chunked(2)?.joinToString(" "),
            )
            InfoRow(label = stringResource(R.string.label_sak), value = tag?.sakHex)
            InfoRow(label = stringResource(R.string.label_atqa), value = tag?.atqaHex)
            InfoRow(
                label = stringResource(R.string.label_ats),
                value = tag?.let {
                    if (it.ats.isEmpty()) {
                        stringResource(R.string.tag_ats_none)
                    } else {
                        it.ats.joinToString(" ") { b -> "%02X".format(b) }
                    }
                },
            )
            // Static 卡在读卡时进一步判定漏洞代次，PRNG 栏显示 Static GEN1/GEN2
            InfoRow(
                label = stringResource(R.string.label_prng),
                value = tag?.let { it.staticGen?.label ?: it.prng.label },
                monospace = false,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?, monospace: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value ?: stringResource(R.string.tag_value_placeholder),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (monospace) FontFamily.Monospace else null,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

// ------------------------------------------------------------------
// 密钥矩阵卡片
// ------------------------------------------------------------------

@Composable
private fun KeysCard(
    sectors: List<SectorKeys>,
    onKeyClick: (sector: Int, keyType: KeyType) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.reader_keys_title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            KeyMatrix(sectors = sectors, onKeyClick = onKeyClick)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.reader_keys_hint),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
