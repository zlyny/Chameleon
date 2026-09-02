package com.example.chameleon.device

import com.example.chameleon.ble.ChameleonBleClient
import com.example.chameleon.protocol.ChameleonCommand
import com.example.chameleon.protocol.ChameleonFrame
import com.example.chameleon.protocol.ChameleonStatus
import java.io.IOException

/** 设备返回非预期状态码时抛出，[statusDescription] 为用户可读的状态描述 */
class ChameleonStatusException(
    val cmd: Int,
    val status: Int,
    val statusDescription: String,
) : IOException(statusDescription)

/**
 * ChameleonUltra 命令编排层：在 BLE 请求-响应之上封装为带类型解析的 suspend API。
 *
 * 各命令的期望 status 各不相同（如设备级命令成功为 0x0068，HF 卡操作成功为 0x0000），
 * 由本层统一校验并抛出携带用户可读信息的 [ChameleonStatusException]。
 *
 * 当前面向 Mifare Classic 1K（16 扇区）实现；2K/4K 支持在 [MF1_SECTOR_COUNT] 扩展。
 */
class ChameleonSession(private val client: ChameleonBleClient) {

    /** 读取设备当前工作模式 */
    suspend fun getDeviceMode(): DeviceMode {
        val resp = request(ChameleonCommand.GET_DEVICE_MODE)
        requireStatus(resp, ChameleonStatus.SUCCESS)
        return if (resp.data.isNotEmpty() && resp.data[0].toInt() != 0) DeviceMode.READER else DeviceMode.EMULATOR
    }

    /** 获取电池信息，返回（电压 mV， 电量百分比） */
    suspend fun getBatteryInfo(): Pair<Int, Int> {
        val resp = request(ChameleonCommand.GET_BATTERY_INFO)
        requireStatus(resp, ChameleonStatus.SUCCESS)
        require(resp.data.size >= 3) { "GET_BATTERY_INFO 响应数据异常" }
        val voltageMv = ((resp.data[0].toInt() and 0xFF) shl 8) or (resp.data[1].toInt() and 0xFF)
        return voltageMv to (resp.data[2].toInt() and 0xFF)
    }

    /** 切换设备工作模式（切换动作耗时较长，超时放宽） */
    suspend fun changeDeviceMode(mode: DeviceMode) {
        val payload = byteArrayOf(if (mode == DeviceMode.READER) 1 else 0)
        val resp = request(ChameleonCommand.CHANGE_DEVICE_MODE, payload, timeoutMs = MODE_SWITCH_TIMEOUT_MS)
        requireStatus(resp, ChameleonStatus.SUCCESS)
    }

    /**
     * 扫描 14A 标签。无卡时抛出 [ChameleonStatusException]（HF_TAG_NO），
     * 由调用方转为用户提示。
     */
    suspend fun scan14a(): TagInfo {
        val resp = request(ChameleonCommand.HF14A_SCAN)
        requireStatus(resp, ChameleonStatus.HF_TAG_OK)

        // DATA: uidLen[1] + uid[N] + atqa[2] + sak[1] + atsLen[1] + ats[M]
        val data = resp.data
        require(data.size >= 5) { "HF14A_SCAN 响应数据异常" }
        val uidLen = data[0].toInt() and 0xFF
        require(data.size >= 5 + uidLen + 0) { "HF14A_SCAN 响应数据异常" }
        val uid = data.copyOfRange(1, 1 + uidLen)
        val atqa = data.copyOfRange(1 + uidLen, 3 + uidLen)
        val sak = data[3 + uidLen].toInt() and 0xFF
        val atsLen = data[4 + uidLen].toInt() and 0xFF
        val ats = data.copyOfRange(5 + uidLen, 5 + uidLen + atsLen)
        return TagInfo(uid = uid, atqa = atqa, sak = sak, ats = ats, prng = PrngType.UNKNOWN)
    }

    /** 检测卡是否支持 Mifare Classic（status=HF_TAG_OK 即支持） */
    suspend fun detectMf1Support(): Boolean {
        val resp = request(ChameleonCommand.MF1_DETECT_SUPPORT)
        return resp.status == ChameleonStatus.HF_TAG_OK.raw
    }

    /** 检测 Mifare Classic PRNG 类型 */
    suspend fun detectPrng(): PrngType {
        val resp = request(ChameleonCommand.MF1_DETECT_PRNG)
        requireStatus(resp, ChameleonStatus.HF_TAG_OK)
        return if (resp.data.isNotEmpty()) PrngType.of(resp.data[0].toInt()) else PrngType.UNKNOWN
    }

