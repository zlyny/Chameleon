package com.example.chameleon

import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.device.DeviceMode
import com.example.chameleon.device.KeyStatus
import com.example.chameleon.device.KeyType
import com.example.chameleon.protocol.ChameleonCommand
import com.example.chameleon.protocol.ChameleonStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设备层（[ChameleonSession]）的 JVM 单测：经 [FakeChameleonTransport]
 * 回放固件响应，无需真机与实体卡。
 *
 * 重点覆盖**历史上真实出过 bug 的两处位运算**：
 * - 字典攻击的 mask 是「跳过掩码」而非「选中掩码」：方向反了会让设备跳过
 *   全部 16 个扇区，表现为「命中 0/32」；
 * - `hf14aRaw` 的 options 是 MSB 优先位域：按 LSB 组装会导致设备不等卡
 *   应答，StaticNested 代次检测恒失败。
 *
 * 另覆盖帧解析（scan14a）与状态码语义（设备级 0x0068 / HF 卡 0x0000）。
 */
class ChameleonSessionTest {

    // ------------------------------------------------------------------
    // 状态码语义
    // ------------------------------------------------------------------

    @Test
    fun getDeviceMode_按响应首字节区分读卡器与模拟卡() = runTest {
        val reader = FakeChameleonTransport().apply {
            respond(ChameleonCommand.GET_DEVICE_MODE, ChameleonStatus.SUCCESS.raw, byteArrayOf(1))
        }
        assertEquals(DeviceMode.READER, ChameleonSession(reader).getDeviceMode())

        val emulator = FakeChameleonTransport().apply {
            respond(ChameleonCommand.GET_DEVICE_MODE, ChameleonStatus.SUCCESS.raw, byteArrayOf(0))
        }
        assertEquals(DeviceMode.EMULATOR, ChameleonSession(emulator).getDeviceMode())
    }

    // ------------------------------------------------------------------
    // 帧解析
    // ------------------------------------------------------------------

    @Test
    fun scan14a_解析UID与ATQA与SAK() = runTest {
        val fake = FakeChameleonTransport().apply {
            respond(
                cmd = ChameleonCommand.HF14A_SCAN,
                status = ChameleonStatus.HF_TAG_OK.raw,
                // uidLen[1] + uid[4] + atqa[2] + sak[1] + atsLen[1] + ats[0]
                data = byteArrayOf(
                    4,
                    0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
                    0x04, 0x00,
                    0x08,
                    0x00,
                ),
            )
        }

        val tag = ChameleonSession(fake).scan14a()

        assertEquals("DEADBEEF", tag.uidHex)
        assertEquals("08", tag.sakHex)
        assertEquals("0400", tag.atqaHex)
        assertEquals(0, tag.ats.size)
    }

    // ------------------------------------------------------------------
    // 位运算一：字典攻击掩码（历史 bug 点）
    // ------------------------------------------------------------------

    @Test
    fun 字典攻击mask为跳过掩码_检查位清零其余保持FF() = runTest {
        val fake = FakeChameleonTransport().apply {
            respond(
                cmd = ChameleonCommand.MF1_CHECK_KEYS_OF_SECTORS,
                status = ChameleonStatus.HF_TAG_OK.raw,
                data = ByteArray(490), // 全 0：无命中，便于只断言请求侧
            )
        }

        ChameleonSession(fake).checkKeysOfSectors(keys = listOf(ByteArray(6)))

        // 前 10 字节是 mask：扇区 0..15 共 32 个密钥位，1 字节容纳 4 个扇区
        // × A/B 两位，故前 4 字节须全 0（全部检查）；后 6 字节对应不存在的
        // 扇区 16..39，保持 0xFF（全部跳过）
        assertArrayEquals(
            "mask 方向反了会让设备跳过全部扇区（历史表现为命中 0/32）",
            byteArrayOf(0, 0, 0, 0, -1, -1, -1, -1, -1, -1),
            fake.lastSentFrame.data.copyOfRange(0, 10),
        )
    }

