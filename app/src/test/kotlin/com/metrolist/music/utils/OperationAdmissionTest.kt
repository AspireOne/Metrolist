package com.metrolist.music.utils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationAdmissionTest {
    @Test
    fun `same browse id is admitted once until it leaves`() {
        val admission = OperationAdmission()

        assertTrue(admission.tryEnter("playlist"))
        assertFalse(admission.tryEnter("playlist"))
        assertEquals(setOf("playlist"), admission.active.value)

        admission.leave("playlist")

        assertTrue(admission.tryEnter("playlist"))
    }

    @Test
    fun `concurrent requests for one browse id admit exactly one caller`() {
        val admission = OperationAdmission()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val admitted = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)

        repeat(8) {
            executor.execute {
                start.await()
                if (admission.tryEnter("playlist")) admitted.incrementAndGet()
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(1, admitted.get())
        assertEquals(setOf("playlist"), admission.active.value)
    }
}
