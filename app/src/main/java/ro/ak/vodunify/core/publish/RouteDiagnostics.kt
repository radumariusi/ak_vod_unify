package ro.ak.vodunify.core.publish

import ro.ak.vodunify.core.db.PublishRecordEntity
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackedRef
import ro.ak.vodunify.core.reconcile.KeyResolver
import ro.ak.vodunify.core.reconcile.ReconciledResume

/** Poziția unui element într-o sursă. */
data class SourcePosition(
    val source: String,
    val label: String,
    /** Null = sursa nu are elementul sau nu știe poziția. */
    val progress: Float?,
    val positionMs: Long?,
    val updatedAt: Long?,
    val isWinner: Boolean,
    val echo: Boolean,
    /** False = sursa nu raportează elementul. */
    val present: Boolean,
)

/** Starea publicării către o destinație din profil. */
data class DestinationRoute(
    val destination: String,
    val label: String,
    val status: PublishStatus?,
    /** Ce s-ar întâmpla la „Publică acum” sau de ce nu se publică. */
    val note: String,
    val attemptAt: Long?,
    val sentProgress: Float?,
    val readBackProgress: Float?,
)

/** Ce poziție primește fiecare player la deschidere. */
data class PlayerRoute(val player: String, val note: String, val getsPosition: Boolean)

data class RouteItem(
    val itemKey: String,
    val ref: TrackedRef,
    val positions: List<SourcePosition>,
    val winner: String?,
    val destinations: List<DestinationRoute>,
    val players: List<PlayerRoute>,
    val lastUpdate: Long,
    /** E cazul „Publică acum”: mod ≠ doar vizualizare și măcar o destinație ar primi poziția. */
    val canPublish: Boolean,
)

/**
 * Construiește ecranul „Diagnostic rute” (pur): pentru fiecare film / episod început, poziția
 * din fiecare sursă (Trakt, SIMKL, local), câștigătoarea reconcilierii, starea publicării pe
 * destinațiile profilului activ și ce poziție vede fiecare player.
 */
object RouteDiagnostics {
    const val TRAKT = "trakt"

