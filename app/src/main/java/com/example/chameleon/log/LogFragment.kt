package com.example.chameleon.log

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
import kotlinx.coroutines.launch

/**
 * 日志页：展示协议通信日志（TX/RX 帧、流程信息、错误提示），
 * 按类型着色并自动滚动到底部，支持清空。
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
