package com.example.chameleon.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.chameleon.MainViewModel
import com.example.chameleon.R
import com.example.chameleon.databinding.FragmentReaderBinding
import com.example.chameleon.device.ChameleonSession
import com.example.chameleon.device.KeyState
import com.example.chameleon.device.KeyStatus
import com.example.chameleon.device.KeyType
import com.example.chameleon.device.PrngType
import com.example.chameleon.device.SectorKeys
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * 读卡页（本轮核心功能）：
 * 1. 「读卡」——读卡号并检测 PRNG（设备在模拟卡模式时自动切换为读卡器模式）；
 * 2. 「恢复密钥」——字典攻击，命中的扇区密钥位显示为绿色对号；
 * 3. 点击红色叉号——对该密钥位发起 Nested 攻击：Static PRNG 卡走
 *    StaticNested、Weak PRNG 卡走 Nested（自动适配）；攻击依赖随机数
 *    碰撞，单次未命中属正常现象，再次点击即可重试；
 * 4. 「Dump」——用已恢复密钥读取全卡数据存入 dump 卡片库（未破解扇区置 0），
 *    卡片管理页可查看 / 写入设备 / 删除。
 */
class ReaderFragment : Fragment() {

    private var _binding: FragmentReaderBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MainViewModel by activityViewModels()

