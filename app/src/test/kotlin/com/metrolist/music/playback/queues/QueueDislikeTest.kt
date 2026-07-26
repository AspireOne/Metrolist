package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.innertube.models.WatchEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class QueueDislikeTest {

    private fun items(vararg ids: String) = ids.map { MediaItem.Builder().setMediaId(it).build() }

    @Test
    fun filterDisliked_emptySetReturnsTheSameListInstance() {
        val list = items("a", "b")

        assertSame(list, list.filterDisliked(emptySet()))
    }

    @Test
    fun filterDisliked_removesEveryCopyOfADislikedId() {
        val result = items("a", "b", "a", "c").filterDisliked(setOf("a"))

        assertEquals(listOf("b", "c"), result.map { it.mediaId })
    }

    @Test
    fun filterDisliked_keepsEverythingWhenNothingMatches() {
        val result = items("a", "b").filterDisliked(setOf("z"))

        assertEquals(listOf("a", "b"), result.map { it.mediaId })
    }

    @Test
    fun listQueue_isNotRadio() {
        assertFalse(ListQueue(items = items("a")).isRadio)
    }

    @Test
    fun emptyQueue_isNotRadio() {
        assertFalse(EmptyQueue.isRadio)
    }

    @Test
    fun youTubePlaylistQueue_isNotRadio() {
        // A user playlist pages through nextPage() just like a radio does, so this is the case
        // that would silently start dropping tracks if isRadio were derived from hasNextPage().
        assertFalse(YouTubePlaylistQueue(playlistId = "VLPL123").isRadio)
    }

    @Test
    fun youTubeQueue_radioPlaylistIsRadio() {
        assertTrue(YouTubeQueue(WatchEndpoint(videoId = "v1", playlistId = "RDAMVMv1")).isRadio)
    }

    @Test
    fun youTubeQueue_bareVideoIdIsRadio() {
        assertTrue(YouTubeQueue(WatchEndpoint(videoId = "v1")).isRadio)
    }

    @Test
    fun youTubeQueue_userPlaylistIsNotRadio() {
        assertFalse(YouTubeQueue(WatchEndpoint(videoId = "v1", playlistId = "VLPL123")).isRadio)
    }

    @Test
    fun youTubeQueue_radioFactoryIsRadio() {
        assertTrue(YouTubeQueue(WatchEndpoint(playlistId = "RDAMVMabc")).isRadio)
    }

    @Test
    fun youTubeAlbumRadio_isRadio() {
        // True even though getInitialStatus() yields the chosen album: only nextPage() is filtered.
        assertTrue(YouTubeAlbumRadio(playlistId = "OLAK5uy_1").isRadio)
    }
}
