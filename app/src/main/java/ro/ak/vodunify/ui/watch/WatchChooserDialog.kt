package ro.ak.vodunify.ui.watch

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.playback.LinkPrecision
import ro.ak.vodunify.core.playback.MarketWarning
import ro.ak.vodunify.core.playback.OfferType
import ro.ak.vodunify.core.playback.ResumeInfo
import ro.ak.vodunify.core.playback.ResumeState
import ro.ak.vodunify.core.playback.ServiceOption
import ro.ak.vodunify.core.playback.formatPosition
import ro.ak.vodunify.ui.components.NetworkImage
import ro.ak.vodunify.ui.components.layoutSpec

/** Dialogul „Alege unde vezi”. Se afișează cât timp [WatchChooserViewModel.state] nu e null. */
@Composable
fun WatchChooserHost(viewModel: WatchChooserViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }
    val s = state ?: return
    Dialog(onDismissRequest = viewModel::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ChooserContent(s, viewModel)
    }
    s.confirm?.let { target ->
        ResumeConfirmDialog(
            appName = target.installed.app.displayName,
            resume = s.resume,
            syncing = s.syncing,
            onOpenNow = viewModel::confirmOpenNow,
            onWait = viewModel::waitForSync,
            onDismiss = viewModel::cancelConfirm,
        )
    }
}

@Composable
private fun ChooserContent(s: ChooserState, vm: WatchChooserViewModel) {
    val spec = layoutSpec()
    val firstFocus = remember { FocusRequester() }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .padding(if (spec.isCompact) 12.dp else 48.dp)
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .heightIn(max = 900.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Alege unde vezi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (s.request.type == ro.ak.vodunify.core.playback.PlaybackRequest.Type.EPISODE) "${s.request.title} · ${s.request.label}" else s.request.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ResumeChip(s.resume)

            val options = s.options
            if (s.loading || options == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                options.warning?.let { w -> item(key = "warn") { WarningCard(w) } }

                item(key = "svc_title") { SectionLabel("Servicii oficiale · ${options.market.label} (${options.market.code})") }
                if (options.services.isEmpty()) {
                    item(key = "svc_empty") { Hint("Niciun serviciu oficial în piața ta de redare.") }
                }
                items(options.services, key = { "svc_${it.provider.id}" }) { opt ->
                    val focus = if (opt == options.services.firstOrNull()) firstFocus else null
                    ServiceRow(opt, opt.app?.let { s.launches[it]?.precision }, focus) { vm.chooseService(opt) }
                }

                item(key = "pl_title") { SectionLabel("Playere instalate") }
                if (options.players.isEmpty()) {
                    item(key = "pl_empty") { Hint("Nu am găsit Nuvio, Stremio sau DebridStream pe acest dispozitiv.") }
                }
                items(options.players, key = { "pl_${it.id}" }) { app ->
                    val focus = if (options.services.isEmpty() && app == options.players.firstOrNull()) firstFocus else null
                    ChoiceRow(
                        title = s.launches[app]?.installed?.label ?: app.displayName,
                        subtitle = s.launches[app]?.precision?.label ?: "Nu poate fi pornit",
                        badge = if (app == options.recommendedPlayer) "Recomandat" else null,
                        logo = null,
                        focusRequester = focus,
                        enabled = s.launches[app] != null,
                    ) { vm.choosePlayer(app) }
                }
                if (options.missingPlayers.isNotEmpty() && options.players.isNotEmpty()) {
                    item(key = "pl_missing") { Hint("Neinstalate: " + options.missingPlayers.joinToString { it.displayName }) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = vm::dismiss, modifier = if (options.services.isEmpty() && options.players.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier) {
                    Text("Închide")
                }
            }
        }
    }
}

@Composable
private fun ServiceRow(opt: ServiceOption, precision: LinkPrecision?, focus: FocusRequester?, onClick: () -> Unit) {
    val app = opt.app
    val subtitle = buildList {
        add(opt.offer.label)
        when {
            app == null -> add("fără integrare încă · vezi ofertele")
            !opt.installed -> add("${app.displayName} nu e instalat · instalează")
            else -> {
                if (opt.usesPreferredPlayer) add("se deschide în ${app.displayName}")
                add(precision?.label ?: LinkPrecision.APP.label)
            }
        }
    }.joinToString(" · ")
    ChoiceRow(
        title = opt.provider.name,
        subtitle = subtitle,
        badge = when {
            opt.usesPreferredPlayer -> "Preferat"
            opt.offer == OfferType.RENT || opt.offer == OfferType.BUY -> null
            opt.installed -> "Instalat"
            else -> null
        },
        logo = opt.provider.logoUrl,
        focusRequester = focus,
        enabled = true,
        dimmed = !opt.installed,
        onClick = onClick,
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    badge: String?,
    logo: String?,
    focusRequester: FocusRequester?,
    enabled: Boolean,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (focused) BorderStroke(2.dp, Color.White) else null,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> focusRequester?.let { m.focusRequester(it) } ?: m },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NetworkImage(logo, title, Modifier.size(44.dp), fallbackText = title.take(2))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            badge?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun ResumeChip(resume: ResumeInfo) {
    val color = when (resume.state) {
        ResumeState.SYNCED -> Color(0xFF4CAF50)
        ResumeState.PENDING -> Color(0xFFFFB300)
        ResumeState.ESTIMATED -> Color(0xFF64B5F6)
        ResumeState.MANUAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = buildString {
        append(resume.state.label)
        resume.positionMs?.takeIf { resume.hasPosition }?.let { append(" · ").append(formatPosition(it)) }
        resume.source?.let { append(" · ").append(it) }
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.18f), border = BorderStroke(1.dp, color)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun WarningCard(w: MarketWarning) {
    val text = when (w) {
        is MarketWarning.OnlyElsewhere ->
            "Nu e pe niciun serviciu cu abonament în ${w.playbackMarket.label}, piața conturilor tale. " +
                "În ${w.otherMarket.label} e pe: ${w.services.take(4).joinToString()}. " +
                "Acolo ai nevoie de un cont din ${w.otherMarket.code}; aplicația oficială poate să nu-l găsească."
        MarketWarning.NowhereStreaming -> "Nu apare pe niciun serviciu cu abonament în RO sau US. Poți încerca un player."
    }
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0x33FFB300), border = BorderStroke(1.dp, Color(0xFFFFB300))) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ResumeConfirmDialog(
    appName: String,
    resume: ResumeInfo,
    syncing: Boolean,
    onOpenNow: () -> Unit,
    onWait: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val pos = resume.positionMs?.let(::formatPosition) ?: "--:--"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reiei în $appName?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (resume.state) {
                        ResumeState.PENDING -> "Poziția $pos e încă în așteptare: un tracker nu a confirmat ultima actualizare."
                        ResumeState.ESTIMATED -> "Poziția $pos e estimată din procentul vizionat. Aplicația-țintă nu primește poziția: caută manual momentul."
                        else -> "Poziția: $pos"
                    },
                )
                if (syncing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Se sincronizează…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onOpenNow, modifier = Modifier.focusRequester(focus)) { Text("Deschide acum · $pos") }
        },
        dismissButton = {
            OutlinedButton(onClick = onWait, enabled = !syncing) { Text("Așteaptă sincronizarea") }
        },
    )
}
