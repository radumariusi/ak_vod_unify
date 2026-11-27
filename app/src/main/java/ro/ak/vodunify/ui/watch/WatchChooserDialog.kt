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
import ro.ak.vodunify.ui.components.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import ro.ak.vodunify.ui.components.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import ro.ak.vodunify.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
import ro.ak.vodunify.core.playback.ResumeInfo
import ro.ak.vodunify.core.playback.ResumeState
import ro.ak.vodunify.core.playback.ServiceOption
import ro.ak.vodunify.core.playback.PlaybackRequest
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.playback.WatchOptions
import ro.ak.vodunify.ui.components.ChoiceDialog
import ro.ak.vodunify.ui.components.ChoiceOption
import ro.ak.vodunify.core.playback.formatPosition
import ro.ak.vodunify.core.connector.ExternalTrackers
import ro.ak.vodunify.ui.components.layoutSpec

/**
 * „Alege unde vezi”, ca listă (ca în AK VOD Hub): serviciile tale pe care e titlul (ex. „Netflix (RO)”),
 * apoi playerele. Dacă nu e pe niciun serviciu al tău, arată unde se găsește (neinstalat) și playerele.
 * La pornirea directă („Vezi”) lista nu apare deloc, decât dacă nu avem ce porni singuri.
 */
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
    when {
        s.editingMinute -> SetMinuteDialog(
            initialMs = s.resume.positionMs?.takeIf { s.resume.hasPosition } ?: 0L,
            runtimeMinutes = s.request.runtimeMinutes,
            onSave = viewModel::saveMinute,
            onDismiss = viewModel::closeSetMinute,
        )
        s.confirm != null -> ResumeConfirmDialog(
            appName = s.confirm.installed.app.displayName,
            resume = s.resume,
            syncing = s.syncing,
            onOpenNow = viewModel::confirmOpenNow,
            onWait = viewModel::waitForSync,
            onDismiss = viewModel::cancelConfirm,
        )
        s.direct -> Unit // pornire directă: se încarcă, apoi se deschide singur
        s.loading || s.options == null -> Dialog(onDismissRequest = viewModel::dismiss) {
            Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        else -> ChooserList(s, s.options, viewModel)
    }
}

private sealed interface ChooserAction {
    data class Service(val option: ServiceOption) : ChooserAction
    data class Player(val app: TargetApp) : ChooserAction
    data object SetMinute : ChooserAction
}

@Composable
private fun ChooserList(s: ChooserState, options: WatchOptions, vm: WatchChooserViewModel) {
    // Aplicația în care ai văzut ultima dată: marcată și aleasă din start (acolo continui standard).
    fun isLast(app: TargetApp?): Boolean =
        app != null && s.resume.appPackage != null && s.launches[app]?.installed?.packageName == s.resume.appPackage
    val lastMark = " · ultima dată aici"
    val entries = buildList {
        options.services.forEachIndexed { i, opt ->
            val app = opt.app
            val launch = app?.let { s.launches[it] }
            val detail = when {
                launch == null -> "Nu poate fi pornit"
                opt.usesPreferredPlayer -> "Se deschide în ${launch.installed.label}"
                else -> launch.precision.label
            }
            add(ChoiceOption(opt.label + if (isLast(app)) lastMark else "", detail, enabled = launch != null,
                heading = if (i == 0) "Serviciile tale" else null) to ChooserAction.Service(opt))
        }
        options.elsewhere.forEachIndexed { i, opt ->
            add(
                ChoiceOption(
                    opt.label,
                    "Neinstalat · deschide magazinul",
                    heading = if (i == 0) "Nu e pe serviciile tale. Se găsește pe:" else null,
                ) to ChooserAction.Service(opt),
            )
        }
        options.players.forEachIndexed { i, app ->
            val launch = s.launches[app]
            add(
                ChoiceOption(
                    label = (launch?.installed?.label ?: app.displayName) + (if (i == 0) " · standard" else "") + if (isLast(app)) lastMark else "",
                    detail = launch?.precision?.label ?: "Nu poate fi pornit",
                    enabled = launch != null,
                    heading = if (i == 0) "Redă într-un player" else null,
                ) to ChooserAction.Player(app),
            )
        }
        if (s.canSetMinute) {
            add(ChoiceOption("Setează minutul…", "Poziția se salvează local, în „Continuă vizionarea”", heading = "Altele") to ChooserAction.SetMinute)
        }
    }
    val title = if (s.request.type == PlaybackRequest.Type.EPISODE) "${s.request.title} · ${s.request.label}" else s.request.label
    val footer = buildList {
        if (s.appsUnavailable) add("Aplicațiile instalate nu au putut fi verificate. Închide lista și încearcă din nou.")
        if (!s.appsUnavailable && options.services.isEmpty() && options.elsewhere.isEmpty()) add("Nu e pe niciun serviciu de streaming din bibliotecile tale (RO / US).")
        if (!s.appsUnavailable && options.players.isEmpty()) add("Niciun player instalat (Stremio, Nuvio, DebridStream, AK VoD Hub…).")
        s.resume.positionMs?.takeIf { s.resume.hasPosition }?.let { pos ->
            add("Reiei de la ${formatPosition(pos)}" + (s.resume.source?.let { " ($it)" } ?: ""))
        }
    }.joinToString("\n").ifBlank { null }
    ChoiceDialog(
        title = "Unde vezi · $title",
        options = entries.map { it.first },
        selected = entries.indexOfFirst { (_, action) ->
            (action is ChooserAction.Service && isLast(action.option.app)) || (action is ChooserAction.Player && isLast(action.app))
        }.takeIf { it >= 0 },
        onPick = { index ->
            when (val action = entries[index].second) {
                is ChooserAction.Service -> vm.chooseService(action.option)
                is ChooserAction.Player -> vm.choosePlayer(action.app)
                ChooserAction.SetMinute -> vm.openSetMinute()
            }
        },
        onDismiss = vm::dismiss,
        footer = footer,
        dismissOnPick = false,
    )
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

/** „Setează minutul”: butoane mari pentru D-pad și touch, fără tastatură. */
@Composable
private fun SetMinuteDialog(initialMs: Long, runtimeMinutes: Int?, onSave: (Long) -> Unit, onDismiss: () -> Unit) {
    val max = runtimeMinutes?.takeIf { it > 0 } ?: 600
    var minute by remember { mutableIntStateOf((initialMs / 60_000L).toInt().coerceIn(0, max)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Setează minutul") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatPosition(minute * 60_000L) + (runtimeMinutes?.let { " / " + formatPosition(it * 60_000L) } ?: ""),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(-10, -1, 1, 10).forEach { step ->
                        OutlinedButton(
                            onClick = { minute = (minute + step).coerceIn(0, max) },
                            modifier = if (step == 1) Modifier.focusRequester(focus) else Modifier,
                        ) { Text(if (step > 0) "+$step" else "$step") }
                    }
                }
                Text(
                    if (runtimeMinutes == null) "Durata nu e cunoscută: poziția se salvează local, dar nu poate fi trimisă ca procent."
                    else if (ExternalTrackers.ENABLED) "Peste 80% nu se publică: trackerele l-ar putea trece ca văzut."
                    else "Poziția se salvează local.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(minute * 60_000L) }) { Text(if (minute == 0) "Șterge poziția" else "Salvează") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        },
    )
}
