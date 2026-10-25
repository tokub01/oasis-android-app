package de.velwebsolutions.oasis.account.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * View models that live only as long as [content] is on screen: the sign-in and registration forms (passwords!) are
 * cleared once the member is signed in, instead of staying in the activity's store. Hilt's factory of the parent owner
 * creates them.
 */
@Composable
fun ScopedViewModels(content: @Composable () -> Unit) {
    // The activity, or the parent screen's owner in a test.
    val parent = checkNotNull(LocalViewModelStoreOwner.current)
    val owner = remember(parent) { ScopedOwner(parent) }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

private class ScopedOwner(private val parent: ViewModelStoreOwner) :
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory ?: ViewModelProvider.NewInstanceFactory()

    override val defaultViewModelCreationExtras: CreationExtras
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty
}
