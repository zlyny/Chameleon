package com.example.chameleon

import com.example.chameleon.protocol.ChameleonFrame
import com.example.chameleon.protocol.ChameleonTransport
import com.example.chameleon.protocol.ChameleonTransportException

/**
 * [ChameleonTransport] 的测试替身：按命令码回放预先录制的响应帧。
 *
 * 存在意义：本项目每次验证都要「真机 + 实体卡」，成本极高。有了它就能在
 * JVM 上跑通「读卡 → 破解 → dump」整条链路，把协议编解码、位运算、
 * 状态机这些最易出错的部分纳入自动化测试。
 *
 * 用法：
 * ```kotlin
 * val fake = FakeChameleonTransport()
 * fake.respond(ChameleonCommand.GET_DEVICE_MODE, ChameleonFrame(cmd, status, data))
 * val session = ChameleonSession(fake)
 * ```
 */
class FakeChameleonTransport : ChameleonTransport {

    /** 命令码 -> 响应帧 */
    private val responses = mutableMapOf<Int, ChameleonFrame>()

    /** 所有发出去的帧（供断言请求内容，如字典攻击掩码） */
    val sentFrames = mutableListOf<ChameleonFrame>()

    /** 便捷断言：最后一次发出的帧 */
    val lastSentFrame: ChameleonFrame get() = sentFrames.last()

    override val isReady: Boolean get() = true

    var closed = false
        private set

    /** 注册某个命令的响应；未注册的命令会抛异常，避免测试静默通过 */
    fun respond(cmd: Int, status: Int, data: ByteArray = ByteArray(0)) {
        responses[cmd] = ChameleonFrame(cmd, status, data)
    }

    override suspend fun request(frame: ChameleonFrame, timeoutMs: Long): ChameleonFrame {
        sentFrames += frame
        return responses[frame.cmd]
            ?: throw ChameleonTransportException(
                "Fake 未配置命令 0x%04X 的响应".format(frame.cmd),
            )
    }

    override fun close() {
        closed = true
    }
}
