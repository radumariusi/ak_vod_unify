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
            if (rows.continueWatching.isEmpty()) {
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
            WideRow("Continuă vizionarea", rows.continueWatching)
            WideRow("Următorul episod", rows.upNext)
            WideRow("De văzut", rows.watchlist)
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
    Column(Modifier.width(spec.cardWidth)) {
        FocusCard(
            onClick = { item.tmdbId?.let { actions.openTitle(item.kind, it) } },
            modifier = Modifier.size(spec.cardWidth, spec.cardHeight),
            restoreKey = "$rowKey|${item.key}",
            onFocused = {
                val id = item.tmdbId
                if (backdrop != null && id != null) {
                    backdrop.focus(
                        FocusedTitle(
                            kind = item.kind,
                            id = id,
                            title = item.title,
                            meta = item.subtitle.orEmpty(),
                            overview = item.overview,
                            backdropUrl = item.backdropUrl,
                        ),
                    )
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                NetworkImage(item.backdropUrl ?: item.posterUrl, item.title, Modifier.fillMaxSize(), fallbackText = item.title)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))),
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
                    } else {
                        Text(
                            "?",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(Color(0xCC000000), MaterialTheme.shapes.extraSmall)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.subtitle.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
