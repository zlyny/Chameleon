package com.example.chameleon.data

import com.example.chameleon.device.ChameleonSession

/**
 * dump 卡片内容：块数据（[bytes]）+ 每字节"是否已知"掩码（[known]）。
 *
 * dump 时未能读取成功的字节（未破解扇区、单块读取失败、trailer 未恢复
 * 的密钥区等）掩码为 false，eml 文件中以 XX 记录、数值保持 0；已知字节
 * 为真实读出值或按密钥矩阵回填的密钥。各消费方按需解释未知字节：
 * - 查看对话框：显示为红色的 XX，直观区分"未读取"与"真实数据 00"；
 * - 写入槽：按 0x00 写入设备（[bytes] 中未知字节即为 0）；
 * - 导出：按 [toExportBinary] 的区域规则填充。
 */
class DumpContent(val bytes: ByteArray, val known: BooleanArray) {

    init {
        require(bytes.size == known.size) { "数据与掩码长度不一致" }
        require(bytes.size % ChameleonSession.MF1_BLOCK_SIZE == 0) { "块数据长度不是 16 的倍数" }
    }

    val blockCount: Int get() = bytes.size / ChameleonSession.MF1_BLOCK_SIZE

    /** 指定块的全部字节是否均已知 */
    fun isBlockKnown(block: Int): Boolean {
        val from = block * ChameleonSession.MF1_BLOCK_SIZE
        for (i in from until from + ChameleonSession.MF1_BLOCK_SIZE) {
            if (!known[i]) return false
        }
        return true
    }

    /**
     * 导出为二进制 dump：未知字节按区域填充——
     * - trailer 的 KeyA/KeyB 区填 FF×6（未恢复密钥的出厂默认值）；
     * - trailer 的访问控制位区 [6:10] 填 FF 07 80 69（出厂传输模式的
     *   默认访问位 + 通用字节）；
     * - 其余数据块的未知字节填 00。
     */
    fun toExportBinary(): ByteArray {
        val out = bytes.copyOf()
        for (i in out.indices) {
            if (known[i]) continue
            val inBlock = i % ChameleonSession.MF1_BLOCK_SIZE
            val isTrailer =
                (i / ChameleonSession.MF1_BLOCK_SIZE) % ChameleonSession.MF1_BLOCKS_PER_SECTOR ==
                    ChameleonSession.MF1_TRAILER_BLOCK_IN_SECTOR
            out[i] = when {
                !isTrailer -> 0x00

                inBlock < ChameleonSession.MF1_TRAILER_ACCESS_OFFSET ||
                    inBlock >= ChameleonSession.MF1_TRAILER_KEY_B_OFFSET -> 0xFF

                else -> EXPORT_ACCESS_FILL[inBlock - ChameleonSession.MF1_TRAILER_ACCESS_OFFSET]
            }.toByte()
        }
        return out
    }

    companion object {
        /** trailer 访问控制位区域的导出填充（出厂 transport 模式默认值） */
        private val EXPORT_ACCESS_FILL = byteArrayOf(0xFF.toByte(), 0x07, 0x80.toByte(), 0x69)

        /** 创建指定字节数的全未知内容（dump 流程的初始状态） */
        fun allUnknown(size: Int) = DumpContent(ByteArray(size), BooleanArray(size))
    }
}
