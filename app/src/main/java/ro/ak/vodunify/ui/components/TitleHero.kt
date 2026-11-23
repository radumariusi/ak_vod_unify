package ro.ak.vodunify.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val TV_ROW_TITLE = 40.dp
private val TV_CARD_PADDING = 20.dp
private val TV_ROW_GAP = 18.dp

/** Același loc pentru carduri landscape și portrait. Spațiul liber de sub ele revine zonei hero. */
fun tvHomeRowHeight(spec: LayoutSpec): Dp =
    TV_ROW_TITLE + maxOf(spec.posterHeight, spec.cardHeight) + TV_CARD_PADDING + TV_ROW_GAP

fun tvTitleHeroHeight(viewport: Dp, spec: LayoutSpec): Dp =
    (viewport - tvHomeRowHeight(spec) - TV_ROW_TITLE).coerceAtLeast(0.dp)

/** Sigla, metadatele și descrierea pornesc din aceleași coordonate în Acasă și în fișa titlului. */
@Composable
fun TitleHero(
    title: String,
    logoUrl: String?,
    heroHeight: Dp,
    modifier: Modifier = Modifier,
    pendingLogo: Boolean = false,
    content: @Composable (descriptionLines: Int) -> Unit,
) {
    val spec = layoutSpec()
    val compactTv = spec.isTv && heroHeight < 185.dp
    Column(
        modifier
            .padding(horizontal = spec.screenPadding)
            .widthIn(max = 740.dp)
            .fillMaxWidth(if (spec.isCompact) 1f else 0.74f)
            .padding(top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TitleLogo(
            title = title,
            logoUrl = logoUrl,
            pending = pendingLogo,
            height = when {
                compactTv -> 54.dp
                spec.isTv -> 68.dp
                spec.isCompact -> 56.dp
                else -> 64.dp
            },
            maxWidth = if (spec.isTv) 430.dp else 380.dp,
            textStyle = if (compactTv || spec.isCompact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        )
        content(if (compactTv && heroHeight < 158.dp) 2 else 3)
    }
}
