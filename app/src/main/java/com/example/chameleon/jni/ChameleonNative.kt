package com.example.chameleon.jni

/**
 * ChameleonUltra 原生库（libchameleon.so）的 JNI 桥接。
 *
 * 定位：承载计算密集型的卡安全算法，避免在 Java/Kotlin 层拖慢 UI 线程。
 * 规划中的能力（对应 ChameleonUltra 的 MF1_DARKSIDE_ACQUIRE /
 * MF1_NESTED_ACQUIRE 命令返回的原始数据求解 Mifare Classic 密钥）：
 * - Crypto1 密钥流恢复（darkside）
 * - nested attack 密钥恢复（静态/非静态随机数）
 *
 * 当前版本仅提供库版本查询，用于验证 JNI 链路连通。
 */
object ChameleonNative {

    init {
        System.loadLibrary("chameleon")
    }

    /** 获取原生库版本号 */
    external fun nativeVersion(): String
}
