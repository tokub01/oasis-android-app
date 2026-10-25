package de.velwebsolutions.oasis.network.ui

import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.data.NetworkRepository
import java.util.concurrent.ConcurrentHashMap

/** The buttons and menu items of a Tropfen card (the web's `InteractsWithPosts`). */
enum class PostAction {
    React,
    Unreact,
    Bookmark,
    Unbookmark,
    Delete,
    RemoveFromOasis,
    Mute,
    Unmute,
    Restrict,
    Unrestrict,
    Block
    ;

    /** The opposite of a reaction or bookmark, to roll back an optimistic toggle; null for every other action. */
    val undo: PostAction?
        get() = when (this) {
            React -> Unreact
            Unreact -> React
            Bookmark -> Unbookmark
            Unbookmark -> Bookmark
            else -> null
        }
}

/** Runs a card action and returns the server's confirmation (the web's status line). */
suspend fun NetworkRepository.perform(action: PostAction, post: Post): String = when (action) {
    PostAction.React -> react(post.id, on = true)
    PostAction.Unreact -> react(post.id, on = false)
    PostAction.Bookmark -> bookmark(post.id, on = true)
    PostAction.Unbookmark -> bookmark(post.id, on = false)
    PostAction.Delete -> deletePost(post.id)
    PostAction.RemoveFromOasis -> removeFromOasis(post.id)
    PostAction.Mute -> mute(post.author.id, on = true)
    PostAction.Unmute -> mute(post.author.id, on = false)
    PostAction.Restrict -> restrict(post.author.id, on = true)
    PostAction.Unrestrict -> restrict(post.author.id, on = false)
    PostAction.Block -> block(post.author.id)
}

/**
 * The effect of a successful action on a loaded list, as the web shows it after querying the list again: reactions and
 * bookmarks toggle, deleted and removed Tropfen disappear, and muting or blocking hides the person's Tropfen.
 */
fun List<Post>.applying(action: PostAction, post: Post): List<Post> = when (action) {
    PostAction.React, PostAction.Unreact -> map { item ->
        if (item.id != post.id) {
            item
        } else {
            val on = action == PostAction.React
            item.copy(
                viewer = item.viewer?.copy(reacted = on),
                stats = item.stats?.let { it.copy(reactions = it.reactions + if (on) 1 else -1) }
            )
        }
    }

    PostAction.Bookmark, PostAction.Unbookmark -> map { item ->
        if (item.id == post.id) item.copy(viewer = item.viewer?.copy(bookmarked = action == PostAction.Bookmark)) else item
    }

    PostAction.Restrict, PostAction.Unrestrict -> map { item ->
        if (item.author.id == post.author.id) {
            item.copy(viewer = item.viewer?.copy(hasRestrictedAuthor = action == PostAction.Restrict))
        } else {
            item
        }
    }

    PostAction.Unmute -> map { item ->
        if (item.author.id == post.author.id) item.copy(viewer = item.viewer?.copy(hasMutedAuthor = false)) else item
    }

    PostAction.Delete, PostAction.RemoveFromOasis -> filterNot { it.id == post.id }

    PostAction.Mute, PostAction.Block -> filterNot { it.author.id == post.author.id }
}

/**
 * The cards whose action is still running. Like the iOS card (`busy`), a card takes no further taps until its action
 * has finished, so a quick double tap does not send a reaction and its withdrawal at the same time.
 */
class BusyCards {
    private val ids = ConcurrentHashMap.newKeySet<Int>()

    /** Runs [action] for the card of [postId] unless one is running already; returns whether it ran. */
    suspend fun run(postId: Int, action: suspend () -> Unit): Boolean {
        if (!ids.add(postId)) return false
        try {
            action()
        } finally {
            ids.remove(postId)
        }
        return true
    }
}
