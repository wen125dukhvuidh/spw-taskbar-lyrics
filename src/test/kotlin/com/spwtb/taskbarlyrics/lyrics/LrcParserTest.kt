package com.spwtb.taskbarlyrics.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrcParserTest {

    @Test
    fun `parses basic lines and fills end time from the next line`() {
        val lines = LrcParser.parse("[00:01.00]第一句\n[00:02.50]第二句")
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].startMs)
        assertEquals(2500L, lines[0].endMs)
        assertEquals("第一句", lines[0].text)
        assertEquals(2500L, lines[1].startMs)
        // 最后一行用固定时长兜底
        assertEquals(7500L, lines[1].endMs)
        assertTrue(lines[0].cells.isNotEmpty())
    }

    @Test
    fun `expands multiple time tags on one line`() {
        val lines = LrcParser.parse("[00:01.00][00:03.00]副歌")
        assertEquals(2, lines.size)
        assertEquals(listOf(1000L, 3000L), lines.map { it.startMs })
        assertEquals(listOf("副歌", "副歌"), lines.map { it.text })
    }

    @Test
    fun `accepts long fraction digits`() {
        // 压制工具会写 5~6 位毫秒；以前整行会被丢掉
        val lines = LrcParser.parse("[01:47.490005]长毫秒")
        assertEquals(1, lines.size)
        assertEquals(107_490L, lines[0].startMs)
    }

    @Test
    fun `applies offset tag`() {
        val lines = LrcParser.parse("[offset:+500]\n[00:01.00]偏移")
        assertEquals(1, lines.size)
        assertEquals(1500L, lines[0].startMs)
    }

    @Test
    fun `pairs original and translation at the same timestamp`() {
        val raw = """
            [00:01.00]Hello
            [00:01.00]你好
            [00:02.00]World
            [00:02.00]世界
        """.trimIndent()
        val lines = LrcParser.parse(raw)
        assertEquals(2, lines.size)
        assertEquals("Hello", lines[0].text)
        assertEquals("你好", lines[0].translation)
        assertEquals("World", lines[1].text)
        assertEquals("世界", lines[1].translation)
    }

    @Test
    fun `treats placeholder as no translation`() {
        val raw = """
            [00:01.00]Hello
            [00:01.00]//
            [00:02.00]World
            [00:02.00]世界
        """.trimIndent()
        val lines = LrcParser.parse(raw)
        assertEquals(2, lines.size)
        assertNull(lines[0].translation)
        assertEquals("世界", lines[1].translation)
    }

    @Test
    fun `joins same-timestamp credits when the track is not bilingual`() {
        val raw = """
            [00:01.00]第一句
            [00:02.00]第二句
            [00:03.00]第三句
            [00:04.00]作曲：某人
            [00:04.00]编曲：某人
        """.trimIndent()
        val lines = LrcParser.parse(raw)
        // 4 个区块里只有 1 个是双行 -> 0.25 < 0.5，不当双语
        assertEquals(4, lines.size)
        assertEquals("作曲：某人 编曲：某人", lines[3].text)
        assertNull(lines[3].translation)
    }
}
