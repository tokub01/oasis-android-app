package de.velwebsolutions.oasis.account.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.account.data.DefaultAccountRepository
import de.velwebsolutions.oasis.account.ui.releases.PreferencesReleaseNotesStore
import de.velwebsolutions.oasis.account.ui.releases.ReleaseNotesStore
import javax.inject.Singleton

/** The account area's repository. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AccountModule {
    @Binds
    @Singleton
    abstract fun repository(repository: DefaultAccountRepository): AccountRepository
}

/** The version whose notes this device has seen (Was ist neu). */
@Module
@InstallIn(SingletonComponent::class)
object AccountStoreModule {
    @Provides
    @Singleton
    fun releaseNotes(@ApplicationContext context: Context): ReleaseNotesStore = PreferencesReleaseNotesStore(context)
}
