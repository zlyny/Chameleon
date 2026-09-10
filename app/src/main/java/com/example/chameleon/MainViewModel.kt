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
import com.example.chameleon.device.DumpCard
import com.example.chameleon.device.DumpRepository
import com.example.chameleon.device.KeyDictionary
import com.example.chameleon.device.KeyState
import com.example.chameleon.device.KeyStatus
import com.example.chameleon.device.KeyType
import com.example.chameleon.device.PrngType
import com.example.chameleon.device.SectorKeys
import com.example.chameleon.device.StaticNestedGen
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
    sealed interface ConnectionState {  //sealed(封闭)的核心价值,让编译器知道这个接口总共只有 3 个子类型
        data object Disconnected : ConnectionState  //全局只有一个实例的常量状态,所以用单例

        data object Connecting : ConnectionState

        data class Connected(val name: String, val address: String) : ConnectionState   //数据类,可以设置默认值
    }

    /** 日志类型，决定 UI 中的显示颜色 */
    enum class LogKind { TX, RX, INFO, ERROR }

    data class LogEntry(val id: Long, val kind: LogKind, val text: String)

    /** 设备操作阶段（同一时刻只允许一个在途流程，相关按钮据此禁用） */
    sealed interface ReaderPhase {
        data object Idle : ReaderPhase

        data object Reading : ReaderPhase

        data object Recovering : ReaderPhase

        data object Dumping : ReaderPhase

        /** 卡片管理页：dump 写入设备模拟卡中（读卡页按钮同样禁用） */
        data object WritingEmu : ReaderPhase
    }

    /** 读卡页聚合状态 */
    data class ReaderState( //将读卡数据打包,界面只 collect 一条流,防止数据不一致
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
    private val _deviceMode = MutableStateFlow(DeviceMode.UNKNOWN)  //下划线开头是私有的可写版，只有 ViewModel 内部能改
    val deviceMode: StateFlow<DeviceMode> = _deviceMode.asStateFlow()       //公开版本只读

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _readerState = MutableStateFlow(ReaderState())
    val readerState: StateFlow<ReaderState> = _readerState.asStateFlow()

    private var client: ChameleonBleClient? = null  //ble层
    private var session: ChameleonSession? = null   //会话层
    private var nextLogId = 0L      //日志序号自增

    /** dump 卡片库（app 专属目录，卡片管理页与 dump 流程共用） */
    private val dumpRepository = DumpRepository(application)

    /** 模式切换在途标志（主线程读写，防重复点击双发切换指令） */
    private var modeSwitchInFlight = false

    // ------------------------------------------------------------------
    // 连接管理
    // ------------------------------------------------------------------

    /**
     * 连接扫描页选定的设备（[address] 为 MAC 地址，设备已在 CentralManager 缓存中）。
     * 连接就绪后自动创建命令会话并读取设备工作模式。
     */
    fun connectDevice(address: String, name: String?) {
        if (client != null) return  //防重入保护
        val displayName = name ?: address

        val newClient = ChameleonBleClient(BleCenter.centralManager)
        newClient.listener = object : ChameleonBleClient.Listener { // 匿名对象实现监听器
            override fun onReady(mtu: Int) {
                appendLog(LogKind.INFO, "连接就绪（MTU=$mtu）")
                session = ChameleonSession(newClient)
                _connectionState.value = ConnectionState.Connected(displayName, address)    //将蓝牙回调转换成状态流
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
        client = newClient  //配合函数入口的防冲入保护使用
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
        val s = session ?: return   //取到局部变量中,防止协程运行时 断开连接session失效
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
     * 读卡：确保读卡器模式 -> 扫描 14A 标签 -> 检测 Mifare Classic 支持 ->
     * 检测 PRNG（Static 卡进一步判定 StaticNested 漏洞代次 GEN1/GEN2）。
     * 成功后初始化 16 个扇区的密钥状态矩阵。
     */
    fun readCard() {
        val s = session ?: run {
            _readerState.update { it.copy(lastError = "设备未连接") }    // it.copy是复制一个新的it,并部分赋值
            return
        }
        if (_readerState.value.phase != ReaderPhase.Idle) return
        _readerState.update { it.copy(phase = ReaderPhase.Reading, lastError = null) }  //进入 Reading 阶段
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
                    return@launch   //从协程中退出
                }
                val prng = try {
                    s.detectPrng()
                } catch (e: Exception) {
                    appendLog(LogKind.INFO, "PRNG 检测失败：${describeError(e)}")
                    PrngType.UNKNOWN
                }
                // Static 卡进一步判定 StaticNested 漏洞代次（GEN1/GEN2），
                // 仅影响展示与用户预期；检测失败不阻断读卡
                val staticGen = if (prng == PrngType.STATIC) {
                    try {
                        s.detectStaticNestedGen()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                tag = tag.copy(prng = prng, staticGen = staticGen)
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
                        "PRNG=${staticGen?.label ?: prng.label}（${tag.guessedType}）",
                )
            } catch (e: Exception) {
                handleReaderError("读卡失败", e)
            } finally {
                _readerState.update { it.copy(phase = ReaderPhase.Idle) }
            }
        }
    }

    /**
     * 字典攻击：用当前字典恢复所有扇区的 A/B 密钥，命中位写入状态矩阵。
     * 已恢复的密钥位跳过（不重复检查），历史结果保留。
     */
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
        val before = state.sectors
        _readerState.update { it.copy(phase = ReaderPhase.Recovering, lastError = null) }
        viewModelScope.launch {
            try {
                appendLog(LogKind.INFO, "开始字典破解（字典 ${keys.size} 个密钥）…")
                val result = s.checkKeysOfSectors(
                    keys,
                    shouldCheck = { sector, type -> isKeyMissing(before, sector, type) },
                )
                _readerState.update { st -> st.copy(sectors = mergeSectorKeys(st.sectors, result)) }
                val foundCount = countFound(_readerState.value.sectors)
                appendLog(
                    LogKind.INFO,
                    "字典破解完成：已恢复 $foundCount/${ChameleonSession.MF1_SECTOR_COUNT * 2} 个密钥",
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
     * 1. 按 PRNG 类型分派——Static 走 staticnested；Weak 走 nested（先测 NT dist）；
     * 2. 取已恢复密钥作为已知密钥（已知块取其扇区 trailer，优先跨扇区）；
     * 3. 采集参数并 NDK 求解候选密钥（[solveStaticNested] / [solveWeakNested]）；
     * 4. 逐候选验证（MF1_AUTH_ONE_KEY_BLOCK），命中后写入密钥矩阵。
     *
     * 注意：Nested 攻击依赖随机数碰撞，单次成功率有限——找不到密钥属正常
     * 现象，提示用户再次点击重试（与 CLI 行为一致）。
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

        // PRNG 分派：Static/Weak 分别走对应算法；Hard 需 hardnested（后续版本）
        when (tag.prng) {
            PrngType.STATIC, PrngType.WEAK -> Unit
            PrngType.HARD -> {
                _readerState.update { it.copy(lastError = "Hard PRNG 卡需使用 hardnested，暂不支持") }
                return
            }
            else -> {
                _readerState.update { it.copy(lastError = "PRNG 类型未知，请重新读卡") }
                return
            }
        }

        // 已知密钥：优先取目标扇区之外的已恢复密钥（跨扇区嵌套采集更稳），
        // 仅目标扇区有已知密钥时回落使用（同扇区跨密钥位认证亦可）
        val known = pickKnownKey(state.sectors, targetSector = sector) ?: run {
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

                // 1. 采集参数 + NDK 求解（按 PRNG 类型分派，日志对齐 CLI）
                val candidates = if (tag.prng == PrngType.STATIC) {
                    solveStaticNested(s, blockKnown, known, blockTarget, keyType)
                } else {
                    solveWeakNested(s, blockKnown, known, blockTarget, keyType)
                }

                if (candidates.isEmpty()) {
                    appendLog(LogKind.ERROR, " - 无候选密钥：采集数据未命中（Nested 成功率有限），可再次点击重试")
                    _readerState.update { it.copy(lastError = "本次未找到密钥（属正常现象），可再次点击红叉重试") }
                    return@launch
                }
                appendLog(LogKind.INFO, " - [${candidates.size} candidate key(s) found ]")

                // 2. 逐候选验证，命中即写入密钥矩阵
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
                        // 3. 密钥复用：大量卡全卡共用同一密钥，命中后立即
                        //    用它对未恢复位再做一次检查（对齐 CLI autopwn）
                        try {
                            reuseRecoveredKey(s, key)
                        } catch (e: Exception) {
                            appendLog(LogKind.INFO, " - 密钥复用检查失败：${describeError(e)}")
                        }
                        return@launch
                    }
                }
                appendLog(LogKind.ERROR, " - 候选密钥全部验证失败，可再次点击重试")
                _readerState.update { it.copy(lastError = "候选密钥验证未通过（属正常现象），可再次点击红叉重试") }
            } catch (e: Exception) {
                handleReaderError("Nested 攻击失败", e)
            } finally {
                _readerState.update { it.copy(phase = ReaderPhase.Idle) }
            }
        }
    }

    /** 已知密钥挑选：返回 Triple(扇区号, 密钥类型, 密钥)。优先目标扇区之外，回落目标扇区 */
    private fun pickKnownKey(
        sectors: List<SectorKeys>,
        targetSector: Int,
    ): Triple<Int, KeyType, ByteArray>? {
        val known = sectors.flatMap { sk ->
            listOfNotNull(
                sk.keyA.key?.let { Triple(sk.sector, KeyType.A, it) },
                sk.keyB.key?.let { Triple(sk.sector, KeyType.B, it) },
            )
        }
        return known.firstOrNull { it.first != targetSector } ?: known.firstOrNull()
    }

    /**
     * 密钥复用检查（对齐 CLI autopwn 的 try_key）：Nested 命中后立即用该
     * 密钥对尚未恢复的密钥位再做一次字典检查——现实中大量卡全卡共用
     * 同一密钥，一次命中往往能顺带恢复多个扇区。复用失败不影响主流程。
     */
    private suspend fun reuseRecoveredKey(s: ChameleonSession, key: ByteArray) {
        val sectors = _readerState.value.sectors
        val missingBits = sectors.sumOf { sk ->
            listOf(KeyType.A, KeyType.B).count { type -> isKeyMissing(sectors, sk.sector, type) }
        }
        if (missingBits == 0) return

        val foundBefore = countFound(sectors)
        appendLog(LogKind.INFO, " - 复用密钥检查其余 $missingBits 个未恢复位…")
        val result = s.checkKeysOfSectors(
            listOf(key),
            shouldCheck = { sector, type -> isKeyMissing(sectors, sector, type) },
        )
        _readerState.update { st -> st.copy(sectors = mergeSectorKeys(st.sectors, result)) }
        val gained = countFound(_readerState.value.sectors) - foundBefore
        appendLog(
            LogKind.INFO,
            if (gained > 0) " - 复用命中：新增恢复 $gained 个密钥" else " - 其余扇区未复用该密钥",
        )
    }

    /** 指定密钥位是否尚未恢复（字典检查 / 密钥复用的过滤条件） */
    private fun isKeyMissing(sectors: List<SectorKeys>, sector: Int, type: KeyType): Boolean {
        val sk = sectors.getOrNull(sector) ?: return true
        return when (type) {
            KeyType.A -> sk.keyA.status != KeyStatus.FOUND
            KeyType.B -> sk.keyB.status != KeyStatus.FOUND
        }
    }

    /**
     * 合并字典检查结果到现有矩阵：FOUND 优先；update 未命中但当前已 FOUND
     * 的位保留当前值（固件对跳过位返回未命中，不能反向清掉已恢复的密钥）
     */
    private fun mergeSectorKeys(current: List<SectorKeys>, update: List<SectorKeys>): List<SectorKeys> {
        if (current.isEmpty()) return update
        return current.mapIndexed { i, cur ->
            val upd = update.getOrNull(i) ?: cur
            fun pick(curKey: KeyState, updKey: KeyState) =
                if (curKey.status == KeyStatus.FOUND && updKey.status != KeyStatus.FOUND) curKey else updKey
            cur.copy(keyA = pick(cur.keyA, upd.keyA), keyB = pick(cur.keyB, upd.keyB))
        }
    }

    /** 已恢复密钥位计数（A/B 合计） */
    private fun countFound(sectors: List<SectorKeys>): Int =
        sectors.sumOf { sk ->
            listOf(sk.keyA, sk.keyB).count { it.status == KeyStatus.FOUND }
        }

    /**
     * Static Nested 采集 + 求解（Static PRNG 卡）：一次性采得固定 NT 对，
     * NDK 按漏洞代次判定后求解（native-lib.cpp 的 staticnestedRecover）。
     */
    private suspend fun solveStaticNested(
        s: ChameleonSession,
        blockKnown: Int,
        known: Triple<Int, KeyType, ByteArray>,
        blockTarget: Int,
        keyType: KeyType,
    ): LongArray {
        appendLog(LogKind.INFO, "- 已知块=%d(%d),NT vulnerable: StaticNested".format(blockKnown,known.second.code))

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

        val packed = acquired.ntPairs.map { (it.nt shl 32) or it.ntEnc }.toLongArray()
        return solveWithTiming {
            ChameleonNative.staticnestedRecover(acquired.uidValue, keyType.code, packed)
        }
    }

    /**
     * Nested 采集 + 求解（Weak PRNG 卡）：
     * 1. MF1_DETECT_NT_DIST 测 PRNG 前进步数 dist；
     * 2. MF1_NESTED_ACQUIRE 采集 (nt, nt_enc, par) 三元组；
     * 3. NDK 在 dist±14 内枚举真实 NT 并按奇偶位筛选求解（nestedRecover）。
     */
    private suspend fun solveWeakNested(
        s: ChameleonSession,
        blockKnown: Int,
        known: Triple<Int, KeyType, ByteArray>,
        blockTarget: Int,
        keyType: KeyType,
    ): LongArray {
        appendLog(LogKind.INFO, "known=%d(%d)- NT vulnerable: Nested".format(blockKnown,known.second.code))

        val ntDist = s.detectNtDist(blockKnown, known.second, known.third)
        val triples = s.nestedAcquire(blockKnown, known.second, known.third, blockTarget, keyType)
        appendLog(
            LogKind.INFO,
            "  Executing nested: uid=%d dist=%d nts×%d".format(ntDist.uid, ntDist.dist, triples.size),
        )
        triples.forEach {
            appendLog(LogKind.INFO, "    nt=%08X nt_enc=%08X par=%d".format(it.nt, it.ntEnc, it.par))
        }

        val packed = triples.map { (it.nt shl 32) or it.ntEnc }.toLongArray()
        val parities = ByteArray(triples.size) { i -> triples[i].par.toByte() }
        return solveWithTiming {
            ChameleonNative.nestedRecover(ntDist.uid, ntDist.dist, packed, parities)
        }
    }

    /** 计时执行 NDK 求解（计算密集，调度到默认线程池），输出 CLI 同款耗时日志 */
    private suspend fun solveWithTiming(solve: suspend () -> LongArray): LongArray {
        val startedAt = SystemClock.elapsedRealtime()
        val candidates = withContext(Dispatchers.Default) { solve() }   //Dispatchers.Default是按 CPU 核数开的计算线程池,挂起当前协程,直到solve()执行完毕
        appendLog(
            LogKind.INFO,
            "  [ Time elapsed %.1fs ]".format((SystemClock.elapsedRealtime() - startedAt) / 1000f),
        )
        return candidates
    }

    /**
     * Dump 全卡数据：逐扇区用已恢复的密钥读取 4 个块，未破解扇区以全 0 占位，
     * 结果以 eml 文本存入 dump 卡片库（见 [DumpRepository]），供卡片管理页使用。
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

                val saved = dumpRepository.save(tag, blocks)
                _readerState.update { it.copy(dumpLocation = saved.fileName) }
                appendLog(
                    LogKind.INFO,
                    if (failedBlocks == 0) {
                        "Dump 已存入卡片库：${saved.fileName}（卡片管理页可写入设备）"
                    } else {
                        "Dump 已存入卡片库：${saved.fileName}（${failedBlocks} 个块读取失败，已置 0）"
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

    /**
     * 把卡片库中的 dump 写入设备模拟卡（对齐 CLI `hf mf eload` + 反碰撞数据）：
     * 1. 切换设备到模拟卡模式；
     * 2. 设置反碰撞数据（UID/ATQA/SAK 与原卡一致）；
     * 3. 分块写入全部块数据（单帧上限 31 块，1K 卡 4 帧完成）。
     *
     * 写入当前激活卡槽；完成后设备立即模拟这张卡。
     *
     * @return false 表示未启动（未连接 / 设备忙 / 数据异常），原因见日志与 lastError
     */
    fun writeDumpToEmulator(dump: DumpCard): Boolean {
        val s = session ?: run {
            _readerState.update { it.copy(lastError = "设备未连接") }
            return false
        }
        if (_readerState.value.phase != ReaderPhase.Idle) {
            _readerState.update { it.copy(lastError = "设备忙（读卡或写入进行中），请稍后再试") }
            return false
        }
        val blocks = dumpRepository.readBlocks(dump.fileName) ?: run {
            _readerState.update { it.copy(lastError = "读取 dump 文件失败") }
            return false
        }
        if (blocks.size != ChameleonSession.MF1_SECTOR_COUNT *
            ChameleonSession.MF1_BLOCKS_PER_SECTOR * ChameleonSession.MF1_BLOCK_SIZE
        ) {
            _readerState.update { it.copy(lastError = "dump 数据非 1K 卡（64 块），暂不支持") }
            return false
        }

        val uid = HexUtils.parse(dump.uidHex)
        val atqa = HexUtils.parse(dump.atqaHex)
        val sak = HexUtils.parse(dump.sakHex)
        if (uid == null || atqa == null || sak == null) {
            _readerState.update { it.copy(lastError = "dump 元数据解析失败") }
            return false
        }

        _readerState.update { it.copy(phase = ReaderPhase.WritingEmu, lastError = null) }
        viewModelScope.launch {
            try {
                appendLog(LogKind.INFO, "写入模拟卡：UID=${dump.uidHex}（${blocks.size / ChameleonSession.MF1_BLOCK_SIZE} 块）")
                ensureEmulatorMode(s)

                // 反碰撞数据让模拟卡的卡号与原卡一致（ATS 留空）
                s.setAntiCollData(uid, atqa, sak)
                appendLog(LogKind.INFO, " - 反碰撞数据已设置（UID/ATQA/SAK）")

                // 开启 mfkey32 认证日志（对齐 CLI `hf mf econfig --enable-log`），
                // 供模拟卡被认证后离线恢复未知扇区密钥
                s.setDetectionEnable(true)
                appendLog(LogKind.INFO, " - mfkey32 认证日志已开启")

                // 分块写入：每帧 16 块（1K 卡 4 帧），进度随帧输出
                val blocksPerFrame = 16
                var block = 0
                while (block < blocks.size / ChameleonSession.MF1_BLOCK_SIZE) {
                    val from = block * ChameleonSession.MF1_BLOCK_SIZE
                    val to = minOf(from + blocksPerFrame * ChameleonSession.MF1_BLOCK_SIZE, blocks.size)
                    s.writeEmuBlockData(block, blocks.copyOfRange(from, to))
                    block = to / ChameleonSession.MF1_BLOCK_SIZE
                    appendLog(LogKind.INFO, " - 已写入块 $block")
                }
                appendLog(LogKind.INFO, "写入完成，设备正在模拟该卡")
            } catch (e: Exception) {
                handleReaderError("写入模拟卡失败", e)
            } finally {
                _readerState.update { it.copy(phase = ReaderPhase.Idle) }
            }
        }
        return true
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

    /** 确保设备处于模拟卡模式（写入 dump 前调用）；与 [ensureReaderMode] 对称 */
    private suspend fun ensureEmulatorMode(s: ChameleonSession) {
        when (_deviceMode.value) {
            DeviceMode.EMULATOR -> return

            DeviceMode.READER -> {
                appendLog(LogKind.INFO, "切换为模拟卡模式…")
                s.changeDeviceMode(DeviceMode.EMULATOR)
            }

            DeviceMode.UNKNOWN -> {
                val mode = s.getDeviceMode()
                if (mode == DeviceMode.EMULATOR) {
                    _deviceMode.value = mode
                    return
                }
                appendLog(LogKind.INFO, "切换为模拟卡模式…")
                s.changeDeviceMode(DeviceMode.EMULATOR)
            }
        }
        _deviceMode.value = DeviceMode.EMULATOR
        appendLog(LogKind.INFO, "已切换为模拟卡模式")
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

    /**
     * 点击模式图标切换设备工作模式（读卡器 <-> 模拟卡）。
     * 读卡流程进行中拒绝切换避免中断；图标渲染仍由 [deviceMode] 流驱动。
     */
    fun toggleDeviceMode() {
        val s = session ?: run {
            appendLog(LogKind.ERROR, "设备未连接，无法切换工作模式")
            return
        }
        val current = _deviceMode.value
        if (current == DeviceMode.UNKNOWN || modeSwitchInFlight) return
        if (_readerState.value.phase != ReaderPhase.Idle) {
            appendLog(LogKind.INFO, "读卡操作进行中，暂不切换工作模式")
            return
        }
        val target = if (current == DeviceMode.READER) DeviceMode.EMULATOR else DeviceMode.READER

        modeSwitchInFlight = true
        viewModelScope.launch {
            try {
                appendLog(LogKind.INFO, "切换为${if (target == DeviceMode.READER) "读卡器" else "模拟卡"}模式…")
                s.changeDeviceMode(target)
                _deviceMode.value = target
                appendLog(LogKind.INFO, "已切换为${if (target == DeviceMode.READER) "读卡器" else "模拟卡"}模式")
            } catch (e: Exception) {
                appendLog(LogKind.ERROR, "切换工作模式失败：${describeError(e)}")
            } finally {
                modeSwitchInFlight = false
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
