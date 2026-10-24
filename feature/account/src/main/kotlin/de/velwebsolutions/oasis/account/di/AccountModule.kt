package de.velwebsolutions.oasis.account.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.account.data.DefaultAccountRepository
import javax.inject.Singleton

/** The account area's repository. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AccountModule {
    @Binds
    @Singleton
    abstract fun repository(repository: DefaultAccountRepository): AccountRepository
}
