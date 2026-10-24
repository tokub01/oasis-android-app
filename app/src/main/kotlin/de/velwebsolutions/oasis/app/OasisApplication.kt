package de.velwebsolutions.oasis.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import de.velwebsolutions.oasis.core.network.ApiClient
import javax.inject.Inject

/** The app: Hilt's graph, and the image loader that loads members-only images with the API client's headers. */
@HiltAndroidApp
class OasisApplication : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var apiClient: ApiClient

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { apiClient.okHttp })) }
        .build()
}
