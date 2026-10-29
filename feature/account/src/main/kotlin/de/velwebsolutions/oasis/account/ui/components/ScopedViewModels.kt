package de.velwebsolutions.oasis.account.ui.components

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID

/**
 * View models that live only as long as [content] is on screen: the sign-in and registration forms (passwords!) are
 * cleared once the member is signed in, instead of staying in the activity's store. A configuration change (rotation,
 * dark mode, font size, window size) recreates the activity but not the screen, so they stay then, like the activity's
 * own: their store is kept in the parent's store under a key saved with the screen. Hilt's factory of the parent owner
 * creates them.
 */
@Composable
fun ScopedViewModels(content: @Composable () -> Unit) {
    // The activity, or the parent screen's owner in a test.
    val parent = checkNotNull(LocalViewModelStoreOwner.current)
    val activity = checkNotNull(LocalActivity.current)
    val key = rememberSaveable { UUID.randomUUID().toString() }
    val stores = remember(parent) { ScopedStores.of(parent) }
    val owner = remember(parent, key) { ScopedOwner(parent, stores.store(key)) }
    DisposableEffect(owner) {
        onDispose {
            // A recreated activity shows the screen again under the same key; leaving it for good clears its view models.
            if (!activity.isChangingConfigurations) stores.clear(key)
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

/** The stores of the [ScopedViewModels] below one owner, kept in that owner's store so they outlive a recreated activity. */
private class ScopedStores : ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun store(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    fun clear(key: String) {
        store(key).clear()
        stores.remove(key)
    }

    override fun onCleared() {
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
    }

    companion object {
        private val factory = viewModelFactory { initializer { ScopedStores() } }

        fun of(owner: ViewModelStoreOwner): ScopedStores = ViewModelProvider(owner.viewModelStore, factory).get(ScopedStores::class.java)
    }
}

private class ScopedOwner(private val parent: ViewModelStoreOwner, override val viewModelStore: ViewModelStore) :
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory ?: ViewModelProvider.NewInstanceFactory()

    override val defaultViewModelCreationExtras: CreationExtras
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty
}
