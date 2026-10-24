package de.velwebsolutions.oasis.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velwebsolutions.oasis.core.di.DeviceName
import de.velwebsolutions.oasis.core.network.ApiConfig
import de.velwebsolutions.oasis.core.network.RealtimeConfig
import de.velwebsolutions.oasis.design.navigation.Navigator
import javax.inject.Singleton

/** Server, user agent and device name of this build (BuildConfig per build type: debug, staging, release). */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun apiConfig(): ApiConfig = ApiConfig(
        baseUrl = BuildConfig.OASIS_BASE_URL,
        userAgent = "OasisApp/${BuildConfig.VERSION_NAME} (Android; Android ${Build.VERSION.RELEASE})",
        gateKey = BuildConfig.OASIS_GATE_KEY.ifEmpty { null }
    )

    @Provides
    @Singleton
    fun realtimeConfig(): RealtimeConfig = RealtimeConfig(BuildConfig.REVERB_HOST_OVERRIDE.ifEmpty { null })

    /** "Pixel 9" or the name the member gave the phone, shown in Privatsphäre › Geräte. */
    @Provides
    @Singleton
    fun deviceName(@ApplicationContext context: Context): DeviceName = DeviceName(
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}"
    )

    @Provides
    @Singleton
    fun navigator(): Navigator = Navigator()
}
