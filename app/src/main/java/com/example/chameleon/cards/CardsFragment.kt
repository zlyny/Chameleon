package com.example.chameleon.cards

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import com.example.chameleon.device.DumpContent
import com.example.chameleon.util.showSnackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 卡片管理页：列出 dump 卡片库中的卡片（Reader 页 Dump 自动入库）。
 * 每张卡片四个操作：
 * - 「写入槽」——把整卡数据写入 Chameleon 模拟卡（切换到模拟卡模式，
 *   设置 UID/ATQA/SAK 反碰撞数据后分帧写入 64 块，流程见 MainViewModel）；
 *   进行中全部操作按钮禁用，完成经 Snackbar 提示；
 * - 「查看」——按扇区查看 64 块十六进制数据，trailer 的密钥区与访问
 *   控制位着色区分，未知字节（XX）红色标注，见 [showDumpViewer]；
 * - 「导出」——导出为二进制 .bin 到系统 Download 目录（同名文件自动
 *   加序号；未知字节按区域规则填充，见 [DumpContent.toExportBinary]）；
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

    /** Android 9 及以下导出需运行时存储权限：请求期间暂存待导出卡片 */
    private var pendingExport: DumpCard? = null

    private val exportPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val dump = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        if (granted) {
            exportCard(dump)
        } else {
            showSnackbar(R.string.card_export_permission_denied, Snackbar.LENGTH_LONG)
        }
    }

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
            onExport = ::onExportClicked,
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
                launch {
                    // 设备流程（读卡/破解/Dump/写入槽）进行中禁用卡片操作。
                    // 本页与读卡页共用 readerState：lastError 由读卡页消费，
                    // lastSuccess（写入槽完成提示）由本页消费，互不重复
                    mainViewModel.readerState.collect { state ->
                        adapter.renderPhase(
                            busy = state.phase != MainViewModel.ReaderPhase.Idle,
                            writing = state.phase == MainViewModel.ReaderPhase.WritingEmu,
                        )
                        state.lastSuccess?.let {
                            showSnackbar(it, Snackbar.LENGTH_LONG)
                            mainViewModel.consumeLastSuccess()
                        }
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
            showSnackbar(R.string.card_write_start_failed, Snackbar.LENGTH_LONG)
        }
    }

    private fun onViewClicked(dump: DumpCard) {
        val content = viewModel.readBlocks(dump)
        if (content == null) {
            showSnackbar(R.string.card_read_failed)
            return
        }
        showDumpViewer(dump, content)
    }

    private fun onExportClicked(dump: DumpCard) {
        // Android 10+ 经 MediaStore 写 Download 无需任何权限；
        // 更早版本需先取得写存储权限（manifest 已声明 maxSdkVersion=28）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = dump
            exportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        exportCard(dump)
    }

    private fun onDeleteClicked(dump: DumpCard) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.card_delete_confirm_title)
            .setMessage(getString(R.string.card_delete_confirm_msg, dump.uidHex))
            .setPositiveButton(R.string.btn_card_delete) { _, _ ->
                if (viewModel.delete(dump)) {
                    showSnackbar(getString(R.string.card_deleted, dump.uidHex))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 导出卡片为 .bin 到 Download 目录，结果经 Snackbar 反馈 */
    private fun exportCard(dump: DumpCard) {
        val content = viewModel.readBlocks(dump)
        if (content == null) {
            showSnackbar(R.string.card_read_failed)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { viewModel.exportToDownloads(dump, content) }
                .onSuccess { name ->
                    showSnackbar(getString(R.string.card_export_done, name), Snackbar.LENGTH_LONG)
                }
                .onFailure { e ->
                    showSnackbar(
                        getString(R.string.card_export_failed, e.message ?: "未知错误"),
                        Snackbar.LENGTH_LONG,
                    )
                }
        }
    }

    /**
     * 卡片数据查看对话框：按扇区分组展示全部块，关键区域着色区分——
     * - trailer 的 KeyA/KeyB（密钥矩阵回填）→ 绿色；
     * - trailer 的访问控制位 [6:10] → 琥珀色；
     * - 未知字节（XX，未读取成功/未破解）→ 红色。
     * 扇区头标注未破解 / 部分未读取状态，底部附颜色图例帮助理解。
     */
    private fun showDumpViewer(dump: DumpCard, content: DumpContent) {
        val colorKey = ContextCompat.getColor(requireContext(), R.color.key_found)
        val colorControl = ContextCompat.getColor(requireContext(), R.color.dump_control)
        val colorUnknown = ContextCompat.getColor(requireContext(), R.color.log_error)
        val builder = SpannableStringBuilder()

        fun appendColored(text: String, color: Int) {
            val start = builder.length
            builder.append(text)
            builder.setSpan(
                ForegroundColorSpan(color),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        val sectorCount = content.blockCount / ChameleonSession.MF1_BLOCKS_PER_SECTOR
        for (sector in 0 until sectorCount) {
            val from = sector * ChameleonSession.MF1_BLOCKS_PER_SECTOR
            // 扇区状态：全部块未知 = 未破解；个别块未知 = dump 时读取失败
            val knownFlags = (from until from + ChameleonSession.MF1_BLOCKS_PER_SECTOR)
                .map(content::isBlockKnown)
            val titleRes = when {
                knownFlags.all { !it } -> R.string.card_view_sector_locked
                knownFlags.any { !it } -> R.string.card_view_sector_partial
                else -> R.string.card_view_sector_ok
            }
            if (titleRes == R.string.card_view_sector_locked) {
                appendColored(getString(titleRes, sector), colorUnknown)
            } else {
                builder.append(getString(titleRes, sector))
            }
            builder.append('\n')

            for (i in 0 until ChameleonSession.MF1_BLOCKS_PER_SECTOR) {
                val block = from + i
                builder.append("  B%02d  ".format(block))
                val isTrailer = i == ChameleonSession.MF1_TRAILER_BLOCK_IN_SECTOR
                for (b in 0 until ChameleonSession.MF1_BLOCK_SIZE) {
                    if (b > 0) builder.append(' ')
                    val index = block * ChameleonSession.MF1_BLOCK_SIZE + b
                    val isKnown = content.known[index]
                    val text = if (isKnown) "%02X".format(content.bytes[index]) else "XX"
                    when {
                        // 未知字节：红色 XX，直观区分“未读取”与“真实数据 00”
                        !isKnown -> appendColored(text, colorUnknown)

                        // trailer 访问控制位区域：琥珀色
                        isTrailer && b >= ChameleonSession.MF1_TRAILER_ACCESS_OFFSET &&
                            b < ChameleonSession.MF1_TRAILER_KEY_B_OFFSET ->
                            appendColored(text, colorControl)

                        // trailer 密钥区（密钥矩阵回填值）：绿色
                        isTrailer -> appendColored(text, colorKey)

                        else -> builder.append(text)
                    }
                }
                builder.append('\n')
            }
            builder.append('\n')
        }

        // 底部颜色图例：色块用对应颜色渲染
        builder.append(getString(R.string.card_view_legend)).append(' ')
        appendColored("■", colorKey)
        builder.append(' ').append(getString(R.string.card_view_legend_key)).append("    ")
        appendColored("■", colorControl)
        builder.append(' ').append(getString(R.string.card_view_legend_control)).append("    ")
        appendColored("XX", colorUnknown)
        builder.append(' ').append(getString(R.string.card_view_legend_unknown))

        val textView = TextView(requireContext()).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextAppearance(android.R.style.TextAppearance_Small)
            setPadding(48, 32, 48, 32)
            text = builder
        }
        val scroll = ScrollView(requireContext()).apply { addView(textView) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.card_view_title, dump.uidHex))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