    /**
     * 字典攻击：用 [keys] 逐一尝试 [sectorCount] 个扇区的 A/B 密钥位。
     *
     * 请求 DATA：mask[10] + keys[N*6]；固件单帧最多接受 83 个密钥（CLI 同样限制）。
     * mask 是「跳过掩码」（固件 mf1_toolbox_check_keys_of_sectors）：
     * 位=1 表示跳过该密钥位，位=0 表示检查；扇区 s 的 KeyA = byte[s/4] 的
     * bit(7-2*(s%4))，KeyB 为其低位。CLI autopwn 传全 0（检查全部 40 扇区），
     * 此处精确选中扇区 0..sectorCount-1、跳过其余，节省无效扇区的认证耗时。
     * 响应 DATA（490 字节）：found[10] + keys[40][2][6]，其中 keys[s][0]=扇区 s KeyA。
     * 固件优化：KeyA 命中后会顺带读 trailer 中的 KeyB，一次命中可恢复两个密钥。
     *
     * @return 每扇区的 A/B 恢复结果（命中位含密钥，未命中位为 MISSING）
     */
    suspend fun checkKeysOfSectors(
        keys: List<ByteArray>,
        sectorCount: Int = MF1_SECTOR_COUNT,
        timeoutMs: Long = KEY_CHECK_TIMEOUT_MS,
    ): List<SectorKeys> {
        require(keys.isNotEmpty()) { "密钥列表为空" }
        require(keys.size <= MAX_KEYS_PER_REQUEST) { "单次最多 %d 个密钥".format(MAX_KEYS_PER_REQUEST) }
        keys.forEach { require(it.size == MF1_KEY_SIZE) { "密钥长度必须为 6 字节" } }

        // 初始全 1（全部跳过），再把待检查扇区的位清 0
        val mask = ByteArray(MASK_SIZE) { 0xFF.toByte() }
        for (s in 0 until sectorCount) {
            mask[s / 4] = (mask[s / 4].toInt() and (0b11 shl (6 - (s % 4) * 2)).inv()).toByte()
        }
        val payload = mask + keys.reduce { acc, key -> acc + key }

        val resp = request(ChameleonCommand.MF1_CHECK_KEYS_OF_SECTORS, payload, timeoutMs)
        requireStatus(resp, ChameleonStatus.HF_TAG_OK)
        if (resp.data.size != CHECK_KEYS_RESPONSE_LEN) return emptyList()

        val found = resp.data.copyOfRange(0, MASK_SIZE)
        return (0 until sectorCount).map { s ->
            val shift = 6 - (s % 4) * 2
            val bits = (found[s / 4].toInt() shr shift) and 0b11
            val keyBase = MASK_SIZE + s * 2 * MF1_KEY_SIZE
            SectorKeys(
                sector = s,
                keyA = if (bits and 0b10 != 0) {
                    KeyState(KeyStatus.FOUND, resp.data.copyOfRange(keyBase, keyBase + MF1_KEY_SIZE))
                } else {
                    KeyState(KeyStatus.MISSING)
                },
                keyB = if (bits and 0b01 != 0) {
                    KeyState(KeyStatus.FOUND, resp.data.copyOfRange(keyBase + MF1_KEY_SIZE, keyBase + 2 * MF1_KEY_SIZE))
                } else {
                    KeyState(KeyStatus.MISSING)
                },
            )
        }
    }

