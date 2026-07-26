package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.models.MediaMetadata
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
    fun youTubeQueue_playlistRadioIsRadio() {
        // "Start radio" on a playlist builds RDAMPL, not RDAMVM (Library.kt).
        assertTrue(YouTubeQueue(WatchEndpoint(playlistId = "RDAMPLPL123")).isRadio)
    }

    @Test
    fun youTubeQueue_serverProvidedRadioFormsAreRadio() {
        // Artist and station radios come back from the server in other RD forms.
        for (id in listOf("RDEMabc", "RDAOxyz", "RDCLAK5uy_1", "RDTMAK5uy_2")) {
            assertTrue("expected $id to be a radio", YouTubeQueue(WatchEndpoint(playlistId = id)).isRadio)
        }
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

    @Test
    fun albumRadio_doesNotGenerateItsInitialItems() {
        // The pairing that protects the chosen album while still filtering the mix after it.
        assertTrue(YouTubeAlbumRadio(playlistId = "OLAK5uy_1").isRadio)
        assertFalse(YouTubeAlbumRadio(playlistId = "OLAK5uy_1").hasGeneratedInitialItems)
    }

    @Test
    fun pureRadio_generatesItsInitialItems() {
        assertTrue(YouTubeQueue(WatchEndpoint(videoId = "v1")).hasGeneratedInitialItems)
    }

    @Test
    fun userPlaylist_generatesNothing() {
        assertFalse(YouTubePlaylistQueue(playlistId = "VLPL123").hasGeneratedInitialItems)
        assertFalse(ListQueue(items = items("a")).hasGeneratedInitialItems)
    }

    private fun status(
        ids: List<String>,
        mediaItemIndex: Int,
    ) = Queue.Status(title = null, items = items(*ids.toTypedArray()), mediaItemIndex = mediaItemIndex)

    @Test
    fun statusFilterDisliked_shiftsStartIndexWhenDroppingEarlierItems() {
        val result = status(listOf("a", "b", "c", "d"), mediaItemIndex = 2).filterDisliked(setOf("a", "b"))

        assertEquals(listOf("c", "d"), result.items.map { it.mediaId })
        assertEquals("start index must still point at c", 0, result.mediaItemIndex)
    }

    @Test
    fun statusFilterDisliked_leavesStartIndexAloneWhenDroppingLaterItems() {
        val result = status(listOf("a", "b", "c"), mediaItemIndex = 0).filterDisliked(setOf("c"))

        assertEquals(listOf("a", "b"), result.items.map { it.mediaId })
        assertEquals(0, result.mediaItemIndex)
    }

    @Test
    fun statusFilterDisliked_keepsTheSeedTrackEvenWhenDisliked() {
        // Starting a radio from a song is a deliberate request to hear that song.
        val result = status(listOf("a", "b", "c"), mediaItemIndex = 1).filterDisliked(setOf("b", "c"))

        assertEquals(listOf("a", "b"), result.items.map { it.mediaId })
        assertEquals(1, result.mediaItemIndex)
    }

    @Test
    fun statusFilterDisliked_emptySetIsANoOp() {
        val original = status(listOf("a", "b"), mediaItemIndex = 1)
        val result = original.filterDisliked(emptySet())

        assertEquals(listOf("a", "b"), result.items.map { it.mediaId })
        assertEquals(1, result.mediaItemIndex)
    }

    @Test
    fun statusFilterDisliked_droppingEverythingButTheSeedLeavesTheIndexInRange() {
        val result = status(listOf("a", "b"), mediaItemIndex = 1).filterDisliked(setOf("a", "b"))

        assertEquals(listOf("b"), result.items.map { it.mediaId })
        assertEquals(0, result.mediaItemIndex)
    }

    @Test
    fun statusFilterDisliked_indexNeverGoesOutOfRange() {
        val result = status(listOf("a"), mediaItemIndex = 0).filterDisliked(setOf("a"))

        // The seed survives, so this is really a guard against the clamp misbehaving.
        assertTrue(result.mediaItemIndex in result.items.indices)
    }

    private fun taggedItem(
        id: String,
        explicit: Boolean = false,
    ): MediaItem {
        val meta =
            MediaMetadata(
                id = id,
                title = id,
                artists = emptyList(),
                duration = 100,
                explicit = explicit,
            )
        // The uri matters: MediaItem.metadata reads localConfiguration?.tag, and localConfiguration
        // only exists once a uri is set. Production always sets one (Song.toMediaItem).
        return MediaItem.Builder().setMediaId(id).setUri(id).setTag(meta).build()
    }

    @Test
    fun statusFilterExplicit_shiftsStartIndex() {
        // Pre-existing bug this filter had: dropping an item before the start position without
        // moving the index silently began playback on the wrong track.
        val original =
            Queue.Status(
                title = null,
                items = listOf(taggedItem("a", explicit = true), taggedItem("b"), taggedItem("c")),
                mediaItemIndex = 2,
            )

        val result = original.filterExplicit(true)

        assertEquals(listOf("b", "c"), result.items.map { it.mediaId })
        assertEquals("start index must still point at c", 1, result.mediaItemIndex)
    }

    @Test
    fun statusFilterExplicit_droppingTheStartItemLandsOnWhatFollows() {
        val original =
            Queue.Status(
                title = null,
                items = listOf(taggedItem("a"), taggedItem("b", explicit = true), taggedItem("c")),
                mediaItemIndex = 1,
            )

        val result = original.filterExplicit(true)

        assertEquals(listOf("a", "c"), result.items.map { it.mediaId })
        assertEquals(1, result.mediaItemIndex)
    }

    @Test
    fun statusFilters_composeWithoutCorruptingTheIndex() {
        // The ordering used in playQueue: explicit, then video, then disliked.
        val original =
            Queue.Status(
                title = null,
                items = listOf(taggedItem("a", explicit = true), taggedItem("b"), taggedItem("c"), taggedItem("d")),
                mediaItemIndex = 2,
            )

        val result = original.filterExplicit(true).filterDisliked(setOf("b"))

        assertEquals(listOf("c", "d"), result.items.map { it.mediaId })
        assertEquals(0, result.mediaItemIndex)
    }
}
