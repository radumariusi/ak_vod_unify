package ro.ak.vodunify.core.imports

import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.Json
import org.json.JSONObject
import ro.ak.vodimport.WebLoginActivity
import ro.ak.vodunify.core.scrobble.NetflixMatchWorker
import javax.inject.Inject

@AndroidEntryPoint
class ServiceHistoryActivity : WebLoginActivity() {
    @Inject lateinit var repository: HistoryImportRepository
    @Inject lateinit var json: Json

    override fun saveHistory(service: String, payload: JSONObject): String {
        val batch = json.decodeFromString(ImportBatch.serializer(), payload.put("service", service).put("route", "RO").toString())
        if (batch.items.isEmpty()) return "Istoricul acestui profil este gol."
        repository.enqueueAll(batch.items.chunked(300).mapIndexed { index, items ->
            batch.copy(items = items, last = (index + 1) * 300 >= batch.items.size)
        })
        if (service == ImportSources.NETFLIX) {
            NetflixMatchWorker.enqueue(applicationContext, payload.put("status", "ok").toString())
        }
        return "${batch.items.size} vizionări în coadă. Vezi progresul în Setări → Istoric din servicii."
    }
}
