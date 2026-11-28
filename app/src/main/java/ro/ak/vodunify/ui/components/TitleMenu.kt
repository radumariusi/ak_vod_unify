package ro.ak.vodunify.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.tracking.TitleTrackState
import ro.ak.vodunify.core.tracking.TrackingRepository
import javax.inject.Inject

/** Meniul rapid al unui titlu (apăsare lungă pe orice poster): Lista mea, văzut, pagina titlului. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TitleMenuViewModel @Inject constructor(
    private val tracking: TrackingRepository,
    private val seriesMarker: ro.ak.vodunify.core.tracking.SeriesMarker,
    private val correction: ro.ak.vodunify.core.scrobble.TitleCorrection,
) : ViewModel() {
    /** „Titlu greșit”: lista de variante cu același nume (null = închisă). */
    data class FixUi(
        val item: CatalogItem,
        val resume: ro.ak.vodunify.core.db.ResumePointEntity?,
        val watched: Boolean,
        val name: String,
        val options: List<ro.ak.vodunify.core.scrobble.TitleCorrection.Option>? = null,
    )

    private val _fix = MutableStateFlow<FixUi?>(null)
    val fix: StateFlow<FixUi?> = _fix.asStateFlow()

    fun openFix() {
        val i = _item.value ?: return
        val s = state.value
        close()
        val key = ref(i).titleKey
        val name = correction.searchName(key, i.title, s?.resume)
        val ui = FixUi(i, s?.resume, s?.watched == true, name)
        _fix.value = ui
        viewModelScope.launch {
            val current = s?.resume?.ref?.let { ro.ak.vodunify.core.tracking.TrackingEntities.media(it).itemKey } ?: key
            val options = runCatching { correction.options(name, current) }.getOrDefault(emptyList())
            if (_fix.value?.item?.mediaKey == i.mediaKey) _fix.value = ui.copy(options = options)
        }
    }

    fun closeFix() {
        _fix.value = null
    }

    fun chooseFix(option: ro.ak.vodunify.core.scrobble.TitleCorrection.Option) {
        val f = _fix.value ?: return
        _fix.value = null
        viewModelScope.launch {
            _message.value = try {
                correction.apply(ref(f.item), f.resume, f.watched, option.media)
                "Corectat: ${option.label}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Eroare: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private val _item = MutableStateFlow<CatalogItem?>(null)
    val item: StateFlow<CatalogItem?> = _item.asStateFlow()

    val state: StateFlow<TitleTrackState?> = _item
        .flatMapLatest { it?.let { i -> tracking.titleState(i.mediaKey) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun open(item: CatalogItem) {
        _item.value = item
    }

    fun close() {
        _item.value = null
    }

    private fun ref(i: CatalogItem) = MediaRef(
        type = if (i.kind == TitleKind.MOVIE) MediaType.MOVIE else MediaType.SHOW,
        ids = ExternalIds(tmdb = i.id),
        title = i.title,
        year = i.year,
    )

    fun toggleList() = act { i, s ->
        val inList = s?.inMyList == true
        tracking.setInWatchlist(ref(i), !inList)
        if (inList) "„${i.title}” scos din Lista mea" else "„${i.title}” adăugat în Lista mea"
    }

    fun toggleWatched() = act { i, s ->
        val watched = s?.watched == true
        tracking.setWatched(ref(i), !watched)
        if (watched) "„${i.title}” marcat ca nevăzut" else "„${i.title}” marcat ca văzut"
    }

    /** Tot serialul (episoadele apărute) văzut / nevăzut. */
    fun markSeries(watched: Boolean) = act { i, _ ->
        val n = seriesMarker.mark(i.id, i.title, i.year, null, watched)
        if (watched) "„${i.title}”: $n episoade marcate ca văzute" else "„${i.title}” marcat ca nevăzut"
    }

    fun removeFromContinue() = act { i, _ ->
        tracking.removeFromContinueWatching(i.mediaKey)
        "„${i.title}” scos din Continuă vizionarea"
    }

    private fun act(block: suspend (CatalogItem, TitleTrackState?) -> String) {
        val i = _item.value ?: return
        val cached = state.value
        close()
        viewModelScope.launch {
            _message.value = try {
                val resolved = cached ?: runCatching { tracking.titleState(i.mediaKey).first() }.getOrNull()
                block(i, resolved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Eroare: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

@Composable
fun TitleMenuHost(viewModel: TitleMenuViewModel, onOpen: (TitleKind, Long) -> Unit) {
    val item by viewModel.item.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }
    fix?.let { FixDialog(it, viewModel) }
    val i = item ?: return
    val first = remember(i.mediaKey) { FocusRequester() }
    LaunchedEffect(i.mediaKey) { runCatching { first.requestFocus() } }
    AlertDialog(
        onDismissRequest = viewModel::close,
        // Deschis cu OK ținut apăsat: ridicarea lui nu trebuie să aleagă prima opțiune.
        modifier = Modifier.ignoreHeldOk(),
        title = { Text(i.title + (i.year?.let { " ($it)" } ?: "")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val inList = state?.inMyList == true
                Button(onClick = viewModel::toggleList, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                    Text(if (inList) "Scoate din Lista mea" else "+ Lista mea (De văzut)")
                }
                if (state?.resume != null) {
                    OutlinedButton(onClick = viewModel::removeFromContinue, modifier = Modifier.fillMaxWidth()) {
                        Text("Scoate din Continuă vizionarea")
                    }
                }
                if (i.kind == TitleKind.MOVIE) {
                    OutlinedButton(onClick = viewModel::toggleWatched, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state?.watched == true) "Marchează nevăzut" else "Marchează văzut")
                    }
                } else {
                    OutlinedButton(onClick = { viewModel.markSeries(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Marchează tot serialul văzut")
                    }
                    OutlinedButton(onClick = { viewModel.markSeries(false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Marchează tot serialul nevăzut")
                    }
                }
                if (state?.resume != null || state?.watched == true) {
                    OutlinedButton(onClick = viewModel::openFix, modifier = Modifier.fillMaxWidth()) {
                        Text("Titlu greșit")
                    }
                }
                OutlinedButton(
                    onClick = {
                        viewModel.close()
                        onOpen(i.kind, i.id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Deschide pagina titlului") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = viewModel::close) { Text("Închide") } },
    )
}

/** Lista de variante cu același nume: episoade (serial + SxEy) și filme (cu anul). */
@Composable
private fun FixDialog(f: TitleMenuViewModel.FixUi, viewModel: TitleMenuViewModel) {
    val first = remember(f.item.mediaKey, f.options) { FocusRequester() }
    LaunchedEffect(f.item.mediaKey, f.options) { runCatching { first.requestFocus() } }
    AlertDialog(
        onDismissRequest = viewModel::closeFix,
        modifier = Modifier.ignoreHeldOk(),
        title = { Text("Ce ai văzut de fapt? („${f.name}”)") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val options = f.options
                when {
                    options == null -> Text("Caut titlurile cu numele ăsta…")
                    options.isEmpty() -> Text("N-am găsit alt film sau episod cu numele „${f.name}”.")
                    else -> options.forEachIndexed { index, o ->
                        OutlinedButton(
                            onClick = { viewModel.chooseFix(o) },
                            modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(first) else it },
                        ) { Text(o.label) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = viewModel::closeFix, modifier = if (f.options.isNullOrEmpty()) Modifier.focusRequester(first) else Modifier) {
                Text("Renunță")
            }
        },
    )
}
