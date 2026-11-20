package ro.ak.vodunify.core.playback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    /** Pozițiile vin din trackerele conectate; fără tracker, „Reluare manuală”. */
    @Binds abstract fun resumeLookup(impl: TrackerResumeLookup): ResumeLookup
}
