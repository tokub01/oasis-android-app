package de.velwebsolutions.oasis.chat.di

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.goterl.lazysodium.SodiumAndroid
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velwebsolutions.oasis.chat.core.AndroidKeystoreBiometricKeys
import de.velwebsolutions.oasis.chat.core.AndroidSodium
import de.velwebsolutions.oasis.chat.core.BiometricPrompter
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatPreferences
import de.velwebsolutions.oasis.chat.core.ChatVault
import de.velwebsolutions.oasis.chat.core.DataStoreChatPreferences
import de.velwebsolutions.oasis.chat.core.DeviceSecretStore
import de.velwebsolutions.oasis.chat.core.KeystoreDeviceSecretStore
import de.velwebsolutions.oasis.chat.data.ChatRepository
import de.velwebsolutions.oasis.chat.data.DefaultChatRepository
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.di.AppScope
import de.velwebsolutions.oasis.core.network.ApiClient
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/** The chat's small on-device store (biometric copy, "Nicht jetzt"). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ChatStore

private val Context.chatDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat")

/** The chat's shared objects: one vault and one crypto core per app process. */
@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
    /** The crypto core on lazysodium-android. */
    @Provides
    @Singleton
    fun crypto(): ChatCrypto = ChatCrypto(AndroidSodium(SodiumAndroid()))

    /** The chat API over the app's client. */
    @Provides
    @Singleton
    fun repository(client: ApiClient): ChatRepository = DefaultChatRepository(client)

    /** Locks on sign-out (also a revoked token) and when the app goes to the background, like iOS and the web. */
    @Provides
    @Singleton
    fun vault(@AppScope scope: CoroutineScope, session: Session): ChatVault = ChatVault(scope).also { vault ->
        session.addOnSignedOut { Handler(Looper.getMainLooper()).post { vault.lock(announce = false) } }
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) = vault.lockForBackground()
                }
            )
        }
    }

    /** The chat's DataStore. */
    @Provides
    @Singleton
    @ChatStore
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.chatDataStore

    /** The biometric prompt of the visible activity. */
    @Provides
    @Singleton
    fun prompter(): BiometricPrompter = BiometricPrompter()

    /** The biometric copy's secret in the Android Keystore. */
    @Provides
    @Singleton
    fun secrets(
        @ApplicationContext context: Context,
        prompter: BiometricPrompter,
        @ChatStore dataStore: DataStore<Preferences>
    ): DeviceSecretStore = KeystoreDeviceSecretStore(AndroidKeystoreBiometricKeys(context), prompter, dataStore)

    /** "Nicht jetzt" and other small choices. */
    @Provides
    @Singleton
    fun preferences(@ChatStore dataStore: DataStore<Preferences>): ChatPreferences = DataStoreChatPreferences(dataStore)

    /** The context of the signed-in member; chat screens are only shown while someone is signed in. */
    @Provides
    fun context(
        repository: ChatRepository,
        vault: ChatVault,
        crypto: ChatCrypto,
        secrets: DeviceSecretStore,
        preferences: ChatPreferences,
        session: Session
    ): ChatContext = ChatContext(
        repository,
        vault,
        crypto,
        secrets,
        preferences,
        checkNotNull(session.me) {
            "Chat screens need a signed-in member."
        }
    )
}
