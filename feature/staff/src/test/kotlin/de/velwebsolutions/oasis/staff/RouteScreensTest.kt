package de.velwebsolutions.oasis.staff

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.staff.data.StaffFiles
import de.velwebsolutions.oasis.staff.ui.admin.AdminDashboardViewModel
import de.velwebsolutions.oasis.staff.ui.admin.AdminPaymentsViewModel
import de.velwebsolutions.oasis.staff.ui.admin.AdminVotesViewModel
import de.velwebsolutions.oasis.staff.ui.admin.DataBreachesViewModel
import de.velwebsolutions.oasis.staff.ui.admin.SystemViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.CaseDetailViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.ComplaintReviewViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.ModerationOverviewViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.RolesViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.SuspensionsViewModel
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * An activity that answers Hilt's lookup for the view-model factory without a Hilt component: the view models are
 * already in the store, so the factory Hilt builds from it is never asked to create one.
 */
class HiltlessActivity : ComponentActivity(), GeneratedComponentManager<Any> {
    override fun generatedComponent(): Any {
        val entryPoint = Class.forName("dagger.hilt.android.internal.lifecycle.HiltViewModelFactory\$ActivityCreatorEntryPoint")
        return Proxy.newProxyInstance(entryPoint.classLoader, arrayOf(entryPoint, GeneratedComponent::class.java)) { _, method, _ ->
            if (method.name == "getViewModelKeys") emptyMap<Class<*>, Boolean>() else null
        }
    }
}

/** The module's entry point picks the screen for each route (view models put into the store beforehand). */
@RunWith(RobolectricTestRunner::class)
class RouteScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeStaffRepository()
    private val activity = Robolectric.buildActivity(HiltlessActivity::class.java).create().get()
    private val files = StaffFiles(activity.cacheDir)
    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = store
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = when (modelClass) {
            ModerationOverviewViewModel::class -> ModerationOverviewViewModel(repository)
            CaseDetailViewModel::class -> CaseDetailViewModel(2, repository, files)
            ComplaintReviewViewModel::class -> ComplaintReviewViewModel(1, repository)
            RolesViewModel::class -> RolesViewModel(repository)
            SuspensionsViewModel::class -> SuspensionsViewModel(repository)
            AdminDashboardViewModel::class -> AdminDashboardViewModel(repository)
            AdminVotesViewModel::class -> AdminVotesViewModel(repository)
            AdminPaymentsViewModel::class -> AdminPaymentsViewModel(repository)
            DataBreachesViewModel::class -> DataBreachesViewModel(repository)
            else -> SystemViewModel(repository)
        } as T
    }

    @Test
    fun `every staff route shows its screen`() {
        ViewModelProvider.create(store, factory).apply {
            get(ModerationOverviewViewModel::class)
            get("case-2", CaseDetailViewModel::class)
            get("complaint-1", ComplaintReviewViewModel::class)
            get(RolesViewModel::class)
            get(SuspensionsViewModel::class)
            get(AdminDashboardViewModel::class)
            get(AdminVotesViewModel::class)
            get(AdminPaymentsViewModel::class)
            get(DataBreachesViewModel::class)
            get(SystemViewModel::class)
        }
        var route: StaffRoute by mutableStateOf(StaffRoute.Moderation)
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(
                    LocalNavigator provides Navigator(),
                    LocalViewModelStoreOwner provides owner,
                    LocalContext provides activity
                ) { StaffScreen(route) }
            }
        }
        compose.onNodeWithText("Warteschlange").assertExists()
        route = StaffRoute.ModerationCase(2)
        compose.onNodeWithTag("case.status").assertExists()
        route = StaffRoute.ComplaintReview(1)
        compose.onNodeWithText("Begründung des Widerspruchs").assertExists()
        route = StaffRoute.Roles
        compose.onNodeWithText("Moderation und Admin brauchen", substring = true).assertExists()
        route = StaffRoute.Suspensions
        compose.onNodeWithText("Konten, die gerade gesperrt sind", substring = true).assertExists()
        route = StaffRoute.AdminDashboard
        compose.onNodeWithTag("revenue.amount").assertExists()
        route = StaffRoute.AdminVotes
        compose.onNodeWithText("Neue Abstimmung").assertExists()
        route = StaffRoute.AdminPayments
        compose.onNodeWithText("Belege in Oasis", substring = true).assertExists()
        route = StaffRoute.AdminBreaches
        compose.onNodeWithText("Neue Datenpanne erfassen").assertExists()
        route = StaffRoute.AdminSystem
        compose.onNodeWithText("Systemzustand").assertExists()
    }
}
