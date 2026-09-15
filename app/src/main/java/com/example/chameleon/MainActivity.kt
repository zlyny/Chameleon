package com.example.chameleon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.example.chameleon.di.LocalAppContainer
import com.example.chameleon.ui.theme.ChameleonTheme

/**
 * 应用主界面：唯一 Activity，全部 UI 由 Compose 渲染（见 [ChameleonApp]）。
 *
 * 原「MaterialToolbar + FragmentContainerView + BottomNavigationView + 四个
 * Fragment show/hide」的外壳已移除，Fragment 与 ViewBinding 随之成为历史。
 * 页面状态（当前 tab）经 [androidx.compose.runtime.saveable.rememberSaveable]
 * 保留，业务状态由 Activity 作用域的 ViewModel 持有。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 37：Android 15+ 对 targetSdk ≥ 35 强制 edge-to-edge，
        // 这里显式开启以保持各系统版本行为一致；状态栏 / 导航栏的避让由
        // Scaffold 的默认 contentWindowInsets（WindowInsets.systemBars）处理。
        enableEdgeToEdge()
        val container = (application as ChameleonApplication).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                ChameleonTheme {
                    ChameleonApp()
                }
            }
        }
    }
}
