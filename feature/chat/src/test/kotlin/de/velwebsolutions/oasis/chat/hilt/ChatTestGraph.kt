package de.velwebsolutions.oasis.chat.hilt

import androidx.fragment.app.FragmentActivity
import dagger.Module
import dagger.Provides
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import de.velwebsolutions.oasis.chat.ChatTestSupport
import de.velwebsolutions.oasis.chat.core.BiometricPrompter
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.di.ChatModule
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.di.CoreModule
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** What the test graph hands out; each test sets the member and the fake server before it shows a screen. */
object ChatTestGraph {
    lateinit var support: ChatTestSupport
    lateinit var context: ChatContext
    var realtime: RealtimeService = de.velwebsolutions.oasis.chat.FakeRealtime()
    val session = Session(InMemoryTokenStore("t"), "Pixel Test", CoroutineScope(Dispatchers.Main))
    val prompter = BiometricPrompter()
}

/** The chat's and core's objects for the screen tests: the fake server's member instead of the device and the API. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ChatModule::class, CoreModule::class])
object ChatTestModule {
    @Provides
    @Singleton
    fun session(): Session = ChatTestGraph.session

    @Provides
    @Singleton
    fun prompter(): BiometricPrompter = ChatTestGraph.prompter

    @Provides
    fun realtime(): RealtimeService = ChatTestGraph.realtime

    @Provides
    fun context(): ChatContext = ChatTestGraph.context
}

/** The activity the screens are shown in: a Hilt entry point, and a FragmentActivity like the app's for the prompt. */
@AndroidEntryPoint(FragmentActivity::class)
class ChatTestActivity : Hilt_ChatTestActivity()
