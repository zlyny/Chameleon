package com.example.chameleon.cards

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chameleon.device.DumpCard
import com.example.chameleon.device.DumpRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 卡片管理页 ViewModel：维护 dump 卡片库列表，承载不依赖设备的本地操作
 * （枚举 / 读取 / 删除）。写入设备的流程涉及 BLE 会话，由共享的
 * [com.example.chameleon.MainViewModel.writeDumpToEmulator] 承担。
 *
 * 文件扫描走 IO 调度器，避免列表刷新阻塞主线程。
 */
class CardsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DumpRepository(application)

    private val _dumps = MutableStateFlow<List<DumpCard>>(emptyList())
    val dumps: StateFlow<List<DumpCard>> = _dumps.asStateFlow()

    init {
        refresh()
    }

    /** 重新扫描卡片库（进入页面 / 删除 / Reader 页新 Dump 后调用） */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val cards = repository.listDumps()
            _dumps.value = cards
        }
    }

    /** 删除卡片并刷新列表，返回是否删除成功 */
    fun delete(dump: DumpCard): Boolean {
        val ok = repository.delete(dump.fileName)
        refresh()
        return ok
    }

    /** 读取卡片全部块数据（查看数据 / 写入设备共用），文件异常返回 null */
    fun readBlocks(dump: DumpCard): ByteArray? = repository.readBlocks(dump.fileName)
}
