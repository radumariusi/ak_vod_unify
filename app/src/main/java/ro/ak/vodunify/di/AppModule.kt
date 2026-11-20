package ro.ak.vodunify.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import ro.ak.vodunify.core.db.VodDatabase
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): VodDatabase =
        Room.databaseBuilder(context, VodDatabase::class.java, "vod.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun mediaDao(db: VodDatabase) = db.mediaDao()
    @Provides fun watchEventDao(db: VodDatabase) = db.watchEventDao()
    @Provides fun resumePointDao(db: VodDatabase) = db.resumePointDao()
}
