package com.example.chameleon.device

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * 读卡数据导出：写入系统公共下载目录（Download/Chameleon），可被其他应用读取。
 *
 * 文件格式为 MCT / Proxmark 通用的 eml 文本：每个数据块一行 16 字节大写十六进制。
 * 未恢复密钥的扇区以全 0 块占位，保持 1K 卡 64 行的结构完整。
 *
 * - Android 10+：经 MediaStore.Downloads 写入，无需存储权限；
 * - Android 9 及以下：直接写公共下载目录，需 WRITE_EXTERNAL_STORAGE 运行时权限
 *   （由 UI 层在导出前申请，见 [requiresLegacyPermission]）。
 */
object DumpExporter {

    /** 导出文件所在子目录 */
    private const val DUMP_DIR = "Chameleon"

    /** Android 9 及以下导出前需确认的传统存储权限已授予 */
    fun requiresLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * 保存 dump 数据，返回用于展示的保存位置描述。
     *
     * @param tag 读到的标签信息，用于生成文件名（UID_SAK_ATQA）
     * @param blocks 全卡数据（1K 为 64 块 × 16 字节，未破解扇区为全 0）
     */
    fun save(context: Context, tag: TagInfo, blocks: ByteArray): String {
        val fileName = "UID${tag.uidHex}_SAK${tag.sakHex}_ATQA%04X.txt".format(tag.atqaValue)
        val content = buildEmlContent(blocks)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, fileName, content)
        } else {
            saveLegacy(fileName, content)
        }
    }

    private fun buildEmlContent(blocks: ByteArray): String = buildString {
        blocks.forEachChunk { chunk ->
            append(chunk.joinToString("") { "%02X".format(it) })
            append('\n')
        }
    }

    private fun ByteArray.forEachChunk(action: (ByteArray) -> Unit) {
        require(size % ChameleonSession.MF1_BLOCK_SIZE == 0) { "块数据长度不是 16 的倍数" }
        var offset = 0
        while (offset < size) {
            action(copyOfRange(offset, offset + ChameleonSession.MF1_BLOCK_SIZE))
            offset += ChameleonSession.MF1_BLOCK_SIZE
        }
    }

    private fun saveViaMediaStore(context: Context, fileName: String, content: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DUMP_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("创建导出文件失败")
        try {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.US_ASCII)) }
                ?: throw IOException("打开导出文件失败")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "Download/$DUMP_DIR/$fileName"
    }

    private fun saveLegacy(fileName: String, content: String): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DUMP_DIR)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("创建导出目录失败")
        val file = File(dir, fileName)
        file.writeText(content, Charsets.US_ASCII)
        return file.absolutePath
    }
}
