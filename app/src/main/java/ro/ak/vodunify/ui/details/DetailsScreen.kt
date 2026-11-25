package ro.ak.vodunify.ui.details

import ro.ak.vodunify.ui.components.PosterRatings
import ro.ak.vodunify.ui.components.PillRow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.catalog.Episode
import ro.ak.vodunify.core.catalog.Season
import ro.ak.vodunify.core.catalog.SeasonSummary
import ro.ak.vodunify.core.catalog.StudioRef
import ro.ak.vodunify.core.catalog.TitleDetails
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.connector.EpisodeNumber
import ro.ak.vodunify.core.connector.ShowProgress
import ro.ak.vodunify.core.playback.WatchOptions
import ro.ak.vodunify.core.tracking.TitleTrackState
import ro.ak.vodunify.ui.components.AttributionNote
import ro.ak.vodunify.ui.components.Button
import ro.ak.vodunify.ui.components.Dropdown
import ro.ak.vodunify.ui.components.ErrorPanel
import ro.ak.vodunify.ui.components.FocusCard
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.LoadingPanel
import ro.ak.vodunify.ui.components.NetworkImage
import ro.ak.vodunify.ui.components.OutlinedButton
import ro.ak.vodunify.ui.components.PersonRow
import ro.ak.vodunify.ui.components.RatingChip
import ro.ak.vodunify.ui.components.ImdbChip
import ro.ak.vodunify.ui.components.ExpandableText
import ro.ak.vodunify.ui.components.SectionTitle
import ro.ak.vodunify.ui.components.TextButton
import ro.ak.vodunify.ui.components.TitleBackdrop
import ro.ak.vodunify.ui.components.TitleLogo
import ro.ak.vodunify.ui.components.KeepFocusedContentVisible
import ro.ak.vodunify.ui.components.TitleHero
import ro.ak.vodunify.ui.components.tvTitleHeroHeight
import ro.ak.vodunify.ui.components.TitleRow
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
    onBackdropChange: ((String?) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Revenit din altă aplicație (ex. ai instalat un serviciu): recitim ce e instalat.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshInstalled() }
    val backdropUrl = (state as? LoadState.Data)?.value?.item?.backdropUrl
    if (onBackdropChange != null) {
        SideEffect { onBackdropChange(backdropUrl) }
        DisposableEffect(onBackdropChange) {
            onDispose { onBackdropChange(null) }
        }
    }
    when (val s = state) {
        LoadState.Loading -> LoadingPanel(Modifier.fillMaxSize())
        is LoadState.Failed -> ErrorPanel(s.error, onRetry = viewModel::retry)
        is LoadState.Data -> DetailsContent(s.value, viewModel, chooser, drawLocalBackdrop = onBackdropChange == null)
    }
    WatchChooserHost(chooser)
}

/**
 * Fișa titlului, ca în AK VOD Hub: fundalul titlului pe tot ecranul, sigla titlului sus, apoi descrierea,
 * unde îl vezi și acțiunile pe un singur rând, informații, distribuție și recomandări. Episoadele unui
 * serial au ecranul lor, grupate pe sezoane (butonul „Episoade”).
 */
