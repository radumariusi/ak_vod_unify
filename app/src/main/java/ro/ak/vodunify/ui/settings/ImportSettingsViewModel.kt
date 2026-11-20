package ro.ak.vodunify.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.imports.CsvParse
import ro.ak.vodunify.core.imports.HistoryImportRepository
import ro.ak.vodunify.core.imports.ImportBatch
import ro.ak.vodunify.core.imports.ImportServiceStats
import ro.ak.vodunify.core.imports.ImportSources
import ro.ak.vodunify.core.imports.NetflixCsv
import javax.inject.Inject

/** CSV-ul citit, înainte de import (alegi profilul, dacă sunt mai multe). */
data class CsvPreview(val parse: CsvParse, val fileName: String)

@HiltViewModel
class ImportSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: HistoryImportRepository,
) : ViewModel() {
    val stats: StateFlow<List<ImportServiceStats>> = repository.stats
    val pending: StateFlow<Int> = repository.pending

    private val _installed = MutableStateFlow(repository.installedImporters())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val _preview = MutableStateFlow<CsvPreview?>(null)
    val preview: StateFlow<CsvPreview?> = _preview.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val routes: List<String> get() = repository.importerPackages.keys.toList()

    fun refresh() {
        _installed.value = repository.installedImporters()
    }

    fun launchIntent(route: String): Intent? {
        val pkg = repository.importerPackages[route] ?: return null
        val pm = context.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            items.chunked(CHUNK).forEachIndexed { i, chunk ->
                repository.enqueue(
                    ImportBatch(
                        service = ImportSources.NETFLIX,
                        route = "CSV",
                        profile = profile.orEmpty(),
                        items = chunk,
                        last = (i + 1) * CHUNK >= items.size,
                    ),
                )
            }
            _preview.value = null
            _message.value = "Am pus în coadă ${items.size} titluri. Le caut în TMDb în fundal; progresul apare mai jos."
        }
    }

    fun clear(service: String) {
        viewModelScope.launch { repository.clear(service) }
    }

    private companion object {
        const val CHUNK = 500
    }
}
