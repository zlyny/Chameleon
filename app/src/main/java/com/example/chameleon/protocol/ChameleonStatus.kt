package com.example.chameleon.protocol

/**
 * ChameleonUltra 命令响应状态码。
 *
 * 注意：status 并非只有 0 才代表成功——
 * - [HF_TAG_OK]（0x0000）：HF（14A / Mifare）卡操作成功；
 * - [SUCCESS]（0x0068）：设备级命令（如 GET_DEVICE_MODE）执行成功。
 * 每个命令各自约定期望状态，由调用方（device 层）校验。
 */
enum class ChameleonStatus(val raw: Int, val label: String) {
    HF_TAG_OK(0x00, "卡操作成功"),
    HF_TAG_NO(0x01, "未检测到卡片"),
    HF_ERR_STAT(0x02, "卡片通信异常"),
    HF_ERR_CRC(0x03, "卡片通信校验异常"),
    HF_COLLISION(0x04, "卡片冲突"),
    HF_ERR_BCC(0x05, "卡片 BCC 校验错误"),
    MF_ERR_AUTH(0x06, "密钥认证失败"),
    HF_ERR_PARITY(0x07, "卡片奇偶校验错误"),
    HF_ERR_ATS(0x08, "ATS 应答异常"),

    PAR_ERR(0x60, "参数错误"),
    DEVICE_MODE_ERROR(0x66, "设备模式错误"),
    INVALID_CMD(0x67, "命令无效"),
    SUCCESS(0x68, "设备操作成功"),
    NOT_IMPLEMENTED(0x69, "功能未实现"),
    FLASH_WRITE_FAIL(0x70, "Flash 写入失败"),
    FLASH_READ_FAIL(0x71, "Flash 读取失败");

    companion object {

        /** 原始状态码转枚举，未知值返回 null */
        fun of(raw: Int): ChameleonStatus? = entries.firstOrNull { it.raw == raw }

        /** 状态码转用户可读描述，用于日志与错误提示 */
        fun describe(raw: Int): String {
            val known = of(raw)
            return if (known != null) {
                "${known.label}（0x%02X %s）".format(raw, known.name)
            } else {
                "未知状态 0x%02X".format(raw)
            }
        }
    }
}
