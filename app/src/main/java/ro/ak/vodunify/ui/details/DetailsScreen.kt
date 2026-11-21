package ro.ak.vodunify.ui.details

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import ro.ak.vodunify.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import ro.ak.vodunify.ui.components.FilledTonalButton
import ro.ak.vodunify.ui.components.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import ro.ak.vodunify.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.catalog.Episode
import ro.ak.vodunify.core.catalog.MarketAvailability
import ro.ak.vodunify.core.catalog.ProviderInfo
import ro.ak.vodunify.core.catalog.Season
import ro.ak.vodunify.core.catalog.StudioRef
import ro.ak.vodunify.core.catalog.TitleDetails
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.connector.EpisodeNumber
import ro.ak.vodunify.core.connector.ShowProgress
import ro.ak.vodunify.core.tracking.TitleTrackState
import ro.ak.vodunify.ui.components.AttributionNote
import ro.ak.vodunify.ui.components.ErrorPanel
import ro.ak.vodunify.ui.components.FocusCard
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.LoadingPanel
import ro.ak.vodunify.ui.components.NetworkImage
import ro.ak.vodunify.ui.components.TitleBackdrop
import ro.ak.vodunify.ui.components.PersonRow
import ro.ak.vodunify.ui.components.SectionTitle
import ro.ak.vodunify.ui.components.TitleRow
import ro.ak.vodunify.ui.components.formatRating
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.components.openUrl
import ro.ak.vodunify.ui.navigation.LocalCatalogActions
import ro.ak.vodunify.ui.watch.WatchChooserHost
import ro.ak.vodunify.ui.watch.WatchChooserViewModel
import ro.ak.vodunify.ui.watch.playbackRequest

@Composable
fun DetailsScreen(
    viewModel: DetailsViewModel = hiltViewModel(),
    chooser: WatchChooserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        LoadState.Loading -> LoadingPanel(Modifier.fillMaxSize())
        is LoadState.Failed -> ErrorPanel(s.error, onRetry = viewModel::retry)
        is LoadState.Data -> DetailsContent(s.value, viewModel, chooser)
    }
    WatchChooserHost(chooser)
}


