package ro.ak.vodunify.ui.home

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import ro.ak.vodunify.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import ro.ak.vodunify.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.connector.ExternalTrackers
import ro.ak.vodunify.ui.components.DefaultScroll
import ro.ak.vodunify.ui.components.FocusCard
import ro.ak.vodunify.ui.components.FocusedTitle
import ro.ak.vodunify.ui.components.PosterLabel
import ro.ak.vodunify.ui.components.RatingChip
import ro.ak.vodunify.ui.components.PlatformBadge
import ro.ak.vodunify.ui.components.AppIconBadge
import ro.ak.vodunify.ui.components.CinemaBadge
import ro.ak.vodunify.core.catalog.ReleaseChannel
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ro.ak.vodunify.ui.components.LocalBackdrop
import ro.ak.vodunify.ui.components.rowSpacing
import ro.ak.vodunify.ui.components.NetworkImage
import ro.ak.vodunify.ui.components.PlaceholderRow
import ro.ak.vodunify.ui.components.SectionTitle
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.navigation.LocalCatalogActions

/** Rândurile personale de pe Acasă: Continuă vizionarea, Următorul episod, De văzut. */
@Composable
fun PersonalSection(rows: PersonalRows, status: SyncStatus, onSync: () -> Unit) {
    when (rows.state) {
        PersonalState.LOADING -> Unit
        PersonalState.NOT_CONFIGURED -> PlaceholderRow(
            "Continuă vizionarea",
            "Trakt nu e configurat: lipsește secretul TRAKT_CLIENT_ID în GitHub. Pașii sunt în Setări.",
        )
        PersonalState.DISCONNECTED -> ConnectPrompt()
        PersonalState.CONNECTED -> Column {
            if (rows.continueEmpty) {
                if (status is SyncStatus.Running) {
                    SyncingRow()
                } else {
                    PlaceholderRow(
                        "Continuă vizionarea",
                        if (ExternalTrackers.ENABLED) {
                            "Nimic început încă. Ce începi pe TV sau marchezi pe Trakt apare aici."
                        } else {
                            "Nimic început încă. Ce pornești în Netflix, Prime Video, HBO Max, Stremio, Nuvio… " +
                                "apare aici automat, prin AK Scrobbler."
                        },
                    )
                }
            }
            WideRow("Continuă vizionarea · Filme", rows.continueMovies)
            WideRow("Continuă vizionarea · Seriale", rows.continueShows)
            PosterRow("Noutăți din Lista mea · Filme", rows.newMovies)
            PosterRow("Noutăți din Lista mea · Episoade noi", rows.newEpisodes)
            PosterRow("În curând · Filme", rows.soonMovies)
            PosterRow("În curând · Episoade", rows.soonEpisodes)
            if (status is SyncStatus.Failed) {
                Row(
                    Modifier.padding(horizontal = layoutSpec().screenPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Sincronizare eșuată: ${status.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = onSync) { Text("Reîncearcă") }
                }
            }
        }
    }
}

@Composable
private fun SyncingRow() {
    Row(
        Modifier.padding(horizontal = layoutSpec().screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Se sincronizează…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectPrompt() {
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    Column {
        SectionTitle("Continuă vizionarea")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
            modifier = Modifier
                .padding(horizontal = spec.screenPadding, vertical = 8.dp)
                .fillMaxWidth()
                .widthIn(max = 900.dp),
        ) {
            Row(
                Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Conectează Trakt ca să vezi aici ce ai început, următorul episod din seriale și lista „De văzut”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = actions.openSettings) { Text("Conectează") }
            }
        }
    }
}

@Composable
private fun WideRow(title: String, items: List<PersonalItem>) {
    if (items.isEmpty()) return
    val spec = layoutSpec()
    Column {
        SectionTitle(title)
        DefaultScroll {
            LazyRow(
                contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(rowSpacing()),
            ) {
                items(items, key = { it.key }) { WideCard(it, rowKey = title) }
            }
        }
    }
}

/**
 * Card 16:9 (ca „Continuă vizionarea” din AK VOD Hub): imaginea titlului, bara roșie de progres jos
 * și episodul / procentul dedesubt. „?” = poziție necunoscută (nu 0%).
 */
@Composable
private fun WideCard(item: PersonalItem, rowKey: String) {
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    val backdrop = LocalBackdrop.current
    FocusCard(
        onClick = { item.tmdbId?.let { actions.openTitle(item.kind, it) } },
        modifier = Modifier.size(spec.cardWidth, spec.cardHeight),
        restoreKey = "$rowKey|${item.key}",
        onFocused = { focusedTitle(item)?.let { backdrop?.focus(it) } },
        // OK ținut apăsat: meniul titlului (Lista mea, văzut, scoate din Continuă vizionarea…).
        onLongClick = { item.asCatalogItem()?.let(actions.titleMenu) },
    ) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = listOfNotNull(item.title, item.subtitle).joinToString(", ") }) {
            NetworkImage(item.backdropUrl ?: item.posterUrl, item.title, Modifier.fillMaxSize(), fallbackText = item.title)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))),
            )
            // Fără text sub card: episodul (S01E02) și procentul stau pe imagine, ca etichetele de pe postere.
            item.subtitle?.takeIf { it.isNotBlank() }?.let {
                PosterLabel(it, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, end = 6.dp, bottom = 10.dp))
            }
            // Sigla aplicației în care ai văzut ultima dată: acolo continui standard (dacă nu alegi altceva).
            val badge = Modifier.align(Alignment.TopEnd).padding(6.dp)
            val lastApp = item.appPackage
            if (lastApp != null) AppIconBadge(lastApp, badge) else PlatformBadge(item.kind, item.tmdbId, badge)
            if (item.showsProgress || item.progress != null) {
                val p = item.progress
                if (p != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0xAA000000)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(p.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
}

/** Rând de postere (Noutăți / În curând): ca posterele din catalog, cu episodul și data jos, pe poster. */
@Composable
private fun PosterRow(title: String, items: List<PersonalItem>) {
    if (items.isEmpty()) return
    val spec = layoutSpec()
    Column {
        SectionTitle(title)
        DefaultScroll {
            LazyRow(
                contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(rowSpacing()),
            ) {
                items(items, key = { it.key }) { PersonalPoster(it, rowKey = title) }
            }
        }
    }
}

@Composable
private fun PersonalPoster(item: PersonalItem, rowKey: String) {
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    val backdrop = LocalBackdrop.current
    FocusCard(
        onClick = { item.tmdbId?.let { actions.openTitle(item.kind, it) } },
        modifier = Modifier.size(spec.posterWidth, spec.posterHeight),
        restoreKey = "$rowKey|${item.key}",
        onFocused = { focusedTitle(item)?.let { backdrop?.focus(it) } },
        onLongClick = { item.asCatalogItem()?.let(actions.titleMenu) },
    ) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = listOfNotNull(item.title, item.subtitle).joinToString(", ") }) {
            NetworkImage(item.posterUrl, item.title, Modifier.fillMaxSize(), fallbackText = item.title)
            item.rating?.takeIf { it > 0 }?.let { RatingChip(it, Modifier.align(Alignment.TopStart).padding(4.dp)) }
            ReleaseBadge(item, Modifier.align(Alignment.TopEnd).padding(4.dp))
            item.subtitle?.takeIf { it.isNotBlank() }?.let {
                PosterLabel(it, Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
        }
    }
}