    @Test
    fun 字典攻击mask只清shouldCheck通过的位() = runTest {
        val fake = FakeChameleonTransport().apply {
            respond(
                cmd = ChameleonCommand.MF1_CHECK_KEYS_OF_SECTORS,
                status = ChameleonStatus.HF_TAG_OK.raw,
                data = ByteArray(490),
            )
        }

        // 只检查扇区 0 的 KeyA：应仅清 byte0 的 bit7
        ChameleonSession(fake).checkKeysOfSectors(
            keys = listOf(ByteArray(6)),
            shouldCheck = { sector, type -> sector == 0 && type == KeyType.A },
        )

        val mask = fake.lastSentFrame.data.copyOfRange(0, 10)
        assertEquals("扇区 0 KeyA 对应 bit7 应清零", 0x7F.toByte(), mask[0])
        assertArrayEquals(
            "后续字节应全部保持跳过",
            byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1, -1),
            mask.copyOfRange(1, 10),
        )
    }

    @Test
    fun 字典攻击结果按found位图解析出密钥() = runTest {
        val keyA = byteArrayOf(
            0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(),
            0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(),
        )
        val keyB = byteArrayOf(
            0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(),
            0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(),
        )
        val response = ByteArray(490).apply {
            // found[0]：bit7 = 扇区 0 KeyA 命中，bit6 = KeyB 命中
            this[0] = 0b1100_0000.toByte()
            // 密钥区自 offset 10 起：扇区 0 的 KeyA(6) 紧接着 KeyB(6)
            keyA.copyInto(this, 10)
            keyB.copyInto(this, 16)
        }
        val fake = FakeChameleonTransport().apply {
            respond(
                cmd = ChameleonCommand.MF1_CHECK_KEYS_OF_SECTORS,
                status = ChameleonStatus.HF_TAG_OK.raw,
                data = response,
            )
        }

        val sectors = ChameleonSession(fake).checkKeysOfSectors(listOf(ByteArray(6)))

        assertEquals(KeyStatus.FOUND, sectors[0].keyA.status)
        assertArrayEquals("KeyA 应为响应中回填的密钥", keyA, sectors[0].keyA.key)
        assertEquals(KeyStatus.FOUND, sectors[0].keyB.status)
        assertArrayEquals("KeyB 应为响应中回填的密钥", keyB, sectors[0].keyB.key)
        assertEquals("未命中位应为 MISSING", KeyStatus.MISSING, sectors[1].keyA.status)
    }

    // ------------------------------------------------------------------
    // 位运算二：HF14A_RAW options 位域（历史 bug 点）
    // ------------------------------------------------------------------

    @Test
    fun hf14aRaw的options是MSB优先位域_对齐CLI抓包帧() = runTest {
        val fake = FakeChameleonTransport().apply {
            respond(ChameleonCommand.HF14A_RAW, ChameleonStatus.HF_TAG_OK.raw, ByteArray(4))
        }

        ChameleonSession(fake).hf14aRaw(byteArrayOf(0x60, 0x00))

        // 对齐 CLI `hf 14a raw -s -c -d 6000` 抓包帧 70 00 64 00 10 60 00：
        // options=0x70 → bit6 等响应 / bit5 附 CRC / bit4 自动选卡（MSB 优先）
        val payload = fake.lastSentFrame.data
        assertEquals("位域按 LSB 组装会让设备不等卡应答", 0x70.toByte(), payload[0])
        assertArrayEquals("默认等卡应答 100ms", byteArrayOf(0x00, 0x64), payload.copyOfRange(1, 3))
        assertArrayEquals("2 字节 = 16 bit", byteArrayOf(0x00, 0x10), payload.copyOfRange(3, 5))
        assertArrayEquals(byteArrayOf(0x60, 0x00), payload.copyOfRange(5, 7))
    }
}
