package de.velwebsolutions.oasis.core.di

import android.content.Context
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velwebsolutions.oasis.core.auth.AuthApi
import de.velwebsolutions.oasis.core.auth.KeystoreTokenStore
import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.auth.TokenStore
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiConfig
import de.velwebsolutions.oasis.core.network.CommonApi
import de.velwebsolutions.oasis.core.network.RemoteCommonApi
import de.velwebsolutions.oasis.core.onboarding.OnboardingCardStore
import de.velwebsolutions.oasis.core.onboarding.PreferencesOnboardingCardStore
import de.velwebsolutions.oasis.core.screentime.PreferencesScreenTimeStore
import de.velwebsolutions.oasis.core.screentime.ScreenTime
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** The device name sent with sign-ins and shown in Privatsphäre › Geräte. */
data class DeviceName(val value: String)

/** The app-wide coroutine scope (outlives screens: sign-out, push registration, live counters). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/** The shared objects of `:core`. The app module provides [ApiConfig] and [DeviceName]. */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun tokenStore(@ApplicationContext context: Context, config: ApiConfig): TokenStore =
        KeystoreTokenStore(context, origin = config.baseUrl.trimEnd('/'))

    @Provides
    @Singleton
    fun session(tokenStore: TokenStore, deviceName: DeviceName, @AppScope scope: CoroutineScope, auth: Lazy<AuthApi>): Session =
        Session(tokenStore, deviceName.value, scope) { auth.get() }

    /** „Später“ of the Quelle's card „Mach dein Tal lebendig“, on this device. */
    @Provides
    @Singleton
    fun onboardingCard(@ApplicationContext context: Context): OnboardingCardStore = PreferencesOnboardingCardStore(context)

    /** „Deine Zeit in Oasis“ of the signed-in account, on this device only. */
    @Provides
    @Singleton
    fun screenTime(@ApplicationContext context: Context, session: Session): ScreenTime =
        ScreenTime(PreferencesScreenTimeStore(context), userId = { (session.state.value as? SessionState.SignedIn)?.me?.id })

    @Provides
    @Singleton
    fun apiClient(config: ApiConfig, session: Session): ApiClient {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        return ApiClient(config, session, okHttp)
    }

    @Provides
    @Singleton
    fun authApi(client: ApiClient): AuthApi = RemoteAuthApi(client)

    @Provides
    @Singleton
    fun commonApi(client: ApiClient): CommonApi = RemoteCommonApi(client)
}
