package de.velwebsolutions.oasis.network.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.velwebsolutions.oasis.network.data.DefaultNetworkRepository
import de.velwebsolutions.oasis.network.data.NetworkRepository
import javax.inject.Singleton

/** The network area's repository for the view models. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {
    @Binds
    @Singleton
    abstract fun networkRepository(repository: DefaultNetworkRepository): NetworkRepository
}
