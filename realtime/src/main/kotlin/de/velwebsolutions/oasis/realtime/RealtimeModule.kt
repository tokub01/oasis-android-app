package de.velwebsolutions.oasis.realtime

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.velwebsolutions.oasis.core.realtime.NoRealtime
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import javax.inject.Singleton

/** The realtime service of the app. */
@Module
@InstallIn(SingletonComponent::class)
object RealtimeModule {
    @Provides
    @Singleton
    fun realtime(): RealtimeService = NoRealtime()
}
