package com.example.chameleon.protocol

/**
 * ChameleonUltra 通信协议帧。
 *
 * 帧结构（多字节字段均为大端 / 网络字节序）：
 * ```
 * | SOF(1B) | LRC1(1B) | CMD(2B) | STATUS(2B) | LEN(2B) | LRC2(1B) | DATA(LEN B) | LRC3(1B) |
 * |  0x11   |   0xEF   |         |             |         |          |    <=512    |          |
 * ```
 * - SOF 固定为 0x11，LRC1 是 SOF 的校验值，固定为 0xEF；
 * - STATUS：客户端发送时固定 0x0000，设备返回时为命令执行结果；
 * - LRC2 覆盖 CMD|STATUS|LEN 共 6 字节，LRC3 覆盖 DATA；
 * - LRC = 全部字节求和（mod 256）的二进制补码；
 * - 帧总长度 = LEN + 10 字节。
 *
 * 协议参考：RfidResearchGroup/ChameleonUltraDocs protocol.md
 */
class ChameleonFrame(val cmd: Int, val status: Int, val data: ByteArray) {

    /** 编码为待发送的字节序列 */
    fun encode(): ByteArray {
        val out = ByteArray(FRAME_OVERHEAD + data.size)
        out[0] = SOF_BYTE_1
        out[1] = SOF_BYTE_2
        HexUtils.writeU16(out, 2, cmd)
        HexUtils.writeU16(out, 4, status)
        HexUtils.writeU16(out, 6, data.size)
        out[8] = lrc(out, 2, 8).toByte()
        data.copyInto(out, 9)
        out[9 + data.size] = lrc(data, 0, data.size).toByte()
        return out
    }

    companion object {
        /** 帧起始字节 0x11 */
        val SOF_BYTE_1: Byte = 0x11

        /** SOF 的 LRC 校验值 0xEF */
        val SOF_BYTE_2: Byte = 0xEF.toByte()

        const val MAX_DATA_LEN = 512
        const val FRAME_OVERHEAD = 10

        /** 计算 bytes[from, to) 的 LRC 校验值 */
        fun lrc(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
            var sum = 0
            for (i in from until to) {
                sum += bytes[i].toInt() and 0xFF
            }
            return (-sum) and 0xFF
        }

        /**
         * 解码并校验一个完整的帧。
         *
         * @return 帧不完整、帧头错误或 LRC 校验失败时返回 null
         */
        fun decode(bytes: ByteArray): ChameleonFrame? {
            if (bytes.size < FRAME_OVERHEAD) return null
            if (bytes[0] != SOF_BYTE_1 || bytes[1] != SOF_BYTE_2) return null
            val len = HexUtils.readU16(bytes, 6)
            if (len > MAX_DATA_LEN || bytes.size != FRAME_OVERHEAD + len) return null
            if (bytes[8] != lrc(bytes, 2, 8).toByte()) return null
            val data = bytes.copyOfRange(9, 9 + len)
            if (bytes[9 + len] != lrc(data).toByte()) return null
            return ChameleonFrame(HexUtils.readU16(bytes, 2), HexUtils.readU16(bytes, 4), data)
        }
    }
}