@Composable
private fun DetailsContent(
    details: TitleDetails,
    viewModel: DetailsViewModel,
    chooser: WatchChooserViewModel,
    drawLocalBackdrop: Boolean,
) {
    val selectedSeason by viewModel.season.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val connected by viewModel.trackerConnected.collectAsStateWithLifecycle()
    val progress by viewModel.showProgress.collectAsStateWithLifecycle()
    val watch by viewModel.watchOptions.collectAsStateWithLifecycle()
    val watchedEpisodes = if (connected) progress?.watched.orEmpty() else emptySet()
    var showingEpisodes by rememberSaveable(details.item.mediaKey) { mutableStateOf(false) }
    var backFromEpisodes by rememberSaveable(details.item.mediaKey) { mutableStateOf(false) }

    LaunchedEffect(details.item.mediaKey) {
        if (details.item.kind == TitleKind.TV && selectedSeason == null) {
            val next = progress?.next?.season
            (details.seasons.firstOrNull { it.number == next } ?: details.seasons.firstOrNull())?.let { viewModel.selectSeason(it.number) }
        }
    }
    BackHandler(enabled = showingEpisodes) {
        showingEpisodes = false
        backFromEpisodes = true
    }

    Box(Modifier.fillMaxSize()) {
        if (drawLocalBackdrop) {
            TitleBackdrop(details.item.backdropUrl, Modifier.fillMaxSize(), opacity = 0.9f, settleMillis = 0)
        }
        if (showingEpisodes) {
            EpisodesPane(
                details = details,
                selected = selectedSeason,
                episodes = episodes,
                watched = watchedEpisodes,
                next = progress?.next?.let { n -> if (n.season != null && n.episode != null) EpisodeNumber(n.season, n.episode) else null },
                canMark = connected,
                onSelectSeason = viewModel::selectSeason,
                onToggle = { ep -> viewModel.toggleEpisode(details, ep) },
                onToggleSeason = { n, seen -> viewModel.toggleSeason(details, n, seen) },
                onPlay = { ep -> chooser.play(details.playbackRequest(ep), details.availability) },
                message = viewModel.message.collectAsStateWithLifecycle().value,
                onBack = {
                    showingEpisodes = false
                    backFromEpisodes = true
                },
            )
        } else {
            Overview(
                details = details,
                viewModel = viewModel,
                watch = watch,
                connected = connected,
                progress = progress,
                focusEpisodes = backFromEpisodes,
                onFocused = { backFromEpisodes = false },
                onPlay = { chooser.play(details.playbackRequest(), details.availability) },
                onChoose = { chooser.open(details.playbackRequest(), details.availability) },
                onEpisodes = { showingEpisodes = true },
            )
        }
    }
}

@Composable
private fun Overview(
    details: TitleDetails,
    viewModel: DetailsViewModel,
    watch: WatchOptions?,
    connected: Boolean,
    progress: ShowProgress?,
    focusEpisodes: Boolean,
    onFocused: () -> Unit,
    onPlay: () -> Unit,
    onChoose: () -> Unit,
    onEpisodes: () -> Unit,
) {
    val spec = layoutSpec()
    val track by viewModel.trackState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val primaryFocus = remember { FocusRequester() }
    val episodesFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val isTvShow = details.item.kind == TitleKind.TV && details.seasons.isNotEmpty()
    val currentWatch by rememberUpdatedState(watch)
    if (spec.isTv) {
        LaunchedEffect(details.item.mediaKey, focusEpisodes) {
            if (!focusEpisodes) {
                withTimeoutOrNull(250L) { snapshotFlow { currentWatch != null }.first { it } }
            }
            withFrameNanos { }
            runCatching { if (focusEpisodes && isTvShow) episodesFocus.requestFocus() else primaryFocus.requestFocus() }
            if (focusEpisodes) onFocused()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = if (spec.isTv) tvTitleHeroHeight(maxHeight, spec) else 0.dp
        KeepFocusedContentVisible(spec.isTv) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "hero") {
                    DetailsHero(details, heroHeight)
                }
                item(key = "header") {
                    Header(
                        details,
                        watch,
                        Modifier.padding(horizontal = spec.screenPadding),
                        actionsModifier = if (spec.isTv) Modifier
                            .onFocusChanged {
                                if (it.hasFocus && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)) {
                                    listState.requestScrollToItem(0)
                                }
                            }
                            .focusGroup() else Modifier,
                    ) {
                        PrimaryActions(watch, primaryFocus, onPlay, onChoose)
                        if (isTvShow) {
                            OutlinedButton(onClick = onEpisodes, modifier = Modifier.focusRequester(episodesFocus)) { Text("Episoade") }
                        }
                        if (connected) {
                            val inList = track?.inMyList == true
                            OutlinedButton(onClick = { viewModel.toggleWatchlist(details) }, active = inList) {
                                Text(if (inList) "✓ În Lista mea" else "+ Lista mea")
                            }
                            // Starea, ca la Lista mea: „✓ Văzut” când e văzut, „Nevăzut” când nu; apăsarea o schimbă.
                            if (details.item.kind == TitleKind.MOVIE) {
                                WatchedToggle(track?.watched == true) { viewModel.toggleWatched(details) }
                            } else if (isTvShow) {
                                val seriesWatched = track?.resume == null && progress?.let { it.aired > 0 && it.completed >= it.aired } == true
                                WatchedToggle(seriesWatched, watchedLabel = "✓ Serial văzut", unwatchedLabel = "Marchează serialul văzut") {
                                    viewModel.toggleSeries(details, seriesWatched)
                                }
                            }
                        }
                        TrailerButton(details)
                    }
                }
                item(key = "status") {
                    Column(Modifier.padding(horizontal = spec.screenPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        trackSummary(details.item.kind == TitleKind.MOVIE, track, progress)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                item(key = "info") { InfoLines(details, Modifier.padding(horizontal = spec.screenPadding)) }
                item(key = "cast") { PersonRow("Distribuție", details.cast) }
                item(key = "recs") { TitleRow("Recomandări", details.recommendations) }
                item(key = "studios") { StudioRow(details.studios) }
                item(key = "attribution") { AttributionNote() }
            }
        }
    }
}

