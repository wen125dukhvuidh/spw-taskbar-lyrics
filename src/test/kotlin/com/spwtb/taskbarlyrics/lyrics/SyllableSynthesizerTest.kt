package com.spwtb.taskbarlyrics.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyllableSynthesizerTest {

    @Test
    fun `synthesizes one cell per character and keeps the line span`() {
        val cells = SyllableSynthesizer.synthesize("你好世界", 1000L, 2000L)
        assertEquals(4, cells.size)
        assertEquals(1000L, cells.first().startMs)
        assertEquals(2000L, cells.last().endMs)
        assertEquals("你好世界", cells.joinToString("") { it.text })
        for (i in 1 until cells.size) {
            assertEquals(cells[i - 1].endMs, cells[i].startMs)
        }
    }

    @Test
    fun `caps a very long line so the scan does not stall`() {
        // 10 个字 × 400ms = 4000ms 上限，自然结束是 10s
        val cells = SyllableSynthesizer.synthesize("一二三四五六七八九十", 0L, 10_000L)
        assertEquals(4000L, cells.last().endMs)
    }

    @Test
    fun `empty text yields no cells`() {
        assertTrue(SyllableSynthesizer.synthesize("", 0L, 1000L).isEmpty())
    }
}
