package com.metrolist.innertube.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [drainPages], which decides whether a remote list may be treated as whole.
 *
 * The distinction under test is the one `completed` cannot make. Callers delete local state that a
 * remote list omits, so every way of stopping short has to be reported as incomplete — otherwise a
 * prefix authorises deleting everything past it.
 */
class DrainTest {
    /** A fetcher over a fixed token -> (items, nextToken) map; any other token is a failure. */
    private fun fetcherOf(
        vararg pages: Pair<String, Pair<List<String>, String?>>,
    ): suspend (String) -> Result<DrainPage<String>> {
        val byToken = pages.toMap()
        return { token ->
            byToken[token]
                ?.let { (items, next) -> Result.success(DrainPage(items, next)) }
                ?: Result.failure(IllegalStateException("unexpected token $token"))
        }
    }

    @Test
    fun noContinuation_isCompleteWithTheInitialItems() = runBlocking {
        val drained =
            drainPages(
                initialItems = listOf("a"),
                initialContinuation = null,
                maxRequests = 10,
                label = "test",
                fetchPage = { Result.failure(AssertionError("must not fetch")) },
            )

        assertTrue(drained.isComplete)
        assertEquals(listOf("a"), drained.value)
    }

    @Test
    fun followsEveryContinuationToTheEnd() = runBlocking {
        val drained =
            drainPages(
                initialItems = listOf("a"),
                initialContinuation = "t1",
                maxRequests = 10,
                label = "test",
                fetchPage =
                    fetcherOf(
                        "t1" to (listOf("b") to "t2"),
                        "t2" to (listOf("c") to null),
                    ),
            )

        assertTrue(drained.isComplete)
        assertEquals(listOf("a", "b", "c"), drained.value)
    }

    @Test
    fun failedPage_isIncompleteButKeepsWhatArrived() = runBlocking {
        var calls = 0
        val drained =
            drainPages(
                initialItems = listOf("a"),
                initialContinuation = "t1",
                maxRequests = 10,
                label = "test",
                fetchPage = { token ->
                    calls++
                    if (calls == 1) {
                        Result.success(DrainPage(listOf("b"), "t2"))
                    } else {
                        Result.failure(java.io.IOException("network down on $token"))
                    }
                },
            )

        assertFalse("a failed page must not be reported as the whole list", drained.isComplete)
        assertEquals("what did arrive is still usable for the additive phase", listOf("a", "b"), drained.value)
    }

    @Test
    fun repeatedToken_isIncomplete() = runBlocking {
        val drained =
            drainPages(
                initialItems = emptyList(),
                initialContinuation = "loop",
                maxRequests = 10,
                label = "test",
                fetchPage = fetcherOf("loop" to (listOf("a") to "loop")),
            )

        assertFalse(drained.isComplete)
        assertEquals(listOf("a"), drained.value)
    }

    @Test
    fun exceedingThePageLimit_isIncomplete() = runBlocking {
        val drained =
            drainPages(
                initialItems = emptyList(),
                initialContinuation = "t1",
                maxRequests = 2,
                label = "test",
                fetchPage =
                    fetcherOf(
                        "t1" to (listOf("a") to "t2"),
                        "t2" to (listOf("b") to "t3"),
                        "t3" to (listOf("c") to null),
                    ),
            )

        assertFalse(drained.isComplete)
        assertEquals("stopped before fetching the third page", listOf("a", "b"), drained.value)
    }

    @Test
    fun exactlyAtThePageLimit_isComplete() = runBlocking {
        val drained =
            drainPages(
                initialItems = emptyList(),
                initialContinuation = "t1",
                maxRequests = 2,
                label = "test",
                fetchPage =
                    fetcherOf(
                        "t1" to (listOf("a") to "t2"),
                        "t2" to (listOf("b") to null),
                    ),
            )

        assertTrue("the limit permits exactly maxRequests pages", drained.isComplete)
        assertEquals(listOf("a", "b"), drained.value)
    }

    @Test
    fun emptyLastPage_isComplete() = runBlocking {
        val drained =
            drainPages(
                initialItems = listOf("a"),
                initialContinuation = "t1",
                maxRequests = 10,
                label = "test",
                fetchPage = fetcherOf("t1" to (emptyList<String>() to null)),
            )

        assertTrue(drained.isComplete)
        assertEquals(listOf("a"), drained.value)
    }

    @Test
    fun emptyPageMidwayWithAContinuation_keepsGoing() = runBlocking {
        val drained =
            drainPages(
                initialItems = emptyList(),
                initialContinuation = "t1",
                maxRequests = 10,
                label = "test",
                fetchPage =
                    fetcherOf(
                        "t1" to (emptyList<String>() to "t2"),
                        "t2" to (listOf("a") to null),
                    ),
            )

        assertTrue(drained.isComplete)
        assertEquals(listOf("a"), drained.value)
    }
}
