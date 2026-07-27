package com.metrolist.music.ui.menu

import com.metrolist.music.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistAdditionPolicyTest {
    private fun song(id: String) =
        MediaMetadata(
            id = id,
            title = id,
            artists = emptyList(),
            duration = 1,
            thumbnailUrl = null,
        )

    @Test
    fun `complete source uses one bulk request when nothing is skipped`() {
        val prepared =
            preparePlaylistAddition(
                PlaylistAddPayload(
                    songs = listOf(song("a"), song("b")),
                    remoteSourcePlaylistId = "source",
                ),
                duplicateIdsToSkip = emptySet(),
            )

        assertEquals(listOf("a", "b"), prepared.songs.map { it.id })
        assertTrue(prepared.useBulkRemoteAdd)
    }

    @Test
    fun `skipping duplicates selects only non-duplicates and disables bulk request`() {
        val prepared =
            preparePlaylistAddition(
                PlaylistAddPayload(
                    songs = listOf(song("a"), song("b")),
                    remoteSourcePlaylistId = "source",
                ),
                duplicateIdsToSkip = setOf("a"),
            )

        assertEquals(listOf("b"), prepared.songs.map { it.id })
        assertFalse(prepared.useBulkRemoteAdd)
    }

    @Test
    fun `source without playlist id always uses per-song requests`() {
        val prepared =
            preparePlaylistAddition(
                PlaylistAddPayload(songs = listOf(song("a"))),
                duplicateIdsToSkip = emptySet(),
            )

        assertFalse(prepared.useBulkRemoteAdd)
    }
}
