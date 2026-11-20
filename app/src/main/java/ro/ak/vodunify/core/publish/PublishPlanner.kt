package ro.ak.vodunify.core.publish

import ro.ak.vodunify.core.db.PublishRecordEntity
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackedRef
import ro.ak.vodunify.core.reconcile.Evidence
import ro.ak.vodunify.core.reconcile.KeyResolver
import ro.ak.vodunify.core.reconcile.ReconciledResume
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Poziția pe care vrem s-o publicăm, cu sursa ei. */
data class PublishCandidate(
    val itemKey: String,
    val ref: TrackedRef,
    /** 0–1; null = poziție necunoscută (nu se publică niciodată ca 0%). */
    val progress: Float?,
    val positionMs: Long?,
    val origin: String,
    val originAt: Long,
)

sealed interface PublishDecision {
    data object Publish : PublishDecision
    data class Skip(val reason: String) : PublishDecision
}

/**
 * Regulile publicării (pure, testabile):
 *
 * - se publică doar o poziție cunoscută, între 1% și 80%: lipsă ≠ 0%, iar peste 80% Trakt/SIMKL
 *   pot considera vizionarea terminată; „văzut” se marchează doar explicit, cu „Marchează văzut”;
 * - doar filme și episoade (nu seriale întregi);
 * - nu se scrie înapoi în sursa poziției și nu se suprascrie o poziție mai nouă din destinație;
 * - idempotent: aceeași poziție deja trimisă (sau deja prezentă în destinație) nu se retrimite;
 * - ecourile propriilor publicări nu devin surse noi (altfel poziția ar circula în buclă).
 */
object PublishPlanner {
    const val MIN_PROGRESS = 0.01f
    const val MAX_PROGRESS = 0.80f

    /** Diferența sub care două progrese sunt „aceeași poziție” (2%). */
    const val SAME_PROGRESS = 0.02f

    /** Automat: publicăm doar poziții din ultimele 14 zile. */
    val AUTO_MAX_AGE_MS: Long = TimeUnit.DAYS.toMillis(14)

    /** Automat: după o eroare reîncercăm cel mult o dată pe oră. */
    val RETRY_AFTER_MS: Long = TimeUnit.HOURS.toMillis(1)

    /** Automat: cel mult atâtea scrieri pe rulare (limitele API). */
    const val AUTO_MAX_PER_RUN = 10

    fun same(a: Float?, b: Float?): Boolean = a != null && b != null && abs(a - b) <= SAME_PROGRESS

    /**
     * Poziția de publicat pentru un element reconciliat: câștigătoarea, iar dacă ea e ecoul
     * unei publicări proprii, dovada originală (aceeași poziție, dintr-o sursă care nu e ecou).
     */
    fun candidate(r: ReconciledResume, localPositionMs: Long? = null): PublishCandidate? {
        val w = r.winner
        val winnerEvidence = r.evidences.firstOrNull { it.source == w.source && it.at == w.updatedAt }
        val origin: Evidence = if (winnerEvidence == null || !winnerEvidence.echo) {
            winnerEvidence ?: Evidence(w.source, w.updatedAt, w.progress)
        } else {
            r.evidences.filter { !it.echo && same(it.progress, w.progress) }.maxByOrNull { it.at } ?: return null
        }
        return PublishCandidate(
            itemKey = r.itemKey,
            ref = w.ref,
            progress = origin.progress,
            positionMs = localPositionMs?.takeIf { origin.source == LOCAL_SOURCE },
            origin = origin.source,
            originAt = origin.at,
        )
    }

    /**
     * Decide dacă [candidate] se trimite în [destination].
     * [destPoint] = ce știe acum destinația despre element; [record] = ultima publicare acolo;
     * [manual] = cerută explicit de tine (permite retrimiterea unei publicări neconfirmate/eșuate).
     */
    fun decide(
        candidate: PublishCandidate,
        destination: String,
        destPoint: Evidence?,
        record: PublishRecordEntity?,
        now: Long,
        manual: Boolean,
    ): PublishDecision {
        val p = candidate.progress ?: return PublishDecision.Skip("poziție necunoscută (nu trimitem 0%)")
        if (candidate.ref.type == "SHOW") return PublishDecision.Skip("alege un film sau un episod")
        if (p < MIN_PROGRESS) return PublishDecision.Skip("sub 1%, nimic de reluat")
        if (p >= MAX_PROGRESS) {
            return PublishDecision.Skip("peste 80%: ar putea fi trecut ca văzut; folosește „Marchează văzut”")
        }
        if (destination == candidate.origin) return PublishDecision.Skip("e chiar sursa poziției")
        if (destPoint != null && same(destPoint.progress, p)) return PublishDecision.Skip("are deja această poziție")
        if (destPoint != null && destPoint.at > candidate.originAt) return PublishDecision.Skip("are o poziție mai nouă")
        if (record != null && same(record.progress, p) && record.originAt >= candidate.originAt) {
            when (PublishStatus.of(record.status)) {
                PublishStatus.PUBLISHED, PublishStatus.SENT -> return PublishDecision.Skip("deja trimis")
                PublishStatus.NOT_CONFIRMED -> if (!manual) return PublishDecision.Skip("trimis, se reverifică la sincronizare")
                PublishStatus.FAILED -> if (!manual && now - record.attemptAt < RETRY_AFTER_MS) {
                    return PublishDecision.Skip("eroare recentă, reîncerc peste o oră")
                }
                null -> Unit
            }
        }
        return PublishDecision.Publish
    }

    /** Pentru modul automat: doar poziții recente, care nu vin dintr-un ecou. */
    fun autoEligible(c: PublishCandidate, now: Long): Boolean = now - c.originAt <= AUTO_MAX_AGE_MS

    /** Găsește, în ce a citit înapoi destinația, punctul pentru același element (id-uri reconciliate). */
    fun matchReadBack(target: TrackedRef, points: List<ResumePointEntity>): ResumePointEntity? {
        val keys = KeyResolver(listOf(target) + points.map { it.ref })
        val key = keys.itemKey(target)
        return points.filter { keys.itemKey(it.ref) == key }.maxByOrNull { it.updatedAt }
    }

    /** Publicat = destinația arată, la citire, aceeași poziție pe care am trimis-o. */
    fun verify(sent: Float, readBack: ResumePointEntity?): PublishStatus =
        if (readBack != null && same(readBack.progress, sent)) PublishStatus.PUBLISHED else PublishStatus.NOT_CONFIRMED
}
