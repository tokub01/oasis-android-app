package de.velwebsolutions.oasis.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import de.velwebsolutions.oasis.app.shell.AppHost
import de.velwebsolutions.oasis.core.network.ApiClient
import javax.inject.Inject

/** The app: Hilt's graph, and the image loader of [AppHost]. */
@HiltAndroidApp
class OasisApplication : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var apiClient: ApiClient

    override fun newImageLoader(context: PlatformContext): ImageLoader = AppHost.imageLoader(context, apiClient)
}
