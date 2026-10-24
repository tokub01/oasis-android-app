package de.velwebsolutions.oasis.core.di

import android.content.Context
import de.velwebsolutions.oasis.core.auth.AuthApi
import de.velwebsolutions.oasis.core.auth.KeystoreTokenStore
import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.TokenStore
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiConfig
import de.velwebsolutions.oasis.core.network.CommonApi
import de.velwebsolutions.oasis.core.network.RemoteCommonApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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
    fun tokenStore(@ApplicationContext context: Context): TokenStore = KeystoreTokenStore(context)

    @Provides
    @Singleton
    fun session(tokenStore: TokenStore, deviceName: DeviceName, @AppScope scope: CoroutineScope): Session =
        Session(tokenStore, deviceName.value, scope)

    @Provides
    @Singleton
    fun apiClient(config: ApiConfig, session: Session): ApiClient {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        return ApiClient(config, session, okHttp).also { session.auth = RemoteAuthApi(it) }
    }

    @Provides
    @Singleton
    fun authApi(
        @Suppress("UNUSED_PARAMETER") client: ApiClient,
        session: Session,
    ): AuthApi = session.auth // set while the client is created

    @Provides
    @Singleton
    fun commonApi(client: ApiClient): CommonApi = RemoteCommonApi(client)
}
