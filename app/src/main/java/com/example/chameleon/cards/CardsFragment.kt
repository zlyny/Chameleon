package com.example.chameleon.cards

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.chameleon.MainViewModel
import com.example.chameleon.R
import com.example.chameleon.databinding.FragmentCardsBinding
import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.device.DumpCard
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 卡片管理页：列出 dump 卡片库中的卡片（Reader 页 Dump 自动入库）。
 * 每张卡片三个操作：
 * - 「写入设备」——把整卡数据写入 Chameleon 模拟卡（切换到模拟卡模式，
 *   设置 UID/ATQA/SAK 反碰撞数据后分帧写入 64 块，流程见 MainViewModel）；
 * - 「查看数据」——按扇区查看 64 块十六进制数据，全 0 扇区标注“未破解”；
 * - 「删除」——确认后从卡片库移除文件。
 */
class CardsFragment : Fragment() {

    private var _binding: FragmentCardsBinding? = null
    private val binding get() = requireNotNull(_binding)

    /** 共享 ViewModel：设备会话与写入模拟卡流程 */
    private val mainViewModel: MainViewModel by activityViewModels()

    /** 本页 ViewModel：卡片库列表与文件操作 */
    private val viewModel: CardsViewModel by viewModels()

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private lateinit var adapter: DumpCardAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCardsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DumpCardAdapter(
            subtitleOf = ::cardSubtitle,
            onWrite = ::onWriteClicked,
            onView = ::onViewClicked,
            onDelete = ::onDeleteClicked,
        )
        binding.recyclerCards.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.dumps.collect { cards ->
                        adapter.submitList(cards)
                        binding.textCardsEmpty.isVisible = cards.isEmpty()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Fragment 创建后 onStart 仅触发一次（覆盖首次进入）
        viewModel.refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // MainActivity 以 show/hide 切换页面，切页不触发 onStart/onStop，
        // 只回调本方法。每次重新可见时重扫卡片库：Reader 页可能刚 Dump
        // 了新卡片，不重扫就看不到（旧版只依赖 onStart，故需重启才显示）
        if (!hidden) viewModel.refresh()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** 列表项副标题：SAK / ATQA / 块数 / 保存时间 */
    fun cardSubtitle(dump: DumpCard): String = getString(
        R.string.card_item_subtitle,
        dump.sakHex,
        dump.atqaHex,
        dump.sizeBytes.toInt() / 2 / ChameleonSession.MF1_BLOCK_SIZE,
        timeFormat.format(Date(dump.savedAtMillis)),
    )

    private fun onWriteClicked(dump: DumpCard) {
        if (!mainViewModel.writeDumpToEmulator(dump)) {
            Snackbar.make(binding.root, R.string.card_write_start_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun onViewClicked(dump: DumpCard) {
        val blocks = viewModel.readBlocks(dump)
        if (blocks == null) {
            Snackbar.make(binding.root, R.string.card_write_start_failed, Snackbar.LENGTH_LONG).show()
            return
        }
        showDumpViewer(dump, blocks)
    }

    private fun onDeleteClicked(dump: DumpCard) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.card_delete_confirm_title)
            .setMessage(getString(R.string.card_delete_confirm_msg, dump.uidHex))
            .setPositiveButton(R.string.btn_card_delete) { _, _ ->
                if (viewModel.delete(dump)) {
                    Snackbar.make(binding.root, getString(R.string.card_deleted, dump.uidHex), Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 卡片数据查看对话框：按扇区分组展示全部块。dump 时未破解的扇区为
     * 全 0（dump 语义保留），在扇区标题标注“未破解”。
     */
    private fun showDumpViewer(dump: DumpCard, blocks: ByteArray) {
        val content = buildString {
            for (sector in 0 until blocks.size / ChameleonSession.MF1_BLOCK_SIZE / ChameleonSession.MF1_BLOCKS_PER_SECTOR) {
                val from = sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR
                val sectorData = blocks.copyOfRange(
                    from * ChameleonSession.MF1_BLOCK_SIZE,
                    (from + ChameleonSession.MF1_BLOCKS_PER_SECTOR) * ChameleonSession.MF1_BLOCK_SIZE,
                )
                val locked = sectorData.all { it == 0.toByte() }
                append(
                    getString(
                        if (locked) R.string.card_view_sector_locked else R.string.card_view_sector_ok,
                        sector,
                    ),
                )
                append('\n')
                for (i in 0 until ChameleonSession.MF1_BLOCKS_PER_SECTOR) {
                    val block = from + i
                    val line = blocks.copyOfRange(
                        block * ChameleonSession.MF1_BLOCK_SIZE,
                        (block + 1) * ChameleonSession.MF1_BLOCK_SIZE,
                    ).joinToString(" ") { "%02X".format(it) }
                    append("  B%02d  %s\n".format(block, line))
                }
                append('\n')
            }
        }.trimEnd()

        val textView = TextView(requireContext()).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextAppearance(android.R.style.TextAppearance_Small)
            setPadding(48, 32, 48, 32)
            text = content
        }
        val scroll = ScrollView(requireContext()).apply { addView(textView) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.card_view_title, dump.uidHex))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
