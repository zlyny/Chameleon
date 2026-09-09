package com.example.chameleon.scan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chameleon.R
import com.example.chameleon.databinding.ItemDeviceBinding

/** 扫描结果列表适配器，点击回调返回被选中的设备 */
class DeviceAdapter(
    private val onDeviceClick: (DiscoveredDevice) -> Unit,
) : ListAdapter<DiscoveredDevice, DeviceAdapter.ViewHolder>(DIFF_CALLBACK) {

    class ViewHolder(
        private val binding: ItemDeviceBinding, //由item_device.xml自动编译生成
        private val onDeviceClick: (DiscoveredDevice) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: DiscoveredDevice) {
            val context = binding.root.context
            binding.textDeviceName.text =
                device.name ?: context.getString(R.string.unknown_device)
            binding.textDeviceAddress.text =
                context.getString(R.string.device_item_subtitle, device.address, device.rssi)
            binding.imageRssi.setImageLevel(device.rssi.toSignalLevel())
            binding.root.setOnClickListener { onDeviceClick(device) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = //低频,只有池内view不够才调用
        ViewHolder(
            ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onDeviceClick,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {  //高频,每当一条数据滚进屏幕，拿一个(新建或回收的)ViewHolder 把数据画上去
        holder.bind(getItem(position))
    }

    private companion object {

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DiscoveredDevice>() {
            override fun areItemsTheSame(oldItem: DiscoveredDevice, newItem: DiscoveredDevice) =
                oldItem.address == newItem.address  //比较唯一号

            override fun areContentsTheSame(oldItem: DiscoveredDevice, newItem: DiscoveredDevice) =
                oldItem == newItem  //比较每个字段
        }

        /** RSSI（dBm）映射为 0~3 的信号等级，阈值参考 nRF Toolbox 的常用分档 */
        fun Int.toSignalLevel(): Int = when {
            this >= -55 -> 3
            this >= -67 -> 2
            this >= -80 -> 1
            else -> 0
        }
    }
}
