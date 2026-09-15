package com.example.chameleon.protocol

import java.io.IOException

/**
 * 帧传输异常基类：与具体传输方式（BLE / USB / 模拟器）无关。
 *
 * 放在 `protocol` 包而不是 `ble` 包，是为了让 [com.example.chameleon.device]
 * 层不必反向依赖 [ble] 层就能声明它抛出的异常。
 */
open class ChameleonTransportException(message: String) : IOException(message)

/**
 * 帧传输通道：负责把一个 [ChameleonFrame] 送到设备并取回响应。
 *
 * **这是「换传输不动协议」的实际落点**（README 分层原则）：
 * [com.example.chameleon.device.ChameleonSession] 只依赖本接口，
 * 换 USB CDC / TCP / 串口时只需新增一个实现，设备层与协议层都不用改。
 *
 * **同时它也是无硬件测试的开关**：测试里注入 Fake 实现即可在 JVM 上跑通
 * 「读卡 → 破解 → dump」整条链路（见 `app/src/test`）。这个项目最大的成本
 * 一直是「每次验证都要真机 + 实体卡」，本接口是把该成本降下来的前提。
 *
 * **未来 USB（CDC 串口）**：与 NUS 一样是字节流，照样复用 [FrameDecoder]，
 * 协议层与设备层零改动。差异只在两处，都由新实现内部消化：
 * 1. 连接建立（USB 是枚举设备 + 申请权限 + 打开串口，BLE 是 GATT 连接 +
 *    服务发现 + 订阅 notify）；
 * 2. 分片大小（USB bulk 包长与 BLE ATT MTU 不同）——分片逻辑本就属于实现内部。
 *
 * 接入时新增一个实现即可；`MainViewModel` 里创建连接的那一处是唯一需要
 * 做「USB / BLE 自适应」的地方（当前固定用 BLE）。
 *
 * 实现需保证：协议是严格的请求-响应模型，同一时刻只允许一个在途请求。
 */
interface ChameleonTransport {

    /** 是否已就绪（可收发帧）；未就绪时 [request] 应抛异常 */
    val isReady: Boolean

    /**
     * 发送一帧并挂起等待设备返回的下一帧。
     *
     * @param timeoutMs 等待响应的超时时间
     * @throws ChameleonTransportException 发送失败、响应超时或传输中断
     */
    suspend fun request(frame: ChameleonFrame, timeoutMs: Long): ChameleonFrame

    /** 关闭传输并释放资源；未连接时调用应无副作用 */
    fun close()
}
