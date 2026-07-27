package com.metrolist.innertube.utils

import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.PlaylistContinuationPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backs `fullyCompleted`, the strict counterpart to `completed`.
 *
 * Actions that treat a playlist as a whole — exporting it, downloading it, copying it into the
 * library — cannot tell a truncated list from a short playlist, so the rule under test is that
 * anything short of every page is a failure rather than a prefix.
 */
class CollectAllPlaylistSongsTest {

    private fun song(id: String) = SongItem(
        id = id,
        title = "Song $id",
        artists = emptyList(),
        thumbnail = "",
    )

    private fun pagesOf(vararg pages: Pair<List<SongItem>, String?>): suspend (String) -> Result<PlaylistContinuationPage> {
        val byToken = pages.withIndex().associate { (i, page) ->
            "token$i" to PlaylistContinuationPage(songs = page.first, continuation = page.second)
        }
        return { token ->
            byToken[token]
                ?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("unexpected token $token"))
        }
    }

    @Test
    fun noContinuation_returnsTheInitialPage() = runBlocking {
        val result = collectAllPlaylistSongs(
            initialSongs = listOf(song("a")),
            initialContinuation = null,
            maxRequests = 10,
            fetchContinuation = { Result.failure(AssertionError("must not fetch")) },
        )

        assertEquals(listOf(song("a")), result.getOrNull())
    }

    @Test
    fun followsEveryContinuationToTheEnd() = runBlocking {
        val result = collectAllPlaylistSongs(
            initialSongs = listOf(song("a")),
            initialContinuation = "token0",
            maxRequests = 10,
            fetchContinuation = pagesOf(
                listOf(song("b")) to "token1",
                listOf(song("c")) to null,
            ),
        )

        assertEquals(listOf(song("a"), song("b"), song("c")), result.getOrNull())
    }

    @Test
    fun failedRequest_failsInsteadOfReturningWhatArrivedSoFar() = runBlocking {
        var calls = 0
        val result = collectAllPlaylistSongs(
            initialSongs = listOf(song("a")),
            initialContinuation = "token0",
            maxRequests = 10,
            fetchContinuation = {
                calls++
                if (calls == 1) {
                    Result.success(PlaylistContinuationPage(listOf(song("b")), "token1"))
                } else {
                    Result.failure(java.io.IOException("network down"))
                }
            },
        )

        assertTrue("a failed page must not be reported as the whole playlist", result.isFailure)
    }

    @Test
    fun repeatedContinuationToken_fails() = runBlocking {
        // A token that leads back to itself would page forever, and there is no way to know how
        // much of the playlist was missed.
        val result = collectAllPlaylistSongs(
            initialSongs = listOf(song("a")),
            initialContinuation = "loop",
            maxRequests = 100,
            fetchContinuation = { Result.success(PlaylistContinuationPage(listOf(song("b")), "loop")) },
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("looped"))
    }

    @Test
    fun exceedingTheRequestLimit_failsRatherThanTruncating() = runBlocking {
        var next = 0
        val result = collectAllPlaylistSongs(
            initialSongs = emptyList(),
            initialContinuation = "token0",
            maxRequests = 3,
            fetchContinuation = {
                next++
                Result.success(PlaylistContinuationPage(listOf(song("s$next")), "token$next"))
            },
        )

        assertTrue("hitting the page limit must not look like completion", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("exceeded"))
    }

    @Test
    fun emptyPageWithAContinuation_keepsGoing() = runBlocking {
        val result = collectAllPlaylistSongs(
            initialSongs = emptyList(),
            initialContinuation = "token0",
            maxRequests = 10,
            fetchContinuation = pagesOf(
                emptyList<SongItem>() to "token1",
                listOf(song("a")) to null,
            ),
        )

        assertEquals(listOf(song("a")), result.getOrNull())
    }
}
