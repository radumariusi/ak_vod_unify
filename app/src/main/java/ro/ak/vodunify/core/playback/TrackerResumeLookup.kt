package ro.ak.vodunify.core.playback

import kotlinx.coroutines.flow.first
import ro.ak.vodunify.core.db.TrackingDao
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pozițiile de reluare din trackerele conectate (Trakt acum, SIMKL/WeTrakr în etapa 5),
 * citite din tabela locală `resume_points`. Trackerele dau doar procentul, deci poziția e
 * estimată din durată; în timpul unei sincronizări e „În așteptare”.
 */
@Singleton
class TrackerResumeLookup @Inject constructor(
    private val dao: TrackingDao,
    private val tracking: TrackingRepository,
) : ResumeLookup {

    override suspend fun current(request: PlaybackRequest): ResumeInfo {
        if (request.type == PlaybackRequest.Type.SHOW) return ResumeInfo.Manual
        val titleKey = if (request.type == PlaybackRequest.Type.MOVIE) "movie:tmdb:${request.tmdbId}" else "tv:tmdb:${request.tmdbId}"
        val point = dao.resumeFor(titleKey).first()
            .filter { request.type != PlaybackRequest.Type.EPISODE || (it.ref.season == request.season && it.ref.episode == request.episode) }
            .maxByOrNull { it.updatedAt }
            ?: return ResumeInfo.Manual
        return ResumeEstimator.fromProgress(
            progress = point.progress,
            runtimeMinutes = request.runtimeMinutes,
            source = point.source,
            pendingSync = tracking.status.value is SyncStatus.Running,
        )
    }

    override suspend fun sync(request: PlaybackRequest): ResumeInfo {
        tracking.sync()
        return current(request)
    }
}