    /**
     * Static Nested 采集（Static PRNG 卡）：用已知密钥对 [blockKnown] 认证后，
     * 采集对 [blockTarget] 做 nested 认证时的 NT 参数。
     *
     * 请求 DATA：typeKnown[1]+blockKnown[1]+keyKnown[6]+typeTarget[1]+blockTarget[1]
     * 响应 DATA：uid[4]+（nt[4]+ntEnc[4]）×2（大端）
     */
    suspend fun staticNestedAcquire(
        blockKnown: Int,
        typeKnown: KeyType,
        keyKnown: ByteArray,
        blockTarget: Int,
        typeTarget: KeyType,
    ): StaticNestedAcquire {
        require(keyKnown.size == MF1_KEY_SIZE) { "密钥长度必须为 6 字节" }
        val payload = byteArrayOf(
            typeKnown.code.toByte(),
            blockKnown.toByte(),
        ) + keyKnown + byteArrayOf(typeTarget.code.toByte(), blockTarget.toByte())
        val resp = request(ChameleonCommand.MF1_STATIC_NESTED_ACQUIRE, payload)
        requireStatus(resp, ChameleonStatus.HF_TAG_OK)
        // 4 字节 UID + 2 组 NT 对，其余（多余组）按需读取
        require(resp.data.size >= 4 + 2 * 8) { "STATIC_NESTED_ACQUIRE 响应数据异常" }
        val uid = resp.data.copyOfRange(0, 4)
        val pairs = (0 until (resp.data.size - 4) / 8).map { i ->
            val base = 4 + i * 8
            NtPair(
                nt = readU32(resp.data, base),
                ntEnc = readU32(resp.data, base + 4),
            )
        }
        return StaticNestedAcquire(uid = uid, ntPairs = pairs)
    }

    /**
     * 验证单个块的密钥（用于候选密钥筛选）。认证失败不抛异常，返回 false。
     *
     * 请求 DATA：keyType[1]+block[1]+key[6]；status=HF_TAG_OK 即验证通过。
     */
    suspend fun authOneKeyBlock(block: Int, keyType: KeyType, key: ByteArray): Boolean {
        require(key.size == MF1_KEY_SIZE) { "密钥长度必须为 6 字节" }
        val payload = byteArrayOf(keyType.code.toByte(), block.toByte()) + key
        val resp = request(ChameleonCommand.MF1_AUTH_ONE_KEY_BLOCK, payload)
        return resp.status == ChameleonStatus.HF_TAG_OK.raw
    }

    /**
     * 读取一个数据块（16 字节）。认证失败抛出 [ChameleonStatusException]。
     *
     * 请求 DATA：keyType[1] + block[1] + key[6]
     */
    suspend fun readBlock(block: Int, keyType: KeyType, key: ByteArray): ByteArray {
        val payload = byteArrayOf(keyType.code.toByte(), block.toByte()) + key
        val resp = request(ChameleonCommand.MF1_READ_ONE_BLOCK, payload)
        requireStatus(resp, ChameleonStatus.HF_TAG_OK)
        return resp.data
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private suspend fun request(
        cmd: Int,
        data: ByteArray = ByteArray(0),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ChameleonFrame = client.request(ChameleonFrame(cmd, 0, data), timeoutMs)

    /** 大端读取 4 字节无符号整数（Long 存放避免符号问题） */
    private fun readU32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    /** 校验响应状态，非预期时抛出携带可读信息的异常 */
    private fun requireStatus(resp: ChameleonFrame, expected: ChameleonStatus) {
        if (resp.status != expected.raw) {
            throw ChameleonStatusException(resp.cmd, resp.status, ChameleonStatus.describe(resp.status))
        }
    }

    companion object {
        /** Mifare Classic 1K：16 扇区 × 4 块 */
        const val MF1_SECTOR_COUNT = 16
        const val MF1_BLOCKS_PER_SECTOR = 4
        const val MF1_BLOCK_SIZE = 16
        const val MF1_KEY_SIZE = 6

        /** 每扇区最后一块为 trailer：keyA[0..5] + accessBits[6..8] + 通用字节[9] + keyB[10..15] */
        const val MF1_TRAILER_BLOCK_IN_SECTOR = 3

        /** trailer 内 KeyB 的起始偏移 */
        const val MF1_TRAILER_KEY_B_OFFSET = 10

        /** 固件单帧最多接受 83 个密钥（与 CLI 约束一致） */
        const val MAX_KEYS_PER_REQUEST = 83

        /** mask / found 位图固定 10 字节（40 扇区 × 2 密钥位） */
        const val MASK_SIZE = 10

        /** check_keys 响应固定长度：10 字节 found + 40 扇区 × 2 密钥 × 6 字节 */
        const val CHECK_KEYS_RESPONSE_LEN = MASK_SIZE + 40 * 2 * MF1_KEY_SIZE

        private const val DEFAULT_TIMEOUT_MS = 5_000L

        /** 模式切换需重启射频现场，耗时较长 */
        private const val MODE_SWITCH_TIMEOUT_MS = 10_000L

        /** 字典攻击耗时与密钥数×扇区数成正比，取宽松上界（参考 CLI 超时公式） */
        private const val KEY_CHECK_TIMEOUT_MS = 30_000L
    }
}
