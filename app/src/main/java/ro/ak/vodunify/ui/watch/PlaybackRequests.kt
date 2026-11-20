package ro.ak.vodunify.ui.watch

import ro.ak.vodunify.core.catalog.Episode
import ro.ak.vodunify.core.catalog.TitleDetails
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.playback.PlaybackRequest

/** Filmul sau serialul (pagina lui). */
fun TitleDetails.playbackRequest(): PlaybackRequest = PlaybackRequest(
    type = if (item.kind == TitleKind.MOVIE) PlaybackRequest.Type.MOVIE else PlaybackRequest.Type.SHOW,
    tmdbId = item.id,
    imdbId = imdbId,
    title = item.title,
    year = item.year,
    runtimeMinutes = runtimeMinutes,
)

/** Un episod anume din serial. */
fun TitleDetails.playbackRequest(episode: Episode): PlaybackRequest = playbackRequest().copy(
    type = PlaybackRequest.Type.EPISODE,
    season = episode.season,
    episode = episode.number,
    episodeTitle = episode.name,
    runtimeMinutes = episode.runtimeMinutes ?: runtimeMinutes,
)
