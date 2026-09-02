package com.example.chameleon

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chameleon.ble.BleCenter
import com.example.chameleon.ble.ChameleonBleClient
import com.example.chameleon.ble.ChameleonBleException
import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.device.ChameleonStatusException
import com.example.chameleon.device.DeviceMode
import com.example.chameleon.device.DumpExporter
import com.example.chameleon.device.KeyDictionary
import com.example.chameleon.device.KeyState
import com.example.chameleon.device.KeyStatus
import com.example.chameleon.device.KeyType
import com.example.chameleon.device.PrngType
import com.example.chameleon.device.SectorKeys
import com.example.chameleon.device.TagInfo
import com.example.chameleon.jni.ChameleonNative
import com.example.chameleon.protocol.ChameleonCommand
import com.example.chameleon.protocol.ChameleonFrame
import com.example.chameleon.protocol.ChameleonStatus
import com.example.chameleon.protocol.HexUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用级共享 ViewModel：管理 BLE 连接生命周期、设备工作模式缓存、
 * 通信日志与读卡（字典攻击）业务流程。四个页面 Fragment 共享同一实例。
 *
 * 线程模型：全部状态流更新与命令收发均运行在主线程调度器上，
 * UI 可直接观察渲染，无需额外线程同步。
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

    /** 读卡页操作阶段（同一时刻只允许一个在途流程，按钮据此禁用） */
    sealed interface ReaderPhase {
        data object Idle : ReaderPhase

        data object Reading : ReaderPhase

        data object Recovering : ReaderPhase

        data object Dumping : ReaderPhase
    }

    /** 读卡页聚合状态 */
    data class ReaderState(
        val phase: ReaderPhase = ReaderPhase.Idle,
        val tagInfo: TagInfo? = null,
        /** 每扇区 A/B 密钥恢复结果；非 Mifare 卡或未读卡时为空 */
        val sectors: List<SectorKeys> = emptyList(),
        /** 最近一次操作的错误提示（UI 用 Snackbar 展示后清除） */
        val lastError: String? = null,
        /** 最近一次 dump 的保存位置 */
        val dumpLocation: String? = null,
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** 设备工作模式（连接就绪后读取一次并缓存，主界面图标与其挂钩） */
    private val _deviceMode = MutableStateFlow(DeviceMode.UNKNOWN)
    val deviceMode: StateFlow<DeviceMode> = _deviceMode.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _readerState = MutableStateFlow(ReaderState())
    val readerState: StateFlow<ReaderState> = _readerState.asStateFlow()

    private var client: ChameleonBleClient? = null
    private var session: ChameleonSession? = null
    private var nextLogId = 0L

    // ------------------------------------------------------------------
    // 连接管理
    // ------------------------------------------------------------------

    /**
     * 连接扫描页选定的设备（[address] 为 MAC 地址，设备已在 CentralManager 缓存中）。
     * 连接就绪后自动创建命令会话并读取设备工作模式。
     */
    fun connectDevice(address: String, name: String?) {
        if (client != null) return
        val displayName = name ?: address

        val newClient = ChameleonBleClient(BleCenter.centralManager)
        newClient.listener = object : ChameleonBleClient.Listener {
            override fun onReady(mtu: Int) {
                appendLog(LogKind.INFO, "连接就绪（MTU=$mtu）")
                session = ChameleonSession(newClient)
                _connectionState.value = ConnectionState.Connected(displayName, address)
                refreshDeviceMode()
            }

            override fun onFrameReceived(frame: ChameleonFrame) {
                appendLog(LogKind.RX, formatFrameForLog(frame))
            }

            override fun onFrameSent(frame: ChameleonFrame) {
                appendLog(LogKind.TX, formatFrameForLog(frame))
            }

            override fun onDisconnected(byUser: Boolean) {
                appendLog(LogKind.INFO, if (byUser) "已断开连接" else "连接已断开")
                session = null
                client = null
                _connectionState.value = ConnectionState.Disconnected
                _deviceMode.value = DeviceMode.UNKNOWN
                _readerState.value = ReaderState()
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
                client = null
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    /** 断开当前连接 */
    fun disconnect() {
        client?.disconnect()
    }

    /** 查询电池信息（设备级命令，结果写入日志） */
    fun requestBatteryInfo() {
        val s = session ?: return
        viewModelScope.launch {
            try {
                val (voltageMv, percent) = s.getBatteryInfo()
                appendLog(LogKind.INFO, "电池：${voltageMv}mV（电量 $percent%）")
            } catch (e: Exception) {
                appendLog(LogKind.ERROR, "电池查询失败：${describeError(e)}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 读卡流程
    // ------------------------------------------------------------------

    /**
     * 读卡：确保读卡器模式 -> 扫描 14A 标签 -> 检测 Mifare Classic 支持 -> 检测 PRNG。
     * 成功后初始化 16 个扇区的密钥状态矩阵。
     */
    fun readCard() {
        val s = session ?: run {
            _readerState.update { it.copy(lastError = "设备未连接") }
            return
        }
        if (_readerState.value.phase != ReaderPhase.Idle) return
        _readerState.update { it.copy(phase = ReaderPhase.Reading, lastError = null) }
        viewModelScope.launch {
            try {
                ensureReaderMode(s)
                var tag = s.scan14a()
                if (!s.detectMf1Support()) {
                    _readerState.update { state ->
                        state.copy(
                            tagInfo = tag,
                            sectors = emptyList(),
                            lastError = "此卡不支持 Mifare Classic，无法恢复密钥",
                        )
                    }
                    appendLog(LogKind.INFO, "读到标签 UID=${tag.uidHex}，但不支持 Mifare Classic")
                    return@launch
                }
                val prng = try {
                    s.detectPrng()
                } catch (e: Exception) {
                    appendLog(LogKind.INFO, "PRNG 检测失败：${describeError(e)}")
                    PrngType.UNKNOWN
                }
                tag = tag.copy(prng = prng)
                _readerState.update { state ->
                    state.copy(
                        tagInfo = tag,
                        sectors = List(ChameleonSession.MF1_SECTOR_COUNT) { SectorKeys(it) },
                        dumpLocation = null,
                    )
                }
                appendLog(
                    LogKind.INFO,
                    "读卡成功：UID=${tag.uidHex} SAK=${tag.sakHex} ATQA=${tag.atqaHex} " +
                        "PRNG=${prng.label}（${tag.guessedType}）",
                )
            } catch (e: Exception) {
                handleReaderError("读卡失败", e)
            } finally {
                _readerState.update { it.copy(phase = ReaderPhase.Idle) }
            }
        }
    }

    /** 字典攻击：用当前字典恢复所有扇区的 A/B 密钥，命中位写入状态矩阵 */
    fun recoverKeys() {
        val s = session ?: run {
            _readerState.update { it.copy(lastError = "设备未连接") }
            return
        }
        val state = _readerState.value
        if (state.phase != ReaderPhase.Idle) return
        if (state.tagInfo == null || state.sectors.isEmpty()) {
            _readerState.update { it.copy(lastError = "请先读卡") }
            return
        }
        val keys = KeyDictionary.keys
        _readerState.update { it.copy(phase = ReaderPhase.Recovering, lastError = null) }
        viewModelScope.launch {
            try {
                appendLog(LogKind.INFO, "开始字典破解（字典 ${keys.size} 个密钥）…")
                val result = s.checkKeysOfSectors(keys)
                if (result.isEmpty()) {
                    _readerState.update { it.copy(lastError = "字典破解无结果，请确认卡片仍在感应区") }
                    return@launch
                }
                _readerState.update { it.copy(sectors = result) }
                val foundCount = result.sumOf {
                    listOf(it.keyA, it.keyB).count { k -> k.status == KeyStatus.FOUND }
                }
                appendLog(
                    LogKind.INFO,
                    "字典破解完成：命中 $foundCount/${ChameleonSession.MF1_SECTOR_COUNT * 2} 个密钥",
                )
            } catch (e: Exception) {
                handleReaderError("字典破解失败", e)
            } finally {
                _readerState.update { it.copy(phase = ReaderPhase.Idle) }
            }
        }
    }

    /**
     * Nested 攻击：点击密钥矩阵红叉时，对指定扇区的指定密钥位发起攻击。
     *
     * 流程（对齐 CLI `hf mf nested --blk <已知块> -<A|B> -k <已知密钥> --tblk <目标块> --t<A|B>`）：
     * 1. 按 PRNG 类型分派——Static 走 staticnested；Weak 预留（后续版本）；
     * 2. 取任一已恢复密钥作为已知密钥（已知块取其扇区 trailer）；
     * 3. 采集 NT 参数（MF1_STATIC_NESTED_ACQUIRE）；
     * 4. NDK 求解候选密钥（staticnested 算法）；
     * 5. 逐候选验证（MF1_AUTH_ONE_KEY_BLOCK），命中后写入密钥矩阵。
     */
    fun recoverKeyByNested(sector: Int, keyType: KeyType) {
        val s = session ?: run {
            _readerState.update { it.copy(lastError = "设备未连接") }
            return
        }
        val state = _readerState.value
        if (state.phase != ReaderPhase.Idle) return
        val tag = state.tagInfo ?: run {
            _readerState.update { it.copy(lastError = "请先读卡") }
            return
        }

        // PRNG 分派：当前实现 Static；Weak 为后续版本预留入口
        when (tag.prng) {
            PrngType.STATIC -> Unit
            PrngType.WEAK -> {
                _readerState.update { it.copy(lastError = "Weak PRNG 卡的 nested 攻击将在后续版本提供") }
                return
            }
            PrngType.HARD -> {
                _readerState.update { it.copy(lastError = "Hard PRNG 卡需使用 hardnested，暂不支持") }
                return
            }
            else -> {
                _readerState.update { it.copy(lastError = "PRNG 类型未知，请重新读卡") }
                return
            }
        }

        // 已知密钥：任一已恢复的密钥位（已知块取其扇区 trailer）
        val known = state.sectors.firstNotNullOfOrNull { sk ->
            val (type, key) = when {
                sk.keyA.status == KeyStatus.FOUND -> KeyType.A to sk.keyA.key
                sk.keyB.status == KeyStatus.FOUND -> KeyType.B to sk.keyB.key
                else -> null to null
            }
            if (type != null && key != null) Triple(sk.sector, type, key) else null
        } ?: run {
            _readerState.update { it.copy(lastError = "缺少已知密钥：请先用字典攻击恢复至少一个密钥") }
            return
        }

        val blockKnown =
            known.first * ChameleonSession.MF1_BLOCKS_PER_SECTOR + ChameleonSession.MF1_TRAILER_BLOCK_IN_SECTOR
        val blockTarget =
            sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR + ChameleonSession.MF1_TRAILER_BLOCK_IN_SECTOR
        val typeLabel = keyType.name

        _readerState.update { it.copy(phase = ReaderPhase.Recovering, lastError = null) }
        viewModelScope.launch {
            try {
                appendLog(LogKind.INFO, "- Nested recover one key running...")
                appendLog(LogKind.INFO, "- NT vulnerable: StaticNested")

                // 1. 采集 NT 参数
                val acquired = s.staticNestedAcquire(
                    blockKnown = blockKnown,
                    typeKnown = known.second,
                    keyKnown = known.third,
                    blockTarget = blockTarget,
                    typeTarget = keyType,
                )
                appendLog(
                    LogKind.INFO,
                    "  采集参数：UID=%s, NT 对 ×%d".format(
                        acquired.uid.joinToString("") { "%02X".format(it) },
                        acquired.ntPairs.size,
                    ),
                )
                acquired.ntPairs.forEach {
                    appendLog(LogKind.INFO, "    nt=%08X nt_enc=%08X".format(it.nt, it.ntEnc))
                }

                // 2. NDK 求解候选密钥（计算密集，调度到默认线程池）
                val packed = acquired.ntPairs.map { (it.nt shl 32) or it.ntEnc }.toLongArray()
                val startedAt = SystemClock.elapsedRealtime()
                val candidates = withContext(Dispatchers.Default) {
                    ChameleonNative.staticnestedRecover(acquired.uidValue, keyType.code, packed)
                }
                appendLog(
                    LogKind.INFO,
                    "  [ Time elapsed %.1fs ]".format((SystemClock.elapsedRealtime() - startedAt) / 1000f),
                )

                if (candidates.isEmpty()) {
                    appendLog(LogKind.ERROR, " - 无候选密钥：非 Static 漏洞卡或采集数据异常，可重试")
                    _readerState.update { it.copy(lastError = "Nested 攻击无候选密钥，可重试") }
                    return@launch
                }
                appendLog(LogKind.INFO, " - [${candidates.size} candidate key(s) found ]")

                // 3. 逐候选验证，命中即写入密钥矩阵
                for (candidate in candidates) {
                    val key = longToKey(candidate)
                    if (s.authOneKeyBlock(blockTarget, keyType, key)) {
                        _readerState.update { st ->
                            st.copy(sectors = st.sectors.mapIndexed { i, sk ->
                                if (i != sector) sk else sk.copy(
                                    keyA = if (keyType == KeyType.A) KeyState(KeyStatus.FOUND, key) else sk.keyA,
                                    keyB = if (keyType == KeyType.B) KeyState(KeyStatus.FOUND, key) else sk.keyB,
                                )
                            })
                        }
                        appendLog(
                            LogKind.INFO,
                            " - Block %d Type %s Key Found: %s".format(
                                blockTarget,
                                typeLabel,
                                key.joinToString("") { "%02X".format(it) },
                            ),
                        )
                        return@launch
                    }
                }
                appendLog(LogKind.ERROR, " - 候选密钥全部验证失败，可重试")
                _readerState.update { it.copy(lastError = "Nested 攻击失败：候选密钥全部验证未通过，可重试") }
            } catch (e: Exception) {
                handleReaderError("Nested 攻击失败", e)
            } finally {
                _readerState.update { it.copy(phase = ReaderPhase.Idle) }
            }
        }
    }

    /**
     * Dump 全卡数据：逐扇区用已恢复的密钥读取 4 个块，未破解扇区以全 0 占位，
     * 结果以 eml 文本保存到公共下载目录（文件名含 UID/SAK/ATQA）。
     */
    fun dumpCard() {
        val s = session ?: run {
            _readerState.update { it.copy(lastError = "设备未连接") }
            return
        }
        val state = _readerState.value
        if (state.phase != ReaderPhase.Idle) return
        val tag = state.tagInfo ?: run {
            _readerState.update { it.copy(lastError = "请先读卡") }
            return
        }
        if (state.sectors.none { it.keyA.status == KeyStatus.FOUND || it.keyB.status == KeyStatus.FOUND }) {
            _readerState.update { it.copy(lastError = "没有可用密钥，请先恢复密钥") }
            return
        }
        _readerState.update { it.copy(phase = ReaderPhase.Dumping, lastError = null) }
        viewModelScope.launch {
            try {
                val blocks = ByteArray(
                    ChameleonSession.MF1_SECTOR_COUNT *
                        ChameleonSession.MF1_BLOCKS_PER_SECTOR *
                        ChameleonSession.MF1_BLOCK_SIZE,
                )
                var failedBlocks = 0
                for (sector in 0 until ChameleonSession.MF1_SECTOR_COUNT) {
                    val sectorKeys = state.sectors[sector]
                    // 优先 KeyA（KeyA 命中时固件通常已顺带恢复 KeyB）
                    val useKeyA = sectorKeys.keyA.status == KeyStatus.FOUND
                    val key = (if (useKeyA) sectorKeys.keyA else sectorKeys.keyB).key
                    if (key == null) continue // 未破解扇区：保持全 0 占位
                    val keyType = if (useKeyA) KeyType.A else KeyType.B
                    for (i in 0 until ChameleonSession.MF1_BLOCKS_PER_SECTOR) {
                        val block = sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR + i
                        try {
                            val data = s.readBlock(block, keyType, key)
                            data.copyInto(blocks, block * ChameleonSession.MF1_BLOCK_SIZE)
                        } catch (e: ChameleonStatusException) {
                            // 卡片离开：中断整个 dump；其余（如访问位限制）仅记块失败
                            if (e.status == ChameleonStatus.HF_TAG_NO.raw) throw e
                            failedBlocks++
                            appendLog(LogKind.ERROR, "块 $block 读取失败：${e.statusDescription}")
                        }
                    }
                }
                // trailer 块的 KeyA 对读卡器永远返回全 0，KeyB 在常规访问位配置下
                // 同样不可读（Mifare Classic 安全设计）。按已恢复密钥对称回填：
                // KeyA 命中 → 覆盖 byte 0-5，KeyB 已破解则同时覆盖 KeyB 区域；
                // KeyB 命中 → 覆盖 byte 10-15，KeyA 已破解则同时覆盖 KeyA 区域
                //（另一密钥的读出值可能为全 0，直接采用已知密钥更可靠）。
                // access bits（byte 6-9）始终保留真实读出值。
                for (sector in 0 until ChameleonSession.MF1_SECTOR_COUNT) {
                    val trailerOffset =
                        (sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR +
                            ChameleonSession.MF1_TRAILER_BLOCK_IN_SECTOR) *
                            ChameleonSession.MF1_BLOCK_SIZE
                    val keys = state.sectors[sector]
                    val keyA = keys.keyA.key
                    val keyB = keys.keyB.key
                    if (keyA != null) {
                        keyA.copyInto(blocks, trailerOffset)
                        keyB?.copyInto(
                            blocks,
                            trailerOffset + ChameleonSession.MF1_TRAILER_KEY_B_OFFSET,
                        )
                    } else if (keyB != null) {
                        keyB.copyInto(
                            blocks,
                            trailerOffset + ChameleonSession.MF1_TRAILER_KEY_B_OFFSET,
                        )
                        // keyA 为 null 时无可用值，KeyA 区域保留读出值（通常为全 0）
                    }
                }

                val location = DumpExporter.save(getApplication(), tag, blocks)
                _readerState.update { it.copy(dumpLocation = location) }
                appendLog(
                    LogKind.INFO,
                    if (failedBlocks == 0) {
                        "Dump 已保存：$location"
                    } else {
                        "Dump 已保存：$location（${failedBlocks} 个块读取失败，已置 0）"
                    },
                )
            } catch (e: Exception) {
                handleReaderError("Dump 失败", e)
            } finally {
                _readerState.update { it.copy(phase = ReaderPhase.Idle) }
            }
        }
    }

    /** 清除最近一次错误提示（UI 展示 Snackbar 后回调） */
    fun consumeLastError() {
        _readerState.update { it.copy(lastError = null) }
    }

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    fun clearLog() {
        _log.value = emptyList()
    }

    override fun onCleared() {
        client?.disconnect()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 确保设备处于读卡器模式；依据缓存的工作模式避免多余的查询/切换 */
    private suspend fun ensureReaderMode(s: ChameleonSession) {
        when (_deviceMode.value) {
            DeviceMode.READER -> return

            DeviceMode.EMULATOR -> {
                appendLog(LogKind.INFO, "设备处于模拟卡模式，切换为读卡器模式…")
                s.changeDeviceMode(DeviceMode.READER)
            }

            DeviceMode.UNKNOWN -> {
                val mode = s.getDeviceMode()
                if (mode == DeviceMode.READER) {
                    _deviceMode.value = mode
                    return
                }
                appendLog(LogKind.INFO, "设备处于模拟卡模式，切换为读卡器模式…")
                s.changeDeviceMode(DeviceMode.READER)
            }
        }
        _deviceMode.value = DeviceMode.READER
        appendLog(LogKind.INFO, "已切换为读卡器模式")
    }

    /** 连接就绪后读取并缓存设备工作模式（主界面模式图标的唯一数据源） */
    private fun refreshDeviceMode() {
        val s = session ?: return
        viewModelScope.launch {
            try {
                val mode = s.getDeviceMode()
                _deviceMode.value = mode
                appendLog(LogKind.INFO, "设备工作模式：" + if (mode == DeviceMode.READER) "读卡器" else "模拟卡")
            } catch (e: Exception) {
                appendLog(LogKind.ERROR, "读取工作模式失败：${describeError(e)}")
            }
        }
    }

    private suspend fun handleReaderError(action: String, e: Exception) {
        val message = "$action：${describeError(e)}"
        appendLog(LogKind.ERROR, message)
        _readerState.update { it.copy(lastError = message) }
    }

    private fun describeError(e: Exception): String = when (e) {
        is ChameleonStatusException -> e.statusDescription
        is ChameleonBleException -> e.message ?: "通信异常"
        else -> e.message ?: "未知错误"
    }

    /** 48bit 密钥数值 → 6 字节大端（与卡上存储 / 协议传输的字节序一致） */
    private fun longToKey(value: Long): ByteArray =
        ByteArray(ChameleonSession.MF1_KEY_SIZE) { i ->
            ((value shr ((ChameleonSession.MF1_KEY_SIZE - 1 - i) * 8)) and 0xFF).toByte()
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
