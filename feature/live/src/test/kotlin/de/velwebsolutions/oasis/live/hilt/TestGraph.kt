package de.velwebsolutions.oasis.live.hilt

import androidx.activity.ComponentActivity
import dagger.Module
import dagger.Provides
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import de.velwebsolutions.oasis.core.auth.AuthApi
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.di.AppScope
import de.velwebsolutions.oasis.core.di.CoreModule
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.CommonApi
import de.velwebsolutions.oasis.core.network.RemoteCommonApi
import de.velwebsolutions.oasis.core.realtime.NoRealtime
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import de.velwebsolutions.oasis.testing.StubTransport
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app's graph for the module's Hilt tests: the stubbed API instead of a server, no realtime. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [CoreModule::class])
object TestCoreModule {
    @Provides
    @Singleton
    fun transport(): StubTransport = StubTransport()

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Provides
    @Singleton
    fun session(@AppScope scope: CoroutineScope): Session = Session(InMemoryTokenStore("token"), "Test", scope)

    @Provides
    @Singleton
    fun apiClient(transport: StubTransport, session: Session): ApiClient = transport.client.also { session.auth = RemoteAuthApi(it) }

    @Provides
    @Singleton
    fun authApi(@Suppress("UNUSED_PARAMETER") client: ApiClient, session: Session): AuthApi = session.auth

    @Provides
    @Singleton
    fun commonApi(client: ApiClient): CommonApi = RemoteCommonApi(client)

    @Provides
    @Singleton
    fun realtime(): RealtimeService = NoRealtime()
}

/** An activity of the test graph, so `hiltViewModel()` works in the screens. */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
