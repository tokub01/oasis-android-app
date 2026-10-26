package de.velwebsolutions.oasis.account.hilt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
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
    fun session(@AppScope scope: CoroutineScope, auth: dagger.Lazy<AuthApi>): Session =
        Session(InMemoryTokenStore("token"), "Test", scope) { auth.get() }

    @Provides
    @Singleton
    fun apiClient(transport: StubTransport): ApiClient = transport.client

    @Provides
    @Singleton
    fun authApi(client: ApiClient): AuthApi = RemoteAuthApi(client)

    @Provides
    @Singleton
    fun commonApi(client: ApiClient): CommonApi = RemoteCommonApi(client)

    @Provides
    @Singleton
    fun realtime(): RealtimeService = NoRealtime()
}

/**
 * An activity of the test graph, so `hiltViewModel()` works in the screens. It shows [content] again when it is
 * recreated, so saved state can be restored like in the app.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { content() }
    }

    companion object {
        /** What the activity shows. */
        var content: @Composable () -> Unit = {}
    }
}
