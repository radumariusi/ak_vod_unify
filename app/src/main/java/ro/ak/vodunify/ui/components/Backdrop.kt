package ro.ak.vodunify.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.TitleKind

/**
 * Fundalul panoramic din Acasă (ca în AK VOD Hub): imaginea titlului cu focus acoperă tot ecranul, sub meniu,
 * și se schimbă după un focus stabil, nu la fiecare poster peste care treci.
 */
class BackdropState {
    var url: String? by mutableStateOf(null)

    /** Titlul cu focus (poster sau card „Continuă”): îl afișează hero-ul din Acasă. */
    var focused: FocusedTitle? by mutableStateOf(null)

    fun focus(title: FocusedTitle) {
        focused = title
        url = title.backdropUrl
    }
}

/** Ce arată hero-ul pentru cardul cu focus. */
data class FocusedTitle(
    val kind: TitleKind,
    val id: Long,
    val title: String,
    val meta: String,
    val overview: String,
    val backdropUrl: String?,
    val rating: Double? = null,
    /** Sigla de lângă note (aplicația, cinema…), ca pe poster; null = sigla platformei din catalog. */
    val badge: BadgeSpec? = null,
) {
    companion object {
        fun of(item: CatalogItem) = FocusedTitle(
            kind = item.kind,
            id = item.id,
            title = item.title,
            meta = listOfNotNull(item.year?.toString(), item.kind.label).joinToString(" · "),
            overview = item.overview,
            backdropUrl = item.backdropUrl,
            rating = item.rating,
        )
    }
}

val LocalBackdrop = staticCompositionLocalOf<BackdropState?> { null }

/** Imaginea de fundal cu degradeurile AK VOD: stânga și jos se topesc în culoarea fundalului. */
@Composable
fun TitleBackdrop(url: String?, modifier: Modifier = Modifier, opacity: Float = 0.86f, settleMillis: Long = 300) {
    var shown by remember { mutableStateOf(url) }
    LaunchedEffect(url) {
        if (shown != null && url != shown) delay(settleMillis)
        shown = url
    }
    val background = MaterialTheme.colorScheme.background
    Box(modifier.clipToBounds()) {
        Crossfade(shown, animationSpec = tween(400), label = "backdrop") { image ->
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = opacity,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to background,
                    0.40f to background.copy(alpha = 0.78f),
                    0.72f to background.copy(alpha = 0.15f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to background)))
    }
}