/** Văzut / nevăzut: plin cu „✓” când e văzut, contur când nu. */
@Composable
private fun WatchedToggle(
    watched: Boolean,
    watchedLabel: String = "✓ Văzut",
    unwatchedLabel: String = "Marchează văzut",
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        active = watched,
        modifier = Modifier.semantics { stateDescription = if (watched) "Văzut" else "Nevăzut" },
    ) { Text(if (watched) watchedLabel else unwatchedLabel) }
}

@Composable
private fun TrailerButton(details: TitleDetails) {
    val context = LocalContext.current
    details.trailerYoutubeKey?.let { key ->
        OutlinedButton(onClick = { context.openUrl("https://www.youtube.com/watch?v=$key") }) { Text("Trailer") }
    }
}

/**
 * Butonul principal pornește direct pe serviciul tău (ex. „Netflix (RO)”), altfel în playerul standard;
 * „Alege unde vezi” deschide lista cu toate variantele.
 */
@Composable
private fun PrimaryActions(watch: WatchOptions?, focus: FocusRequester, onPlay: () -> Unit, onChoose: () -> Unit) {
    val target = watch?.recommendedService?.label ?: watch?.recommendedPlayer?.displayName
    Button(onClick = if (target == null) onChoose else onPlay, modifier = Modifier.focusRequester(focus)) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(target ?: "Alege unde vezi")
    }
    if (target != null) {
        OutlinedButton(onClick = onChoose) { Text("Alege unde vezi") }
    }
}

