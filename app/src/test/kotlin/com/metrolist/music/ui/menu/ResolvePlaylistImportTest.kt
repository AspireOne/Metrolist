package com.metrolist.music.ui.menu

import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvePlaylistImportTest {
    private val song =
        MediaMetadata(
            id = "song",
            title = "Song",
            artists = emptyList(),
            duration = 1,
        )

    @Test
    fun `resolution failure performs no commit`() =
        runBlocking {
            var committed = false

            val result =
                resolvePlaylistImport(
                    resolveSongs = { Result.failure(IllegalStateException("paging failed")) },
                    commit = { committed = true },
                )

            assertTrue(result.isFailure)
            assertFalse(committed)
        }

    @Test
    fun `successful empty source is committed as intentional empty playlist`() =
        runBlocking {
            var committedSongs: List<MediaMetadata>? = null

            val result =
                resolvePlaylistImport(
                    resolveSongs = { Result.success(emptyList()) },
                    commit = { committedSongs = it },
                )

            assertTrue(result.isSuccess)
            assertEquals(emptyList<MediaMetadata>(), committedSongs)
        }

    @Test(expected = CancellationException::class)
    fun `cancellation is propagated and performs no commit`() {
        runBlocking {
            resolvePlaylistImport(
                resolveSongs = { throw CancellationException("cancelled") },
                commit = { error("must not commit") },
            )
        }
    }

    @Test
    fun `commit failure is reported`() =
        runBlocking {
            val result =
                resolvePlaylistImport(
                    resolveSongs = { Result.success(listOf(song)) },
                    commit = { throw IllegalStateException("database failed") },
                )

            assertTrue(result.isFailure)
        }
}
