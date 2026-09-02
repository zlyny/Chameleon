@file:OptIn(ExperimentalUuidApi::class)

package com.example.chameleon.ble

import com.example.chameleon.protocol.ChameleonFrame
import com.example.chameleon.protocol.FrameDecoder
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.util.chunked
import kotlin.uuid.ExperimentalUuidApi

/** BLE 连接/通信过程中的异常 */
class ChameleonBleException(message: String) : IOException(message)

/**
 * ChameleonUltra BLE 客户端，基于 Nordic Kotlin-BLE-Library 封装 NUS 通信。
 *
 * 连接流程：connect（自动协商最大 MTU）-> 服务发现 -> 定位 NUS 特征 ->
 * 订阅 TX notify（等待 CCCD 写入完成）-> 就绪。之后通过 [send] 分片写
 * RX 特征发帧，设备回复经 [FrameDecoder] 重组后回调 [Listener.onFrameReceived]。
 *
 * 所有回调均在 [Dispatchers.Main] 上执行。
 *
 * 调用方需保证已获得 BLUETOOTH_CONNECT（Android 12+）权限。
 */
class ChameleonBleClient(private val centralManager: CentralManager) {

    interface Listener {

        /** 连接就绪（服务发现 + MTU 协商 + notify 订阅全部完成），[mtu] 为生效的 ATT MTU */
        fun onReady(mtu: Int)

        /** 收到一个完整且校验通过的协议帧 */
        fun onFrameReceived(frame: ChameleonFrame)

        /** 一个协议帧已成功写入设备（供 UI 层记录 TX 日志） */
        fun onFrameSent(frame: ChameleonFrame) {}

        /** 连接断开，[byUser] 为 true 表示本次断开由 [disconnect] 主动发起 */
        fun onDisconnected(byUser: Boolean)

        /** 连接建立失败或使用中出错 */
        fun onError(message: String)
    }

    var listener: Listener? = null

    /** 当前协商生效的 ATT MTU */
    var mtu: Int = 0
        private set

    private val frameDecoder = FrameDecoder()

    private var peripheral: Peripheral? = null
    private var rxCharacteristic: RemoteCharacteristic? = null
    private var scope: CoroutineScope? = null

    /** 当前等待中的请求-响应匹配（同一时刻只允许一个在途请求，由 [requestMutex] 保证） */
    private val requestMutex = Mutex()
    private var pendingResponse: CompletableDeferred<ChameleonFrame>? = null

    /** 链路已就绪（connect() 成功返回后置位） */
    private var ready = false

    /** 用户主动断开标志，用于区分正常断开与异常掉线 */
    private var closeRequested = false

    /** 防止 onDisconnected 重复回调 */
    private var disconnectNotified = false

    /**
     * 连接设备并挂起直到链路就绪（可收发数据）。
     *
     * @param address 扫描时获得的设备 MAC 地址
     * @throws ChameleonBleException 连接失败、超时或设备不支持 NUS
     */
    suspend fun connect(address: String) {
        check(peripheral == null) { "客户端未处于断开状态" }
        closeRequested = false
        disconnectNotified = false

        val target = centralManager.getPeripheralById(address)
            ?: throw ChameleonBleException("设备不在管理器缓存中，请返回重新扫描")

        val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        peripheral = target
        scope = connectionScope

        observeDisconnection(target, connectionScope)

        try {
            centralManager.connect(
                target,
                options = CentralManager.ConnectionOptions.Direct(
                    automaticallyRequestHighestValueLength = true,
                ),
            )
        } catch (e: CancellationException) {
            release()
            throw e
        } catch (e: Exception) {
            release()
            throw ChameleonBleException(e.message ?: "连接失败")
        }

        val nusService = try {
            target.services()
                .filterIsInstance<RemoteServices.Discovered>()
                .first()
                .services
                .firstOrNull { it.uuid == BleConstants.NUS_SERVICE_UUID }
        } catch (e: CancellationException) {
            release()
            throw e
        } catch (e: Exception) {
            release()
            throw ChameleonBleException("服务发现失败：${e.message}")
        } ?: run {
            release()
            throw ChameleonBleException("设备未提供 Nordic UART 服务")
        }

        val tx = nusService.characteristics
            .firstOrNull { it.uuid == BleConstants.NUS_TX_CHARACTERISTIC_UUID }
        val rx = nusService.characteristics
            .firstOrNull { it.uuid == BleConstants.NUS_RX_CHARACTERISTIC_UUID }
        if (tx == null || rx == null) {
            release()
            throw ChameleonBleException("NUS 特征不完整")
        }
        rxCharacteristic = rx

        // 订阅 TX notify；onSubscription 在 CCCD 写入完成后回调，
        // await 它保证不漏掉连接建立后立即到来的通知
        val subscribed = CompletableDeferred<Unit>()
        tx.subscribe { subscribed.complete(Unit) }
            .onEach { data ->
                frameDecoder.feed(data).forEach { frame ->
                    pendingResponse?.complete(frame)
                    listener?.onFrameReceived(frame)
                }
            }
            .catch { e -> listener?.onError("通知接收异常：${e.message}") }
            .launchIn(connectionScope)
        try {
            subscribed.await()
        } catch (e: CancellationException) {
            release()
            throw e
        } catch (e: Exception) {
            release()
            throw ChameleonBleException("开启通知失败：${e.message}")
        }

        mtu = target.maximumWriteValueLength(WriteType.WITH_RESPONSE) + 3
        ready = true
        listener?.onReady(mtu)
    }

