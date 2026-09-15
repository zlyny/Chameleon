package com.example.chameleon.reader

import android.os.SystemClock
import com.example.chameleon.ble.ChameleonBleException
import com.example.chameleon.device.AuthLog
import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.device.ChameleonStatusException
import com.example.chameleon.device.DeviceMode
import com.example.chameleon.device.DeviceModeStore
import com.example.chameleon.data.DumpCard
import com.example.chameleon.data.DumpContent
import com.example.chameleon.data.DumpRepository
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
import com.example.chameleon.UiMessage
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

    /** 最近一次 dump 的保存位置 */
    val dumpLocation: String? = null,
)

/**
 * 读卡流程控制器：从 MainViewModel 拆出的读卡 / 破解 / Dump / 写模拟卡 /
 * mfkey32 全部设备业务流程。持有 [ReaderState] 状态流，UI 仍经
 * MainViewModel 的转发属性观察与触发（UI 层不直接接触本类）。
 *
 * 依赖经构造注入：会话提供方（连接生命周期归 MainViewModel）、工作模式
 * 缓存（DeviceModeStore，与主界面模式图标共享同一份）、dump 卡片库、
 * 日志回调、提示回调（[notify]，与页面级反馈共用同一条 Snackbar 通道）。
 * 协程运行在注入的 scope（viewModelScope，主线程调度器）上，
 * 状态流更新无需额外线程同步。
 *
 * ---------------------------------------------------------------------------
 * 代码地图（本文件近千行，按下面顺序读不容易迷路）
 *
 * 对外入口（每个都遵循同一套骨架，见下方「统一的流程骨架」）：
 *   readCard()            读卡号 + 测 PRNG + 判定 StaticNested 代次
 *   recoverKeys()         字典攻击（13 个内置弱密钥）
 *   recoverKeyByNested()  按 PRNG 分派 StaticNested / Nested 攻击
 *   dumpCard()            用已恢复密钥读全卡 → 存卡片库
 *   writeDumpToEmulator() 卡片 → 写入模拟卡槽
 *   loadDumpToReader()    卡片库 → 回填读卡页矩阵
 *   mfkey32()             下载认证日志离线破解
 *
 * 公共辅助：
 *   launchExclusive()          ← 流程骨架（守卫 + try/catch/finally），新增流程走它
 *   pickKnownKey / isKeyMissing / mergeSectorKeys / countFound  ← 密钥矩阵相关
 *   reuseRecoveredKey()       ← 命中一个密钥后顺带试探其余扇区
 *   ensureReaderMode / ensureEmulatorMode  ← 设备模式切换（带缓存）
 *   longToKey()               ← 48bit 数值 ↔ 6 字节密钥
 *
 * **统一的流程骨架**：全部流程经 [launchExclusive] 启动，不要照抄手写——
 * 它依次完成「取 session → 拒绝重入 → 前置校验 → 置 phase → 启动协程」，
 * 并且 `try/catch/finally` 只在其中写一次（`finally` 复位 phase 漏掉会让
 * 界面永久停在「进行中」，四个按钮全灰）。
 *
 *   ⚠️ 不要在协程外做耗时的事（文件 IO 要切 Dispatchers.IO）。
 */
