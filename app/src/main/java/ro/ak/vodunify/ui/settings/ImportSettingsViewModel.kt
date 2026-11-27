package ro.ak.vodunify.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.imports.CsvParse
import ro.ak.vodunify.core.imports.HistoryImportRepository
import ro.ak.vodunify.core.imports.ImportBatch
import ro.ak.vodunify.core.imports.ImportItem
import ro.ak.vodunify.core.imports.ImportServiceStats
import ro.ak.vodunify.core.imports.ImportSources
import ro.ak.vodunify.core.imports.NetflixCsv
import ro.ak.vodunify.core.imports.ServiceHistoryActivity
import ro.ak.vodunify.core.connector.DeviceAuthPoll
import ro.ak.vodunify.core.connector.DeviceCode
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.trakt.TraktApi
import ro.ak.vodunify.core.trakt.TraktConnector
import ro.ak.vodunify.core.trakt.TraktHistoryItem
import ro.ak.vodunify.core.trakt.TraktMappers
import ro.ak.vodimport.WebLoginActivity
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject

/** CSV-ul citit, înainte de import (alegi profilul, dacă sunt mai multe). */
data class CsvPreview(val parse: CsvParse, val fileName: String)

@HiltViewModel
class ImportSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: HistoryImportRepository,
    private val trakt: TraktConnector,
    private val traktApi: TraktApi,
) : ViewModel() {
    val stats: StateFlow<List<ImportServiceStats>> = repository.stats
    val pending: StateFlow<Int> = repository.pending

    val traktConfigured = trakt.isConfigured
    val traktAccount = trakt.account.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _traktCode = MutableStateFlow<DeviceCode?>(null)
    val traktCode = _traktCode.asStateFlow()
    private val _traktBusy = MutableStateFlow(false)
    val traktBusy = _traktBusy.asStateFlow()
    private var traktJob: Job? = null

    private val _preview = MutableStateFlow<CsvPreview?>(null)
    val preview: StateFlow<CsvPreview?> = _preview.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun launchIntent(service: String): Intent =
        Intent(context, ServiceHistoryActivity::class.java).putExtra(WebLoginActivity.EXTRA_SERVICE, service)

    fun connectTrakt() = runTrakt("Cer un cod de la Trakt…") {
        val code = trakt.startDeviceAuth()
        _traktCode.value = code
        var interval = code.intervalSeconds
        var approved = false
        while (!approved) {
            delay(interval * 1_000L)
            when (val poll = trakt.pollDeviceAuth(code)) {
                DeviceAuthPoll.Pending -> Unit
                DeviceAuthPoll.SlowDown -> interval += 5
                is DeviceAuthPoll.Approved -> approved = true
                DeviceAuthPoll.Denied -> error("Accesul la Trakt a fost refuzat.")
                DeviceAuthPoll.Expired -> error("Codul Trakt a expirat.")
                is DeviceAuthPoll.Failed -> error(poll.message)
            }
        }
        "Contul Trakt este conectat."
    }

    fun cancelTrakt() { traktJob?.cancel() }

    fun disconnectTrakt() = runTrakt("Deconectez Trakt…") {
        trakt.signOut()
        "Contul Trakt a fost deconectat."
    }

    fun importTrakt() = runTrakt("Citesc istoricul din Trakt…") {
        val items = mutableListOf<ImportItem>()
        val serializer = ListSerializer(TraktHistoryItem.serializer())
        var page = 1
        do {
            val response = traktApi.getPage("sync/history", serializer, mapOf("page" to page.toString(), "limit" to "100"))
            _message.value = "Trakt · pagina $page din ${response.pageCount.coerceAtLeast(page)}"
            for (event in response.items.mapNotNull(TraktMappers::history)) {
                val ref = event.media
                items += ImportItem(
                    series = ref.title.takeIf { ref.type == MediaType.EPISODE },
                    title = ref.episodeTitle ?: ref.title,
                    episode = ref.episodeTitle,
                    seasonNumber = ref.season,
                    episodeNumber = ref.episode,
                    date = event.watchedAt.toEpochMilli(),
                    tmdb = ref.ids.tmdb,
                    imdb = ref.ids.imdb,
                    kind = if (ref.type == MediaType.EPISODE) "episode" else "movie",
                    year = ref.year,
                )
            }
            if (response.items.isEmpty() || page >= response.pageCount) break
            page++
        } while (true)
        withContext(Dispatchers.IO) {
            repository.enqueueAll(items.chunked(CHUNK).mapIndexed { i, chunk ->
                ImportBatch(ImportSources.TRAKT, "RO", items = chunk, last = (i + 1) * CHUNK >= items.size)
            })
        }
        "${items.size} vizionări Trakt în coadă."
    }

    private fun runTrakt(status: String, action: suspend () -> String) {
        if (_traktBusy.value) return
        _traktBusy.value = true
        _message.value = status
        traktJob = viewModelScope.launch {
            try {
                _message.value = action()
            } catch (e: CancellationException) {
                _message.value = null
                throw e
            } catch (e: Exception) {
                _message.value = "Trakt: ${e.message ?: "eroare"}"
            } finally {
                _traktCode.value = null
                _traktBusy.value = false
            }
        }
    }

    fun setMessage(text: String?) {
        _message.value = text
    }

    fun readCsv(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    NetflixCsv.parse(text)
                }
            }
            result.onSuccess { parse ->
                if (parse.items.isEmpty()) {
                    _message.value = "Nu am găsit titluri în fișier. E CSV-ul de la Netflix (coloanele Title și Date)?"
                } else {
                    _preview.value = CsvPreview(parse, uri.lastPathSegment ?: "CSV")
                    _message.value = null
                }
            }.onFailure { _message.value = "Nu pot citi fișierul: ${it.message}" }
        }
    }

    fun cancelCsv() {
        _preview.value = null
    }

    /** [profile] = null: toate rândurile. */
    fun importCsv(profile: String?) {
        val p = _preview.value ?: return
        val items = if (profile == null) {
            p.parse.items
        } else {
            p.parse.items.filterIndexed { i, _ -> p.parse.itemProfiles.getOrNull(i) == profile }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.enqueueAll(items.chunked(CHUNK).mapIndexed { i, chunk ->
                    ImportBatch(
                        service = ImportSources.NETFLIX,
                        route = "CSV",
                        profile = profile.orEmpty(),
                        items = chunk,
                        last = (i + 1) * CHUNK >= items.size,
                    )
            })
            _preview.value = null
            _message.value = "Am pus în coadă ${items.size} titluri. Le caut în TMDb în fundal; progresul apare mai jos."
        }
    }

    fun clear(service: String) {
        viewModelScope.launch { repository.clear(service) }
    }

    fun retryUnmatched(service: String) {
        viewModelScope.launch {
            try {
                val count = repository.retryUnmatched(service)
                _message.value = if (count == 0) "Pentru titlurile acestui import vechi, pornește un nou import din același profil."
                else "$count titluri puse în coadă pentru o nouă potrivire."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Reîncercare eșuată: ${e.message}"
            }
        }
    }

    private companion object {
        const val CHUNK = 500
    }
}
