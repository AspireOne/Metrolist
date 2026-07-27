package com.metrolist.music.ui.menu

import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playlist songs are paged in as the list is scrolled, so a caller's list is usually a prefix of
 * the playlist. Every playlist-wide action routes through [resolveAllSongs] to turn that prefix
 * into the whole thing, and the cases that matter are the ones where it must refuse to hand back a
 * prefix rather than quietly truncating a bookmark, an export or a download.
 *
 * The no-loader branch reaches the network and is not covered here.
 */
class ResolveAllSongsTest {

    private fun song(id: String) = SongItem(
        id = id,
        title = "Song $id",
        artists = emptyList(),
        thumbnail = "",
    )

    private val loaded = listOf(song("a"), song("b"))
    private val complete = listOf(song("a"), song("b"), song("c"))

    @Test
    fun completeList_isUsedWithoutLoading() = runBlocking {
        var loaderCalls = 0
        val result = resolveAllSongs(
            playlistId = "PL1",
            songs = loaded,
            songsComplete = true,
            loadAll = {
                loaderCalls++
                Result.success(complete)
            },
        )

        assertEquals(loaded, result.getOrNull())
        assertEquals("a complete list must not trigger a fetch", 0, loaderCalls)
    }

    @Test
    fun partialList_isReplacedByTheLoadedList() = runBlocking {
        val result = resolveAllSongs(
            playlistId = "PL1",
            songs = loaded,
            songsComplete = false,
            loadAll = { Result.success(complete) },
        )

        assertEquals(complete, result.getOrNull())
    }

    @Test
    fun emptyListIsNeverTreatedAsComplete() = runBlocking {
        // songsComplete is true here, but an empty list is indistinguishable from "not loaded yet",
        // so it must still go to the loader rather than yielding nothing.
        var loaderCalls = 0
        val result = resolveAllSongs(
            playlistId = "PL1",
            songs = emptyList(),
            songsComplete = true,
            loadAll = {
                loaderCalls++
                Result.success(complete)
            },
        )

        assertEquals(complete, result.getOrNull())
        assertEquals(1, loaderCalls)
    }

    @Test
    fun loaderFailure_propagatesInsteadOfFallingBackToThePartialList() = runBlocking {
        val failure = java.io.IOException("network down")
        val result = resolveAllSongs(
            playlistId = "PL1",
            songs = loaded,
            songsComplete = false,
            loadAll = { Result.failure(failure) },
        )

        assertTrue("a failed load must not degrade to the partial list", result.isFailure)
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun completeButEmptyPlaylist_resolvesToEmpty() = runBlocking {
        val result = resolveAllSongs(
            playlistId = "PL1",
            songs = emptyList(),
            songsComplete = true,
            loadAll = { Result.success(emptyList()) },
        )

        assertEquals(emptyList<SongItem>(), result.getOrNull())
    }
}
