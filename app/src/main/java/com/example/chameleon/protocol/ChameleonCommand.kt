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

    /** 切换设备模式（读卡器 / 模拟卡），请求 DATA 为 1 字节：0x01=读卡器，0x00=模拟卡 */
    const val CHANGE_DEVICE_MODE = 1001

    /** 获取设备模式，响应 DATA 为 1 字节：0x01=读卡器，0x00=模拟卡 */
    const val GET_DEVICE_MODE = 1002

    /** 获取 nRF 芯片 ID */
    const val GET_DEVICE_CHIP_ID = 1011

    /** 获取电池信息，响应 3 字节：电压 U16(大端, mV) + 电量百分比 */
    const val GET_BATTERY_INFO = 1025

    /** 获取设备型号（Ultra / Lite） */
    const val GET_DEVICE_MODEL = 1033

    /** 获取设备支持的能力（命令 ID 列表） */
    const val GET_DEVICE_CAPABILITIES = 1035

    /** 14A 卡片扫描，响应 DATA：uidLen[1] + uid[N] + atqa[2] + sak[1] + atsLen[1] + ats[N] */
    const val HF14A_SCAN = 2000

    /** 检测卡是否支持 Mifare Classic，status=HF_TAG_OK 表示支持 */
    const val MF1_DETECT_SUPPORT = 2001

    /** 检测 Mifare Classic PRNG 类型，响应 DATA[0]：0=Static 1=Weak 2=Hard */
    const val MF1_DETECT_PRNG = 2002

    /**
     * Static Nested 采集（Static PRNG 卡）：请求 DATA：typeKnown[1]+blockKnown[1]+
     * keyKnown[6]+typeTarget[1]+blockTarget[1]；响应 DATA：uid[4]+（nt[4]+ntEnc[4]）×2。
     */
    const val MF1_STATIC_NESTED_ACQUIRE = 2003

    /** 检测 NT 距离（Weak PRNG 卡 nested 攻击前置），响应 DATA：uid[4]+dist[2] */
    const val MF1_DETECT_NT_DIST = 2005

    /**
     * Nested 采集（Weak PRNG 卡，为后续版本预留）：请求 DATA 与 STATIC_NESTED_ACQUIRE
     * 相同；响应 DATA：uid[4]+（nt[4]+ntEnc[4]+par[1]）×N。
     */
    const val MF1_NESTED_ACQUIRE = 2006

    /** 验证单个块的密钥，请求 DATA：keyType[1]+block[1]+key[6]，status=HF_TAG_OK 即通过 */
    const val MF1_AUTH_ONE_KEY_BLOCK = 2007

    /** Mifare Classic 读取一个扇区块，请求：keyType[1]+block[1]+key[6]，响应 16 字节数据 */
    const val MF1_READ_ONE_BLOCK = 2008

    /** Mifare Classic 写入一个扇区块 */
    const val MF1_WRITE_ONE_BLOCK = 2009

    /**
     * 14A 原始指令收发（对齐 CLI `hf 14a raw`）。请求 DATA：
     * options[1] + respTimeoutMs[2] + bitlen[2] + data[N]（均大端）。
     * options 为 MSB 优先位域：bit7=激活场 bit6=等待响应 bit5=发送附 CRC
     * bit4=自动选卡 bit3=保持场 bit2=校验响应 CRC；响应 DATA 为卡应答。
     */
    const val HF14A_RAW = 2010

    /**
     * Mifare Classic 字典攻击：对掩码选中的扇区密钥位逐一尝试密钥列表。
     * 请求 DATA：mask[10] + keys[N*6]；响应 DATA（490 字节）：found[10] + keys[40][2][6]。
     * mask 为跳过掩码：位=1 跳过、位=0 检查；扇区 s 的 KeyA=byte[s/4] 的
     * bit(7-2*(s%4))，KeyB 为其低位。
     */
    const val MF1_CHECK_KEYS_OF_SECTORS = 2012

    /**
     * 写入模拟卡块数据（对齐 CLI `hf mf eload`）。请求 DATA：
     * blockStart[1] + data[N*16]（N 块连续写入，从 blockStart 起自增）；
     * 单帧 data 上限 512 字节，即一次最多 31 块。status=SUCCESS。
     */
    const val MF1_WRITE_EMU_BLOCK_DATA = 4000

    /**
     * 设置当前 HF 卡槽的反碰撞数据（对齐 CLI `hf 14a anti_coll_data`）。
     * 请求 DATA：uidLen[1] + uid[N] + atqa[2] + sak[1] + atsLen[1] + ats[N]；
     * status=SUCCESS。写入 dump 后用于让模拟卡的 UID/ATQA/SAK 与原卡一致。
     */
    const val HF14A_SET_ANTI_COLL_DATA = 4001

    /**
     * 开/关 Mifare 模拟卡的 mfkey32 认证日志（对齐 CLI `hf mf econfig
     * --enable-log`）。请求 DATA：enable[1]（1=开启 0=关闭）；status=SUCCESS。
     * 开启后模拟卡被读卡器认证时的 NT/NR/AR 参数会被记录，供 mfkey32 离线恢复密钥。
     */
    const val MF1_SET_DETECTION_ENABLE = 4004

    /**
     * 获取模拟卡认证日志条数（mfkey32 破解用）。请求 DATA 为空；
     * 响应 DATA：count[4]（大端 U32）；status=SUCCESS。
     */
    const val MF1_GET_DETECTION_COUNT = 4005

    /**
     * 下载模拟卡认证日志。请求 DATA：startIndex[4]（大端 U32）；
     * 响应 DATA：自该索引起的日志条目（每条 18 字节，见 [com.example.chameleon.device.AuthLog]）；
     * status=SUCCESS。响应帧 data 上限约 512 字节，调用方需按返回条数推进索引分批下载。
     */
    const val MF1_GET_DETECTION_LOG = 4006

    /** 命令码转可读名称，用于日志展示 */
    fun nameOf(cmd: Int): String = when (cmd) {
        GET_APP_VERSION -> "GET_APP_VERSION"
        CHANGE_DEVICE_MODE -> "CHANGE_DEVICE_MODE"
        GET_DEVICE_MODE -> "GET_DEVICE_MODE"
        GET_DEVICE_CHIP_ID -> "GET_DEVICE_CHIP_ID"
        GET_BATTERY_INFO -> "GET_BATTERY_INFO"
        GET_DEVICE_MODEL -> "GET_DEVICE_MODEL"
        GET_DEVICE_CAPABILITIES -> "GET_DEVICE_CAPABILITIES"
        HF14A_SCAN -> "HF14A_SCAN"
        MF1_DETECT_SUPPORT -> "MF1_DETECT_SUPPORT"
        MF1_DETECT_PRNG -> "MF1_DETECT_PRNG"
        MF1_STATIC_NESTED_ACQUIRE -> "MF1_STATIC_NESTED_ACQUIRE"
        MF1_DETECT_NT_DIST -> "MF1_DETECT_NT_DIST"
        MF1_NESTED_ACQUIRE -> "MF1_NESTED_ACQUIRE"
        MF1_AUTH_ONE_KEY_BLOCK -> "MF1_AUTH_ONE_KEY_BLOCK"
        MF1_READ_ONE_BLOCK -> "MF1_READ_ONE_BLOCK"
        MF1_WRITE_ONE_BLOCK -> "MF1_WRITE_ONE_BLOCK"
        HF14A_RAW -> "HF14A_RAW"
        MF1_CHECK_KEYS_OF_SECTORS -> "MF1_CHECK_KEYS_OF_SECTORS"
        MF1_WRITE_EMU_BLOCK_DATA -> "MF1_WRITE_EMU_BLOCK_DATA"
        HF14A_SET_ANTI_COLL_DATA -> "HF14A_SET_ANTI_COLL_DATA"
        MF1_SET_DETECTION_ENABLE -> "MF1_SET_DETECTION_ENABLE"
        MF1_GET_DETECTION_COUNT -> "MF1_GET_DETECTION_COUNT"
        MF1_GET_DETECTION_LOG -> "MF1_GET_DETECTION_LOG"
        else -> "CMD_%d".format(cmd)
    }
}
