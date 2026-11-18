package jp.personal.mediadl.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Dark = darkColorScheme(
    primary = Color(0xFF9D8CFF), onPrimary = Color(0xFF1B0F5C),
    secondary = Color(0xFF4FD8FF), tertiary = Color(0xFFFF7AB6),
    background = Color(0xFF101218), surface = Color(0xFF16181F),
    surfaceVariant = Color(0xFF242733), surfaceContainer = Color(0xFF1C1F28),
)
private val Light = lightColorScheme(
    primary = Color(0xFF5B4BFF), secondary = Color(0xFF0091C2), tertiary = Color(0xFFD81B60),
    background = Color(0xFFF7F7FB), surface = Color(0xFFFFFFFF),
)

@Composable
fun MediaDLTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
