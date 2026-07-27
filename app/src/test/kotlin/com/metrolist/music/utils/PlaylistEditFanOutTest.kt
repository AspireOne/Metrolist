package com.metrolist.music.utils

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers [addSongsIndividually], the fan-out behind per-song remote playlist edits.
 *
 * The property under test is that one song's remote failure cannot decide the fate of the songs
 * behind it. Every song in a batch is already committed locally before any remote work starts, and
 * playlist sync rebuilds the local list from the remote one, so a song that was never *attempted*
 * remotely is a song a later sync deletes.
 */
class PlaylistEditFanOutTest {
    /** Records every id handed to the fan-out, in order, across all passes. */
    private class Recorder(private val failFor: (String, Int) -> Throwable?) {
        val attempts = mutableListOf<String>()
        private val countsById = mutableMapOf<String, Int>()

        fun addOne(id: String) {
            attempts += id
            val attemptNumber = (countsById[id] ?: 0) + 1
            countsById[id] = attemptNumber
            failFor(id, attemptNumber)?.let { throw it }
        }
    }

    @Test
    fun `a failure in the middle does not abort the songs behind it`() = runBlocking {
        val recorder = Recorder { id, _ -> if (id == "b") IOException("boom") else null }

        val failures =
            addSongsIndividually(
                songIds = listOf("a", "b", "c"),
                shouldRetry = { false },
                addOne = { recorder.addOne(it) },
            )

        assertEquals(listOf("a", "b", "c"), recorder.attempts)
        assertEquals(listOf("b"), failures.map { it.songId })
    }

    @Test
    fun `the retry pass covers only the residue and rescues a transient failure`() = runBlocking {
        val recorder = Recorder { id, attempt ->
            if (id == "b" && attempt == 1) IOException("transient") else null
        }

        val failures =
            addSongsIndividually(
                songIds = listOf("a", "b", "c"),
                shouldRetry = { true },
                addOne = { recorder.addOne(it) },
            )

        assertEquals(listOf("a", "b", "c", "b"), recorder.attempts)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a permanent failure is reported once with its error after the retry pass`() = runBlocking {
        val error = IllegalStateException("video unavailable")
        val recorder = Recorder { id, _ -> if (id == "b") error else null }

        val failures =
            addSongsIndividually(
                songIds = listOf("a", "b", "c"),
                shouldRetry = { true },
                addOne = { recorder.addOne(it) },
            )

        assertEquals(listOf("a", "b", "c", "b"), recorder.attempts)
        assertEquals(listOf("b"), failures.map { it.songId })
        assertSame(error, failures.single().error)
    }

    @Test
    fun `the retry pass is skipped when the caller declines it`() = runBlocking {
        var retryConsulted = false
        val recorder = Recorder { _, _ -> IOException("offline") }

        val failures =
            addSongsIndividually(
                songIds = listOf("a", "b"),
                shouldRetry = {
                    retryConsulted = true
                    false
                },
                addOne = { recorder.addOne(it) },
            )

        assertTrue(retryConsulted)
        assertEquals(listOf("a", "b"), recorder.attempts)
        assertEquals(listOf("a", "b"), failures.map { it.songId })
    }

    @Test
    fun `the caller is not consulted about retrying when nothing failed`() = runBlocking {
        val recorder = Recorder { _, _ -> null }

        val failures =
            addSongsIndividually(
                songIds = listOf("a", "b"),
                shouldRetry = { fail("must not consult shouldRetry without a residue"); false },
                addOne = { recorder.addOne(it) },
            )

        assertEquals(listOf("a", "b"), recorder.attempts)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `cancellation propagates instead of being recorded as a failure`() = runBlocking {
        val recorder = Recorder { id, _ ->
            if (id == "b") CancellationException("cancelled") else null
        }

        try {
            addSongsIndividually(
                songIds = listOf("a", "b", "c"),
                addOne = { recorder.addOne(it) },
            )
            fail("cancellation must not be swallowed")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }

        // "c" is never reached: a cancelled batch has no residue to report or retry.
        assertEquals(listOf("a", "b"), recorder.attempts)
    }

    @Test
    fun `an empty batch attempts nothing`() = runBlocking {
        val recorder = Recorder { _, _ -> fail("must not attempt anything"); null }

        val failures =
            addSongsIndividually(
                songIds = emptyList(),
                shouldRetry = { fail("must not consult shouldRetry"); false },
                addOne = { recorder.addOne(it) },
            )

        assertTrue(recorder.attempts.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `every song is attempted even when all of them fail`() = runBlocking {
        val recorder = Recorder { _, _ -> IOException("boom") }

        val failures =
            addSongsIndividually(
                songIds = listOf("a", "b", "c"),
                shouldRetry = { false },
                addOne = { recorder.addOne(it) },
            )

        assertEquals(listOf("a", "b", "c"), recorder.attempts)
        assertEquals(listOf("a", "b", "c"), failures.map { it.songId })
    }
}
