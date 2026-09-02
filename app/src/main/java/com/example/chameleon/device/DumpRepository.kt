package com.example.chameleon.device

import android.content.Context
import java.io.File
import java.io.IOException

/** 一张已导出的 dump 卡片（元数据来自文件名，内容存于 .eml 文件） */
data class DumpCard(
    /** 文件名（卡片库内唯一标识），形如 UID1E6FE3A6_SAK08_ATQA0400.eml */
    val fileName: String,
    val uidHex: String,
    val sakHex: String,
    val atqaHex: String,
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
 * 每个数据块一行 32 个大写十六进制字符；未破解扇区为全 0 行。
 * 文件名 `UID<UID>_SAK<SAK>_ATQA<ATQA>.eml` 即元数据（ATQA 为线上
 * 字节序，可逆向解析回原始字节），无需额外索引文件。
 *
 * Room 扩展预留（后续版本）：为"标记破解失败 / 读写失败扇区"引入
 * Room 实体（以 [DumpCard.fileName] 为主键，记录每扇区状态与备注），
 * 本仓库保持 API 不变（list/read/save/delete），由文件扫描升级为
 * 数据库查询即可，UI 层无需改动。当前阶段每扇区状态可直接从内容
 * 推导（扇区全 0 = 未破解），查看对话框即按此展示。
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
    fun save(tag: TagInfo, blocks: ByteArray): DumpCard {
        require(blocks.size % ChameleonSession.MF1_BLOCK_SIZE == 0) { "块数据长度不是 16 的倍数" }
        val fileName = "UID${tag.uidHex}_SAK${tag.sakHex}_ATQA${tag.atqaHex}$FILE_EXT"
        val file = File(dir, fileName)
        file.writeText(buildEmlContent(blocks), Charsets.US_ASCII)
        return parseCard(file) ?: throw IOException("生成卡片条目失败")
    }

    /** 读取卡片全部块数据；文件缺失或内容非法返回 null */
    fun readBlocks(fileName: String): ByteArray? {
        val file = File(dir, fileName)
        if (!file.isFile) return null
        return runCatching {
            val hex = file.readText(Charsets.US_ASCII).filterNot { it.isWhitespace() }
            val data = ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
            data.takeIf { it.size % ChameleonSession.MF1_BLOCK_SIZE == 0 }
        }.getOrNull()
    }

    /** 删除卡片，返回是否删除成功 */
    fun delete(fileName: String): Boolean = File(dir, fileName).delete()

    /** 块数据 -> eml 文本：每块一行大写十六进制 */
    private fun buildEmlContent(blocks: ByteArray): String = buildString {
        blocks.forEachChunk { chunk ->
            append(chunk.joinToString("") { "%02X".format(it) })
            append('\n')
        }
    }

    private fun ByteArray.forEachChunk(action: (ByteArray) -> Unit) {
        var offset = 0
        while (offset < size) {
            action(copyOfRange(offset, offset + ChameleonSession.MF1_BLOCK_SIZE))
            offset += ChameleonSession.MF1_BLOCK_SIZE
        }
    }

    private fun parseCard(file: File): DumpCard? {
        val m = FILE_NAME_PATTERN.matchEntire(file.name) ?: return null
        return DumpCard(
            fileName = file.name,
            uidHex = m.groupValues[1],
            sakHex = m.groupValues[2],
            atqaHex = m.groupValues[3],
            savedAtMillis = file.lastModified(),
            sizeBytes = file.length(),
        )
    }

    companion object {
        private const val DUMP_DIR = "dumps"
        private const val FILE_EXT = ".eml"

        /** 文件名元数据格式：UID1E6FE3A6_SAK08_ATQA0400.eml */
        private val FILE_NAME_PATTERN =
            Regex("UID([0-9A-F]+)_SAK([0-9A-F]{2})_ATQA([0-9A-F]{4})\\.eml")
    }
}
