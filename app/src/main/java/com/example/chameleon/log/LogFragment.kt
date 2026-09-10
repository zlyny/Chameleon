package com.example.chameleon.log

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.chameleon.MainViewModel
import com.example.chameleon.R
import com.example.chameleon.databinding.FragmentLogBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * 日志页：展示协议通信日志（TX/RX 帧、流程信息、错误提示），
 * 按类型着色并自动滚动到底部，支持一键复制与清空。
 */
class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCopyLog.setOnClickListener { copyLogToClipboard() }
        binding.btnClearLog.setOnClickListener { viewModel.clearLog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.log.collect { renderLog(it) }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /**
     * 把当前全部日志（纯文本，每条一行）写入系统剪贴板，便于粘贴到
     * 其他应用分析。着色等信息不会进入剪贴板，只复制文本内容。
     */
    private fun copyLogToClipboard() {
        val entries = viewModel.log.value
        if (entries.isEmpty()) {
            Snackbar.make(binding.root, R.string.log_copy_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        val clipboard = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
            ?: return
        val text = entries.joinToString("\n") { it.text }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_title), text))
        // Android 13+ 复制纯文本时系统会显示统一的复制成功提示，
        // 再弹 Snackbar 会重复；Android 12 及以下自行提示
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Snackbar.make(binding.root, getString(R.string.log_copied, entries.size), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun renderLog(entries: List<MainViewModel.LogEntry>) {
        if (entries.isEmpty()) {
            binding.textLog.setText(R.string.log_empty)
            return
        }
        val builder = SpannableStringBuilder()
        entries.forEach { entry ->
            val start = builder.length
            builder.append(entry.text).append('\n')
            builder.setSpan(
                ForegroundColorSpan(
                    ContextCompat.getColor(requireContext(), entry.kind.colorRes()),
                ),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        binding.textLog.text = builder
        // 日志更新后滚动到底部
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun MainViewModel.LogKind.colorRes(): Int = when (this) {
        MainViewModel.LogKind.TX -> R.color.log_tx
        MainViewModel.LogKind.RX -> R.color.log_rx
        MainViewModel.LogKind.ERROR -> R.color.log_error
        MainViewModel.LogKind.INFO -> R.color.log_info
    }
}
