package de.velwebsolutions.oasis.network.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.components.AuthorizedImage
import de.velwebsolutions.oasis.design.components.AvatarTint
import de.velwebsolutions.oasis.design.components.AvatarView
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.Member
import de.velwebsolutions.oasis.network.api.OasisSummary

/** The avatar of a member as the server describes it (photo only where the viewer may see it). */
@Composable
fun MemberAvatar(member: Member, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    AvatarView(member.initials, member.avatarColor, modifier, photoUrl = member.photoUrl, size = size)
}

/** The small mark of an oasis in compact lists (`x-network.oasis-mark`): a strip of its banner, or the colour dot. */
@Composable
fun OasisMark(oasis: OasisSummary, modifier: Modifier = Modifier) {
    val banner = oasis.bannerUrl
    if (banner != null) {
        Box(
            modifier
                .size(72.dp, 36.dp)
                .clip(RoundedCornerShape(OasisRadius.CHIP.dp))
                .background(AvatarTint.surface(oasis.color))
                .clearAndSetSemantics {}
        ) {
            AuthorizedImage(banner, contentDescription = null, modifier = Modifier.size(72.dp, 36.dp), contentScale = ContentScale.Crop)
        }
    } else {
        Box(
            modifier
                .size(36.dp)
                .background(AvatarTint.surface(oasis.color), CircleShape)
                .border(1.dp, if (oasis.color == "dune") OasisColors.DuneLine else Color.Transparent, CircleShape)
                .clearAndSetSemantics {}
        )
    }
}

/** "12 Mitglieder" / "1 Mitglied". */
fun membersLabel(count: Int): String = "$count ${if (count == 1) "Mitglied" else "Mitglieder"}"

/** A row with an oasis mark, name and member count (Quelle, Entdecken, Suche, profile). */
@Composable
fun OasisRow(oasis: OasisSummary, modifier: Modifier = Modifier, detail: String? = null) {
    val navigator = LocalNavigator.current
    Row(
        modifier
            .fillMaxWidth()
            .pressable({ navigator.open(NetworkRoute.Oasis(oasis.slug)) })
            .testTag("oasis.row.${oasis.slug}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OasisMark(oasis)
        Column(Modifier.weight(1f)) {
            Text(oasis.name, style = OasisType.BodyBold, color = OasisColors.Night, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val text = detail ?: oasis.membersCount?.let(::membersLabel)
            if (text != null) Text(text, style = OasisType.Small, color = OasisColors.Muted)
        }
    }
}

/** A row with avatar, name and handle linking to the profile, with optional buttons at the end. */
@Composable
fun MemberRow(member: Member, modifier: Modifier = Modifier, size: Dp = 40.dp, trailing: @Composable RowScope.() -> Unit = {}) {
    val navigator = LocalNavigator.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .pressable({ navigator.open(NetworkRoute.Profile(member.username)) }),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MemberAvatar(member, size = size)
            Column(Modifier.weight(1f)) {
                Text(
                    member.displayName,
                    style = OasisType.BodyBold,
                    color = OasisColors.Night,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "@${member.username}",
                    style = OasisType.Small,
                    color = OasisColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing()
    }
}
