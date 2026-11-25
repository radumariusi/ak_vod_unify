package ro.ak.vodunify.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.painter.Painter
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.playback.AppRole
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.mediaKey
import ro.ak.vodunify.core.playback.PosterActivity
import ro.ak.vodunify.core.playback.PosterBadge
import ro.ak.vodunify.core.playback.PosterBadgeRequest
import ro.ak.vodunify.core.playback.ServiceBadge
import ro.ak.vodunify.core.playback.PosterStage

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

/** O informație despre un titlu, cu ce se știe deja ([peek], fără așteptare) și aflarea ei ([load], în fundal). */
class TitleLookup<T : Any>(
    val peek: (TitleKind, Long) -> T?,
    val load: suspend (TitleKind, Long) -> T?,
)

/** La fel, după o cheie (pachetul aplicației, id-ul serviciului). */
class KeyLookup(
    val peek: (String) -> String?,
    val load: suspend (String) -> String?,
)

/**
 * Cum află un poster sigla pătrată a unui serviciu de streaming (Netflix „N”…): după pachetul aplicației sau
 * după id-ul serviciului ([ro.ak.vodunify.core.playback.StreamingService.id]). Dat de AppNavigation.
 */
class ServiceLogoLookup(val byPackage: KeyLookup, val byService: KeyLookup)

val LocalServiceLogos = staticCompositionLocalOf<ServiceLogoLookup?> { null }

/**
 * Ce se știe deja (din [KeyLookup.peek]) se arată din primul cadru; altfel se află în fundal. [ready] devine
 * true după răspuns (cu sau fără siglă).
 */
@Composable
private fun rememberKeyLookup(lookup: KeyLookup?, key: String?): Pair<String?, Boolean> {
    val initial = remember(lookup, key) { if (lookup == null || key == null) null else lookup.peek(key) }
    var url by remember(lookup, key) { mutableStateOf(initial) }
    var ready by remember(lookup, key) { mutableStateOf(lookup == null || key == null || initial != null) }
    if (!ready && lookup != null && key != null) {
        LaunchedEffect(lookup, key) {
            url = runCatching { lookup.load(key) }.getOrNull()
            ready = true
        }
    }
    return url to ready
}

/**
 * Iconițele aplicațiilor, citite din sistem în fundal și partajate între postere.
 */
private object AppIcons {
    data class InstalledIcon(val bitmap: ImageBitmap?, val label: String?)

    private val cache = LruCache<String, InstalledIcon>(64)
    private val lock = Mutex()

    fun peek(packageName: String): InstalledIcon? = cache.get(packageName)