    /** 密钥矩阵单元格引用：[0]=KeyA 行、[1]=KeyB 行，下标为扇区号 */
    private val keyCells = arrayOfNulls<ImageView>(2 * ChameleonSession.MF1_SECTOR_COUNT)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentReaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRead.setOnClickListener { viewModel.readCard() }
        binding.btnRecover.setOnClickListener { viewModel.recoverKeys() }
        binding.btnDump.setOnClickListener { viewModel.dumpCard() }
        buildKeyMatrix()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.connectionState.collect { renderButtons() } }
                launch { viewModel.readerState.collect { renderReaderState(it) } }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------
    // 状态渲染
    // ------------------------------------------------------------------

    private fun renderReaderState(state: MainViewModel.ReaderState) {
        renderTagInfo(state)
        renderKeyMatrix(state.sectors)
        renderDumpLocation(state.dumpLocation)
        renderButtons()

        state.lastError?.let { message ->
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            viewModel.consumeLastError()
        }
    }

    private fun renderTagInfo(state: MainViewModel.ReaderState) {
        val tag = state.tagInfo
        if (tag == null) {
            binding.textUid.text = getString(R.string.tag_value_placeholder)
            binding.textSak.text = getString(R.string.tag_value_placeholder)
            binding.textAtqa.text = getString(R.string.tag_value_placeholder)
            binding.textAts.text = getString(R.string.tag_value_placeholder)
            binding.textPrng.text = getString(R.string.tag_value_placeholder)
            binding.textType.text = getString(R.string.tag_value_placeholder)
            return
        }
        binding.textUid.text = tag.uidHex.chunked(2).joinToString(" ")
        binding.textSak.text = tag.sakHex
        binding.textAtqa.text = tag.atqaHex
        binding.textAts.text =
            if (tag.ats.isEmpty()) getString(R.string.tag_ats_none)
            else tag.ats.joinToString(" ") { "%02X".format(it) }
        // Static 卡在读卡时进一步判定漏洞代次，PRNG 栏显示 Static GEN1/GEN2
        binding.textPrng.text = tag.staticGen?.label ?: tag.prng.label
        binding.textType.text = tag.guessedType

        // Static PRNG 是 Static Nested 攻击的前提，额外标注提示
        binding.textPrng.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (tag.prng == PrngType.STATIC) R.color.key_found else R.color.log_info,
            ),
        )
    }

    private fun renderKeyMatrix(sectors: List<SectorKeys>) {
        for (s in 0 until ChameleonSession.MF1_SECTOR_COUNT) {
            val sectorKeys = sectors.getOrNull(s)
            cellOf(KEY_ROW_A, s)?.setImageResource(iconFor(sectorKeys?.keyA))
            cellOf(KEY_ROW_B, s)?.setImageResource(iconFor(sectorKeys?.keyB))
        }
    }

    private fun renderDumpLocation(location: String?) {
        binding.textDumpLocation.isVisible = location != null
        binding.textDumpLocation.text = getString(R.string.dump_saved_at, location)
    }

    /** 按钮可用性 = 已连接 && 空闲 && 各自的前置条件满足；进行中时切换文案 */
    private fun renderButtons() {
        val connected = viewModel.connectionState.value is MainViewModel.ConnectionState.Connected
        val state = viewModel.readerState.value
        val idle = state.phase == MainViewModel.ReaderPhase.Idle
        val cardReady = state.sectors.isNotEmpty()
        val hasKey = state.sectors.any {
            it.keyA.status == KeyStatus.FOUND || it.keyB.status == KeyStatus.FOUND
        }

        binding.btnRead.isEnabled = connected && idle
        binding.btnRecover.isEnabled = connected && idle && cardReady
        binding.btnDump.isEnabled = connected && idle && hasKey

        binding.btnRead.setText(
            when (state.phase) {
                MainViewModel.ReaderPhase.Reading -> R.string.btn_reading
                else -> R.string.btn_read
            },
        )
        binding.btnRecover.setText(
            when (state.phase) {
                MainViewModel.ReaderPhase.Recovering -> R.string.btn_recovering
                else -> R.string.btn_recover_keys
            },
        )
        binding.btnDump.setText(
            when (state.phase) {
                MainViewModel.ReaderPhase.Dumping -> R.string.btn_dumping
                else -> R.string.btn_dump
            },
        )
    }

    // ------------------------------------------------------------------
    // 密钥矩阵
    // ------------------------------------------------------------------

    /** 动态构建密钥矩阵：0-7 与 8-15 两组（各 9 列 × 3 行），避免 17 列超出屏幕 */
    private fun buildKeyMatrix() {
        val cellPx = (CELL_SIZE_DP * resources.displayMetrics.density).toInt()
        buildKeyMatrixGroup(binding.gridKeysLow, 0..7, cellPx)
        buildKeyMatrixGroup(binding.gridKeysHigh, 8..15, cellPx)
    }

    /** 一组矩阵：列号行 + KeyA 行 + KeyB 行 */
    private fun buildKeyMatrixGroup(grid: GridLayout, sectors: IntRange, cellPx: Int) {
        grid.columnCount = sectors.count() + 1
        grid.addView(makeLabel("", cellPx))
        sectors.forEach { s -> grid.addView(makeLabel(s.toString(), cellPx)) }
        listOf("A", "B").forEachIndexed { row, label ->
            grid.addView(makeLabel(label, cellPx))
            sectors.forEach { s -> grid.addView(makeKeyCell(row, s, cellPx)) }
        }
    }

    private fun makeLabel(text: String, sizePx: Int): TextView =
        TextView(requireContext()).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = sizePx
                height = sizePx
            }
            this.text = text
            gravity = android.view.Gravity.CENTER
            setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
        }

    private fun makeKeyCell(row: Int, sector: Int, sizePx: Int): ImageView =
        ImageView(requireContext()).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = sizePx
                height = sizePx
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.ic_key_unknown)
            setOnClickListener { onKeyCellClicked(row, sector) }
        }.also { keyCells[row * ChameleonSession.MF1_SECTOR_COUNT + sector] = it }

    private fun cellOf(row: Int, sector: Int): ImageView? =
        keyCells[row * ChameleonSession.MF1_SECTOR_COUNT + sector]

    private fun iconFor(state: KeyState?): Int = when (state?.status) {
        KeyStatus.FOUND -> R.drawable.ic_key_found
        KeyStatus.MISSING -> R.drawable.ic_key_missing
        else -> R.drawable.ic_key_unknown
    }

    /**
     * 点击密钥矩阵单元格。未破解（红色叉号）的位触发 Nested 攻击：
     * ViewModel 按卡的 PRNG 类型自动分派 Static/Weak 算法。命中后红叉
     * 刷新为绿勾；未命中时 Snackbar 提示重试（攻击成功率有限，属正常）。
     */
    private fun onKeyCellClicked(row: Int, sector: Int) {
        val sectorKeys = viewModel.readerState.value.sectors.getOrNull(sector) ?: return
        val keyState = if (row == KEY_ROW_A) sectorKeys.keyA else sectorKeys.keyB
        if (keyState.status != KeyStatus.MISSING) return

        viewModel.recoverKeyByNested(sector, if (row == KEY_ROW_A) KeyType.A else KeyType.B)
    }

    private companion object {
        const val KEY_ROW_A = 0
        const val KEY_ROW_B = 1

        /** 矩阵单元格边长：9 列 × 28dp = 252dp，小屏（320dp）也可容纳 */
        const val CELL_SIZE_DP = 28
    }
}
