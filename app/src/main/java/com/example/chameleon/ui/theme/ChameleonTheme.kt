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
 * **为什么不直接开启动态取色**：XML 主题并未下线——Manifest 里 Activity 的
 * 窗口主题仍是 `Theme.Chameleon`（决定窗口背景、状态栏/导航栏配色），
 * 所以这里沿用 values/colors.xml 中的主色，保证窗口与 Compose 内容观感一致。
 *
 * 日志、查看器等内联配色仍经 `colorResource(R.color.*)` 读取——它们已在
 * values/colors.xml 与 values-night/colors.xml 中分别定义，跟随系统深浅模式
 * 切换。新增颜色一律走 colors.xml（两套），不要在 Compose 里另写死色值。
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
