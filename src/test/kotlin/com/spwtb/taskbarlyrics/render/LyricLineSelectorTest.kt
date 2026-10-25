package com.spwtb.taskbarlyrics.render

import com.spwtb.taskbarlyrics.lyrics.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricLineSelectorTest {

    private val lines = listOf(
        line(0, "A", "甲"),
        line(1000, "B", null),
        line(2000, "C", null)
    )

    @Test
    fun `auto shows translation when the line has one`() {
        val pick = LyricLineSelector.pick(lines, 0, SecondLineMode.AUTO, trackHasTranslation = true)!!
        assertEquals("A", pick.mainText)
        assertEquals("甲", pick.secondText)
        assertEquals(SecondLineKind.TRANSLATION, pick.secondKind)
    }

    @Test
    fun `auto shows placeholder when the track has translation but the line does not`() {
        val pick = LyricLineSelector.pick(lines, 1000, SecondLineMode.AUTO, trackHasTranslation = true)!!
        assertEquals("B", pick.mainText)
        assertEquals("…", pick.secondText)
        assertEquals(SecondLineKind.PLACEHOLDER, pick.secondKind)
    }

    @Test
    fun `auto falls back to the next line when no translation exists`() {
        val pick = LyricLineSelector.pick(lines, 1000, SecondLineMode.AUTO, trackHasTranslation = false)!!
        assertEquals("B", pick.mainText)
        assertEquals("C", pick.secondText)
        assertEquals(SecondLineKind.NEXT, pick.secondKind)
    }

    @Test
    fun `none hides the second line`() {
        val pick = LyricLineSelector.pick(lines, 0, SecondLineMode.NONE, trackHasTranslation = true)!!
        assertNull(pick.secondText)
    }

    private fun line(start: Long, text: String, translation: String?) = LyricLine(
        startMs = start,
        endMs = start + 1000,
        text = text,
        translation = translation,
        cells = emptyList()
    )
}