    suspend fun load(context: Context, packageName: String): InstalledIcon = lock.withLock {
        peek(packageName) ?: withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val app = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
            val bitmap = app?.let { runCatching { pm.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
            val label = app?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            InstalledIcon(bitmap, label).also { cache.put(packageName, it) }
        }
    }
}

/** O siglă pătrată (imagine), cât o etichetă, cu aceleași colțuri, fără chenar. */
@Composable
private fun SquareLogo(url: String, fallback: Painter?, modifier: Modifier = Modifier, onLoadError: () -> Unit = {}) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = fallback,
        error = fallback,
        onError = { onLoadError() },
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
fun AppIconBadge(
    packageName: String,
    modifier: Modifier = Modifier,
    fallbackLabel: String? = null,
    preferInstalledIcon: Boolean = false,
) {
    val context = LocalContext.current
    var installed by remember(packageName) { mutableStateOf(AppIcons.peek(packageName)) }
    if (installed == null) LaunchedEffect(packageName) { installed = AppIcons.load(context, packageName) }
    val icon = installed?.bitmap
    val isService = remember(packageName) { TargetApp.byPackage(packageName)?.role == AppRole.OFFICIAL }
    val lookupOnline = isService && (!preferInstalledIcon || installed != null && icon == null)
    val (logoUrl, _) = rememberKeyLookup(LocalServiceLogos.current?.byPackage, packageName.takeIf { lookupOnline })
    var logoFailed by remember(logoUrl, packageName) { mutableStateOf(false) }
    val iconPainter = remember(icon) { icon?.let { BitmapPainter(it) } }
    val name = fallbackLabel ?: installed?.label
    when {
        logoUrl != null && !logoFailed -> SquareLogo(logoUrl, iconPainter, modifier) { logoFailed = true }
        icon != null -> Image(
            icon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(BadgeHeight).clip(BadgeShape),
        )
        !name.isNullOrBlank() -> LetterLogo(name, modifier)
        else -> Box(modifier.size(BadgeHeight))
    }
}

/**
 * Sigla pătrată a unui serviciu de streaming pe care nu-l ai instalat (ex. „apare pe Netflix”), după id-ul
 * serviciului; fără siglă, inițiala numelui.
 */
@Composable
fun ServiceLogoBadge(serviceId: String?, name: String, modifier: Modifier = Modifier) {
    val (logoUrl, ready) = rememberKeyLookup(LocalServiceLogos.current?.byService, serviceId)
    var logoFailed by remember(logoUrl, serviceId) { mutableStateOf(false) }
    when {
        logoUrl != null && !logoFailed -> SquareLogo(logoUrl, null, modifier) { logoFailed = true }
        !ready -> Box(modifier.size(BadgeHeight))
        else -> LetterLogo(name, modifier)
    }
}

class PosterBadgeLookup(
    val peek: (PosterBadgeRequest) -> List<PosterBadge>?,
    val load: suspend (PosterBadgeRequest) -> List<PosterBadge>,
)

val LocalPosterBadge = staticCompositionLocalOf<PosterBadgeLookup?> { null }
val LocalPosterActivity = compositionLocalOf<Map<String, PosterActivity>> { emptyMap() }
val LocalPosterEpisodeTotal = staticCompositionLocalOf<TitleLookup<Int>?> { null }

/** O informație despre titlu: ce se știe deja din primul cadru, restul în fundal. */
@Composable
private fun <T : Any> rememberTitleLookup(lookup: TitleLookup<T>?, kind: TitleKind, id: Long?, refresh: Boolean = false): T? {
    val initial = remember(lookup, kind, id) { if (lookup == null || id == null) null else lookup.peek(kind, id) }
    var value by remember(lookup, kind, id) { mutableStateOf(initial) }
    if ((initial == null || refresh) && lookup != null && id != null) {
        LaunchedEffect(lookup, kind, id) { lookup.load(kind, id)?.let { value = it } }
    }
    return value
}

/** Bifă pentru titluri văzute, ceas pentru filme și seriale începute. */
@Composable
fun PosterViewingStatus(
    kind: TitleKind,
    id: Long?,
    modifier: Modifier = Modifier,
    stage: PosterStage? = null,
    episodeCount: Int? = null,
) {
    val activity = if (stage == null && id != null) LocalPosterActivity.current[mediaKey(kind, id)] else null
    val total = if (kind == TitleKind.TV && activity?.watchedEpisodes != null && !activity.hasActiveResume) {
        rememberTitleLookup(LocalPosterEpisodeTotal.current, kind, id, refresh = true) ?: episodeCount
    } else null
    val resolved = PosterBadgeRequest(kind, id, stage, activity).resolvedStage(total)
    when {
        resolved == PosterStage.WATCHED -> ViewingStatusBadge(watched = true, modifier)
        resolved == PosterStage.IN_PROGRESS -> ViewingStatusBadge(watched = false, modifier)
    }
}

/** Desenat ca simbol, independent de fontul instalat pe televizor. */
@Composable
fun ViewingStatusBadge(watched: Boolean, modifier: Modifier = Modifier) {
    val ink = if (watched) Color(0xFF9CF5CE) else Color(0xFFFFCF76)
    val background = if (watched) Color(0xF210362E) else Color(0xF2332713)
    Canvas(
        modifier
            .size(BadgeHeight)
            .background(background, BadgeShape)
            .semantics { contentDescription = if (watched) "Văzut" else "Văzut parțial" },
    ) {
        if (watched) {
            val start = Offset(size.width * .24f, size.height * .51f)
            val bend = Offset(size.width * .43f, size.height * .69f)
            val end = Offset(size.width * .77f, size.height * .32f)
            drawLine(ink, start, bend, 1.8.dp.toPx(), StrokeCap.Round)
            drawLine(ink, bend, end, 1.8.dp.toPx(), StrokeCap.Round)
        } else {
            drawCircle(ink, radius = size.minDimension * .32f, style = Stroke(1.4.dp.toPx()))
            val top = center - Offset(0f, size.height * .18f)
            val end = center + Offset(size.width * .12f, size.height * .07f)
            drawLine(ink, top, center, 1.3.dp.toPx(), StrokeCap.Round)
            drawLine(ink, center, end, 1.3.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Iconițele și eticheta intră pe rânduri suplimentare când posterul este îngust. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformBadge(
    kind: TitleKind,
    id: Long?,
    modifier: Modifier = Modifier,
    stage: PosterStage? = null,
    label: String? = null,
    confirmedService: ServiceBadge? = null,
) {
    val lookup = LocalPosterBadge.current
    val activity = id?.let { LocalPosterActivity.current[mediaKey(kind, it)] }
    val request = PosterBadgeRequest(kind, id, stage, activity, confirmedService)
    var badges by remember(lookup, request) { mutableStateOf(lookup?.peek?.invoke(request).orEmpty()) }
    if (lookup != null) LaunchedEffect(lookup, request) { badges = lookup.load(request) }
    if (badges.isEmpty() && label.isNullOrBlank()) return
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        badges.forEach { badge ->
            val iconModifier = Modifier.semantics { contentDescription = badge.label }
            val pkg = badge.packageName
            if (pkg == null) ServiceLogoBadge(badge.serviceId, badge.label, iconModifier)
            else AppIconBadge(pkg, iconModifier, fallbackLabel = badge.label)
        }
        label?.takeIf { it.isNotBlank() }?.let { PosterLabel(it) }
    }
}

/**
 * Ce siglă stă lângă un titlu: aplicația (pachetul), cinema RO / SUA, un serviciu neinstalat sau un text scurt
 * („Digital US”). Null = sigla platformei din catalog ([PlatformBadge]). Aceeași pe poster și în hero.
 */
sealed interface BadgeSpec {
    data class App(val packageName: String, val label: String? = null) : BadgeSpec
    data class PlaybackSource(val packageName: String?) : BadgeSpec
    data class ConfirmedService(val service: ServiceBadge) : BadgeSpec
    data object InProgress : BadgeSpec
    data class Cinema(val regions: List<String>) : BadgeSpec
    data class Service(val serviceId: String?, val name: String) : BadgeSpec
    data class Label(val text: String) : BadgeSpec
}

@Composable
fun TitleBadge(spec: BadgeSpec?, kind: TitleKind, id: Long?, modifier: Modifier = Modifier, label: String? = null) {
    when (spec) {
        null -> PlatformBadge(kind, id, modifier, label = label)
        BadgeSpec.InProgress -> PlatformBadge(kind, id, modifier, stage = PosterStage.IN_PROGRESS, label = label)
        is BadgeSpec.ConfirmedService -> PlatformBadge(kind, id, modifier, label = label, confirmedService = spec.service)
        else -> PosterBottomRow(modifier, label) {
            when (spec) {
                is BadgeSpec.PlaybackSource -> {
                    val pkg = spec.packageName?.takeIf { it.isNotBlank() }
                    if (pkg != null) {
                        val app = TargetApp.byPackage(pkg)?.displayName ?: pkg.substringAfterLast('.')
                        AppIconBadge(pkg, Modifier.semantics { contentDescription = app }, fallbackLabel = app, preferInstalledIcon = true)
                    } else {
                        PosterLabel("▶", Modifier.semantics { contentDescription = "Aplicația de redare necunoscută" })
                    }
                }
                is BadgeSpec.App -> AppIconBadge(spec.packageName, fallbackLabel = spec.label)
                is BadgeSpec.Cinema -> CinemaBadges(spec.regions)
                is BadgeSpec.Service -> ServiceLogoBadge(spec.serviceId, spec.name)
                is BadgeSpec.Label -> PosterLabel(spec.text)
                BadgeSpec.InProgress -> Unit
                is BadgeSpec.ConfirmedService -> Unit
            }
        }
    }
}

/**
 * Sigla de cinema: o claketă (tabla de filmare) cu steagul țării în corpul ei. România: tricolorul;
 * SUA: dungile și cantonul albastru. Are forma și înălțimea celorlalte etichete de pe poster.
 */
@Composable
internal fun CinemaBadges(regions: List<String>, modifier: Modifier = Modifier, availableWidth: Dp? = null) {
    if (regions.isEmpty()) return
    val gap = if (availableWidth == null) 3.dp else 1.dp
    val width = availableWidth?.let { ((it - gap * (regions.size - 1)) / regions.size).coerceIn(1.dp, 25.dp) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        regions.forEach { region -> CinemaBadge(region, scale = width?.let { it / 25.dp } ?: 1f) }
    }
}

@Composable
private fun CinemaBadge(region: String, scale: Float) {
    val us = region.equals("US", ignoreCase = true)
    Box(
        Modifier
            .height(BadgeHeight * scale)
            .background(BadgeBackground, BadgeShape)
            .padding(horizontal = 3.dp * scale, vertical = 1.5.dp * scale)
            .semantics { contentDescription = if (us) "Cinema SUA" else "Cinema România" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 19.dp * scale, height = 13.dp * scale)) {
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
val LocalImdbRating = staticCompositionLocalOf<TitleLookup<Double>?> { null }

/** Notele de pe poster, sus: TMDb și, alături, IMDb (când se știe). */
@Composable
fun PosterRatings(kind: TitleKind, id: Long?, tmdb: Double?, modifier: Modifier = Modifier) {
    val imdb = rememberTitleLookup(LocalImdbRating.current, kind, id)
    PosterRatingChips(tmdb, imdb, modifier)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PosterRatingChips(tmdb: Double?, imdb: Double?, modifier: Modifier = Modifier) {
    val t = tmdb?.takeIf { it > 0 }
    val i = imdb?.takeIf { it > 0 }
    if (t == null && i == null) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