    /**
     * 发送一个协议帧。帧按当前有效载荷上限分片，逐片以有响应写
     * （WRITE_TYPE_WITH_RESPONSE）写入 RX 特征，保证可靠有序传输。
     *
     * @throws ChameleonBleException 任一分片写入失败
     */
    suspend fun send(frame: ChameleonFrame) {
        check(ready) { "BLE 连接未就绪" }
        val target = peripheral ?: throw ChameleonBleException("连接已断开")
        val rx = rxCharacteristic ?: throw ChameleonBleException("连接已断开")
        val maxLength = target.maximumWriteValueLength(WriteType.WITH_RESPONSE)
        frame.encode().chunked(maxLength).forEach { chunk ->
            rx.write(chunk, WriteType.WITH_RESPONSE)
        }
        listener?.onFrameSent(frame)
    }

    /**
     * 发送一个协议帧并挂起等待设备返回的下一帧响应（不校验 cmd 匹配，
     * 协议为严格的请求-响应串行模型，配合 [requestMutex] 保证时序）。
     *
     * 响应帧同时仍会经 [Listener.onFrameReceived] 回调（供日志等使用）。
     *
     * @param timeoutMs 等待响应的超时时间
     * @throws ChameleonBleException 发送失败、超时或等待期间连接断开
     */
    suspend fun request(frame: ChameleonFrame, timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): ChameleonFrame {
        requestMutex.withLock {
            val deferred = CompletableDeferred<ChameleonFrame>()
            pendingResponse = deferred
            try {
                send(frame)
                try {
                    return withTimeout(timeoutMs) { deferred.await() }
                } catch (e: TimeoutCancellationException) {
                    throw ChameleonBleException(
                        "设备响应超时（%d ms），cmd=%s".format(timeoutMs, frame.cmd),
                    )
                }
            } finally {
                pendingResponse = null
            }
        }
    }

    /** 主动断开连接；未连接时调用无副作用。断开结果经 [Listener.onDisconnected] 回调。 */
    fun disconnect() {
        val target = peripheral ?: return
        if (closeRequested) return
        closeRequested = true
        scope?.launch {
            try {
                target.disconnect()
            } catch (_: Exception) {
                // 断开失败也继续走本地清理，避免状态残留
            }
            if (ready) {
                ready = false
                notifyDisconnected(byUser = true)
            }
            release()
        }
    }

    /** 监听连接状态 Flow，捕获异常掉线 */
    private fun observeDisconnection(target: Peripheral, connectionScope: CoroutineScope) {
        target.state
            .onEach { state ->
                if (state is ConnectionState.Disconnected && ready) {
                    ready = false
                    notifyDisconnected(byUser = closeRequested)
                    release()
                }
            }
            .catch { e -> listener?.onError("连接状态异常：${e.message}") }
            .launchIn(connectionScope)
    }

    private fun notifyDisconnected(byUser: Boolean) {
        if (disconnectNotified) return
        disconnectNotified = true
        pendingResponse?.completeExceptionally(ChameleonBleException("连接已断开"))
        pendingResponse = null
        listener?.onDisconnected(byUser)
    }

    /** 清理本地状态（不断开连接，连接由调用方或 [disconnect] 负责） */
    private fun release() {
        scope?.cancel()
        scope = null
        peripheral = null
        rxCharacteristic = null
        ready = false
    }

    private companion object {
        /** 常规命令默认超时；慢命令（如字典攻击）由调用方显式传入更长超时 */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 5_000L
    }
}
