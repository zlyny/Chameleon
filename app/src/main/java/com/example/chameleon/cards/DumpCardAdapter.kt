package com.example.chameleon.cards

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chameleon.databinding.ItemDumpCardBinding
import com.example.chameleon.device.DumpCard

/**
 * dump 卡片列表 Adapter：每张卡片展示 UID 与元数据，
 * 三个操作按钮（写入设备 / 查看数据 / 删除）经构造回调交还 Fragment 处理。
 */
class DumpCardAdapter(
    private val subtitleOf: (DumpCard) -> String,
    private val onWrite: (DumpCard) -> Unit,
    private val onView: (DumpCard) -> Unit,
    private val onDelete: (DumpCard) -> Unit,
) : ListAdapter<DumpCard, DumpCardAdapter.ViewHolder>(DIFF) {

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
            binding.btnCardView.setOnClickListener { onView(dump) }
            binding.btnCardDelete.setOnClickListener { onDelete(dump) }
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
