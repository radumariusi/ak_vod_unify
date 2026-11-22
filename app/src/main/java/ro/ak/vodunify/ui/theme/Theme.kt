package ro.ak.vodunify.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Identitatea vizuală AK VOD: fundal bleumarin închis, accent roșu doar pentru marcă și progres.
 * Butoanele, filele și listele de alegere au aceeași formă și aceleași tonuri peste tot (ca în meniul principal):
 * - în repaus: „sticlă” albă transparentă (sau nimic, la file), text alb;
 * - activ / ales (fila curentă, opțiunea aleasă): sticlă mai deschisă, text alb îngroșat;
 * - cu focus (D-pad): plin alb, text bleumarin închis. Cardurile cu focus: contur alb.
 */

/** Roșul AK VOD: marca și barele de progres. */
val Accent = Color(0xFFE50914)
val Background = Color(0xFF0C1017)
val SurfaceColor = Color(0xFF151D2A)
val SurfaceVariantColor = Color(0xFF202C3D)

/** Focus pe butoane, file și chip-uri: plin alb cu text închis (ca în meniul principal). */
val FocusFill = Color(0xFFFFFFFF)
val OnFocusFill = Color(0xFF14202F)

/** Sticla butoanelor în repaus. */
val GlassIdle = Color(0x1FFFFFFF)

/** Sticla mai deschisă: fila curentă, opțiunea aleasă, acțiunea principală. */
val GlassActive = Color(0x42FFFFFF)

/** Fundalul barelor care grupează butoane (meniul principal). */
val GlassBar = Color(0x26FFFFFF)

/** Forma butoanelor, filelor și chip-urilor: capsulă. */
val PillShape = RoundedCornerShape(percent = 50)

/** Forma rândurilor pe două linii (liste de alegere, setări pliabile, sezoane). */
val RowShape = RoundedCornerShape(16.dp)

/** Conturul de focus al cardurilor: gros și aproape alb, vizibil de la 3 m pe orice poster. */
val FocusBorder = Color(0xFFE8EAED)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C1016),
    onPrimaryContainer = Color(0xFFFFDAD6),
    inversePrimary = Accent,
    secondary = FocusFill,
    onSecondary = OnFocusFill,
    secondaryContainer = GlassActive,
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFF01D277),
    onTertiary = Color(0xFF00210F),
    tertiaryContainer = Color(0xFF0D3B2A),
    onTertiaryContainer = Color(0xFFB7F2D2),
    background = Background,
    onBackground = Color(0xFFE8EAED),
    surface = SurfaceColor,
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = SurfaceVariantColor,
    onSurfaceVariant = Color(0xFFBBC6D6),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE8EAED),
    inverseOnSurface = Background,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF4A5A70),
    outlineVariant = Color(0xFF2B3A50),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2B3A50),
    surfaceDim = Background,
    surfaceContainerLowest = Color(0xFF080B10),
    surfaceContainerLow = Color(0xFF111824),
    surfaceContainer = SurfaceColor,
    surfaceContainerHigh = Color(0xFF1A2333),
    surfaceContainerHighest = SurfaceVariantColor,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Temă întunecată pentru TV și telefon. Culoarea textului implicit e cea a temei (nu negru). */
@Composable
fun VodUnifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, shapes = AppShapes) {
        CompositionLocalProvider(LocalContentColor provides Colors.onBackground, content = content)
    }
}
