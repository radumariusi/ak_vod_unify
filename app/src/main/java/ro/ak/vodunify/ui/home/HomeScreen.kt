package ro.ak.vodunify.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.ui.components.FocusCard
import ro.ak.vodunify.ui.components.layoutSpec

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val spec = layoutSpec()
    val heroFocus = remember { FocusRequester() }

    // Pe TV punem focusul inițial pe butonul principal, ca D-pad-ul să aibă de unde porni.
    if (spec.isTv) {
        LaunchedEffect(Unit) { runCatching { heroFocus.requestFocus() } }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Hero(state.hero, heroFocus) }
        items(state.rows, key = { it.title }) { row -> ContentRow(row) }
    }
}

@Composable
private fun Hero(hero: HomeHero, focusRequester: FocusRequester) {
    val spec = layoutSpec()
    Box(
        Modifier
            .fillMaxWidth()
            .height(spec.heroHeight)
            .padding(horizontal = spec.screenPadding)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF3A0A10), Color(0xFF14202E), Color(0xFF0E1116))),
            ),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(if (spec.isCompact) 16.dp else 32.dp)
                .fillMaxWidth(if (spec.isCompact) 1f else 0.6f),
        ) {
            Text(
                hero.title,
                style = if (spec.isCompact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                hero.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {}, modifier = Modifier.focusRequester(focusRequester)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Redă")
                }
                OutlinedButton(onClick = {}) { Text("Detalii") }
            }
        }
    }
}

@Composable
private fun ContentRow(row: HomeRow) {
    val spec = layoutSpec()
    Column {
        Text(
            row.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = spec.screenPadding, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (spec.isTv) 20.dp else 12.dp),
        ) {
            items(row.items, key = { it.id }) { item -> PosterCard(item) }
        }
    }
}

private val cardColors = listOf(
    Color(0xFF2E3A59), Color(0xFF4A2C40), Color(0xFF1F4E4A), Color(0xFF5A4A1F), Color(0xFF3B2E5A),
)

@Composable
private fun PosterCard(item: HomeItem) {
    val spec = layoutSpec()
    Column(Modifier.width(spec.cardWidth)) {
        FocusCard(onClick = {}, modifier = Modifier.size(spec.cardWidth, spec.cardHeight)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(cardColors[item.id.hashCode().mod(cardColors.size)]),
            ) {
                item.progress?.let {
                    LinearProgressIndicator(
                        progress = { it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
                Box(Modifier.fillMaxHeight())
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
