package com.metrolist.music.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [mayRemoveMissing], the gate in front of every sync that deletes local state a remote list
 * omits.
 *
 * Both conditions have caused real data loss when absent: an incomplete read un-liked thousands of
 * songs that were only missing from a truncated page, and an empty read cannot be told apart from a
 * response that failed to parse.
 */
class MayRemoveMissingTest {
    @Test
    fun completeAndNonEmpty_mayRemove() {
        assertTrue(mayRemoveMissing(isComplete = true, remoteCount = 1))
        assertTrue(mayRemoveMissing(isComplete = true, remoteCount = 5000))
    }

    @Test
    fun incompleteList_mayNotRemove() {
        assertFalse(
            "a prefix omits everything past the cut, which is not the same as it being gone",
            mayRemoveMissing(isComplete = false, remoteCount = 1046),
        )
    }

    @Test
    fun emptyList_mayNotRemove() {
        assertFalse(
            "an unparseable response also arrives as a successful, complete, empty list",
            mayRemoveMissing(isComplete = true, remoteCount = 0),
        )
    }

    @Test
    fun incompleteAndEmpty_mayNotRemove() {
        assertFalse(mayRemoveMissing(isComplete = false, remoteCount = 0))
    }
}
