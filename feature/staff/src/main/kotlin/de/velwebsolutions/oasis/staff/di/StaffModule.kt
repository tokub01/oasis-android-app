package de.velwebsolutions.oasis.staff.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velwebsolutions.oasis.staff.data.DefaultStaffRepository
import de.velwebsolutions.oasis.staff.data.StaffFiles
import de.velwebsolutions.oasis.staff.data.StaffRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StaffModule {
    @Binds
    abstract fun repository(repository: DefaultStaffRepository): StaffRepository

    companion object {
        @Provides
        @Singleton
        fun files(@ApplicationContext context: Context): StaffFiles = StaffFiles(context.cacheDir)
    }
}
