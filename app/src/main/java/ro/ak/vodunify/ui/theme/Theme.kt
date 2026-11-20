package ro.ak.vodunify.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFE50914)
val Background = Color(0xFF0E1116)
val SurfaceColor = Color(0xFF1A1F27)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF4FC3F7),
    background = Background,
    onBackground = Color(0xFFECEFF4),
    surface = SurfaceColor,
    onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF262C36),
    onSurfaceVariant = Color(0xFFB0B8C4),
)

@Composable
fun VodUnifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
