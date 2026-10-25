package de.velwebsolutions.oasis.membership

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.membership.billing.PlayDelivery
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.data.MembershipFiles
import de.velwebsolutions.oasis.membership.ui.CancelViewModel
import de.velwebsolutions.oasis.membership.ui.InvoiceViewModel
import de.velwebsolutions.oasis.membership.ui.OverviewViewModel
import de.velwebsolutions.oasis.membership.ui.PlayCheckoutViewModel
import de.velwebsolutions.oasis.membership.ui.PortalViewModel
import de.velwebsolutions.oasis.membership.ui.WithdrawViewModel
import kotlin.reflect.KClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An activity that answers Hilt's lookup for the view-model factory without a Hilt component: the view models are
 * already in the store, so the factory Hilt builds from it is never asked to create one.
 */
class HiltlessActivity : androidx.activity.ComponentActivity(), dagger.hilt.internal.GeneratedComponentManager<Any> {
    override fun generatedComponent(): Any {
        val entryPoint = Class.forName("dagger.hilt.android.internal.lifecycle.HiltViewModelFactory\$ActivityCreatorEntryPoint")
        return java.lang.reflect.Proxy.newProxyInstance(
            entryPoint.classLoader,
            arrayOf(entryPoint, dagger.hilt.internal.GeneratedComponent::class.java)
        ) {
                _,
                method,
                _
            ->
            if (method.name == "getViewModelKeys") emptyMap<Class<*>, Boolean>() else null
        }
    }
}

/**
 * The module's entry point picks the screen for each route. Hilt is not running in unit tests, so the view models the
 * screens ask Hilt for are put into the view-model store beforehand, under the keys the screens use.
 */
@RunWith(RobolectricTestRunner::class)
class RouteScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val repository = FakeMembershipRepository()
    private val files = MembershipFiles(context.cacheDir)
    private val events = MembershipEvents()
    private val gateway = FakeBillingGateway()
    private val verifier = FakeVerifier()

    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = store
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = when (modelClass) {
            OverviewViewModel::class -> OverviewViewModel(repository, events)
            PortalViewModel::class -> PortalViewModel(repository, files)
            InvoiceViewModel::class -> InvoiceViewModel(7, repository, files)
            CancelViewModel::class -> CancelViewModel(repository, files, events, Fixtures.session())
            WithdrawViewModel::class -> WithdrawViewModel(repository, files, events, Fixtures.session())
            else -> PlayCheckoutViewModel(repository, gateway, PlayDelivery(gateway, verifier), verifier, events, Fixtures.session())
        } as T
    }

    private val activity = org.robolectric.Robolectric.buildActivity(HiltlessActivity::class.java).create().get()

    private fun prepare() {
        val provider = ViewModelProvider.create(store, factory)
        provider[OverviewViewModel::class]
        provider[PortalViewModel::class]
        provider["invoice-7", InvoiceViewModel::class]
        provider[CancelViewModel::class]
        provider[WithdrawViewModel::class]
        provider[PlayCheckoutViewModel::class]
    }

    @Test
    fun `every membership route shows its screen`() {
        prepare()
        var route: MembershipRoute by mutableStateOf(MembershipRoute.Overview)
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(
                    LocalNavigator provides Navigator().apply { selectedTab = MainTab.Profile },
                    LocalViewModelStoreOwner provides owner,
                    androidx.compose.ui.platform.LocalContext provides activity
                ) { MembershipScreen(route) }
            }
        }
        compose.onNodeWithTag("membership.statusCard").assertExists()
        route = MembershipRoute.Portal
        compose.onNodeWithText("Bezahlte Beiträge").assertExists()
        route = MembershipRoute.Invoice(7)
        compose.onNodeWithTag("invoice.view").assertExists()
        route = MembershipRoute.Cancel
        compose.onNodeWithText("Bestätigung deiner Kündigung gemäß § 312k BGB.").assertExists()
        route = MembershipRoute.Withdraw
        compose.onNodeWithText("Gesetzliches Widerrufsrecht innerhalb von 14 Tagen (§ 355 BGB).").assertExists()
        route = MembershipRoute.Checkout
        compose.onNodeWithText("Bleib in der Oase.").assertExists()
    }
}
