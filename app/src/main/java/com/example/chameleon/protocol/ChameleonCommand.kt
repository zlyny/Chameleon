package com.example.chameleon.protocol

/**
 * ChameleonUltra 协议命令码。
 *
 * 这里只收录当前用到的命令；完整命令表参考官方文档
 * RfidResearchGroup/ChameleonUltraDocs protocol.md，后续按需扩充。
 */
object ChameleonCommand {

    /** 获取固件应用版本 */
    const val GET_APP_VERSION = 1000

    /** 获取设备模式（仿真器 / 读卡器） */
    const val GET_DEVICE_MODE = 1002

    /** 获取 nRF 芯片 ID */
    const val GET_DEVICE_CHIP_ID = 1011

    /** 获取电池信息，响应 3 字节：电压 U16(大端, mV) + 电量百分比 */
    const val GET_BATTERY_INFO = 1025

    /** 获取设备型号（Ultra / Lite） */
    const val GET_DEVICE_MODEL = 1033

    /** 获取设备支持的能力（命令 ID 列表） */
    const val GET_DEVICE_CAPABILITIES = 1035

    /** 14A 卡片扫描 */
    const val HF14A_SCAN = 2000

    /** Mifare Classic 校验一组密钥 */
    const val MF1_CHECK_KEYS_OF_SECTORS = 2012

    /** Mifare Classic 读取一个扇区块 */
    const val MF1_READ_ONE_BLOCK = 2008

    /** Mifare Classic 写入一个扇区块 */
    const val MF1_WRITE_ONE_BLOCK = 2009

    /** 命令码转可读名称，用于日志展示 */
    fun nameOf(cmd: Int): String = when (cmd) {
        GET_APP_VERSION -> "GET_APP_VERSION"
        GET_DEVICE_MODE -> "GET_DEVICE_MODE"
        GET_DEVICE_CHIP_ID -> "GET_DEVICE_CHIP_ID"
        GET_BATTERY_INFO -> "GET_BATTERY_INFO"
        GET_DEVICE_MODEL -> "GET_DEVICE_MODEL"
        GET_DEVICE_CAPABILITIES -> "GET_DEVICE_CAPABILITIES"
        HF14A_SCAN -> "HF14A_SCAN"
        MF1_CHECK_KEYS_OF_SECTORS -> "MF1_CHECK_KEYS_OF_SECTORS"
        MF1_READ_ONE_BLOCK -> "MF1_READ_ONE_BLOCK"
        MF1_WRITE_ONE_BLOCK -> "MF1_WRITE_ONE_BLOCK"
        else -> "CMD_%d".format(cmd)
    }
}