class ReaderFlowController(
    private val scope: CoroutineScope,
    private val sessionProvider: () -> ChameleonSession?,
    private val deviceModeStore: DeviceModeStore,
    private val dumpRepository: DumpRepository,
    private val appendLog: (LogKind, String) -> Unit,
    private val notify: (UiMessage) -> Unit,
) {

    private val _readerState = MutableStateFlow(ReaderState())
    val readerState: StateFlow<ReaderState> = _readerState.asStateFlow()

    // ------------------------------------------------------------------
    // 流程骨架
    // ------------------------------------------------------------------

    /**
     * 启动一个独占流程（同一时刻只允许一个在途流程，设备是串行模型）。
     *
     * 依次：取 session（拿不到报 [ReaderError.NotConnected]）→ `phase != Idle`
     * 时走 [onBusy] 并拒绝 → [precondition] 返回非 null 时报该错误 →
     * 置 phase → 启动协程，协程内的异常统一走 [handleReaderError]，
     * **finally 复位 phase 只在这里写一次**。
     *
     * @param action 失败时写入日志的前缀，如「读卡失败」
     * @param block 流程主体；第二个参数是启动瞬间抓取的状态快照（避免主体里
     *   重复读 `_readerState.value` 又要处理可空性）
     * @return true 表示已启动（false 为被守卫拦下，原因已上报）
     */
    private fun launchExclusive(
        phase: ReaderPhase,
        action: String,
        onBusy: () -> Unit = {},
        precondition: (ReaderState) -> ReaderError? = { null },
        block: suspend (session: ChameleonSession, state: ReaderState) -> Unit,
    ): Boolean {
        val session = sessionProvider() ?: run {
            notify(UiMessage.Error(ReaderError.NotConnected))
            return false
        }
        val state = _readerState.value
        if (state.phase != ReaderPhase.Idle) {
            onBusy()
            return false
        }
        precondition(state)?.let { error ->
            notify(UiMessage.Error(error))
            return false
        }

        _readerState.update { it.copy(phase = phase) }
        scope.launch {
            try {
                block(session, state)
            } catch (e: Exception) {
                handleReaderError(action, e)
            } finally {
                _readerState.update { it.copy(phase = ReaderPhase.Idle) }
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // 读卡
    // ------------------------------------------------------------------

    /**
     * 读卡：确保读卡器模式 -> 扫描 14A 标签 -> 检测 Mifare Classic 支持 ->
     * 检测 PRNG（Static 卡进一步判定 StaticNested 漏洞代次 GEN1/GEN2）。
     * 成功后初始化 16 个扇区的密钥状态矩阵。
     */
    fun readCard() {
        launchExclusive(ReaderPhase.Reading, "读卡失败") { s, _ ->
            ensureReaderMode(s)
            var tag = s.scan14a()
            if (!s.detectMf1Support()) {
                _readerState.update { state ->
                    state.copy(tagInfo = tag, sectors = emptyList())
                }
                notify(UiMessage.Error(ReaderError.MifareNotSupported))
                appendLog(LogKind.INFO, "读到标签 UID=${tag.uidHex}，但不支持 Mifare Classic")
                return@launchExclusive
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
        val keys = KeyDictionary.keys
        launchExclusive(
            phase = ReaderPhase.Recovering,
            action = "字典破解失败",
            precondition = {
                if (it.tagInfo == null || it.sectors.isEmpty()) ReaderError.CardNotRead else null
            },
        ) { s, state ->
            val before = state.sectors
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
        launchExclusive(
            phase = ReaderPhase.Recovering,
            action = "Nested 攻击失败",
            precondition = { state ->
                when (state.tagInfo?.prng) {
                    null -> ReaderError.CardNotRead
                    // Static/Weak 分别走对应算法；Hard 需 hardnested（后续版本）
                    PrngType.STATIC, PrngType.WEAK -> {
                        // 已知密钥：优先取目标扇区之外的已恢复密钥（跨扇区嵌套采集更稳），
                        // 仅目标扇区有已知密钥时回落使用（同扇区跨密钥位认证亦可）
                        if (pickKnownKey(state.sectors, targetSector = sector) == null) {
                            ReaderError.NoKnownKey
                        } else {
                            null
                        }
                    }
                    PrngType.HARD -> ReaderError.HardPrngUnsupported
                    else -> ReaderError.PrngUnknown
                }
            },
        ) { s, state ->
            // 前置条件已在上面校验过，这里取到的必然非空（用早返回避免 !!）
            val tag = state.tagInfo ?: return@launchExclusive
            val known = pickKnownKey(state.sectors, targetSector = sector) ?: return@launchExclusive

            val blockKnown =
                known.first * ChameleonSession.MF1_BLOCKS_PER_SECTOR +
                    ChameleonSession.MF1_TRAILER_BLOCK_IN_SECTOR
            val blockTarget =
                sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR +
                    ChameleonSession.MF1_TRAILER_BLOCK_IN_SECTOR
            val typeLabel = keyType.name

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
                            return@launchExclusive
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
                notify(UiMessage.Error(ReaderError.NestedMiss))
                return@launchExclusive
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
                    return@launchExclusive
                }
            }
            appendLog(LogKind.ERROR, " - 候选密钥全部验证失败，可再次点击重试")
            notify(UiMessage.Error(ReaderError.NestedVerifyFailed))
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

    /**
     * 把某扇区实际用于读取的密钥位升级为 VERIFIED（矩阵由绿勾换蓝色对号）：
     * 该密钥确实能完整访问该扇区，dump 出的数据完整可信。
     *
     * 只标**实际完成读取的那一位**（dump 用的是 A 就只升级 A），不连带另一位。
     */
    private fun markKeyVerified(sector: Int, keyType: KeyType) {
        _readerState.update { st ->
            st.copy(sectors = st.sectors.mapIndexed { i, sk ->
                if (i != sector) sk
                else sk.copy(
                    keyA = if (keyType == KeyType.A && sk.keyA.isFound) {
                        sk.keyA.copy(status = KeyStatus.VERIFIED)
                    } else {
                        sk.keyA
                    },
                    keyB = if (keyType == KeyType.B && sk.keyB.isFound) {
                        sk.keyB.copy(status = KeyStatus.VERIFIED)
                    } else {
                        sk.keyB
                    },
                )
            })
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
        if (deviceModeStore.mode.value != DeviceMode.READER) {
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
     * 某扇区 4 块全部读取成功时，所用密钥位**立刻**升级为 VERIFIED（密钥矩阵
     * 由绿勾换蓝色对号）——不必等整卡跑完，因此 dump 过程中矩阵本身就是进度条。
     * 含义：该密钥确实能完整访问该扇区，dump 出的数据完整可信。
     */
    fun dumpCard() {
        launchExclusive(
            phase = ReaderPhase.Dumping,
            action = "Dump 失败",
            precondition = {
                when {
                    it.tagInfo == null -> ReaderError.CardNotRead
                    it.sectors.none { sk -> sk.keyA.isFound || sk.keyB.isFound } -> ReaderError.NoKnownKey
                    else -> null
                }
            },
            block = { s, state ->
                val tag = state.tagInfo ?: return@launchExclusive

                // 初始全部未知（XX）：只有成功读出或回填的字节才标记已知
                val content = DumpContent.allUnknown(
                    ChameleonSession.MF1_SECTOR_COUNT *
                        ChameleonSession.MF1_BLOCKS_PER_SECTOR *
                        ChameleonSession.MF1_BLOCK_SIZE,
                )
                val bytes = content.bytes
                val known = content.known
                var failedBlocks = 0
                for (sector in 0 until ChameleonSession.MF1_SECTOR_COUNT) {
                    val sectorKeys = state.sectors[sector]
                    // 优先 KeyA（KeyA 命中时固件通常已顺带恢复 KeyB）
                    val useKeyA = sectorKeys.keyA.isFound
                    val key = (if (useKeyA) sectorKeys.keyA else sectorKeys.keyB).key
                    if (key == null) continue // 未破解扇区：整扇区保持未知（XX）
                    val keyType = if (useKeyA) KeyType.A else KeyType.B
                    var readOk = 0
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
                            readOk++
                        } catch (e: ChameleonStatusException) {
                            // 卡片离开：中断整个 dump；其余（如访问位限制）仅记块失败
                            if (e.status == ChameleonStatus.HF_TAG_NO.raw) throw e
                            failedBlocks++
                            appendLog(LogKind.ERROR, "块 $block 读取失败：${e.statusDescription}")
                        }
                    }
                    // 4/4 块全部读成功 → 立刻把所用密钥位升级 VERIFIED（矩阵换蓝色对号），
                    // 不必等整卡跑完：dump 期间可据此实时看到进度
                    if (readOk == ChameleonSession.MF1_BLOCKS_PER_SECTOR) {
                        markKeyVerified(sector, keyType)
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

                // 卡片库文件写入属 IO，切到 IO 线程避免在 UI 线程做磁盘访问
                val saved = withContext(Dispatchers.IO) { dumpRepository.save(tag, content) }
                _readerState.update { it.copy(dumpLocation = saved.fileName) }
                appendLog(
                    LogKind.INFO,
                    if (failedBlocks == 0) {
                        "Dump 已存入卡片库：${saved.fileName}（卡片管理页可写入槽）"
                    } else {
                        "Dump 已存入卡片库：${saved.fileName}（${failedBlocks} 个块读取失败，标记为 XX）"
                    },
                )
            },
        )
    }

    /**
     * 把卡片库中的 dump 写入设备模拟卡（对齐 CLI `hf mf eload` + 反碰撞数据）：
     * 1. 切换设备到模拟卡模式；
     * 2. 设置反碰撞数据（UID/ATQA/SAK 与原卡一致）；
     * 3. 分块写入全部块数据（单帧上限 31 块，1K 卡 4 帧完成）。
     *
     * 写入当前激活卡槽；完成后设备立即模拟这张卡，成功提示经 [notify] 弹出
     * Snackbar。未知字节（XX，见 [DumpContent]）按 0x00 写入设备。
     *
     * 未启动的各种原因（未连接 / 设备忙 / 元数据非法）都会经 [notify] 上报具体
     * 文案，调用方无需再补一条提示。
     */
    fun writeDumpToEmulator(dump: DumpCard) {
        // 元数据解析放在启动流程**之前**：非法时直接返回，不占用 phase
        // （放在协程里的话按钮会先闪一下「写入中…」再复位）
        val uid = HexUtils.parse(dump.uidHex)
        val atqa = HexUtils.parse(dump.atqaHex)
        val sak = HexUtils.parse(dump.sakHex)
        if (uid == null || atqa == null || sak == null) {
            notify(UiMessage.Error(ReaderError.DumpMetaInvalid))
            return
        }
        launchExclusive(
            phase = ReaderPhase.WritingEmu,
            action = "写入模拟卡失败",
            onBusy = { notify(UiMessage.Error(ReaderError.DeviceBusy)) },
            block = { s, _ ->
                // 卡片库文件读取属 IO，切到 IO 线程（原本在 UI 线程同步读）
                val content = withContext(Dispatchers.IO) { dumpRepository.read(dump.fileName) }
                if (content == null) {
                    notify(UiMessage.Error(ReaderError.DumpReadFailed))
                    return@launchExclusive
                }
                if (content.bytes.size != ChameleonSession.MF1_SECTOR_COUNT *
                    ChameleonSession.MF1_BLOCKS_PER_SECTOR * ChameleonSession.MF1_BLOCK_SIZE
                ) {
                    notify(UiMessage.Error(ReaderError.DumpNot1K))
                    return@launchExclusive
                }
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
                notify(UiMessage.Text("已写入模拟卡：UID=${dump.uidHex}，设备正在模拟该卡"))
            },
        )
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
            notify(UiMessage.Error(ReaderError.DeviceBusy))
            return
        }
        val uid = HexUtils.parse(dump.uidHex)
        val atqa = HexUtils.parse(dump.atqaHex)
        val sak = HexUtils.parse(dump.sakHex)?.firstOrNull()?.toInt()
        if (uid == null || atqa == null || sak == null) {
            notify(UiMessage.Error(ReaderError.DumpMetaInvalid))
            return
        }
        // 卡片库文件读取属 IO，放到协程里切 IO 线程执行（原本在 UI 线程同步读）
        scope.launch {
            val content = withContext(Dispatchers.IO) { dumpRepository.read(dump.fileName) }
            if (content == null) {
                notify(UiMessage.Error(ReaderError.DumpReadFailed))
                return@launch
            }
            applyLoadedDump(dump, uid, atqa, sak, content)
        }
    }

    /** [loadDumpToReader] 的落地部分：trailer 密钥回填密钥矩阵并更新状态 */
    private fun applyLoadedDump(
        dump: DumpCard,
        uid: ByteArray,
        atqa: ByteArray,
        sak: Int,
        content: DumpContent,
    ) {
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
        _readerState.update { it.copy(tagInfo = tag, sectors = sectors) }
        appendLog(
            LogKind.INFO,
            "已加载卡片 ${dump.fileName}：UID=${dump.uidHex} PRNG=${dump.prng.label}，回填 $foundCount 个密钥位",
        )
        notify(UiMessage.Text("已加载 ${dump.uidHex}（回填 $foundCount 个密钥位），可继续破解剩余扇区"))
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
        launchExclusive(
            phase = ReaderPhase.Mfkey32,
            action = "mfkey32 破解失败",
            block = { s, _ ->
                // 1. 下载全部认证日志（单帧约 28 条，按返回条数推进索引）
                val count = s.getDetectionCount()
                appendLog(LogKind.INFO, "检测日志数量: $count")
                if (count == 0) {
                    notify(UiMessage.Error(ReaderError.NoAuthLog))
                    return@launchExclusive
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
                    notify(UiMessage.Error(ReaderError.AuthLogUidMismatch))
                    return@launchExclusive
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
                        // mfkey32Verify 是 native Crypto1 计算，逐条复核必须离开 UI 线程
                        // （与 solveWithTiming 同理，只是这里按命中密钥循环调用）
                        val matches = withContext(Dispatchers.Default) {
                            recs.count {
                                ChameleonNative.mfkey32Verify(uid, it.nt, it.nr, it.ar, keyValue)
                            }
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
                    notify(UiMessage.Error(ReaderError.Mfkey32NoKey))
                } else {
                    appendLog(LogKind.INFO, "========== 结果 ==========")
                    resultLines.forEach { appendLog(LogKind.INFO, it) }
                    notify(UiMessage.Text("mfkey32 恢复 ${resultLines.size} 个密钥"))
                }
            },
        )
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
        when (deviceModeStore.mode.value) {
            DeviceMode.READER -> return

            DeviceMode.EMULATOR -> {
                appendLog(LogKind.INFO, "设备处于模拟卡模式，切换为读卡器模式…")
                s.changeDeviceMode(DeviceMode.READER)
            }

            DeviceMode.UNKNOWN -> {
                val mode = s.getDeviceMode()
                if (mode == DeviceMode.READER) {
                    deviceModeStore.update(mode)
                    return
                }
                appendLog(LogKind.INFO, "设备处于模拟卡模式，切换为读卡器模式…")
                s.changeDeviceMode(DeviceMode.READER)
            }
        }
        deviceModeStore.update(DeviceMode.READER)
        appendLog(LogKind.INFO, "已切换为读卡器模式")
    }

    /** 确保设备处于模拟卡模式（写入 dump 前调用）；与 [ensureReaderMode] 对称 */
    private suspend fun ensureEmulatorMode(s: ChameleonSession) {
        when (deviceModeStore.mode.value) {
            DeviceMode.EMULATOR -> return

            DeviceMode.READER -> {
                appendLog(LogKind.INFO, "切换为模拟卡模式…")
                s.changeDeviceMode(DeviceMode.EMULATOR)
            }

            DeviceMode.UNKNOWN -> {
                val mode = s.getDeviceMode()
                if (mode == DeviceMode.EMULATOR) {
                    deviceModeStore.update(mode)
                    return
                }
                appendLog(LogKind.INFO, "切换为模拟卡模式…")
                s.changeDeviceMode(DeviceMode.EMULATOR)
            }
        }
        deviceModeStore.update(DeviceMode.EMULATOR)
        appendLog(LogKind.INFO, "已切换为模拟卡模式")
    }

    /** 操作失败：日志记全量，UI 侧只拿到结构化错误（[ReaderError.OperationFailed]） */
    private suspend fun handleReaderError(action: String, e: Exception) {
        val detail = describeError(e)
        appendLog(LogKind.ERROR, "$action：$detail")
        notify(UiMessage.Error(ReaderError.OperationFailed(action, detail)))
    }

    /**
     * 48bit 密钥数值 → 6 字节大端（与卡上存储 / 协议传输的字节序一致）。
     *
     * NDK 求解返回的是 Long（如 0xA0A1A2A3A4A5），要变回卡上的 6 字节。
     * 关键在 `MF1_KEY_SIZE - 1 - i`：**i 越小越要取高位字节**，这就是「大端」。
     * 若写成 `i * 8` 就变成了小端，密钥会整个反过来。
     */
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
