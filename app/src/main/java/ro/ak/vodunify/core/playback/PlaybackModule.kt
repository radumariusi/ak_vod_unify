package ro.ak.vodunify.core.playback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    /** Înlocuiește cu implementarea bazată pe trackere când Trakt/SIMKL au puncte de reluare. */
    @Binds abstract fun resumeLookup(impl: NoResumeLookup): ResumeLookup
}
