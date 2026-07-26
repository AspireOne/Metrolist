package com.metrolist.music.db.entities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Liked and disliked are opposite poles of one axis, so the interesting cases are the transitions
 * between them rather than either flag on its own.
 */
class SongEntityDislikeTest {

    private fun song(
        liked: Boolean = false,
        disliked: Boolean = false,
    ) = SongEntity(
        id = "song-1",
        title = "Test Song",
        liked = liked,
        likedDate = if (liked) java.time.LocalDateTime.now() else null,
        disliked = disliked,
        dislikedDate = if (disliked) java.time.LocalDateTime.now() else null,
    )

    @Test
    fun neutralSong_becomesDisliked() {
        val result = song().toggleDislike()

        assertTrue(result.disliked)
        assertNotNull(result.dislikedDate)
        assertFalse(result.liked)
    }

    @Test
    fun dislikedSong_becomesNeutral() {
        val result = song(disliked = true).toggleDislike()

        assertFalse(result.disliked)
        assertNull(result.dislikedDate)
    }

    @Test
    fun likedSong_disliking_clearsTheLikeAndItsDate() {
        val result = song(liked = true).toggleDislike()

        assertTrue(result.disliked)
        assertFalse("disliking must clear the like", result.liked)
        assertNull("a stale likedDate would resurface in liked-songs sorts", result.likedDate)
    }

    @Test
    fun undislikingASongDoesNotSilentlyRelikeIt() {
        val result = song(disliked = true).toggleDislike()

        assertFalse(result.liked)
        assertNull(result.likedDate)
    }

    @Test
    fun localToggleDislike_leavesAnUnlikedSongsLikeStateAlone() {
        val original = song()
        val result = original.localToggleDislike()

        assertFalse(result.liked)
        assertNull(result.likedDate)
    }

    @Test
    fun dislikedSong_liking_clearsTheDislike() {
        // toggleLike() fires a fire-and-forget YouTube call on Dispatchers.IO; it is wrapped in
        // runCatching upstream, so it cannot fail this assertion on the copy semantics.
        val result = song(disliked = true).toggleLike()

        assertTrue(result.liked)
        assertFalse("liking must clear the dislike", result.disliked)
        assertNull(result.dislikedDate)
    }

    @Test
    fun unlikingASongLeavesItNeutralNotDisliked() {
        val result = song(liked = true).toggleLike()

        assertFalse(result.liked)
        assertFalse("unliking is not a dislike", result.disliked)
    }
}
