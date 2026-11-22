package ro.ak.vodunify.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.RoundRect
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import coil.compose.AsyncImage
import ro.ak.vodunify.core.playback.AppRole
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.playback.PosterBadge

/*
 * Etichetele de pe postere (nota TMDb, sigla platformei, cinema, „S02E05 · 25 nov”) au aceeași formă:
 * aceeași înălțime, același fundal negru transparent și aceleași colțuri.
 */

/** Înălțimea tuturor etichetelor de pe poster. */
val BadgeHeight = 16.dp
val BadgeShape = RoundedCornerShape(4.dp)
val BadgeBackground = Color(0xCC000000)

/** Etichetă mică pe poster, în același stil cu nota TMDb (ex. „S02E05 · 21:00”). */
@Composable
fun PosterLabel(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(BadgeHeight)
            .background(BadgeBackground, BadgeShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Cum află un poster sigla pătrată a unui serviciu de streaming (Netflix „N”…): după pachetul aplicației sau
 * după id-ul serviciului ([ro.ak.vodunify.core.playback.StreamingService.id]). Dat de AppNavigation.
 */
class ServiceLogoLookup(
    val byPackage: suspend (String) -> String?,
    val byService: suspend (String) -> String?,
)

val LocalServiceLogos = staticCompositionLocalOf<ServiceLogoLookup?> { null }

/** O siglă pătrată (imagine), cât o etichetă, cu aceleași colțuri, fără chenar. */
@Composable
private fun SquareLogo(url: String, fallback: Painter?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        error = fallback,
        modifier = modifier.size(BadgeHeight).clip(BadgeShape),
    )
}

/** Inițiala unui nume într-un pătrat (când nu există nicio siglă), niciodată numele întreg. */
@Composable
private fun LetterLogo(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(BadgeHeight).background(BadgeBackground, BadgeShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(),
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/**
 * Sigla pătrată a unei aplicații: pentru serviciile de streaming varianta pătrată a siglei (Netflix „N”, din
 * TMDb / JustWatch), pentru playere (Stremio, Nuvio…) iconița pătrată din sistem. Niciodată numele întreg
 * sau bannerul lat al aplicației.
 */
@Composable
fun AppIconBadge(packageName: String, modifier: Modifier = Modifier, fallbackLabel: String? = null) {
    val context = LocalContext.current
    val lookup = LocalServiceLogos.current
    val isService = remember(packageName) { TargetApp.byPackage(packageName)?.role == AppRole.OFFICIAL }
    var logoUrl by remember(packageName) { mutableStateOf<String?>(null) }
    var looking by remember(packageName) { mutableStateOf(lookup != null && isService) }
    LaunchedEffect(packageName, lookup) {
        if (lookup != null && isService) {
            logoUrl = runCatching { lookup.byPackage(packageName) }.getOrNull()
            looking = false
        }
    }
    val icon: ImageBitmap? = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    val iconPainter = remember(icon) { icon?.let { BitmapPainter(it) } }
    val url = logoUrl
    when {
        url != null -> SquareLogo(url, iconPainter, modifier)
        looking -> Box(modifier.size(BadgeHeight))
        icon != null -> Image(
            icon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(BadgeHeight).clip(BadgeShape),
        )
        else -> {
            val label = fallbackLabel ?: remember(packageName) {
                runCatching {
                    context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
                }.getOrNull()
            }
            label?.takeIf { it.isNotBlank() }?.let { LetterLogo(it, modifier) }
        }
    }
}

/**
 * Sigla pătrată a unui serviciu de streaming pe care nu-l ai instalat (ex. „apare pe Netflix”), după id-ul
 * serviciului; fără siglă, inițiala numelui.
 */
@Composable
fun ServiceLogoBadge(serviceId: String?, name: String, modifier: Modifier = Modifier) {
    val lookup = LocalServiceLogos.current
    var logoUrl by remember(serviceId) { mutableStateOf<String?>(null) }
    var looking by remember(serviceId) { mutableStateOf(lookup != null && serviceId != null) }
    LaunchedEffect(serviceId, lookup) {
        if (lookup != null && serviceId != null) {
            logoUrl = runCatching { lookup.byService(serviceId) }.getOrNull()
            looking = false
        }
    }
    val url = logoUrl
    when {
        url != null -> SquareLogo(url, null, modifier)
        looking -> Box(modifier.size(BadgeHeight))
        else -> LetterLogo(name, modifier)
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

/**
 * Sigla de cinema: o claketă (tabla de filmare) cu steagul țării în corpul ei. România: tricolorul;
 * SUA: dungile și cantonul albastru. Are forma și înălțimea celorlalte etichete de pe poster.
 */
@Composable
fun CinemaBadge(region: String, modifier: Modifier = Modifier) {
    val us = region.equals("US", ignoreCase = true)
    Box(
        modifier
            .height(BadgeHeight)
            .background(BadgeBackground, BadgeShape)
            .padding(horizontal = 3.dp, vertical = 1.5.dp)
            .semantics { contentDescription = if (us) "Cinema SUA" else "Cinema România" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 19.dp, height = 13.dp)) {
            val w = size.width
            val h = size.height
            val barH = h * 0.28f
            val top = barH + h * 0.08f
            val bodyH = h - top
            val r = CornerRadius(w * 0.07f)
            // Bara de sus a claketei: albă, cu dungi oblice negre.
            drawRoundRect(Color.White, Offset.Zero, Size(w, barH), r)
            val stripe = w * 0.11f
            for (i in 0..3) {
                val x = w * (0.06f + i * 0.25f)
                val p = Path().apply {
                    moveTo(x, barH); lineTo(x + stripe, 0f); lineTo(x + stripe * 2, 0f); lineTo(x + stripe, barH); close()
                }
                drawPath(p, Color.Black)
            }
            // Corpul claketei = steagul țării.
            val body = Path().apply { addRoundRect(RoundRect(0f, top, w, h, r)) }
            clipPath(body) {
                if (!us) {
                    val third = w / 3f
                    drawRect(Color(0xFF002B7F), Offset(0f, top), Size(third, bodyH))
                    drawRect(Color(0xFFFCD116), Offset(third, top), Size(third, bodyH))
                    drawRect(Color(0xFFCE1126), Offset(third * 2, top), Size(w - third * 2, bodyH))
                } else {
                    val band = bodyH / 7f
                    drawRect(Color.White, Offset(0f, top), Size(w, bodyH))
                    for (i in 0 until 7 step 2) drawRect(Color(0xFFB22234), Offset(0f, top + i * band), Size(w, band))
                    drawRect(Color(0xFF3C3B6E), Offset(0f, top), Size(w * 0.45f, band * 4))
                    for (row in 0..1) for (col in 0..2) {
                        drawCircle(Color.White, radius = bodyH * 0.05f, center = Offset(w * (0.08f + col * 0.14f), top + band * (1f + row * 1.8f)))
                    }
                }
            }
        }
    }
}

/** Cum află un poster nota IMDb (prin cheia OMDb); dat de AppNavigation. */
val LocalImdbRating = staticCompositionLocalOf<(suspend (TitleKind, Long) -> Double?)?> { null }

/** Notele de pe poster, sus: TMDb și, alături, IMDb (când se știe). */
@Composable
fun PosterRatings(kind: TitleKind, id: Long?, tmdb: Double?, modifier: Modifier = Modifier) {
    val source = LocalImdbRating.current
    var imdb by remember(kind, id) { mutableStateOf<Double?>(null) }
    LaunchedEffect(kind, id) {
        if (source != null && id != null) imdb = runCatching { source(kind, id) }.getOrNull()
    }
    val t = tmdb?.takeIf { it > 0 }
    val i = imdb?.takeIf { it > 0 }
    if (t == null && i == null) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        t?.let { RatingChip(it) }
        i?.let { ImdbChip(it) }
    }
}

/**
 * Rândul de jos al unui poster: sigla (aplicația / cinema) și, alături, eticheta (data, „S01E02 · 45%”).
 * Eticheta se scurtează dacă nu încape.
 */
@Composable
fun PosterBottomRow(modifier: Modifier = Modifier, label: String? = null, badge: @Composable () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        badge()
        label?.takeIf { it.isNotBlank() }?.let { PosterLabel(it, Modifier.weight(1f, fill = false)) }
    }
}
