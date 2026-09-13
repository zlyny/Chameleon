package com.example.chameleon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Compose 侧主题：与 XML 主题 `Theme.Chameleon`（parent 为
 * `Theme.Material3.DayNight.NoActionBar`）保持一致。
 *
 * 当前只有部分页面（日志页）用 Compose 渲染，它们与 View 页面同屏共存，
 * 因此这里刻意**不开启动态取色**、并沿用 values/colors.xml 中的主色，
 * 避免同一界面内 Compose 区域与 View 区域配色/深浅模式不一致。
 *
 * 日志等内联配色仍经 `colorResource(R.color.log_*)` 读取——它们已在
 * values/colors.xml 与 values-night/colors.xml 中分别定义，跟随
 * 系统深浅模式切换，Compose 与 View 两侧共用同一份色值。
 */
private val Purple200 = Color(0xFFBB86FC)
private val Purple500 = Color(0xFF6200EE)
private val Teal200 = Color(0xFF03DAC5)
private val White = Color(0xFFFFFFFF)
private val Black = Color(0xFF000000)

private val LightColors = lightColorScheme(
    primary = Purple500,
    onPrimary = White,
    secondary = Teal200,
    onSecondary = Black,
)

private val DarkColors = darkColorScheme(
    primary = Purple200,
    onPrimary = Black,
    secondary = Teal200,
    onSecondary = Black,
)

@Composable
fun ChameleonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
