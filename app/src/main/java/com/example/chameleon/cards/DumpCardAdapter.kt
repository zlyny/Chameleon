package com.example.chameleon.cards

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chameleon.R
import com.example.chameleon.databinding.ItemDumpCardBinding
import com.example.chameleon.device.DumpCard

/**
 * dump 卡片列表 Adapter：每张卡片展示 UID 与元数据，
 * 五个操作按钮（写入槽 / 加载 / 查看 / 导出 / 删除）经构造回调交还 Fragment 处理。
 *
 * 设备流程（读卡 / 破解 / Dump / 写入槽 / mfkey32）进行中经 [renderPhase] 禁用
 * 全部操作按钮，防止写入途中误删卡片或重复发起设备操作。
 */
class DumpCardAdapter(
    private val subtitleOf: (DumpCard) -> String,
    private val onWrite: (DumpCard) -> Unit,
    private val onLoad: (DumpCard) -> Unit,
    private val onView: (DumpCard) -> Unit,
    private val onExport: (DumpCard) -> Unit,
    private val onDelete: (DumpCard) -> Unit,
) : ListAdapter<DumpCard, DumpCardAdapter.ViewHolder>(DIFF) {

    /** 设备流程进行中：禁用全部操作按钮 */
    private var busy = false

    /** 写入槽进行中：写入按钮切换为“写入中…”文案 */
    private var writing = false

    /** 渲染设备流程阶段（见 ReaderPhase），变化时刷新列表 */
    fun renderPhase(busy: Boolean, writing: Boolean) {
        if (this.busy == busy && this.writing == writing) return
        this.busy = busy
        this.writing = writing
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDumpCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemDumpCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(dump: DumpCard) {
            binding.textCardUid.text = "UID ${dump.uidHex}"
            binding.textCardSubtitle.text = subtitleOf(dump)
            binding.btnCardWrite.setOnClickListener { onWrite(dump) }
            binding.btnCardLoad.setOnClickListener { onLoad(dump) }
            binding.btnCardView.setOnClickListener { onView(dump) }
            binding.btnCardExport.setOnClickListener { onExport(dump) }
            binding.btnCardDelete.setOnClickListener { onDelete(dump) }

            val enabled = !busy
            binding.btnCardWrite.isEnabled = enabled
            binding.btnCardLoad.isEnabled = enabled
            binding.btnCardView.isEnabled = enabled
            binding.btnCardExport.isEnabled = enabled
            binding.btnCardDelete.isEnabled = enabled
            binding.btnCardWrite.setText(
                if (writing) R.string.btn_card_writing else R.string.btn_card_write,
            )
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DumpCard>() {
            override fun areItemsTheSame(oldItem: DumpCard, newItem: DumpCard) =
                oldItem.fileName == newItem.fileName

            override fun areContentsTheSame(oldItem: DumpCard, newItem: DumpCard) = oldItem == newItem
        }
    }
}
