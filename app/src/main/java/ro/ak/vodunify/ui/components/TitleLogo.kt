package ro.ak.vodunify.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Titlul ca siglă (ca în AK VOD Hub): logo-ul TMDb al titlului, aliniat la stânga. Numele (text) apare doar
 * când titlul nu are siglă sau imaginea nu se poate descărca; cât timp sigla se caută ([pending]) sau se
 * încarcă, locul rămâne gol (fără să clipească întâi numele).
 */
@Composable
fun TitleLogo(
    title: String,
    logoUrl: String?,
    height: Dp,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 380.dp,
    textStyle: TextStyle = MaterialTheme.typography.headlineMedium,
    pending: Boolean = false,
) {
    var failed by remember(logoUrl) { mutableStateOf(false) }
    Box(modifier.widthIn(max = maxWidth).fillMaxWidth().height(height), contentAlignment = Alignment.CenterStart) {
        if (!pending && (logoUrl == null || failed)) {
            Text(
                title,
                style = textStyle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (logoUrl != null) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onSuccess = { failed = false },
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
