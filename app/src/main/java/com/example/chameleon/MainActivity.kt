package com.example.chameleon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
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
        setContent {
            ChameleonTheme {
                ChameleonApp(mainViewModel = viewModel())
            }
        }
    }
}
