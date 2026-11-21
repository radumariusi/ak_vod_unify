package ro.ak.vodunify.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.playback.PosterBadge

private val BadgeBackground = Color(0xCC000000)

/** Etichetă mică pe poster, în același stil cu nota TMDb (ex. „S02E05 · 21:00”). */
@Composable
fun PosterLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(BadgeBackground, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * Sigla unei aplicații instalate (Netflix, Stremio…), luată din sistem. Dacă sistemul nu dă sigla, apare
 * numele aplicației ([fallbackLabel]) în locul ei, ca să nu rămână posterul fără nimic.
 */
@Composable
fun AppIconBadge(packageName: String, modifier: Modifier = Modifier, fallbackLabel: String? = null) {
    val context = LocalContext.current
    val icon: ImageBitmap? = remember(packageName) {
        val pm = context.packageManager
        runCatching { pm.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(packageName)?.toBitmap(160, 90)?.asImageBitmap() }.getOrNull()
    }
    if (icon == null) {
        val label = fallbackLabel ?: remember(packageName) {
            runCatching {
                context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            }.getOrNull()
        }
        label?.let { PosterLabel(it, modifier) }
        return
    }
    Box(
        modifier
            .size(24.dp)
            .background(BadgeBackground, RoundedCornerShape(5.dp))
            .padding(2.dp),
    ) {
        Image(icon, contentDescription = null, modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)))
    }
}

/** Cum află un poster sigla lui (platforma ta pe care e titlul sau playerul standard); dat de AppNavigation. */
val LocalPosterBadge = staticCompositionLocalOf<(suspend (TitleKind, Long) -> PosterBadge?)?> { null }

/** Sigla platformei / playerului pe un poster din catalog; nimic până se află (sau dacă nu e de unde). */
@Composable
fun PlatformBadge(kind: TitleKind, id: Long?, modifier: Modifier = Modifier) {
    val source = LocalPosterBadge.current ?: return
    if (id == null) return
    var badge by remember(kind, id) { mutableStateOf<PosterBadge?>(null) }
    LaunchedEffect(kind, id) { badge = runCatching { source(kind, id) }.getOrNull() }
    badge?.let { AppIconBadge(it.packageName, modifier, fallbackLabel = it.label) }
}

/** Sigla de cinema: un ecran cu „US” sau „RO” înăuntru, aceeași pentru ambele țări. */
@Composable
fun CinemaBadge(region: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(BadgeBackground, RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Box(
            Modifier
                .size(28.dp, 17.dp)
                .border(1.5.dp, Color.White, RoundedCornerShape(2.dp))
                .background(Color(0xFF1A1A1A), RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(region, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1)
        }
        // Piciorul ecranului.
        Box(Modifier.width(12.dp).height(2.dp).background(Color.White, RoundedCornerShape(1.dp)))
    }
}