@Composable
private fun DetailsContent(details: TitleDetails, viewModel: DetailsViewModel, chooser: WatchChooserViewModel) {
    val spec = layoutSpec()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val primaryFocus = remember { FocusRequester() }
    val selectedSeason by viewModel.season.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val connected by viewModel.trackerConnected.collectAsStateWithLifecycle()
    val track by viewModel.trackState.collectAsStateWithLifecycle()
    val progress by viewModel.showProgress.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val availabilityIndex = if (connected) 2 else 1
    val watchedEpisodes = if (connected) progress?.watched.orEmpty() else emptySet()

    if (spec.isTv) {
        LaunchedEffect(details.item.mediaKey) { runCatching { primaryFocus.requestFocus() } }
    }
    LaunchedEffect(details.item.mediaKey) {
        if (details.item.kind == TitleKind.TV && selectedSeason == null) {
            details.seasons.firstOrNull()?.let { viewModel.selectSeason(it.number) }
        }
    }
    LaunchedEffect(details.item.mediaKey, connected) {
        if (connected) viewModel.loadShowProgress(details)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Header(
                details,
                primaryFocus,
                onChooseWhere = { chooser.open(details.playbackRequest(), details.availability) },
                onShowOffers = { scope.launch { listState.animateScrollToItem(availabilityIndex) } },
            )
        }
        if (connected) {
            item(key = "track") {
                TrackPanel(
                    details = details,
                    state = track,
                    progress = progress,
                    busy = busy,
                    message = message,
                    onToggleWatchlist = { viewModel.toggleWatchlist(details) },
                    onToggleWatched = { viewModel.toggleWatched(details) },
                )
            }
        }
        item(key = "availability") { AvailabilitySection(details.availability) }
        if (details.item.kind == TitleKind.TV && details.seasons.isNotEmpty()) {
            item(key = "seasons") {
                SeasonPicker(details, selectedSeason, viewModel::selectSeason)
            }
            when (val e = episodes) {
                null, LoadState.Loading -> item(key = "episodes_loading") { LoadingPanel() }
                is LoadState.Failed -> item(key = "episodes_error") { ErrorPanel(e.error, onRetry = null) }
                is LoadState.Data -> episodeItems(
                    e.value,
                    watched = watchedEpisodes,
                    // Episoadele se marchează din AK Scrobbler; manual doar cu un tracker extern activ.
                    canMark = connected && ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED,
                    onToggle = { ep -> viewModel.toggleEpisode(details, ep) },
                    onWatch = { ep -> chooser.open(details.playbackRequest(ep), details.availability) },
                )
            }
        }
        item(key = "cast") { PersonRow("Distribuție", details.cast) }
        item(key = "crew") { PersonRow("Echipa", details.crew) }
        item(key = "studios") { StudioRow(details.studios) }
        item(key = "recs") { TitleRow("Recomandări", details.recommendations) }
        item(key = "attribution") { AttributionNote() }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.episodeItems(
    season: Season,
    watched: Set<EpisodeNumber>,
    canMark: Boolean,
    onToggle: (Episode) -> Unit,
    onWatch: (Episode) -> Unit,
) {
    if (season.episodes.isEmpty()) {
        item(key = "episodes_empty") {
            Text(
                "Nu există episoade listate pentru acest sezon.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = layoutSpec().screenPadding),
            )
        }
        return
    }
    items(season.episodes, key = { "ep_${it.season}_${it.number}" }) { ep ->
        EpisodeCard(
            episode = ep,
            watched = EpisodeNumber(ep.season, ep.number) in watched,
            canMark = canMark,
            onToggle = { onToggle(ep) },
            onWatch = { onWatch(ep) },
        )
    }
}

@Composable
private fun Header(
    details: TitleDetails,
    primaryFocus: FocusRequester,
    onChooseWhere: () -> Unit,
    onShowOffers: () -> Unit,
) {
    val spec = layoutSpec()
    val context = LocalContext.current
    val item = details.item
    Box(Modifier.fillMaxWidth()) {
        // Fundalul fișei, ca în AK VOD Hub: imaginea se topește în fundal spre stânga și în jos.
        TitleBackdrop(
            item.backdropUrl,
            Modifier
                .fillMaxWidth()
                .height(spec.heroHeight + 200.dp),
            settleMillis = 0,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = spec.screenPadding, end = spec.screenPadding, top = spec.heroHeight / 3),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (!spec.isCompact) {
                NetworkImage(
                    item.posterUrl,
                    item.title,
                    Modifier.size(spec.posterWidth * 1.4f, spec.posterHeight * 1.4f),
                    fallbackText = item.title,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.title,
                    style = if (spec.isCompact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                details.originalTitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(metaLine(details), style = MaterialTheme.typography.bodyMedium)
                if (details.genres.isNotEmpty()) {
                    Text(
                        details.genres.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                details.nextEpisode?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                details.tagline?.let {
                    Text("„$it”", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                if (item.overview.isNotBlank()) {
                    Text(
                        item.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 820.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onChooseWhere, modifier = Modifier.focusRequester(primaryFocus)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (item.kind == TitleKind.TV) "Alege unde vezi serialul" else "Alege unde vezi")
                    }
                    if (!spec.isCompact) {
                        OutlinedButton(onClick = onShowOffers) { Text("Oferte") }
                    }
                    details.trailerYoutubeKey?.let { key ->
                        OutlinedButton(onClick = { context.openUrl("https://www.youtube.com/watch?v=$key") }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Trailer")
                        }
                    }
                    details.imdbId?.let { imdb ->
                        if (!spec.isCompact) {
                            OutlinedButton(onClick = { context.openUrl("https://www.imdb.com/title/$imdb/") }) { Text("IMDb") }
                        }
                    }
                }
            }
        }
    }
}

private fun metaLine(d: TitleDetails): String = listOfNotNull(
    d.item.year?.toString(),
    d.item.kind.label,
    d.runtimeMinutes?.takeIf { it > 0 }?.let { m -> if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" },
    d.seasonCount?.let { if (it == 1) "1 sezon" else "$it sezoane" },
    d.episodeCount?.let { "$it episoade" },
    formatRating(d.item.rating)?.let { r -> d.voteCount?.let { "$r ($it voturi)" } ?: r },
).joinToString(" · ")

// ---------- De văzut / Văzut / progres ----------

@Composable
private fun TrackPanel(
    details: TitleDetails,
    state: TitleTrackState?,
    progress: ShowProgress?,
    busy: Boolean,
    message: String?,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    val spec = layoutSpec()
    val isMovie = details.item.kind == TitleKind.MOVIE
    Column(
        Modifier.padding(horizontal = spec.screenPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val inList = state?.inWatchlist == true
            if (inList) {
                FilledTonalButton(onClick = onToggleWatchlist, enabled = !busy) { Text("✓ În „De văzut”") }
            } else {
                OutlinedButton(onClick = onToggleWatchlist, enabled = !busy) { Text("+ De văzut") }
            }
            if (isMovie) {
                if (state?.watched == true) {
                    FilledTonalButton(onClick = onToggleWatched, enabled = !busy) { Text("✓ Văzut") }
                } else {
                    OutlinedButton(onClick = onToggleWatched, enabled = !busy) { Text("Marchează văzut") }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        trackSummary(isMovie, state, progress)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun trackSummary(isMovie: Boolean, state: TitleTrackState?, progress: ShowProgress?): String? {
    val parts = mutableListOf<String>()
    state?.resume?.let { r ->
        val where = if (r.ref.type == "EPISODE" && r.ref.season != null && r.ref.episode != null) {
            "S${r.ref.season}E${r.ref.episode} "
        } else {
            ""
        }
        parts += "Început: $where" + (r.progress?.let { "${(it * 100).toInt()}%" } ?: "poziție necunoscută")
    }
    if (!isMovie && progress != null && progress.aired > 0) {
        parts += "Văzute ${progress.completed} din ${progress.aired} episoade"
        progress.next?.let { n -> parts += "Urmează S${n.season}E${n.episode}" + (n.episodeTitle?.let { " · $it" } ?: "") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

// ---------- Disponibilitate (TMDb Watch Providers, date JustWatch) ----------

@Composable
private fun AvailabilitySection(markets: List<MarketAvailability>) {
    val spec = layoutSpec()
    val context = LocalContext.current
    Column {
        SectionTitle("Disponibilitate")
        Column(
            Modifier.padding(horizontal = spec.screenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            markets.forEach { m ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 1000.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${m.market.label} (${m.market.code})", fontWeight = FontWeight.SemiBold)
                        if (m.isEmpty) {
                            Text(
                                "Nu apare pe niciun serviciu de streaming în această piață.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            ProviderGroup("Abonament", m.flatrate)
                            ProviderGroup("Gratuit", m.free)
                            ProviderGroup("Închiriere", m.rent)
                            ProviderGroup("Cumpărare", m.buy)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Date furnizate de JustWatch",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            m.link?.let { link ->
                                OutlinedButton(onClick = { context.openUrl(link) }) { Text("Toate ofertele") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderGroup(label: String, providers: List<ProviderInfo>) {
    if (providers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(providers, key = { it.id }) { p ->
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    NetworkImage(p.logoUrl, p.name, Modifier.size(48.dp), fallbackText = p.name.take(2))
                    Text(
                        p.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------- Sezoane și episoade ----------

@Composable
private fun SeasonPicker(details: TitleDetails, selected: Int?, onSelect: (Int) -> Unit) {
    val spec = layoutSpec()
    Column {
        SectionTitle("Sezoane")
        LazyRow(
            contentPadding = PaddingValues(horizontal = spec.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(details.seasons, key = { it.number }) { s ->
                FilterChip(
                    selected = s.number == selected,
                    onClick = { onSelect(s.number) },
                    label = { Text("${s.name} · ${s.episodeCount} ep.") },
                )
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: Episode, watched: Boolean, canMark: Boolean, onToggle: () -> Unit, onWatch: () -> Unit) {
    val spec = layoutSpec()
    FocusCard(
        onClick = onWatch,
        focusScale = 1.01f,
        modifier = Modifier
            .padding(horizontal = spec.screenPadding)
            .fillMaxWidth()
            .widthIn(max = 1100.dp),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NetworkImage(
                episode.stillUrl,
                null,
                Modifier.size(spec.cardWidth * 0.8f, spec.cardHeight * 0.8f),
                fallbackText = "E${episode.number}",
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (watched) "✓ " else "") + "${episode.number}. ${episode.name}",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (watched) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Alege unde vezi", tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    listOfNotNull(episode.airDate, episode.runtimeMinutes?.let { "${it}m" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (episode.overview.isNotBlank()) {
                    Text(
                        episode.overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (canMark) {
                OutlinedButton(onClick = onToggle, modifier = Modifier.align(Alignment.CenterVertically)) {
                    Text(if (watched) "Nevăzut" else "Văzut")
                }
            }
        }
    }
}

// ---------- Studiouri / rețele ----------

@Composable
private fun StudioRow(studios: List<StudioRef>) {
    if (studios.isEmpty()) return
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    Column {
        SectionTitle("Studiouri și rețele")
        LazyRow(
            contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(studios, key = { "${it.kind}:${it.id}" }) { studio ->
                FocusCard(onClick = { actions.openStudio(studio.kind, studio.id) }, modifier = Modifier.width(180.dp)) {
                    Column(
                        Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(Color(0xFFE6E8EC)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (studio.logoUrl != null) {
                                NetworkImage(
                                    studio.logoUrl,
                                    studio.name,
                                    Modifier.fillMaxSize().padding(6.dp),
                                    contentScale = ContentScale.Fit,
                                    placeholderColor = Color.Transparent,
                                )
                            } else {
                                Text(studio.name, color = Color(0xFF151D2A), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(studio.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(studio.kind.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
