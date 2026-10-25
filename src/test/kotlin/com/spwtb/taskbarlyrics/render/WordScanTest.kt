package com.spwtb.taskbarlyrics.render

import com.spwtb.taskbarlyrics.lyrics.LyricCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WordScanTest {

    private val frame = LyricsFrame(
        mainText = "Hi there",
        secondText = null,
        cover = null,
        cells = listOf(
            LyricCell(0L, 1000L, "Hi"),
            LyricCell(1000L, 2000L, " there")
        ),
        positionMs = 0L,
        lineStartMs = 0L,
        lineEndMs = 2000L
    )

    @Test
    fun `interpolates inside a cell instead of snapping per character`() {
        // 原版用例：8 个字符的 "Hi there"，第一个 cell 唱到一半时进度是 1/8
        val progress = WordScan.progress(frame.copy(positionMs = 500L), "Hi there")
        assertEquals(1.0 / 8.0, progress!!, 1e-9)
    }

    @Test
    fun `returns full progress at the end of the line`() {
        assertEquals(1.0, WordScan.progress(frame.copy(positionMs = 2000L), "Hi there"))
    }

    @Test
    fun `has no progress without cells`() {
        assertNull(WordScan.progress(frame.copy(cells = emptyList()), "Hi there"))
    }
}
