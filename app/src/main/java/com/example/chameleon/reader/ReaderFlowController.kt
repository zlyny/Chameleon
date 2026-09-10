package com.example.chameleon.reader

import android.os.SystemClock
import com.example.chameleon.ble.ChameleonBleException
import com.example.chameleon.device.AuthLog
import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.device.ChameleonStatusException
import com.example.chameleon.device.DeviceMode
import com.example.chameleon.device.DumpCard
import com.example.chameleon.device.DumpContent
import com.example.chameleon.device.DumpRepository
import com.example.chameleon.device.KeyDictionary
import com.example.chameleon.device.KeyState
import com.example.chameleon.device.KeyStatus
import com.example.chameleon.device.KeyType
import com.example.chameleon.device.PrngType
import com.example.chameleon.device.SectorKeys
import com.example.chameleon.device.TagInfo
import com.example.chameleon.jni.ChameleonNative
import com.example.chameleon.log.LogKind
import com.example.chameleon.protocol.ChameleonStatus
import com.example.chameleon.protocol.HexUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays

/** 设备操作阶段（同一时刻只允许一个在途流程，相关按钮据此禁用） */
sealed interface ReaderPhase {
    data object Idle : ReaderPhase

    data object Reading : ReaderPhase

    data object Recovering : ReaderPhase

    data object Dumping : ReaderPhase

    /** 卡片管理页：写入槽进行中（dump 写入设备模拟卡，读卡页按钮同样禁用） */
    data object WritingEmu : ReaderPhase

    /** 读卡页：mfkey32 认证日志离线破解进行中 */
    data object Mfkey32 : ReaderPhase
}

/** 读卡页聚合状态 */
data class ReaderState( //将读卡数据打包,界面只 collect 一条流,防止数据不一致
    val phase: ReaderPhase = ReaderPhase.Idle,
    val tagInfo: TagInfo? = null,
    /** 每扇区 A/B 密钥恢复结果；非 Mifare 卡或未读卡时为空 */
    val sectors: List<SectorKeys> = emptyList(),
    /** 最近一次操作的错误提示（UI 用 Snackbar 展示后清除） */
    val lastError: String? = null,

    /** 最近一次操作的成功提示（UI 用 Snackbar 展示后清除，与 [lastError] 对称） */
    val lastSuccess: String? = null,

    /** 最近一次 dump 的保存位置 */
    val dumpLocation: String? = null,
)

/**
 * 读卡流程控制器：从 MainViewModel 拆出的读卡 / 破解 / Dump / 写模拟卡 /
 * mfkey32 全部设备业务流程。持有 [ReaderState] 状态流，UI 仍经
 * MainViewModel 的转发属性观察与触发（Fragment 不直接接触本类）。
 *
 * 依赖经构造注入：会话提供方（连接生命周期归 MainViewModel）、设备模式
 * 缓存流（ensure*Mode 与主界面模式图标共享同一份）、dump 卡片库、
 * 日志回调。协程运行在注入的 scope（viewModelScope，主线程调度器）上，
 * 状态流更新无需额外线程同步。
 */
