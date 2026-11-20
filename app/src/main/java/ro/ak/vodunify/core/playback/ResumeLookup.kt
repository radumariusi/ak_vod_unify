package ro.ak.vodunify.core.playback

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sursa pozițiilor de reluare pentru „Alege unde vezi”.
 * Până la integrarea trackerelor (Trakt, apoi SIMKL/WeTrakr) folosim [NoResumeLookup],
 * care întoarce mereu „Reluare manuală”. Trackerele înlocuiesc legătura din PlaybackModule.
 */
interface ResumeLookup {
    /** Poziția cunoscută acum (din cache local), fără rețea. */
    suspend fun current(request: PlaybackRequest): ResumeInfo

    /** Cere trackerelor poziția proaspătă (rețea) și o întoarce. */
    suspend fun sync(request: PlaybackRequest): ResumeInfo = current(request)
}

@Singleton
class NoResumeLookup @Inject constructor() : ResumeLookup {
    override suspend fun current(request: PlaybackRequest): ResumeInfo = ResumeInfo.Manual
}

/**
 * Transformă ce dau trackerele în [ResumeInfo]. Trakt/SIMKL dau doar procentul,
 * deci poziția e estimată din durata titlului; o actualizare nepublicată încă = „În așteptare”.
 */
object ResumeEstimator {
    fun fromProgress(
        progress: Float?,
        runtimeMinutes: Int?,
        source: String?,
        pendingSync: Boolean = false,
        exactPositionMs: Long? = null,
    ): ResumeInfo {
        val duration = runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L }
        val position = exactPositionMs ?: if (progress != null && duration != null) (duration * progress.coerceIn(0f, 1f)).toLong() else null
        if (position == null || position <= 0) return ResumeInfo.Manual
        val state = when {
            pendingSync -> ResumeState.PENDING
            exactPositionMs != null -> ResumeState.SYNCED
            else -> ResumeState.ESTIMATED
        }
        return ResumeInfo(state, position, duration, source)
    }
}
