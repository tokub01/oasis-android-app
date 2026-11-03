package de.velwebsolutions.oasis.network

import androidx.compose.runtime.Composable
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.network.ui.feeds.BookmarksScreen
import de.velwebsolutions.oasis.network.ui.feeds.DiscoverScreen
import de.velwebsolutions.oasis.network.ui.feeds.IdeasScreen
import de.velwebsolutions.oasis.network.ui.feeds.SearchScreen
import de.velwebsolutions.oasis.network.ui.feeds.SourceScreen
import de.velwebsolutions.oasis.network.ui.feeds.SwipeScreen
import de.velwebsolutions.oasis.network.ui.feeds.TagScreen
import de.velwebsolutions.oasis.network.ui.feeds.WeeklyLetterScreen
import de.velwebsolutions.oasis.network.ui.oases.CloseOasisScreen
import de.velwebsolutions.oasis.network.ui.oases.CollectPostScreen
import de.velwebsolutions.oasis.network.ui.oases.OasesScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisDetailScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisFormScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisMapScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisStatisticsScreen
import de.velwebsolutions.oasis.network.ui.posts.CarryScreen
import de.velwebsolutions.oasis.network.ui.posts.ComposeScreen
import de.velwebsolutions.oasis.network.ui.posts.EditPostScreen
import de.velwebsolutions.oasis.network.ui.posts.PostDetailScreen
import de.velwebsolutions.oasis.network.ui.profiles.EditProfileScreen
import de.velwebsolutions.oasis.network.ui.profiles.FriendsScreen
import de.velwebsolutions.oasis.network.ui.profiles.InvitationScreen
import de.velwebsolutions.oasis.network.ui.profiles.InvitationsScreen
import de.velwebsolutions.oasis.network.ui.profiles.ProfileScreen
import de.velwebsolutions.oasis.network.ui.profiles.StatisticsScreen
import de.velwebsolutions.oasis.network.ui.profiles.TimeCapsulesScreen
import de.velwebsolutions.oasis.network.ui.profiles.YearbookScreen

/** Entry point of the Network module: the screen for each [NetworkRoute] (mapped in the app). */
@Composable
fun NetworkScreen(route: NetworkRoute) {
    when (route) {
        NetworkRoute.Source -> SourceScreen()

        NetworkRoute.Discover -> DiscoverScreen()

        NetworkRoute.Swipe -> SwipeScreen()

        is NetworkRoute.Post -> PostDetailScreen(route.id)

        is NetworkRoute.EditPost -> EditPostScreen(route.id)

        is NetworkRoute.CarryPost -> CarryScreen(route.id)

        is NetworkRoute.Compose -> ComposeScreen(route.oasisSlug, route.replyTo)

        NetworkRoute.Oases -> OasesScreen()

        is NetworkRoute.Oasis -> OasisDetailScreen(route.slug, atMeetups = route.meetups, atCollection = route.collection)

        is NetworkRoute.CollectPost -> CollectPostScreen(route.postId, route.slug)

        is NetworkRoute.OasisMap -> OasisMapScreen(route.region)

        NetworkRoute.CreateOasis -> OasisFormScreen(slug = null)

        is NetworkRoute.EditOasis -> OasisFormScreen(route.slug)

        is NetworkRoute.CloseOasis -> CloseOasisScreen(route.slug)

        is NetworkRoute.OasisStatistics -> OasisStatisticsScreen(route.slug)

        is NetworkRoute.Profile -> ProfileScreen(route.handle)

        NetworkRoute.EditProfile -> EditProfileScreen()

        NetworkRoute.Friends -> FriendsScreen()

        NetworkRoute.Statistics -> StatisticsScreen()

        NetworkRoute.TimeCapsules -> TimeCapsulesScreen()

        NetworkRoute.CapsuleInvitations -> TimeCapsulesScreen(atInvitations = true)

        is NetworkRoute.Yearbook -> YearbookScreen(route.year)

        NetworkRoute.Invitations -> InvitationsScreen()

        is NetworkRoute.Invitation -> InvitationScreen(route.token)

        is NetworkRoute.Search -> SearchScreen(route.query.orEmpty())

        is NetworkRoute.Tag -> TagScreen(route.tag)

        is NetworkRoute.WeeklyLetter -> WeeklyLetterScreen(route.id)

        is NetworkRoute.Ideas -> IdeasScreen(route.id)

        // The last route (Merkliste) without a comparison: an exhaustive `when` would add an unreachable throw.
        else -> BookmarksScreen()
    }
}