/**
 * Sigla de sus a posterelor din „În curând” / „Noutăți”: la filme, unde apare la data de jos (cinema România
 * sau SUA, aplicația serviciului de streaming ori „Digital”); altfel sigla obișnuită a titlului.
 */
@Composable
private fun ReleaseBadge(item: PersonalItem, modifier: Modifier) {
    val release = item.release
    if (release == null) {
        PlatformBadge(item.kind, item.tmdbId, modifier)
        return
    }
    val pkg = item.appPackage
    val service = release.serviceName
    when (release.channel) {
        ReleaseChannel.CINEMA -> CinemaBadge(release.region, modifier)
        ReleaseChannel.STREAMING -> when {
            pkg != null -> AppIconBadge(pkg, modifier, fallbackLabel = service)
            service != null -> PosterLabel(service, modifier)
            else -> PosterLabel("Streaming", modifier)
        }
        ReleaseChannel.DIGITAL -> PosterLabel("Digital ${release.region}", modifier)
        ReleaseChannel.UNKNOWN -> PlatformBadge(item.kind, item.tmdbId, modifier)
    }
}

private fun focusedTitle(item: PersonalItem): FocusedTitle? {
    val id = item.tmdbId ?: return null
    return FocusedTitle(
        kind = item.kind,
        id = id,
        title = item.title,
        meta = listOfNotNull(releaseText(item), item.subtitle?.takeIf { it.isNotBlank() }).joinToString(" · "),
        overview = item.overview,
        backdropUrl = item.backdropUrl,
        rating = item.rating,
    )
}

/** Unde apare filmul, pentru hero: „În cinema în România · 25 nov”. */
private fun releaseText(item: PersonalItem): String? {
    val r = item.release ?: return null
    return when (r.channel) {
        ReleaseChannel.CINEMA -> if (r.region == "RO") "În cinema în România" else "În cinema în SUA"
        ReleaseChannel.STREAMING -> "Pe ${r.serviceName ?: "streaming"}" + if (r.region == "US" && !r.mine) " (SUA)" else ""
        ReleaseChannel.DIGITAL -> "Digital, închiriere / cumpărare (${r.region})"
        ReleaseChannel.UNKNOWN -> null
    }
}
