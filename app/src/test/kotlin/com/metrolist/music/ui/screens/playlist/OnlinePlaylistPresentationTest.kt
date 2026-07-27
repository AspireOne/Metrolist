package com.metrolist.music.ui.screens.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlinePlaylistPresentationTest {
    @Test
    fun `empty visible prefix with continuation still renders paging content`() {
        assertTrue(
            shouldRenderPlaylistContent(
                hasPlaylist = true,
                visibleSongCount = 0,
                allSongsLoaded = false,
            ),
        )
    }

    @Test
    fun `terminal empty playlist renders final empty state`() {
        assertFalse(
            shouldRenderPlaylistContent(
                hasPlaylist = true,
                visibleSongCount = 0,
                allSongsLoaded = true,
            ),
        )
    }

    @Test
    fun `missing playlist never renders playlist content`() {
        assertFalse(
            shouldRenderPlaylistContent(
                hasPlaylist = false,
                visibleSongCount = 10,
                allSongsLoaded = false,
            ),
        )
    }
}
