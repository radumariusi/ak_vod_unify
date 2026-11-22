package ro.ak.vodunify.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/*
 * Etichetele de pe postere (nota TMDb, sigla platformei, cinema, „S02E05 · 25 nov”) au aceeași formă:
 * aceeași înălțime, același fundal negru transparent și aceleași colțuri.
 */

/** Înălțimea tuturor etichetelor de pe poster. */
val BadgeHeight = 18.dp
val BadgeShape = RoundedCornerShape(4.dp)
val BadgeBackground = Color(0xCC000000)

/** Etichetă mică pe poster, în același stil cu nota TMDb (ex. „S02E05 · 21:00”). */
@Composable
fun PosterLabel(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(BadgeHeight)
            .background(BadgeBackground, BadgeShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Sigla unei aplicații instalate (Netflix, Stremio…), luată din sistem: cât o etichetă, cu aceleași colțuri,
 * fără chenar în jur. Dacă sistemul nu dă sigla, apare numele aplicației ([fallbackLabel]) ca etichetă.
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
    val wide = icon.width > icon.height * 1.3f
    Image(
        icon,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .height(BadgeHeight)
            .width(if (wide) BadgeHeight * 16f / 9f else BadgeHeight)
            .clip(BadgeShape),
    )
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

/**
 * Sigla de cinema, câte una pentru fiecare țară: o claketă albă lângă steagul țării (România: tricolorul;
 * SUA: dungi și cantonul albastru). Are forma celorlalte etichete de pe poster.
 */
@Composable
fun CinemaBadge(region: String, modifier: Modifier = Modifier) {
    val us = region.equals("US", ignoreCase = true)
    Row(
        modifier
            .height(BadgeHeight)
            .background(BadgeBackground, BadgeShape)
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .semantics { contentDescription = if (us) "Cinema SUA" else "Cinema România" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Clapper(Modifier.size(12.dp))
        Flag(us, Modifier.size(width = 17.dp, height = 12.dp).clip(RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun Clapper(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Corpul.
        drawRoundRect(Color.White, topLeft = Offset(0f, h * 0.42f), size = Size(w, h * 0.58f), cornerRadius = CornerRadius(w * 0.08f))
        // Bara de sus, cu dungi oblice.
        val top = Path().apply {
            moveTo(0f, h * 0.06f); lineTo(w, 0f); lineTo(w, h * 0.30f); lineTo(0f, h * 0.36f); close()
        }
        drawPath(top, Color.White)
        for (i in 0..2) {
            val x = w * (0.18f + i * 0.3f)
            drawLine(Color.Black, Offset(x, h * 0.34f), Offset(x + w * 0.14f, h * 0.02f), strokeWidth = w * 0.09f)
        }
    }
}

@Composable
private fun Flag(us: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (!us) {
            val third = w / 3f
            drawRect(Color(0xFF002B7F), Offset(0f, 0f), Size(third, h))
            drawRect(Color(0xFFFCD116), Offset(third, 0f), Size(third, h))
            drawRect(Color(0xFFCE1126), Offset(third * 2, 0f), Size(w - third * 2, h))
        } else {
            val stripe = h / 7f
            for (i in 0 until 7) {
                drawRect(if (i % 2 == 0) Color(0xFFB22234) else Color.White, Offset(0f, i * stripe), Size(w, stripe + 0.5f))
            }
            drawRect(Color(0xFF3C3B6E), Offset(0f, 0f), Size(w * 0.45f, stripe * 4))
            // Câteva stele, ca puncte.
            for (r in 0..1) for (c in 0..2) {
                drawCircle(Color.White, radius = h * 0.045f, center = Offset(w * (0.08f + c * 0.14f), stripe * (1f + r * 1.8f)))
            }
        }
    }
}
