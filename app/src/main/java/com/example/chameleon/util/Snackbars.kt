package com.example.chameleon.util

import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.example.chameleon.R
import com.google.android.material.snackbar.Snackbar

/**
 * 页面内 Snackbar 的统一入口：锚定到主界面底部导航栏上方弹出，
 * 避免遮挡导航按钮。
 *
 * [Snackbar.make] 默认依附 android.R.id.content 的底部，Snackbar 会
 * 直接盖住 [com.google.android.material.bottomnavigation.BottomNavigationView]；
 * [Snackbar.setAnchorView] 可将其固定在锚点视图上方。
 */
fun Fragment.showSnackbar(
    message: String,
    duration: Int = Snackbar.LENGTH_SHORT,
) {
    Snackbar.make(requireView(), message, duration)
        .apply { activity?.findViewById<View>(R.id.bottomNav)?.let(::setAnchorView) }
        .show()
}

/** [showSnackbar] 的资源版本 */
fun Fragment.showSnackbar(
    @StringRes messageRes: Int,
    duration: Int = Snackbar.LENGTH_SHORT,
) = showSnackbar(getString(messageRes), duration)
