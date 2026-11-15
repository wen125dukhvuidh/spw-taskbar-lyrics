package com.spwtb.taskbarlyrics.render

import com.spwtb.taskbarlyrics.lyrics.*
import java.awt.image.BufferedImage
import kotlin.test.*

class FrameComposerTest {
    private val song = LyricSong("song", "测试歌曲", "测试歌手", "", "C:/Music/test.flac")
    private val cover = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
    private val timeline = listOf(LyricLine(30000, 32000, "第一句", null, emptyList()))
    private fun compose(
        lines: List<LyricLine> = timeline, position: Long = 0,
        state: LyricsState = LyricsState.READY, playing: Boolean = true,
        settings: RenderSettings = RenderSettings()
    ) = FrameComposer.compose(song, lines, false, state, playing, position, settings) { cover }

    @Test
    fun `intro displays song and cover until the first lyric starts`() {
        val intro = compose(position = 29999)
        assertNotNull(intro.frame)
        assertEquals("测试歌曲", intro.frame.mainText)
        assertEquals("测试歌手 · 暂无歌词", intro.frame.secondText)
        assertSame(cover, intro.frame.cover)
        assertTrue(intro.frame.cells.isEmpty())
        assertNull(LyricLineSelector.pick(timeline, 29999, SecondLineMode.AUTO))
        val first = compose(position = 30000)
        assertEquals("第一句", first.frame?.mainText)
        assertSame(cover, first.frame?.cover)
        assertNotEquals(intro.key, first.key)
    }

    @Test
    fun `loading timeline does not hide the cover`() {
        val update = compose(lines = emptyList(), state = LyricsState.PENDING)
        assertEquals("测试歌曲", update.frame?.mainText)
        assertEquals("测试歌手 · 歌词加载中", update.frame?.secondText)
        assertSame(cover, update.frame?.cover)
    }

    @Test
    fun `a track without lyrics retains song information`() {
        val update = compose(lines = emptyList(), state = LyricsState.NONE)
        assertEquals("测试歌手 · 暂无歌词", update.frame?.secondText)
        assertSame(cover, update.frame?.cover)
    }

    @Test
    fun `blank interlude retains song information`() {
        val gap = listOf(LyricLine(0, 30000, "", null, emptyList()))
        assertEquals("测试歌手 · 间奏", compose(lines = gap, position = 5000).frame?.secondText)
    }

    @Test
    fun `paused playback hides without requesting cover but can remain visible by setting`() {
        val hidden = FrameComposer.compose(song, timeline, false, LyricsState.READY,
            false, 0, RenderSettings()) { error("隐藏时不应读取封面") }
        assertNull(hidden.frame)
        assertNotNull(compose(playing = false,
            settings = RenderSettings(autoHideWhenNoPlayback = false)).frame)
    }

    @Test
    fun `second line and cover visibility settings apply to intro too`() {
        val update = FrameComposer.compose(song, timeline, false, LyricsState.READY,
            true, 0, RenderSettings(secondLine = SecondLineMode.NONE, showCover = false)) {
            error("封面关闭时不应读取封面")
        }
        assertEquals("测试歌曲", update.frame?.mainText)
        assertNull(update.frame?.secondText)
        assertNull(update.frame?.cover)
    }

    @Test
    fun `missing song metadata falls back to filename`() {
        val unnamed = song.copy(title = "", artist = "", path = "C:/Music/track.flac")
        val update = FrameComposer.compose(unnamed, timeline, false, LyricsState.READY,
            true, 0, RenderSettings()) { null }
        assertEquals("track", update.frame?.mainText)
        assertEquals("暂无歌词", update.frame?.secondText)
    }

    @Test
    fun `no current song produces no frame`() {
        val update = FrameComposer.compose(null, emptyList(), false, LyricsState.NONE,
            true, 0, RenderSettings()) { error("没有歌曲时不应读取封面") }
        assertNull(update.frame)
    }
}