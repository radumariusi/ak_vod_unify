package ro.ak.vodunify.ui.components

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        modifier = modifier.padding(horizontal = layoutSpec().screenPadding, vertical = 8.dp),
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

@Composable
fun PosterCard(item: CatalogItem, modifier: Modifier = Modifier) {
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    Column(modifier.width(spec.posterWidth)) {
        FocusCard(
            onClick = { actions.openTitle(item.kind, item.id) },
            modifier = Modifier.size(spec.posterWidth, spec.posterHeight),
        ) {
            NetworkImage(item.posterUrl, item.title, Modifier.fillMaxSize(), fallbackText = item.title)
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(item.subtitle.takeIf { it.isNotBlank() }, formatRating(item.rating)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TitleRow(title: String, items: List<CatalogItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val spec = layoutSpec()
    Column(modifier) {
        SectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (spec.isTv) 20.dp else 12.dp),
        ) {
            items(items, key = { it.mediaKey }) { PosterCard(it) }
        }
    }
}

@Composable
fun PersonCard(person: PersonSummary, modifier: Modifier = Modifier) {
    val actions = LocalCatalogActions.current
    val size = if (layoutSpec().isTv) 110.dp else 84.dp
    Column(modifier.width(size + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusCard(onClick = { actions.openPerson(person.id) }, modifier = Modifier.size(size), shape = CircleShape) {
            NetworkImage(person.profileUrl, person.name, Modifier.fillMaxSize(), fallbackText = initials(person.name))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            person.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
        person.role?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun initials(name: String) = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }

@Composable
fun PersonRow(title: String, people: List<PersonSummary>) {
    if (people.isEmpty()) return
    val spec = layoutSpec()
    Column {
        SectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(people, key = { "${it.id}:${it.role}" }) { PersonCard(it) }
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
fun AttributionNote(modifier: Modifier = Modifier, includeJustWatch: Boolean = true) {
    Text(
        buildString {
            append("Date despre titluri: TMDb. Acest produs folosește API-ul TMDb, dar nu este aprobat sau certificat de TMDb.")
            if (includeJustWatch) append(" Disponibilitatea pe servicii este furnizată de JustWatch.")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = layoutSpec().screenPadding, vertical = 12.dp),
    )
}
