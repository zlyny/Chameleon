@file:OptIn(ExperimentalUuidApi::class)

package com.example.chameleon.ble

import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** BLE / GATT 常量定义 */
object BleConstants {

    /** Nordic UART Service（NUS），ChameleonUltra 通过它与上位机通信 */
    val NUS_SERVICE_UUID: Uuid = Uuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** NUS RX 特征：上位机 -> 设备，支持 write_request / write_command */
    val NUS_RX_CHARACTERISTIC_UUID: Uuid = Uuid.parse("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** NUS TX 特征：设备 -> 上位机，通过 notify 推送 */
    val NUS_TX_CHARACTERISTIC_UUID: Uuid = Uuid.parse("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** 扫描时长 */
    val SCAN_DURATION = 10.seconds
}
