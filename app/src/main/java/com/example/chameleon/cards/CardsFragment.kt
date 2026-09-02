package com.example.chameleon.cards

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.chameleon.R
import com.example.chameleon.databinding.FragmentCardsBinding

/**
 * 卡片管理页：占位，后续版本在此实现模拟卡槽管理（选择卡槽、
 * 加载 dump 到设备、卡槽类型设置等）。
 */
class CardsFragment : Fragment() {

    private var _binding: FragmentCardsBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCardsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
