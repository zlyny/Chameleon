package com.example.chameleon.device

import android.content.Context
import java.io.File
import java.io.IOException

/** 一张已导出的 dump 卡片（元数据来自文件名，内容存于 .eml 文件） */
data class DumpCard(
    /** 文件名（卡片库内唯一标识），形如 1E6FE3A6_08_0400_1.eml */
    val fileName: String,
    val uidHex: String,
    val sakHex: String,
    val atqaHex: String,
    /** 读卡时检测的 PRNG 类型（dump 时随文件名一起保存，「加载」时无需重新检测） */
    val prng: PrngType,
    val savedAtMillis: Long,
    val sizeBytes: Long,
)

/**
 * dump 卡片库：保存 / 枚举 / 读取 / 删除，卡片管理页的唯一数据源。
 *
 * 存储位置为 app 专属外部目录 `Android/data/<pkg>/files/dumps/`：
 * 无需任何存储权限、可自由枚举与删除、卸载即清理。旧版本曾写入公共
 * Download 目录（MediaStore），因应用对公共目录没有持续读写权限、
 * 无法支撑卡片管理，已迁移至此（旧 Download 文件不受影响）。
 *
 * 文件格式为 MCT / Proxmark / CLI `hf mf eload` 通用的 eml 文本：
 * 每个数据块一行 32 个大写十六进制字符；未读取成功的字节以 XX 记录
 * （见 [DumpContent]，读取时掩码置 false、数值为 0），旧版全 0 行仍
 * 兼容读取（全部视为已知）。
 *
 * 文件名 `<UID>_<SAK>_<ATQA>_<PRNG>.eml`（如 `29919F13_08_0400_1.eml`，
 * 最后一段为 PRNG 编码，见 [PrngType.fileNameCode]）即元数据，无需额外
 * 索引文件；v4 之前的旧格式 `UID<UID>_SAK<SAK>_ATQA<ATQA>.eml` 仍兼容
 * 枚举（PRNG 视为未知）。
 *
 * Room 扩展预留（后续版本）：为"标记破解失败 / 读写失败扇区"引入
 * Room 实体（以 [DumpCard.fileName] 为主键，记录每扇区状态与备注），
 * 本仓库保持 API 不变（list/read/save/delete），由文件扫描升级为
 * 数据库查询即可，UI 层无需改动。当前阶段每扇区状态可直接从内容
 * 推导（扇区全 XX = 未破解），查看对话框即按此展示。
 */
class DumpRepository(private val context: Context) {

    private val dir: File
        get() = File(context.getExternalFilesDir(null), DUMP_DIR).apply { mkdirs() }

    /** 扫描卡片库，按保存时间倒序（最新在前） */
    fun listDumps(): List<DumpCard> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_EXT) }
            .orEmpty()
            .mapNotNull(::parseCard)
            .sortedByDescending { it.savedAtMillis }

    /** 保存 dump（同名覆盖，即同一张卡重新 dump 时更新），返回卡片条目 */
    fun save(tag: TagInfo, content: DumpContent): DumpCard {
        val fileName = "${tag.uidHex}_${tag.sakHex}_${tag.atqaHex}_${tag.prng.fileNameCode}$FILE_EXT"
        val file = File(dir, fileName)
        file.writeText(buildEmlContent(content), Charsets.US_ASCII)
        return parseCard(file) ?: throw IOException("生成卡片条目失败")
    }

    /**
     * 读取卡片内容（块数据 + 未知掩码）；文件缺失或内容非法返回 null。
     * XX 字节解析为未知（数值 0），其余十六进制对解析为已知字节。
     */
    fun read(fileName: String): DumpContent? {
        val file = File(dir, fileName)
        if (!file.isFile) return null
        return runCatching {
            val hex = file.readText(Charsets.US_ASCII).filterNot { it.isWhitespace() }
            require(hex.length % 2 == 0)
            val size = hex.length / 2
            require(size % ChameleonSession.MF1_BLOCK_SIZE == 0)
            val bytes = ByteArray(size)
            val known = BooleanArray(size)
            for (i in 0 until size) {
                // XX = 未知字节（未读取成功/未破解）：保持数值 0、掩码 false
                if (hex[i * 2] == 'X' || hex[i * 2 + 1] == 'X') continue
                val value =
                    (Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)
                require(value in 0..0xFF) // 非法十六进制字符（digit 返回 -1）时中止
                bytes[i] = value.toByte()
                known[i] = true
            }
            DumpContent(bytes, known)
        }.getOrNull()
    }

    /** 删除卡片，返回是否删除成功 */
    fun delete(fileName: String): Boolean = File(dir, fileName).delete()

    /** 内容 -> eml 文本：每块一行大写十六进制，未知字节记为 XX */
    private fun buildEmlContent(content: DumpContent): String = buildString {
        for (block in 0 until content.blockCount) {
            val from = block * ChameleonSession.MF1_BLOCK_SIZE
            for (i in from until from + ChameleonSession.MF1_BLOCK_SIZE) {
                if (content.known[i]) {
                    append("%02X".format(content.bytes[i]))
                } else {
                    append("XX")
                }
            }
            append('\n')
        }
    }

    /** 解析文件名元数据；新格式含 PRNG 段，v4 前旧格式（无 PRNG）视为未知 */
    private fun parseCard(file: File): DumpCard? {
        NEW_FILE_NAME_PATTERN.matchEntire(file.name)?.let { m ->
            return DumpCard(
                fileName = file.name,
                uidHex = m.groupValues[1],
                sakHex = m.groupValues[2],
                atqaHex = m.groupValues[3],
                prng = PrngType.ofFileNameCode(m.groupValues[4].toInt()),
                savedAtMillis = file.lastModified(),
                sizeBytes = file.length(),
            )
        }
        val legacy = LEGACY_FILE_NAME_PATTERN.matchEntire(file.name) ?: return null
        return DumpCard(
            fileName = file.name,
            uidHex = legacy.groupValues[1],
            sakHex = legacy.groupValues[2],
            atqaHex = legacy.groupValues[3],
            prng = PrngType.UNKNOWN,
            savedAtMillis = file.lastModified(),
            sizeBytes = file.length(),
        )
    }

    companion object {
        private const val DUMP_DIR = "dumps"
        private const val FILE_EXT = ".eml"

        /** 文件名元数据格式：29919F13_08_0400_1.eml（末段为 PRNG 编码） */
        private val NEW_FILE_NAME_PATTERN =
            Regex("([0-9A-F]+)_([0-9A-F]{2})_([0-9A-F]{4})_([0-9])\\.eml")

        /** v4 前的旧格式：UID1E6FE3A6_SAK08_ATQA0400.eml（无 PRNG 段） */
        private val LEGACY_FILE_NAME_PATTERN =
            Regex("UID([0-9A-F]+)_SAK([0-9A-F]{2})_ATQA([0-9A-F]{4})\\.eml")
    }
}
