package ro.ak.vodunify.ui.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.PersonSummary
import ro.ak.vodunify.ui.navigation.LocalCatalogActions
import java.util.Locale

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(horizontal = layoutSpec().screenPadding, vertical = 6.dp),
    )
}

/** Imagine de rețea cu fundal neutru și text de rezervă când lipsește imaginea. */
@Composable
fun NetworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallbackText: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Box(modifier.background(placeholderColor), contentAlignment = Alignment.Center) {
        if (url == null) {
            if (fallbackText != null) {
                Text(
                    fallbackText,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun formatRating(rating: Double?): String? = rating?.let { String.format(Locale.US, "★ %.1f", it) }

/** Insigna TMDb de pe poster (ca în AK VOD Hub): sigla în culorile TMDb + nota, mică, să nu acopere posterul. */
@Composable
fun RatingChip(rating: Double, modifier: Modifier = Modifier) {
    BrandRating(rating, modifier) { TmdbSquareLogo() }
}

/** Nota IMDb, cu sigla pătrată IMDb (galbenă). */
@Composable
fun ImdbChip(rating: Double, modifier: Modifier = Modifier) {
    BrandRating(rating, modifier) { ImdbSquareLogo() }
}

/** Textul siglelor mici: fără spațiu în plus deasupra / dedesubt, ca literele să stea în pătrat. */
private val LogoText = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/** Sigla pătrată TMDb: pătrat bleumarin cu „TM / DB” în degradeul verde → albastru TMDb. */
@Composable
fun TmdbSquareLogo(modifier: Modifier = Modifier) {
    val gradient = Brush.horizontalGradient(listOf(Color(0xFF90CEA1), Color(0xFF01B4E4)))
    Box(
        modifier
            .size(BadgeHeight)
            .background(Color(0xFF0D253F), BadgeShape)
            .semantics { contentDescription = "TMDb" },
        contentAlignment = Alignment.Center,
    ) {
        // Literele albe, colorate apoi cu degradeul TMDb (SrcIn păstrează doar forma literelor).
        Column(
            Modifier
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(gradient, blendMode = BlendMode.SrcIn)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val style = LogoText.copy(color = Color.White, fontSize = 6.5.sp, lineHeight = 6.5.sp, fontWeight = FontWeight.Black)
            Text("TM", style = style, maxLines = 1, softWrap = false)
            Text("DB", style = style, maxLines = 1, softWrap = false)
        }
    }
}

/** Sigla pătrată IMDb: pătrat galben cu „IMDb” negru (ca iconița aplicației IMDb). */
@Composable
fun ImdbSquareLogo(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(BadgeHeight)
            .background(Color(0xFFF5C518), BadgeShape)
            .semantics { contentDescription = "IMDb" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "IMDb",
            style = LogoText.copy(color = Color.Black, fontSize = 5.5.sp, lineHeight = 5.5.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.2).sp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun BrandRating(rating: Double, modifier: Modifier, logo: @Composable () -> Unit) {
    // Aceeași etichetă ca sigla platformei și „S01E02 · 25 nov” (PosterBadges): înălțime, fundal și colțuri.
    // Sigla pătrată lipită în stânga (ca siglele aplicațiilor), nota alături.
    Row(
        modifier
            .height(BadgeHeight)
            .background(BadgeBackground, BadgeShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        logo()
        Text(
            String.format(Locale.US, "%.1f", rating),
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 3.dp),
        )
    }
}

/**
 * Posterul unui titlu (2:3), ca în AK VOD Hub: nota TMDb pe poster, fără text dedesubt (numele e pe poster și
 * în hero), focus cu mărire ușoară și contur alb. [rowKey] face cheia unică pe ecran, ca focusul să revină
 * pe acest card la întoarcerea din fișă.
 */
@Composable
fun PosterCard(
    item: CatalogItem,
    modifier: Modifier = Modifier,
    rowKey: String = "",
    fillCell: Boolean = false,
    badge: BadgeSpec? = null,
) {
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    val backdrop = LocalBackdrop.current
    val longPress = LocalPosterLongPress.current
    FocusCard(
        onClick = { actions.openTitle(item.kind, item.id) },
        // wrapContentSize: în grile (Caută, Lista mea, actori) celula e mai lată decât posterul; fără el
        // posterul s-ar lăți și s-ar tăia sus-jos. Așa are peste tot același format 2:3.
        modifier = if (fillCell) modifier.fillMaxWidth().aspectRatio(2f / 3) else
            modifier.wrapContentSize().size(spec.posterWidth, spec.posterHeight),
        restoreKey = "$rowKey|${item.mediaKey}",
        onFocused = { backdrop?.focus(FocusedTitle.of(item).copy(badge = badge)) },
        onLongClick = longPress ?: { actions.titleMenu(item) },
    ) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = item.title }) {
            NetworkImage(item.posterUrl, item.title, Modifier.fillMaxSize(), fallbackText = item.title)
            PosterRatings(item.kind, item.id, item.rating, Modifier.align(Alignment.TopStart).padding(4.dp))
            PosterViewingStatus(item.kind, item.id, Modifier.align(Alignment.TopEnd).padding(4.dp), episodeCount = item.episodeCount)
            TitleBadge(badge, item.kind, item.id, Modifier.align(Alignment.BottomStart).padding(4.dp))
        }
    }
}

/**
 * Ce face apăsarea lungă pe un poster (titlul are deja focusul); null = meniul rapid al titlului.
 * Acasă o folosește ca să sară la butoanele din hero (ca în AK VOD Hub).
 */
val LocalPosterLongPress = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Spațiul dintre carduri: destul cât cardul mărit la focus să nu atingă vecinii. */
@Composable
fun rowSpacing() = if (layoutSpec().isTv) 18.dp else 12.dp

@Composable
fun TitleRow(title: String, items: List<CatalogItem>, modifier: Modifier = Modifier, badge: BadgeSpec? = null) {
    if (items.isEmpty()) return
    val spec = layoutSpec()
    Column(modifier.peekRow()) {
        SectionTitle(title)
        DefaultScroll {
            val state = rememberRestoringRowState(title, items) { it.mediaKey }
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(rowSpacing()),
            ) {
                items(items, key = { it.mediaKey }) { PosterCard(it, rowKey = title, badge = badge) }
            }
        }
    }
}

/** Actor / membru al echipei, ca în AK VOD Hub: fotografie dreptunghiulară, numele și rolul dedesubt. */
@Composable
fun PersonCard(person: PersonSummary, modifier: Modifier = Modifier) {
    val actions = LocalCatalogActions.current
    val tv = layoutSpec().isTv
    val w = if (tv) 96.dp else 84.dp
    Column(modifier.wrapContentSize().width(w + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusCard(
            onClick = { actions.openPerson(person.id) },
            modifier = Modifier.size(w, w * 1.5f),
            shape = RoundedCornerShape(8.dp),
            restoreKey = "person|${person.id}|${person.role}",
        ) {
            NetworkImage(person.profileUrl, person.name, Modifier.fillMaxSize(), fallbackText = initials(person.name))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            person.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            person.role.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun initials(name: String) = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }

@Composable
fun PersonRow(title: String, people: List<PersonSummary>) {
    if (people.isEmpty()) return
    val spec = layoutSpec()
    Column {
        SectionTitle(title)
        DefaultScroll {
            LazyRow(
                contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(people, key = { "${it.id}:${it.role}" }) { PersonCard(it) }
            }
        }
    }
}

@Composable
fun LoadingPanel(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Mesaj de eroare clar; pentru cheia lipsă afișează pașii de configurare. */
@Composable
fun ErrorPanel(error: CatalogError, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val actions = LocalCatalogActions.current
    val (title, body) = when (error) {
        CatalogError.NotConfigured -> "Catalogul TMDb nu e configurat" to
            "Aplicația a fost compilată fără cheia TMDb.\n\n" +
            "1. Creează un cont gratuit pe themoviedb.org.\n" +
            "2. Settings → API → cere o cheie (Developer) și copiază „API Read Access Token”.\n" +
            "3. În GitHub: repo → Settings → Secrets and variables → Actions → secret nou TMDB_API_KEY.\n" +
            "4. Construiește din nou APK-ul (Actions → Release APK) și actualizează aplicația."
        CatalogError.Unauthorized -> "TMDb a respins cheia" to
            "Cheia TMDB_API_KEY din GitHub nu e validă sau a fost revocată. Copiază din nou " +
            "„API Read Access Token” din themoviedb.org → Settings → API, actualizează secretul și refă APK-ul."
        is CatalogError.Other -> "Nu am putut încărca datele" to error.message
    }
    Box(modifier.fillMaxWidth().padding(layoutSpec().screenPadding), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.widthIn(max = 720.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onRetry != null && error !is CatalogError.NotConfigured) {
                        Button(onClick = onRetry) { Text("Reîncearcă") }
                    }
                    OutlinedButton(onClick = actions.openSettings) { Text("Setări") }
                }
            }
        }
    }
}

/** Rând rezervat pentru funcții din etapele următoare (ex. „Continuă” din Trakt). */
@Composable
fun PlaceholderRow(title: String, message: String) {
    val spec = layoutSpec()
    Column {
        SectionTitle(title)
        Box(
            Modifier
                .padding(horizontal = spec.screenPadding, vertical = 8.dp)
                .fillMaxWidth()
                .height(spec.cardHeight / 1.5f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/** Atribuirea cerută de TMDb și JustWatch. */
@Composable
fun AttributionNote(
    modifier: Modifier = Modifier,
    includeJustWatch: Boolean = true,
    includeWhatToWatch: Boolean = false,
    includeHomeCharts: Boolean = false,
) {
    Text(
        buildString {
            append("Date despre titluri: TMDb. Acest produs folosește API-ul TMDb, dar nu este aprobat sau certificat de TMDb.")
            if (includeJustWatch) append(" Disponibilitatea pe servicii este furnizată de JustWatch.")
            if (includeWhatToWatch) append(" Catalog VOYO România: WhatToWatch.ro.")
            if (includeHomeCharts) append(" Topuri pe țări: Netflix Tudum.")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = layoutSpec().screenPadding, vertical = 12.dp),
    )
}
