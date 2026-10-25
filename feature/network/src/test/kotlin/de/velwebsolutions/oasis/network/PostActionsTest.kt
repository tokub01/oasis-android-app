package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.applying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a card action does to a loaded list (iOS `PostActionTests`). */
class PostActionsTest {
    private val mine = decodePost(postJson(1, author = 5)).let { it.copy(stats = Post.Stats(reactions = 2, replies = 1)) }
    private val sameAuthor = decodePost(postJson(2, author = 5))
    private val other = decodePost(postJson(3, author = 6))
    private val anonymous = decodePost(postJson(4, author = 5)).copy(viewer = null, stats = null)
    private val list = listOf(mine, sameAuthor, other, anonymous)

    @Test
    fun `reacting counts the reaction for the author and withdrawing takes it back`() {
        val reacted = list.applying(PostAction.React, mine)
        assertEquals(true, reacted[0].viewer?.reacted)
        assertEquals(3, reacted[0].stats?.reactions)
        assertEquals(false, reacted[1].viewer?.reacted)
        val withdrawn = reacted.applying(PostAction.Unreact, mine)
        assertEquals(2, withdrawn[0].stats?.reactions)
        val noStats = list.applying(PostAction.React, anonymous)
        assertNull(noStats[3].viewer)
        assertNull(noStats[3].stats)
        assertEquals(false, list.applying(PostAction.React, sameAuthor)[1].viewer?.reacted?.not())
    }

    @Test
    fun `bookmarks toggle only the card`() {
        val marked = list.applying(PostAction.Bookmark, other)
        assertEquals(true, marked[2].viewer?.bookmarked)
        assertEquals(false, marked[0].viewer?.bookmarked)
        assertEquals(false, marked.applying(PostAction.Unbookmark, other)[2].viewer?.bookmarked)
        assertNull(list.applying(PostAction.Bookmark, anonymous)[3].viewer)
    }

    @Test
    fun `restricting and unmuting mark every card of the person`() {
        val restricted = list.applying(PostAction.Restrict, mine)
        assertEquals(listOf(true, true, false), restricted.take(3).map { it.viewer?.hasRestrictedAuthor })
        assertNull(restricted[3].viewer)
        assertEquals(false, restricted.applying(PostAction.Unrestrict, mine)[1].viewer?.hasRestrictedAuthor)
        val muted = list.map { it.copy(viewer = it.viewer?.copy(hasMutedAuthor = true)) }
        val unmuted = muted.applying(PostAction.Unmute, mine)
        assertEquals(listOf(false, false, true), unmuted.take(3).map { it.viewer?.hasMutedAuthor })
        assertNull(unmuted[3].viewer)
    }

    @Test
    fun `deleting, removing, muting and blocking take cards away`() {
        assertEquals(listOf(2, 3, 4), list.applying(PostAction.Delete, mine).map { it.id })
        assertEquals(listOf(1, 2, 4), list.applying(PostAction.RemoveFromOasis, other).map { it.id })
        assertEquals(listOf(3), list.applying(PostAction.Mute, mine).map { it.id })
        assertEquals(listOf(1, 2, 4), list.applying(PostAction.Block, other).map { it.id })
    }
}
