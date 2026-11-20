package ro.ak.vodunify.core.playback

import ro.ak.vodunify.core.publish.LOCAL_SOURCE
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pozițiile de reluare din trackerele conectate (Trakt, SIMKL), reconciliate între surse. Trackerele dau doar procentul, deci poziția e
 * estimată din durată; în timpul unei sincronizări e „În așteptare”.
 */
@Singleton
class TrackerResumeLookup @Inject constructor(
    private val tracking: TrackingRepository,
) : ResumeLookup {

    override suspend fun current(request: PlaybackRequest): ResumeInfo {
        if (request.type == PlaybackRequest.Type.SHOW) return ResumeInfo.Manual
        val titleKey = if (request.type == PlaybackRequest.Type.MOVIE) "movie:tmdb:${request.tmdbId}" else "tv:tmdb:${request.tmdbId}"
        // Punctul reconciliat: cel mai recent din toate sursele, fără cele terminate între timp.
        val point = tracking.reconcileNow().resume
            .filter { it.titleKey == titleKey }
            .map { it.winner }
            .filter { request.type != PlaybackRequest.Type.EPISODE || (it.ref.season == request.season && it.ref.episode == request.episode) }
            .maxByOrNull { it.updatedAt }
            ?: return ResumeInfo.Manual
        return ResumeEstimator.fromProgress(
            progress = point.progress,
            runtimeMinutes = request.runtimeMinutes,
            source = point.source,
            pendingSync = tracking.status.value is SyncStatus.Running,
            // Poziția setată de tine e exactă: nu mai e estimată din procent.
            exactPositionMs = point.positionMs?.takeIf { point.source == LOCAL_SOURCE },
            local = point.source == LOCAL_SOURCE,
        )
    }

    override suspend fun sync(request: PlaybackRequest): ResumeInfo {
        tracking.sync()
        return current(request)
    }
}