@Composable
private fun DetailsHero(details: TitleDetails, heroHeight: Dp) {
    val item = details.item
    TitleHero(item.title, details.logoUrl, heroHeight, Modifier.heightIn(min = heroHeight)) { lines ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PosterRatings(item.kind, item.id, item.rating)
            Text(
                metaLine(details),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val text = item.overview.ifBlank { details.tagline.orEmpty() }
        if (text.isNotBlank()) ExpandableText(text, collapsedLines = lines)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(
    details: TitleDetails,
    watch: WatchOptions?,
    modifier: Modifier = Modifier,
    actionsModifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            modifier = actionsModifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions()
        }
        WhereToWatch(watch)
        details.nextEpisode?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        details.originalTitle?.let {
            Text("Titlu original: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Unde e titlul, doar în bibliotecile tale (fiecare serviciu în țara setată, RO sau US): serviciile tale pe care
 * îl găsești; dacă nu e pe niciunul, unde se găsește (neinstalat) și playerul în care îl poți porni.
 */
@Composable
private fun WhereToWatch(watch: WatchOptions?) {
    watch ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        watch.services.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pe serviciile tale:", style = MaterialTheme.typography.bodyMedium, color = muted)
            watch.services.forEach { s -> ServiceBadge(s.provider.logoUrl, s.label) }
        }
        watch.elsewhere.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nu e pe serviciile tale. Se găsește pe:", style = MaterialTheme.typography.bodyMedium, color = muted)
                watch.elsewhere.forEach { s -> ServiceBadge(s.provider.logoUrl, "${s.label} · neinstalat") }
            }
            playerHint(watch)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
        }
        else -> Text(
            listOfNotNull("Nu e pe niciun serviciu de streaming din bibliotecile tale (RO / US).", playerHint(watch)).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}

private fun playerHint(watch: WatchOptions): String? =
    watch.players.firstOrNull()?.let { "Îl poți porni în ${it.displayName}." }
        ?: "Instalează un player (Stremio, Nuvio, DebridStream…) ca să-l poți porni."

@Composable
private fun ServiceBadge(logo: String?, label: String) {
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NetworkImage(logo, null, Modifier.size(24.dp).clip(MaterialTheme.shapes.small), fallbackText = null)
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

private fun metaLine(d: TitleDetails): String = listOfNotNull(
    d.item.year?.toString(),
    d.item.kind.label,
    d.runtimeMinutes?.takeIf { it > 0 }?.let { m -> if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" },
    d.seasonCount?.let { if (it == 1) "1 sezon" else "$it sezoane" },
    d.genres.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", "),
).joinToString(" · ")

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
        progress.next?.let { n -> parts += "Urmează S%02dE%02d".format(n.season, n.episode) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Regia, scenariul, creatorii (ca „Informații” din AK VOD Hub), pe două coloane. */
@Composable
private fun InfoLines(details: TitleDetails, modifier: Modifier = Modifier) {
    val lines = details.crew
        .groupBy { it.role.orEmpty() }
        .filterKeys { it.isNotBlank() }
        .map { (role, people) -> role to people.take(3).joinToString(", ") { it.name } }
        .toMutableList()
    details.studios.takeIf { it.isNotEmpty() }?.let { s -> lines += "Studiouri" to s.take(3).joinToString(", ") { it.name } }
    if (lines.isEmpty()) return
    Column(modifier.widthIn(max = 1000.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Informații", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        lines.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                pair.forEach { (label, value) ->
                    Row(Modifier.weight(1f)) {
                        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------- Episoade, pe sezoane (ca în AK VOD Hub) ----------

@Composable
private fun EpisodesPane(
    details: TitleDetails,
    selected: Int?,
    episodes: LoadState<Season>?,
    watched: Set<EpisodeNumber>,
    next: EpisodeNumber?,
    canMark: Boolean,
    onSelectSeason: (Int) -> Unit,
    onToggle: (Episode) -> Unit,
    onToggleSeason: (Int, Boolean) -> Unit,
    onPlay: (Episode) -> Unit,
    message: String?,
    onBack: () -> Unit,
) {
    val spec = layoutSpec()
    val seasons = details.seasons
    val current = seasons.firstOrNull { it.number == selected } ?: seasons.firstOrNull() ?: return
    fun seasonLabel(s: SeasonSummary): String {
        val seen = watched.count { it.season == s.number && it.episode <= s.episodeCount }
        return if (seen > 0) "${s.name} · $seen/${s.episodeCount}" else "${s.name} · ${s.episodeCount} ep."
    }
    if (spec.isCompact) {
        Column(Modifier.fillMaxSize().padding(horizontal = spec.screenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Înapoi la fișă") }
            Dropdown("Sezonul", current, seasons, ::seasonLabel, { onSelectSeason(it.number) })
            SeasonEpisodes(current, episodes, watched, next, canMark, onToggle, onToggleSeason, onPlay, message, Modifier.weight(1f))
        }
        return
    }
    Row(
        Modifier.fillMaxSize().padding(horizontal = spec.screenPadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            Modifier
                .weight(0.28f)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TitleLogo(details.item.title, details.logoUrl, height = 52.dp, maxWidth = 260.dp, textStyle = MaterialTheme.typography.titleLarge)
            Text(
                listOfNotNull(details.item.year?.toString(), if (seasons.size == 1) "1 sezon" else "${seasons.size} sezoane").joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onBack) { Text("Înapoi la fișă") }
            LazyColumn(
                state = rememberLazyListState(seasons.indexOf(current).coerceAtLeast(0)),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(seasons, key = { it.number }) { season ->
                    SeasonRow(seasonLabel(season), active = season.number == current.number) { onSelectSeason(season.number) }
                }
            }
        }
        SeasonEpisodes(current, episodes, watched, next, canMark, onToggle, onToggleSeason, onPlay, message, Modifier.weight(0.72f))
    }
}

@Composable
private fun SeasonRow(label: String, active: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    PillRow(onClick = onClick, active = active, modifier = Modifier.fillMaxWidth()) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    focused -> c.onSecondary
                    active -> c.onSurface
                    else -> c.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SeasonEpisodes(
    season: SeasonSummary,
    episodes: LoadState<Season>?,
    watched: Set<EpisodeNumber>,
    next: EpisodeNumber?,
    canMark: Boolean,
    onToggle: (Episode) -> Unit,
    onToggleSeason: (Int, Boolean) -> Unit,
    onPlay: (Episode) -> Unit,
    message: String?,
    modifier: Modifier,
) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(season.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            val loaded = (episodes as? LoadState.Data)?.value?.takeIf { it.number == season.number }
            if (canMark && loaded != null) {
                val aired = ro.ak.vodunify.core.tracking.SeriesMarker.aired(loaded.episodes)
                if (aired.isNotEmpty() || loaded.episodes.any { EpisodeNumber(it.season, it.number) in watched }) {
                    val seasonWatched = loaded.episodes.isNotEmpty() && loaded.episodes.all { EpisodeNumber(it.season, it.number) in watched }
                    WatchedToggle(seasonWatched, watchedLabel = "✓ Sezon văzut", unwatchedLabel = "Marchează sezonul văzut") {
                        onToggleSeason(season.number, seasonWatched)
                    }
                }
            }
        }
        Box(Modifier.heightIn(min = 22.dp)) {
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        when (episodes) {
            null, LoadState.Loading -> LoadingPanel()
            is LoadState.Failed -> ErrorPanel(episodes.error, onRetry = null)
            is LoadState.Data -> if (episodes.value.number != season.number) {
                LoadingPanel()
            } else if (episodes.value.episodes.isEmpty()) {
                Text("Nu există episoade listate pentru acest sezon.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                key(episodes.value.number) {
                    EpisodeList(episodes.value.episodes, watched, next, canMark, onToggle, onPlay)
                }
            }
        }
    }
}

@Composable
private fun EpisodeList(
    list: List<Episode>,
    watched: Set<EpisodeNumber>,
    next: EpisodeNumber?,
    canMark: Boolean,
    onToggle: (Episode) -> Unit,
    onPlay: (Episode) -> Unit,
) {
    val spec = layoutSpec()
    // Focusul intră pe următorul episod de văzut (sau pe primul).
    val initial = remember { list.indexOfFirst { EpisodeNumber(it.season, it.number) == next }.coerceAtLeast(0) }
    val focus = remember { FocusRequester() }
    val state = rememberLazyListState(initial)
    if (spec.isTv) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(list, key = { "ep_${it.season}_${it.number}" }) { ep ->
            val number = EpisodeNumber(ep.season, ep.number)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EpisodeRow(
                    episode = ep,
                    watched = number in watched,
                    isNext = number == next,
                    modifier = Modifier.weight(1f).then(if (ep == list[initial]) Modifier.focusRequester(focus) else Modifier),
                    onClick = { onPlay(ep) },
                )
                if (canMark) {
                    WatchedToggle(number in watched) { onToggle(ep) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, watched: Boolean, isNext: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    FocusCard(onClick = onClick, focusScale = 1.01f, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().background(c.surface.copy(alpha = 0.92f)).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(136.dp).aspectRatio(16f / 9).clip(MaterialTheme.shapes.small)) {
                NetworkImage(episode.stillUrl, null, Modifier.fillMaxSize(), fallbackText = "E${episode.number}", contentScale = ContentScale.Crop)
                if (watched) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(c.primary))
                }
            }
            Column(Modifier.weight(1f).heightIn(min = 70.dp), verticalArrangement = Arrangement.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${episode.number}. ${episode.name}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Notele episodului: TMDb și IMDb (IMDb doar cu cheia OMDb).
                    episode.rating?.let { RatingChip(it) }
                    episode.imdbRating?.let { ImdbChip(it) }
                    episode.runtimeMinutes?.takeIf { it > 0 }?.let { Text("$it min", style = MaterialTheme.typography.labelMedium) }
                }
                if (episode.overview.isNotBlank()) {
                    Text(episode.overview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val info = listOfNotNull(
                    "Următorul de văzut".takeIf { isNext },
                    "Văzut".takeIf { watched },
                    episode.airDate,
                ).joinToString(" · ")
                if (info.isNotEmpty()) {
                    Text(info, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1)
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
                FocusCard(onClick = { actions.openStudio(studio.kind, studio.id) }, modifier = Modifier.width(160.dp)) {
                    Column(
                        Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(Color(0xFFE6E8EC), MaterialTheme.shapes.extraSmall),
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
                        Text(studio.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
