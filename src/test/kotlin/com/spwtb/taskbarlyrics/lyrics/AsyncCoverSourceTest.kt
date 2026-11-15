package com.spwtb.taskbarlyrics.lyrics

import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AsyncCoverSourceTest {
    private fun image() = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `slow decoding does not block rendering or enqueue duplicate reads`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val calls = AtomicInteger()
        val expected = image()
        val source = AsyncCoverSource({
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            expected
        })
        source.start { ready.countDown() }
        try {
            assertNull(source.get("song"))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            repeat(100) { assertNull(source.get("song")) }
            release.countDown()
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            assertSame(expected, source.get("song"))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            source.clear()
        }
    }

    @Test
    fun `failed reads are throttled and recover without changing songs`() {
        val now = AtomicLong()
        val calls = AtomicInteger()
        val completed = LinkedBlockingQueue<Boolean>()
        val expected = image()
        val source = AsyncCoverSource({
            if (calls.incrementAndGet() == 1) error("temporarily unavailable")
            expected
        }, now::get)
        source.start { completed.add(true) }
        try {
            assertNull(source.get("song"))
            assertEquals(true, completed.poll(3, TimeUnit.SECONDS))
            repeat(100) { assertNull(source.get("song")) }
            assertEquals(1, calls.get())
            now.set(5_000_000_000L)
            assertNull(source.get("song"))
            assertEquals(true, completed.poll(3, TimeUnit.SECONDS))
            assertSame(expected, source.get("song"))
            assertEquals(2, calls.get())
        } finally {
            source.clear()
        }
    }

    @Test
    fun `disabled loader does not read and can be started again`() {
        val calls = AtomicInteger()
        val source = AsyncCoverSource({ calls.incrementAndGet(); image() })
        val ready = CountDownLatch(1)
        source.clear()
        assertNull(source.get("song"))
        assertEquals(0, calls.get())
        source.start { ready.countDown() }
        try {
            assertNull(source.get("song"))
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            source.clear()
        }
    }
}
