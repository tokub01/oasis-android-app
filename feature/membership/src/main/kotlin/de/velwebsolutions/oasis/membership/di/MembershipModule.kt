package de.velwebsolutions.oasis.membership.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velwebsolutions.oasis.membership.billing.BillingGateway
import de.velwebsolutions.oasis.membership.billing.PlayBillingGateway
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseVerifier
import de.velwebsolutions.oasis.membership.billing.ServerPendingPlayPurchaseVerifier
import de.velwebsolutions.oasis.membership.data.DefaultMembershipRepository
import de.velwebsolutions.oasis.membership.data.MembershipFiles
import de.velwebsolutions.oasis.membership.data.MembershipRepository
import javax.inject.Singleton

/** The membership's repository, Google Play Billing and the server check of Play purchases. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MembershipModule {
    @Binds
    abstract fun repository(repository: DefaultMembershipRepository): MembershipRepository

    @Binds
    abstract fun billing(gateway: PlayBillingGateway): BillingGateway

    /** Replace with the real verifier once the backend can check Google Play purchases (see the class). */
    @Binds
    abstract fun verifier(verifier: ServerPendingPlayPurchaseVerifier): PlayPurchaseVerifier

    companion object {
        @Provides
        @Singleton
        fun files(@ApplicationContext context: Context): MembershipFiles = MembershipFiles(context.cacheDir)
    }
}
