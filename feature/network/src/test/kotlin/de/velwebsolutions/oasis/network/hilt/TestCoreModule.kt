package de.velwebsolutions.oasis.network.hilt

import androidx.activity.ComponentActivity
import dagger.Module
import dagger.Provides
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.di.AppScope
import de.velwebsolutions.oasis.core.di.CoreModule
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.CommonApi
import de.velwebsolutions.oasis.core.network.RemoteCommonApi
import de.velwebsolutions.oasis.testing.StubTransport
import de.velwebsolutions.oasis.testing.testTokenStore
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The API of Hilt tests: canned answers instead of a server (unknown requests fail like an unreachable server). */
object TestApi {
    val transport = StubTransport()
}

/** `:core`'s objects for Hilt tests: the stubbed client, a signed-out session in memory. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [CoreModule::class])
object TestCoreModule {
    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun session(@AppScope scope: CoroutineScope): Session = Session(testTokenStore(null), "Pixel Test", scope)

    @Provides
    @Singleton
    fun apiClient(): ApiClient = TestApi.transport.client

    @Provides
    @Singleton
    fun commonApi(client: ApiClient): CommonApi = RemoteCommonApi(client)
}

/** An activity with Hilt's view-model factory, for screens that create their view models themselves. */
@AndroidEntryPoint(ComponentActivity::class)
class NetworkTestActivity : Hilt_NetworkTestActivity()