class ReaderFlowController(
    private val scope: CoroutineScope,
    private val sessionProvider: () -> ChameleonSession?,
    private val deviceMode: MutableStateFlow<DeviceMode>,
    private val dumpRepository: DumpRepository,
    private val appendLog: (LogKind, String) -> Unit,
) {

    private val _readerState = MutableStateFlow(ReaderState())
    val readerState: StateFlow<ReaderState> = _readerState.asStateFlow()

    // ------------------------------------------------------------------
    // 读卡
    // ------------------------------------------------------------------

    /**
     * 读卡：确保读卡器模式 -> 扫描 14A 标签 -> 检测 Mifare Classic 支持 ->
     * 检测 PRNG（Static 卡进一步判定 StaticNested 漏洞代次 GEN1/GEN2）。
     * 成功后初始化 16 个扇区的密钥状态矩阵。
     */
    fun readCard() {
        val s = sessionProvider() ?: run {
            _readerState.update { it.copy(lastError = "设备未连接") }    // it.copy是复制一个新的it,并部分赋值
            return
        }
        if (_readerState.value.phase != ReaderPhase.Idle) return
        _readerState.update { it.copy(phase = ReaderPhase.Reading, lastError = null) }  //进入 Reading 阶段
        scope.launch {
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

    // ------------------------------------------------------------------
    // 字典攻击
    // ------------------------------------------------------------------

    /**
     * 字典攻击：用当前字典恢复所有扇区的 A/B 密钥，命中位写入状态矩阵。
     * 已恢复的密钥位跳过（不重复检查），历史结果保留。
     */
    fun recoverKeys() {
        val s = sessionProvider() ?: run {
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
        scope.launch {
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

    // ------------------------------------------------------------------
    // Nested 攻击
    // ------------------------------------------------------------------

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
        val s = sessionProvider() ?: run {
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
        scope.launch {
            try {
                appendLog(LogKind.INFO, "- Nested recover one key running...")

                // 0. KeyB 捷径（先于 Nested 采集）：目标扇区 KeyA 已恢复时，多数卡的
                //    访问位允许 KeyA 直接读出 trailer 中的 KeyB——读出非全 0 即为
                //    真实密钥，免去 Nested 采集与求解；读出全 0（访问位限制不可读）
                //    或读取失败则回落 Nested 攻击
                if (keyType == KeyType.B) {
                    val keyAState = state.sectors[sector].keyA
                    val keyA = keyAState.key
                    if (keyAState.isFound && keyA != null) {
                        val keyB = try {
                            val trailer = s.readBlock(blockTarget, KeyType.A, keyA)
                            trailer.copyOfRange(
                                ChameleonSession.MF1_TRAILER_KEY_B_OFFSET,
                                ChameleonSession.MF1_BLOCK_SIZE,
                            )
                        } catch (e: Exception) {
                            appendLog(LogKind.INFO, " - KeyA 读 trailer 失败（${describeError(e)}），回落 Nested 攻击")
                            null
                        }
                        if (keyB != null) {
                            if (keyB.all { it == 0.toByte() }) {
                                appendLog(LogKind.INFO, " - KeyB 经 KeyA 读出为全 0（访问位限制不可读），回落 Nested 攻击")
                            } else {
                                appendLog(
                                    LogKind.INFO,
                                    " - Block %d Type %s Key Found: %s".format(
                                        blockTarget,
                                        typeLabel,
                                        keyB.joinToString("") { "%02X".format(it) },
                                    ),
                                )
                                applyFoundKey(s, sector, keyType, keyB)
                                return@launch
                            }
                        }
                    }
                }

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
                        appendLog(
                            LogKind.INFO,
                            " - Block %d Type %s Key Found: %s".format(
                                blockTarget,
                                typeLabel,
                                key.joinToString("") { "%02X".format(it) },
                            ),
                        )
                        applyFoundKey(s, sector, keyType, key)
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

    /**
     * 攻击命中后的统一收尾：写入密钥矩阵并做密钥复用检查（对齐 CLI
     * autopwn 的 try_key——现实中大量卡全卡共用同一密钥，一次命中往往
     * 能顺带恢复多个扇区）。复用失败不影响已命中的结果。
     */
    private suspend fun applyFoundKey(s: ChameleonSession, sector: Int, keyType: KeyType, key: ByteArray) {
        _readerState.update { st ->
            st.copy(sectors = st.sectors.mapIndexed { i, sk ->
                if (i != sector) sk else sk.copy(
                    keyA = if (keyType == KeyType.A) KeyState(KeyStatus.FOUND, key) else sk.keyA,
                    keyB = if (keyType == KeyType.B) KeyState(KeyStatus.FOUND, key) else sk.keyB,
                )
            })
        }
        try {
            reuseRecoveredKey(s, key)
        } catch (e: Exception) {
            appendLog(LogKind.INFO, " - 密钥复用检查失败：${describeError(e)}")
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
     *
     * 仅读卡器模式可在线试探（需场上有真实卡）；mfkey32 场景设备处于
     * 模拟卡模式时跳过，用户切回读卡器模式并放回原卡后可点「Recover」复查。
     */
    private suspend fun reuseRecoveredKey(s: ChameleonSession, key: ByteArray) {
        if (deviceMode.value != DeviceMode.READER) {
            appendLog(LogKind.INFO, " - 跳过密钥复用检查（设备非读卡器模式；放回原卡后可点 Recover 复查）")
            return
        }
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
            KeyType.A -> !sk.keyA.isFound
            KeyType.B -> !sk.keyB.isFound
        }
    }

    /**
     * 合并字典检查结果到现有矩阵：恢复状态优先级 VERIFIED > FOUND > 未恢复
     * （FOUND 经 Dump 全扇区读取成功后升级为 VERIFIED，不能被反向降级）；
     * update 未命中但当前已恢复的位保留当前值（固件对跳过位返回未命中，
     * 不能反向清掉已恢复的密钥）
     */
    private fun mergeSectorKeys(current: List<SectorKeys>, update: List<SectorKeys>): List<SectorKeys> {
        if (current.isEmpty()) return update

        fun priority(status: KeyStatus) = when (status) {
            KeyStatus.VERIFIED -> 3
            KeyStatus.FOUND -> 2
            else -> 1
        }
        fun pick(curKey: KeyState, updKey: KeyState) =
            if (priority(curKey.status) > priority(updKey.status)) curKey else updKey
        return current.mapIndexed { i, cur ->
            val upd = update.getOrNull(i) ?: cur
            cur.copy(keyA = pick(cur.keyA, upd.keyA), keyB = pick(cur.keyB, upd.keyB))
        }
    }

    /** 已恢复密钥位计数（A/B 合计，FOUND 与 VERIFIED 均计入） */
    private fun countFound(sectors: List<SectorKeys>): Int =
        sectors.sumOf { sk ->
            listOf(sk.keyA, sk.keyB).count { it.isFound }
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

    // ------------------------------------------------------------------
    // Dump / 卡片库
    // ------------------------------------------------------------------

    /**
     * Dump 全卡数据：逐扇区用已恢复的密钥读取 4 个块，结果以 eml 文本存入
     * dump 卡片库（见 [DumpRepository]），供卡片管理页使用。
     *
     * 未读取成功的字节标记为未知（eml 中记 XX，见 [DumpContent]），三类来源：
     * - 未破解扇区：整扇区 4 块保持未知；
     * - 单块读取失败（访问位限制等）：该块 16 字节标记未知；
     * - trailer 块：密钥区不以读出值为准（KeyA 恒读出全 0，KeyB 依访问位
     *   可能不可读，均为 Mifare Classic 安全设计），按密钥矩阵回填——已破解
     *   的密钥覆盖对应区域，未破解的区域标记未知；访问位区 [6:10] 保留真实
     *   读出值，但读出全零视为读取失败（真实卡的访问控制位不会全零），
     *   同样标记未知。
     *
     * 某扇区 4 块全部读取成功时，所用密钥位升级为 VERIFIED（密钥矩阵显示
     * 蓝色对号）：该密钥确实能完整访问该扇区，dump 出的数据完整可信。
     */
    fun dumpCard() {
        val s = sessionProvider() ?: run {
            _readerState.update { it.copy(lastError = "设备未连接") }
            return
        }
        val state = _readerState.value
        if (state.phase != ReaderPhase.Idle) return
        val tag = state.tagInfo ?: run {
            _readerState.update { it.copy(lastError = "请先读卡") }
            return
        }
        if (state.sectors.none { it.keyA.isFound || it.keyB.isFound }) {
            _readerState.update { it.copy(lastError = "没有可用密钥，请先恢复密钥") }
            return
        }
        _readerState.update { it.copy(phase = ReaderPhase.Dumping, lastError = null) }
        scope.launch {
            try {
                // 初始全部未知（XX）：只有成功读出或回填的字节才标记已知
                val content = DumpContent.allUnknown(
                    ChameleonSession.MF1_SECTOR_COUNT *
                        ChameleonSession.MF1_BLOCKS_PER_SECTOR *
                        ChameleonSession.MF1_BLOCK_SIZE,
                )
                val bytes = content.bytes
                val known = content.known
                var failedBlocks = 0
                // 每扇区成功读取的块数：满 4 块即视为"全扇区读取成功"（VERIFIED 依据）
                val readOkPerSector = IntArray(ChameleonSession.MF1_SECTOR_COUNT)
                // 每扇区实际使用的密钥类型：升级 VERIFIED 时只标实际完成读取的位
                val usedTypePerSector = arrayOfNulls<KeyType>(ChameleonSession.MF1_SECTOR_COUNT)
                for (sector in 0 until ChameleonSession.MF1_SECTOR_COUNT) {
                    val sectorKeys = state.sectors[sector]
                    // 优先 KeyA（KeyA 命中时固件通常已顺带恢复 KeyB）
                    val useKeyA = sectorKeys.keyA.isFound
                    val key = (if (useKeyA) sectorKeys.keyA else sectorKeys.keyB).key
                    if (key == null) continue // 未破解扇区：整扇区保持未知（XX）
                    val keyType = if (useKeyA) KeyType.A else KeyType.B
                    usedTypePerSector[sector] = keyType
                    for (i in 0 until ChameleonSession.MF1_BLOCKS_PER_SECTOR) {
                        val block = sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR + i
                        try {
                            val data = s.readBlock(block, keyType, key)
                            val blockOffset = block * ChameleonSession.MF1_BLOCK_SIZE
                            data.copyInto(bytes, blockOffset)
                            Arrays.fill(
                                known,
                                blockOffset,
                                blockOffset + ChameleonSession.MF1_BLOCK_SIZE,
                                true,
                            )
                            readOkPerSector[sector]++
                        } catch (e: ChameleonStatusException) {
                            // 卡片离开：中断整个 dump；其余（如访问位限制）仅记块失败
                            if (e.status == ChameleonStatus.HF_TAG_NO.raw) throw e
                            failedBlocks++
                            appendLog(LogKind.ERROR, "块 $block 读取失败：${e.statusDescription}")
                        }
                    }
                }
                // trailer 块的 KeyA 对读卡器永远返回全 0，KeyB 在常规访问位配置下
                // 同样不可读（Mifare Classic 安全设计）。密钥区不以读出值为准，
                // 按已恢复密钥矩阵回填：已破解的密钥覆盖对应区域并标记已知，
                // 未破解的区域标记未知（XX）；访问位区保留真实读出值，读出全零
                // 视为读取失败，同样标记未知。
                for (sector in 0 until ChameleonSession.MF1_SECTOR_COUNT) {
                    val trailerOffset =
                        (sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR +
                            ChameleonSession.MF1_TRAILER_BLOCK_IN_SECTOR) *
                            ChameleonSession.MF1_BLOCK_SIZE
                    val keys = state.sectors[sector]
                    val keyA = keys.keyA.key
                    if (keyA != null) keyA.copyInto(bytes, trailerOffset)
                    Arrays.fill(
                        known,
                        trailerOffset,
                        trailerOffset + ChameleonSession.MF1_KEY_SIZE,
                        keyA != null,
                    )
                    val keyB = keys.keyB.key
                    if (keyB != null) {
                        keyB.copyInto(
                            bytes,
                            trailerOffset + ChameleonSession.MF1_TRAILER_KEY_B_OFFSET,
                        )
                    }
                    Arrays.fill(
                        known,
                        trailerOffset + ChameleonSession.MF1_TRAILER_KEY_B_OFFSET,
                        trailerOffset + ChameleonSession.MF1_BLOCK_SIZE,
                        keyB != null,
                    )
                    val accessFrom = trailerOffset + ChameleonSession.MF1_TRAILER_ACCESS_OFFSET
                    val accessTo = trailerOffset + ChameleonSession.MF1_TRAILER_KEY_B_OFFSET
                    if (bytes.copyOfRange(accessFrom, accessTo).all { it == 0.toByte() }) {
                        Arrays.fill(known, accessFrom, accessTo, false)
                    }
                }

                // 全扇区读取成功（4/4 块）的密钥位升级 VERIFIED：矩阵换蓝色对号，
                // 直观区分"已恢复但未经全量验证"与"dump 数据完整可信"
                _readerState.update { st ->
                    st.copy(sectors = st.sectors.mapIndexed { sector, sk ->
                        val used = usedTypePerSector[sector] ?: return@mapIndexed sk
                        if (readOkPerSector[sector] != ChameleonSession.MF1_BLOCKS_PER_SECTOR) sk
                        else sk.copy(
                            keyA = if (used == KeyType.A && sk.keyA.isFound) sk.keyA.copy(status = KeyStatus.VERIFIED) else sk.keyA,
                            keyB = if (used == KeyType.B && sk.keyB.isFound) sk.keyB.copy(status = KeyStatus.VERIFIED) else sk.keyB,
                        )
                    })
                }

                val saved = dumpRepository.save(tag, content)
                _readerState.update { it.copy(dumpLocation = saved.fileName) }
                appendLog(
                    LogKind.INFO,
                    if (failedBlocks == 0) {
                        "Dump 已存入卡片库：${saved.fileName}（卡片管理页可写入槽）"
                    } else {
                        "Dump 已存入卡片库：${saved.fileName}（${failedBlocks} 个块读取失败，标记为 XX）"
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

    /** 清除最近一次成功提示（UI 展示 Snackbar 后回调） */
    fun consumeLastSuccess() {
        _readerState.update { it.copy(lastSuccess = null) }
    }

    /**
     * 把卡片库中的 dump 写入设备模拟卡（对齐 CLI `hf mf eload` + 反碰撞数据）：
     * 1. 切换设备到模拟卡模式；
     * 2. 设置反碰撞数据（UID/ATQA/SAK 与原卡一致）；
     * 3. 分块写入全部块数据（单帧上限 31 块，1K 卡 4 帧完成）。
     *
     * 写入当前激活卡槽；完成后设备立即模拟这张卡，成功经 lastSuccess
     * 通知 UI 弹出 Snackbar。未知字节（XX，见 [DumpContent]）按 0x00
     * 写入设备。
     *
     * @return false 表示未启动（未连接 / 设备忙 / 数据异常），原因见日志与 lastError
     */
    fun writeDumpToEmulator(dump: DumpCard): Boolean {
        val s = sessionProvider() ?: run {
            _readerState.update { it.copy(lastError = "设备未连接") }
            return false
        }
        if (_readerState.value.phase != ReaderPhase.Idle) {
            _readerState.update { it.copy(lastError = "设备忙（读卡或写入进行中），请稍后再试") }
            return false
        }
        val content = dumpRepository.read(dump.fileName) ?: run {
            _readerState.update { it.copy(lastError = "读取 dump 文件失败") }
            return false
        }
        if (content.bytes.size != ChameleonSession.MF1_SECTOR_COUNT *
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
        scope.launch {
            try {
                appendLog(LogKind.INFO, "写入模拟卡：UID=${dump.uidHex}（${content.blockCount} 块）")
                ensureEmulatorMode(s)

                // 反碰撞数据让模拟卡的卡号与原卡一致（ATS 留空）
                s.setAntiCollData(uid, atqa, sak)
                appendLog(LogKind.INFO, " - 反碰撞数据已设置（UID/ATQA/SAK）")

                // 开启 mfkey32 认证日志（对齐 CLI `hf mf econfig --enable-log`），
                // 供模拟卡被认证后离线恢复未知扇区密钥
                s.setDetectionEnable(true)
                appendLog(LogKind.INFO, " - mfkey32 认证日志已开启")

                // 分块写入：每帧 16 块（1K 卡 4 帧），进度随帧输出
                val blocks = content.bytes
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
                _readerState.update { it.copy(lastSuccess = "已写入模拟卡：UID=${dump.uidHex}，设备正在模拟该卡") }
            } catch (e: Exception) {
                handleReaderError("写入模拟卡失败", e)
            } finally {
                _readerState.update { it.copy(phase = ReaderPhase.Idle) }
            }
        }
        return true
    }

    /**
     * 加载 dump 卡片到读卡页（不依赖设备在场）：元数据（UID/SAK/ATQA/
     * PRNG）来自文件名（PRNG 随 dump 保存，见 [PrngType.fileNameCode]），
     * trailer 中的密钥回填密钥矩阵——dump 时已破解的密钥按矩阵写入 eml，
     * 未破解区段记 XX（未知），加载时反向推导：区段全部已知即已恢复。
     * 加载后可直接对剩余扇区继续字典 / Nested 攻击，无需重新读卡。
     */
    fun loadDumpToReader(dump: DumpCard) {
        if (_readerState.value.phase != ReaderPhase.Idle) {
            _readerState.update { it.copy(lastError = "设备忙，请稍后再试") }
            return
        }
        val content = dumpRepository.read(dump.fileName) ?: run {
            _readerState.update { it.copy(lastError = "读取 dump 文件失败") }
            return
        }
        val uid = HexUtils.parse(dump.uidHex)
        val atqa = HexUtils.parse(dump.atqaHex)
        val sak = HexUtils.parse(dump.sakHex)?.firstOrNull()?.toInt()
        if (uid == null || atqa == null || sak == null) {
            _readerState.update { it.copy(lastError = "dump 元数据解析失败") }
            return
        }

        // 密钥矩阵回填：逐扇区解析 trailer 的 KeyA/KeyB 区段
        val sectors = List(ChameleonSession.MF1_SECTOR_COUNT) { sector ->
            val trailerOffset = (sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR +
                ChameleonSession.MF1_TRAILER_BLOCK_IN_SECTOR) * ChameleonSession.MF1_BLOCK_SIZE

            /** 区段 [from, from+size) 内字节是否全部已知（即 dump 时已破解回填） */
            fun keyOf(from: Int, size: Int): ByteArray? {
                val start = trailerOffset + from
                val end = start + size
                if (end > content.bytes.size) return null
                if ((0 until size).any { !content.known[start + it] }) return null
                return content.bytes.copyOfRange(start, end)
            }
            SectorKeys(
                sector = sector,
                keyA = keyOf(0, ChameleonSession.MF1_KEY_SIZE)
                    ?.let { KeyState(KeyStatus.FOUND, it) } ?: KeyState.UNKNOWN_STATE,
                keyB = keyOf(ChameleonSession.MF1_TRAILER_KEY_B_OFFSET, ChameleonSession.MF1_KEY_SIZE)
                    ?.let { KeyState(KeyStatus.FOUND, it) } ?: KeyState.UNKNOWN_STATE,
            )
        }
        val foundCount = countFound(sectors)
        val tag = TagInfo(uid = uid, atqa = atqa, sak = sak, ats = byteArrayOf(), prng = dump.prng)
        _readerState.update {
            it.copy(tagInfo = tag, sectors = sectors, lastError = null)
        }
        appendLog(
            LogKind.INFO,
            "已加载卡片 ${dump.fileName}：UID=${dump.uidHex} PRNG=${dump.prng.label}，回填 $foundCount 个密钥位",
        )
        _readerState.update { it.copy(lastSuccess = "已加载 ${dump.uidHex}（回填 $foundCount 个密钥位），可继续破解剩余扇区") }
    }

    // ------------------------------------------------------------------
    // mfkey32
    // ------------------------------------------------------------------

    /**
     * mfkey32 攻击（对齐根目录 dump_mf1_elog.py 的编排）：下载模拟卡认证
     * 日志，按 (uid, block, key) 分组对记录两两组合离线求解（NDK
     * mfkey32Recover，移植自 mfkey32v2.c），命中密钥写入矩阵并做密钥复用
     * 检查。日志输出格式与 python 脚本一致（明细表 / 破解 / 结果三段）。
     *
     * 前置：「写入槽」已开启认证日志（SET_DETECTION_ENABLE），模拟卡被
     * 读卡器认证后固件记录四元组 (uid, nt, nr, ar)。同组 ≥2 条记录才有
     * 足够信息恢复密钥；嵌套认证记录（isNested，NT 为密文）不满足
     * mfkey32 的明文 NT 假设，破解时过滤。
     */
    fun mfkey32() {
        val s = sessionProvider() ?: run {
            _readerState.update { it.copy(lastError = "设备未连接") }
            return
        }
        if (_readerState.value.phase != ReaderPhase.Idle) return
        _readerState.update { it.copy(phase = ReaderPhase.Mfkey32, lastError = null) }
        scope.launch {
            try {
                // 1. 下载全部认证日志（单帧约 28 条，按返回条数推进索引）
                val count = s.getDetectionCount()
                appendLog(LogKind.INFO, "检测日志数量: $count")
                if (count == 0) {
                    _readerState.update { it.copy(lastError = "无认证日志：请先用「写入槽」模拟该卡并让读卡器认证") }
                    return@launch
                }
                val logs = mutableListOf<AuthLog>()
                while (logs.size < count) {
                    val batch = s.getDetectionLogs(logs.size)
                    if (batch.isEmpty()) {
                        appendLog(LogKind.INFO, "索引 ${logs.size} 之后无日志（下载中断）")
                        break
                    }
                    logs.addAll(batch)
                }
                appendLog(LogKind.INFO, "共下载 ${logs.size} 条:")

                // 2. 读卡页已有卡时只破解该卡的日志，其余卡的旧日志忽略
                val tagUid = _readerState.value.tagInfo?.uidValue ?: 0L
                val usable = if (tagUid != 0L) logs.filter { it.uid == tagUid } else logs
                if (usable.isEmpty()) {
                    _readerState.update { it.copy(lastError = "认证日志与当前卡片 UID 不符") }
                    return@launch
                }

                // 3. 认证记录明细（列格式对齐 python 脚本输出）
                appendLog(
                    LogKind.INFO,
                    "%-4s%-7s%-5s%-8s%-11s%-11s%-11s%-11s".format(
                        "#", "block", "key", "nested", "uid", "nt", "nr", "ar",
                    ),
                )
                usable.forEachIndexed { i, log ->
                    appendLog(
                        LogKind.INFO,
                        "%-4d%-7d%-5s%-8s%-11s%-11s%-11s%-11s".format(
                            i,
                            log.block,
                            if (log.isKeyB) "B" else "A",
                            log.isNested,
                            "%08X".format(log.uid),
                            "%08X".format(log.nt),
                            "%08X".format(log.nr),
                            "%08X".format(log.ar),
                        ),
                    )
                }

                // 4. 分组破解：过滤嵌套认证与超 1K 卡块范围的记录后，
                //    按 (uid, block, key) 分组，组内 ≥2 条记录才可求解
                val groups = usable
                    .filter {
                        !it.isNested && it.block <
                            ChameleonSession.MF1_SECTOR_COUNT * ChameleonSession.MF1_BLOCKS_PER_SECTOR
                    }
                    .groupBy { Triple(it.uid, it.block, it.isKeyB) }
                appendLog(LogKind.INFO, "========== 破解 ==========")
                val resultLines = mutableListOf<String>()
                for ((groupKey, recs) in groups) {
                    val (uid, block, isKeyB) = groupKey
                    val keyLabel = if (isKeyB) "B" else "A"
                    val keyType = if (isKeyB) KeyType.B else KeyType.A
                    val combos = recs.size * (recs.size - 1) / 2
                    appendLog(
                        LogKind.INFO,
                        "uid=%08X block=%d key=%s: %d 条记录, %d 个组合".format(uid, block, keyLabel, recs.size, combos),
                    )
                    if (combos == 0) {
                        appendLog(LogKind.INFO, "  > 不足 2 条记录, 无法破解")
                        continue
                    }

                    val nts = LongArray(recs.size) { recs[it].nt }
                    val nrs = LongArray(recs.size) { recs[it].nr }
                    val ars = LongArray(recs.size) { recs[it].ar }
                    val keys = solveWithTiming {
                        ChameleonNative.mfkey32Recover(uid, nts, nrs, ars)
                    }
                    // 每个命中密钥：统计复核通过记录数（对齐 py 版复核输出），
                    // 写入矩阵并触发密钥复用检查
                    for (keyValue in keys) {
                        val key = longToKey(keyValue)
                        val keyHex = key.joinToString("") { "%02X".format(it) }
                        val matches = recs.count {
                            ChameleonNative.mfkey32Verify(uid, it.nt, it.nr, it.ar, keyValue)
                        }
                        appendLog(LogKind.INFO, "  > 找到密钥: $keyHex (复核通过 $matches/${recs.size} 条记录)")
                        resultLines.add("uid=%08X block=%d key=%s => $keyHex".format(uid, block, keyLabel))
                        applyFoundKey(s, recs.first().sector, keyType, key)
                    }
                    if (keys.isEmpty()) {
                        appendLog(LogKind.INFO, "  > 未找到密钥 (建议: 让读卡器对该块多发起几次认证后重新采集)")
                    }
                }

                // 5. 结果汇总（对齐 python 脚本第三段输出）
                if (resultLines.isEmpty()) {
                    _readerState.update {
                        it.copy(lastError = "mfkey32 未找到密钥（同组至少 2 条认证记录，可让读卡器多认证几次后重试）")
                    }
                } else {
                    appendLog(LogKind.INFO, "========== 结果 ==========")
                    resultLines.forEach { appendLog(LogKind.INFO, it) }
                    _readerState.update { it.copy(lastSuccess = "mfkey32 恢复 ${resultLines.size} 个密钥") }
                }
            } catch (e: Exception) {
                handleReaderError("mfkey32 破解失败", e)
            } finally {
                _readerState.update { it.copy(phase = ReaderPhase.Idle) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 断开连接时由 MainViewModel 调用：清空读卡页状态（卡已不可访问） */
    fun reset() {
        _readerState.value = ReaderState()
    }

    /** 确保设备处于读卡器模式；依据缓存的工作模式避免多余的查询/切换 */
    private suspend fun ensureReaderMode(s: ChameleonSession) {
        when (deviceMode.value) {
            DeviceMode.READER -> return

            DeviceMode.EMULATOR -> {
                appendLog(LogKind.INFO, "设备处于模拟卡模式，切换为读卡器模式…")
                s.changeDeviceMode(DeviceMode.READER)
            }

            DeviceMode.UNKNOWN -> {
                val mode = s.getDeviceMode()
                if (mode == DeviceMode.READER) {
                    deviceMode.value = mode
                    return
                }
                appendLog(LogKind.INFO, "设备处于模拟卡模式，切换为读卡器模式…")
                s.changeDeviceMode(DeviceMode.READER)
            }
        }
        deviceMode.value = DeviceMode.READER
        appendLog(LogKind.INFO, "已切换为读卡器模式")
    }

    /** 确保设备处于模拟卡模式（写入 dump 前调用）；与 [ensureReaderMode] 对称 */
    private suspend fun ensureEmulatorMode(s: ChameleonSession) {
        when (deviceMode.value) {
            DeviceMode.EMULATOR -> return

            DeviceMode.READER -> {
                appendLog(LogKind.INFO, "切换为模拟卡模式…")
                s.changeDeviceMode(DeviceMode.EMULATOR)
            }

            DeviceMode.UNKNOWN -> {
                val mode = s.getDeviceMode()
                if (mode == DeviceMode.EMULATOR) {
                    deviceMode.value = mode
                    return
                }
                appendLog(LogKind.INFO, "切换为模拟卡模式…")
                s.changeDeviceMode(DeviceMode.EMULATOR)
            }
        }
        deviceMode.value = DeviceMode.EMULATOR
        appendLog(LogKind.INFO, "已切换为模拟卡模式")
    }

    private suspend fun handleReaderError(action: String, e: Exception) {
        val message = "$action：${describeError(e)}"
        appendLog(LogKind.ERROR, message)
        _readerState.update { it.copy(lastError = message) }
    }

    /** 48bit 密钥数值 → 6 字节大端（与卡上存储 / 协议传输的字节序一致） */
    private fun longToKey(value: Long): ByteArray =
        ByteArray(ChameleonSession.MF1_KEY_SIZE) { i ->
            ((value shr ((ChameleonSession.MF1_KEY_SIZE - 1 - i) * 8)) and 0xFF).toByte()
        }
}

/** 异常 → 用户可读描述（协议状态 / BLE 通信 / 其他，连接与读卡流程共用） */
internal fun describeError(e: Exception): String = when (e) {
    is ChameleonStatusException -> e.statusDescription
    is ChameleonBleException -> e.message ?: "通信异常"
    else -> e.message ?: "未知错误"
}