    fun build(
        resume: List<ReconciledResume>,
        allResume: List<ResumePointEntity>,
        records: List<PublishRecordEntity>,
        profile: PublishProfile,
        /** Trackerele care pot primi progres, cu numele afișat. */
        trackers: Map<String, String>,
        connected: Set<String>,
        now: Long,
    ): List<RouteItem> {
        val keys = KeyResolver(allResume.map { it.ref } + records.map { it.ref })
        val localByKey = allResume.filter { it.source == LOCAL_SOURCE }.associateBy { keys.itemKey(it.ref) }
        val recordsByKey = records.groupBy { it.itemKey }
        val sources = listOf(LOCAL_SOURCE) + trackers.keys

        val items = resume.map { r ->
            val local = localByKey[r.itemKey]
            val positions = sources.map { s ->
                val e = r.evidences.filter { it.source == s }.maxByOrNull { it.at }
                SourcePosition(
                    source = s,
                    label = if (s == LOCAL_SOURCE) "Local" else trackers[s] ?: s,
                    progress = e?.progress,
                    positionMs = if (s == LOCAL_SOURCE) local?.positionMs else null,
                    updatedAt = e?.at,
                    isWinner = r.winner.source == s,
                    echo = e?.echo == true,
                    present = e != null,
                )
            }
            val candidate = PublishPlanner.candidate(r, local?.positionMs)
            val recs = recordsByKey[r.itemKey].orEmpty().associateBy { it.destination }
            val dests = profile.destinations.filter { it in trackers }.sorted().map { d ->
                val rec = recs[d]
                val note = when {
                    profile.mode == PublishMode.VIEW_ONLY -> "profilul e „Doar vizualizare”"
                    d !in connected -> "neconectat"
                    candidate == null -> "poziția e un ecou al unei publicări proprii"
                    else -> when (val dec = PublishPlanner.decide(candidate, d, r.evidences.filter { it.source == d }.maxByOrNull { it.at }, rec, now, manual = true)) {
                        PublishDecision.Publish -> if (profile.mode == PublishMode.AUTOMATIC) "se trimite la următoarea sincronizare" else "gata de publicat"
                        is PublishDecision.Skip -> dec.reason
                    }
                }
                DestinationRoute(d, trackers[d] ?: d, PublishStatus.of(rec?.status), note, rec?.attemptAt, rec?.progress, rec?.readBackProgress)
            }
            val canPublish = profile.mode != PublishMode.VIEW_ONLY && candidate != null && dests.any { d ->
                d.destination in connected &&
                    PublishPlanner.decide(candidate, d.destination, r.evidences.filter { it.source == d.destination }.maxByOrNull { it.at }, recs[d.destination], now, manual = true) == PublishDecision.Publish
            }
            RouteItem(
                itemKey = r.itemKey,
                ref = r.winner.ref,
                positions = positions,
                winner = r.winner.source,
                destinations = dests,
                players = players(r, positions),
                lastUpdate = maxOf(r.updatedAt, recs.values.maxOfOrNull { it.attemptAt } ?: 0L),
                canPublish = canPublish,
            )
        }

        // Elemente publicate care între timp au fost terminate (nu mai sunt „în curs”): doar istoricul publicării.
        val shown = items.map { it.itemKey }.toSet()
        val orphans = recordsByKey.filterKeys { it !in shown }.map { (key, recs) ->
            val newest = recs.maxBy { it.attemptAt }
            RouteItem(
                itemKey = key,
                ref = newest.ref,
                positions = emptyList(),
                winner = null,
                destinations = recs.sortedBy { it.destination }.map { rec ->
                    DestinationRoute(
                        rec.destination,
                        trackers[rec.destination] ?: rec.destination,
                        PublishStatus.of(rec.status),
                        "nu mai e în curs (terminat sau șters)",
                        rec.attemptAt,
                        rec.progress,
                        rec.readBackProgress,
                    )
                },
                players = emptyList(),
                lastUpdate = newest.attemptAt,
                canPublish = false,
            )
        }
        return (items + orphans).sortedByDescending { it.lastUpdate }
    }

    /** Ce vede fiecare player. Nuvio citește punctele de reluare din Trakt (sync/playback). */
    fun players(r: ReconciledResume, positions: List<SourcePosition>): List<PlayerRoute> {
        val winner = positions.firstOrNull { it.isWinner }
        val trakt = positions.firstOrNull { it.source == TRAKT }
        val nuvio = when {
            trakt == null || !trakt.present -> PlayerRoute("Nuvio", "nu vede poziția: nu e în Trakt", false)
            trakt.progress == null -> PlayerRoute("Nuvio", "Trakt nu are poziția (necunoscută)", false)
            winner != null && !PublishPlanner.same(trakt.progress, winner.progress) ->
                PlayerRoute("Nuvio", "citește Trakt: ${pct(trakt.progress)}, mai vechi decât ${pct(winner.progress)} (${winner.label})", true)
            else -> PlayerRoute("Nuvio", "citește Trakt: ${pct(trakt.progress)}", true)
        }
        return listOf(
            PlayerRoute(
                "Alege unde vezi",
                "arată ${pct(r.progress)} din ${winner?.label ?: r.winner.source} (cea mai nouă poziție)",
                r.progress != null,
            ),
            nuvio,
            PlayerRoute("Stremio", "își folosește propriul progres; reluare manuală", false),
            PlayerRoute("DebridStream", "nu știm dacă citește Trakt; reluare manuală", false),
            PlayerRoute("Netflix, Prime Video, Disney+, HBO Max", "progres propriu în cont; nu primesc poziția", false),
        )
    }

    fun pct(p: Float?): String = p?.let { "${(it * 100).toInt()}%" } ?: "poziție necunoscută"
}
