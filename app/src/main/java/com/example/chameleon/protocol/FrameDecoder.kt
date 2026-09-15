package com.example.chameleon.protocol

/**
 * ChameleonUltra 协议流式分帧器。
 *
 * BLE 通知到达的原始字节流可能把一个协议帧拆成多包（响应帧最长 522 字节，
 * 超过 ATT MTU 时必然分片），也可能粘包。本类负责从字节流中重组出完整的协议帧：
 * 按 SOF（0x11 EF）同步，校验长度与 LRC，校验失败时丢弃 1 字节重新同步。
 *
 * 用法：每收到一包 BLE 数据调用一次 [feed]，返回本次新解码出的完整帧。
 */
class FrameDecoder {

    private var buffer = ByteArray(0)

    /** 缓冲区上限：两倍最大帧长，超过说明流中无有效帧，直接清空防溢出 */
    private val maxBufferSize = (ChameleonFrame.MAX_DATA_LEN + ChameleonFrame.FRAME_OVERHEAD) * 2

    fun feed(chunk: ByteArray): List<ChameleonFrame> {
        if (chunk.isEmpty()) return emptyList()
        buffer += chunk
        if (buffer.size > maxBufferSize) {
            buffer = ByteArray(0)
            return emptyList()
        }
        val frames = mutableListOf<ChameleonFrame>()
        while (true) {
            if (!synchronize()) break
            if (buffer.size < HEADER_LEN) break // 头部未接收完整，等待下一包
            val dataLen = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
            if (dataLen > ChameleonFrame.MAX_DATA_LEN) {
                buffer = buffer.copyOfRange(1, buffer.size) // 长度非法，重新同步
                continue
            }
            val totalLen = ChameleonFrame.FRAME_OVERHEAD + dataLen
            if (buffer.size < totalLen) break // 帧未接收完整，等待下一包
            val frame = ChameleonFrame.decode(buffer.copyOf(totalLen))
            if (frame != null) {
                frames += frame
                buffer = buffer.copyOfRange(totalLen, buffer.size)
            } else {
                // LRC 校验失败：可能是误同步，丢弃 1 字节继续寻找下一个 SOF
                buffer = buffer.copyOfRange(1, buffer.size)
            }
        }
        return frames
    }

    /** 丢弃 SOF 之前的噪声字节，返回缓冲区头部是否为合法 SOF */
    private fun synchronize(): Boolean {
        var i = 0
        // 注意循环条件是 `size - 1`：SOF 是两个字节，最后一个字节无法与「下一个字节」
        // 配对，所以不能参与匹配（否则会越界）。
        while (i < buffer.size - 1) {
            if (buffer[i] == ChameleonFrame.SOF_BYTE_1 &&
                buffer[i + 1] == ChameleonFrame.SOF_BYTE_2
            ) {
                // 把 SOF 之前的噪声整段切掉，让 buffer[0..1] 就是帧头
                if (i > 0) buffer = buffer.copyOfRange(i, buffer.size)
                return true
            }
            i++
        }
        // 整段都没找到完整 SOF。这里有个容易漏的边界：BLE 分包可能把 0x11 和 0xEF
        // 拆在两包里，当前 buffer 末尾那个孤立的 0x11 很可能就是下一包的帧头前半字节，
        // 所以**只保留它**而不是清空——丢掉就会永远解不出这一帧。
        buffer = if (buffer.isNotEmpty() && buffer.last() == ChameleonFrame.SOF_BYTE_1) {
            buffer.copyOfRange(buffer.size - 1, buffer.size)
        } else {
            ByteArray(0)
        }
        return false
    }

    private companion object {
        /** SOF(2) + CMD(2) + STATUS(2) + LEN(2)，读出 LEN 字段所需的最小长度 */
        const val HEADER_LEN = 8
    }
}
