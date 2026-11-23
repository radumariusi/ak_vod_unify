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
import ro.ak.vodunify.ui.components.peekRow
import ro.ak.vodunify.ui.components.FocusCard
import ro.ak.vodunify.ui.components.FocusedTitle
import ro.ak.vodunify.ui.components.PosterLabel
import ro.ak.vodunify.ui.components.RatingChip
import ro.ak.vodunify.ui.components.BadgeSpec
import ro.ak.vodunify.ui.components.TitleBadge
import ro.ak.vodunify.core.calendar.android.CALENDAR_TO_SERVICE
import ro.ak.vodunify.ui.components.PosterRatings
import ro.ak.vodunify.ui.components.AppIconBadge
import ro.ak.vodunify.ui.components.CinemaBadge
import ro.ak.vodunify.core.catalog.ReleaseChannel
import ro.ak.vodunify.core.catalog.TitleKind
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
            if (status is SyncStatus.Failed) SyncFailedRow(status, onSync)
        }
    }
}

@Composable
private fun SyncFailedRow(status: SyncStatus.Failed, onSync: () -> Unit) {
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

internal class HomeRow(val key: String, val focusable: Boolean = true, val content: @Composable () -> Unit)

internal fun personalHomeRows(rows: PersonalRows, status: SyncStatus, onSync: () -> Unit): List<HomeRow> = buildList {
    if (rows.state != PersonalState.CONNECTED) {
        add(HomeRow("personal", focusable = rows.state == PersonalState.DISCONNECTED) { PersonalSection(rows, status, onSync) })
        return@buildList
    }
    if (rows.continueEmpty) {
        add(HomeRow("p_empty", focusable = status is SyncStatus.Failed) { PersonalSection(PersonalRows(PersonalState.CONNECTED), status, onSync) })
    }
    if (rows.continueMovies.isNotEmpty()) add(HomeRow("p_continue_movies") { WideRow("Continuă vizionarea · Filme", rows.continueMovies) })
    if (rows.continueShows.isNotEmpty()) add(HomeRow("p_continue_shows") { WideRow("Continuă vizionarea · Seriale", rows.continueShows) })
    if (rows.newMovies.isNotEmpty()) add(HomeRow("p_new_movies") { PosterRow("Noutăți din Lista mea · Filme", rows.newMovies) })
    if (rows.newEpisodes.isNotEmpty()) add(HomeRow("p_new_episodes") { PosterRow("Noutăți din Lista mea · Episoade noi", rows.newEpisodes) })
    if (rows.soonMovies.isNotEmpty()) add(HomeRow("p_soon_movies") { PosterRow("În curând · Filme", rows.soonMovies) })
    if (rows.soonEpisodes.isNotEmpty()) add(HomeRow("p_soon_episodes") { PosterRow("În curând · Episoade", rows.soonEpisodes) })
    if (status is SyncStatus.Failed && !rows.continueEmpty) add(HomeRow("p_sync_failed") { SyncFailedRow(status, onSync) })
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
    Column(Modifier.peekRow()) {
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
        onFocused = { focusedTitle(item, wideBadge(item))?.let { backdrop?.focus(it) } },
        // OK ținut apăsat: meniul titlului (Lista mea, văzut, scoate din Continuă vizionarea…).
        onLongClick = { item.asCatalogItem()?.let(actions.titleMenu) },
    ) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = listOfNotNull(item.title, item.subtitle).joinToString(", ") }) {
            // Cardul e mic: imaginea panoramică la 780 px, nu la 1280 (mai puțin de descărcat și decodat).
            NetworkImage(item.backdropUrl?.replace("/w1280/", "/w780/") ?: item.posterUrl, item.title, Modifier.fillMaxSize(), fallbackText = item.title)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))),
            )
            // Aplicația redării, episodul și procentul stau pe card.
            PosterRatings(item.kind, item.tmdbId, item.rating, Modifier.align(Alignment.TopStart).padding(6.dp))
            TitleBadge(
                wideBadge(item), item.kind, item.tmdbId,
                Modifier.align(Alignment.BottomStart).padding(start = 6.dp, end = 6.dp, bottom = 10.dp),
                label = item.subtitle,
            )
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
    Column(Modifier.peekRow()) {
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
        onFocused = { focusedTitle(item, releaseBadge(item))?.let { backdrop?.focus(it) } },
        onLongClick = { item.asCatalogItem()?.let(actions.titleMenu) },
    ) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = listOfNotNull(item.title, item.subtitle).joinToString(", ") }) {
            NetworkImage(item.posterUrl, item.title, Modifier.fillMaxSize(), fallbackText = item.title)
            PosterRatings(item.kind, item.tmdbId, item.rating, Modifier.align(Alignment.TopStart).padding(4.dp))
            // Jos: unde apare (aplicația, cinema RO / SUA) lângă dată.
            TitleBadge(
                releaseBadge(item), item.kind, item.tmdbId,
                Modifier.align(Alignment.BottomStart).padding(4.dp),
                label = item.subtitle,
            )
        }
    }
}

private fun wideBadge(item: PersonalItem): BadgeSpec = BadgeSpec.PlaybackSource(item.appPackage)

/**
 * Sigla posterelor din „În curând” / „Noutăți”: la filme, unde apare la data de jos (cinema România sau SUA,
 * aplicația serviciului de streaming ori „Digital”); altfel (null) sigla obișnuită a titlului.
 */
private fun releaseBadge(item: PersonalItem): BadgeSpec? {
    if (item.kind == TitleKind.TV && item.episodeCode != null) return BadgeSpec.InProgress
    val release = item.release ?: return null
    if (release.channel == ReleaseChannel.STREAMING &&
        item.eventDate?.let { it <= java.time.LocalDate.now().toString() } == true
    ) return null
    val pkg = item.appPackage
    val service = release.serviceName
    return when (release.channel) {
        ReleaseChannel.CINEMA -> BadgeSpec.Cinema(release.region)
        ReleaseChannel.STREAMING -> when {
            pkg != null -> BadgeSpec.App(pkg, service)
            service != null -> BadgeSpec.Service(release.serviceId?.let { CALENDAR_TO_SERVICE[it] ?: it }, service)
            else -> BadgeSpec.Label("Streaming")
        }
        // Digital (închiriere / cumpărare): sigla player-ului standard, ca la orice titlu.
        ReleaseChannel.DIGITAL -> null
        ReleaseChannel.UNKNOWN -> null
    }
}

private fun focusedTitle(item: PersonalItem, badge: BadgeSpec?): FocusedTitle? {
    val id = item.tmdbId ?: return null
    return FocusedTitle(
        kind = item.kind,
        id = id,
        title = item.title,
        meta = heroMeta(item),
        overview = item.overview,
        backdropUrl = item.backdropUrl,
        rating = item.rating,
        badge = badge,
    )
}

/**
 * Rândul de sub sigla din hero. Noutăți / În curând: unde apare și data completă, cu ziua săptămânii;
 * Continuă vizionarea: episodul, procentul și când l-ai văzut.
 */
private fun heroMeta(item: PersonalItem): String {
    val date = item.eventDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    if (date != null) {
        return listOfNotNull(releaseText(item), item.episodeCode, HomeSections.fullDayLabel(date)).joinToString(" · ")
    }
    return listOfNotNull(
        releaseText(item),
        item.subtitle?.takeIf { it.isNotBlank() },
        item.watchedAt?.let { HomeSections.watchedLabel(it) },
    ).joinToString(" · ")
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
