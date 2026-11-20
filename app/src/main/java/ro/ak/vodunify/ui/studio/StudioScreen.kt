package ro.ak.vodunify.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.catalog.StudioDetails
import ro.ak.vodunify.ui.components.AttributionNote
import ro.ak.vodunify.ui.components.ErrorPanel
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.LoadingPanel
import ro.ak.vodunify.ui.components.NetworkImage
import ro.ak.vodunify.ui.components.TitleRow
import ro.ak.vodunify.ui.components.layoutSpec

/** Pagina unui studio (companie de producție) sau a unei rețele TV. */
@Composable
fun StudioScreen(viewModel: StudioViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        LoadState.Loading -> LoadingPanel(Modifier.fillMaxSize())
        is LoadState.Failed -> ErrorPanel(s.error, onRetry = viewModel::retry)
        is LoadState.Data -> StudioContent(s.value)
    }
}

@Composable
private fun StudioContent(studio: StudioDetails) {
    val spec = layoutSpec()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Row(
                Modifier.padding(horizontal = spec.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (studio.ref.logoUrl != null) {
                    Box(
                        Modifier
                            .size(200.dp, 100.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFE6E8EC)),
                        contentAlignment = Alignment.Center,
                    ) {
                        NetworkImage(
                            studio.ref.logoUrl,
                            studio.ref.name,
                            Modifier.fillMaxSize().padding(12.dp),
                            contentScale = ContentScale.Fit,
                            placeholderColor = Color.Transparent,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(studio.ref.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(studio.ref.kind.label, studio.country, studio.headquarters).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    studio.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.widthIn(max = 900.dp),
                            maxLines = 5,
                        )
                    }
                }
            }
        }
        item(key = "movies") { TitleRow("Filme", studio.movies) }
        item(key = "shows") { TitleRow("Seriale", studio.shows) }
        if (studio.movies.isEmpty() && studio.shows.isEmpty()) {
            item(key = "empty") {
                Text(
                    "TMDb nu are titluri listate pentru ${studio.ref.name}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spec.screenPadding),
                )
            }
        }
        item(key = "attribution") { AttributionNote(includeJustWatch = false) }
    }
}
