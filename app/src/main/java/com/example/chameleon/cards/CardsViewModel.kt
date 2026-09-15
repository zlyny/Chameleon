package com.example.chameleon.cards

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chameleon.data.DumpCard
import com.example.chameleon.data.DumpContent
import com.example.chameleon.data.DumpRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 卡片管理页 ViewModel：维护 dump 卡片库列表，承载不依赖设备的本地操作
 * （枚举 / 读取 / 删除 / 导出）。写入槽的流程涉及 BLE 会话，由共享的
 * [com.example.chameleon.MainViewModel.writeDumpToEmulator] 承担。
 *
 * 文件扫描与导出走 IO 调度器，避免阻塞主线程。
 *
 * 依赖经构造注入（见 [com.example.chameleon.di.AppContainer]）：本类不再
 * 持有 `Application`，因此可以在 JVM 单测里直接实例化。
 */
class CardsViewModel(
    private val dumpRepository: DumpRepository,
    private val contentResolver: ContentResolver,
) : ViewModel() {

    private val _dumps = MutableStateFlow<List<DumpCard>>(emptyList())
    val dumps: StateFlow<List<DumpCard>> = _dumps.asStateFlow()

    init {
        refresh()
    }

    /** 重新扫描卡片库（进入页面 / 删除 / Reader 页新 Dump 后调用） */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val cards = dumpRepository.listDumps()
            _dumps.value = cards
        }
    }

    /** 删除卡片并刷新列表，返回是否删除成功 */
    fun delete(dump: DumpCard): Boolean {
        val ok = dumpRepository.delete(dump.fileName)
        refresh()
        return ok
    }

    /** 读取卡片内容（查看 / 写入槽 / 导出共用），文件异常返回 null */
    fun readBlocks(dump: DumpCard): DumpContent? = dumpRepository.read(dump.fileName)

    /**
     * 导出卡片为二进制 .bin 到系统 Download 目录，返回写入的文件名。
     *
     * 未知字节按区域规则填充（见 [DumpContent.toExportBinary]）。同名
     * 文件自动追加 " (n)" 序号。Android 10+ 经 MediaStore 写入（无需
     * 任何权限）；Android 9 及以下直接写公共 Download 目录，调用方需
     * 先取得 WRITE_EXTERNAL_STORAGE 运行时权限。
     */
    suspend fun exportToDownloads(dump: DumpCard, content: DumpContent): String =
        withContext(Dispatchers.IO) {
            val binary = content.toExportBinary()
            val baseName = dump.fileName.removeSuffix(".eml") + FILE_EXT_BIN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                insertIntoDownloads(baseName, binary)
            } else {
                writeLegacyFile(baseName, binary)
            }
        }

    /** Android 10+：经 MediaStore 写入 Download 集合（无需存储权限） */
    private fun insertIntoDownloads(baseName: String, binary: ByteArray): String {
        val resolver = contentResolver
        // 同名判断以本应用可见（自己贡献）的文件为限；与其他应用文件
        // 重名时由系统在插入阶段自动加序号，下方回查实际文件名兜底
        val name = uniqueName(baseName) { candidate ->
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(candidate),
                null,
            )?.use { it.count > 0 } ?: false
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore 创建下载项失败")
        resolver.openOutputStream(uri)?.use { it.write(binary) }
            ?: throw IOException("打开下载项输出流失败")
        // 系统对重名文件可能自动改名，回查实际文件名用于结果提示
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        return name
    }

    /** Android 9 及以下：直接写公共 Download 目录（调用方需持有写权限） */
    private fun writeLegacyFile(baseName: String, binary: ByteArray): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .apply { mkdirs() }
        val name = uniqueName(baseName) { File(dir, it).exists() }
        File(dir, name).writeBytes(binary)
        return name
    }

    /** 计算不冲突的文件名：存在同名时追加 " (n)" 序号（n 从 1 递增） */
    private fun uniqueName(baseName: String, exists: (String) -> Boolean): String {
        val stem = baseName.removeSuffix(FILE_EXT_BIN)
        var candidate = baseName
        var index = 1
        while (exists(candidate)) candidate = "$stem (${index++})$FILE_EXT_BIN"
        return candidate
    }

    private companion object {
        const val FILE_EXT_BIN = ".bin"
    }
}
