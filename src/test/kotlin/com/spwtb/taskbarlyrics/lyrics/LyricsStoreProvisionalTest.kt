@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spwtb.taskbarlyrics.lyrics

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 复现「Steam 启动后首曲没有歌词」：
 * 插件注册晚于首曲 onBeforeLoadLyrics，LyricsStore 没有 song；
 * 宿主仍会推当前歌词行/重放完整列表。收到数据时应当补出临时播放会话，
 * 把歌词显示出来，而不是因为 song == null 一直空白。
 */
class LyricsStoreProvisionalTest {

    @Test
    fun `line update without a song creates a provisional playback`() {
        LyricsStore.clear()

        LyricsStore.onLyricLineUpdated(apiLine(1000, 2000, "hello"))

        val song = LyricsStore.song
        assertNotNull(song, "插件启动晚于首曲时，歌词行回调应补出一个临时播放会话")
        assertTrue(song.path.isBlank(), "临时会话没有文件路径（拿不到封面）")
        assertEquals(listOf("hello"), LyricsStore.lines.map { it.text })
        assertEquals(1000L, LyricsStore.positionMs, "没有位置回调时用当前行开始时间当提示")
    }

    @Test
    fun `replayed timeline without a song creates a provisional playback`() {
        LyricsStore.clear()

        LyricsStore.onLyricsLinesReplayed(
            listOf(
                apiLine(1000, 2000, "a"),
                apiLine(2000, 3000, "b")
            )
        )

        assertNotNull(LyricsStore.song, "1.19 的完整列表重放也应补出临时会话")
        assertEquals(listOf("a", "b"), LyricsStore.lines.map { it.text })
        assertEquals(LyricsState.READY, LyricsStore.lyricsState)
        assertEquals(LyricsOrigin.ACCUMULATED, LyricsStore.lyricsOrigin)
    }

    @Test
    fun `real song replaces the provisional playback`() {
        LyricsStore.clear()
        LyricsStore.onLyricLineUpdated(apiLine(1000, 2000, "provisional"))
        assertNotNull(LyricsStore.song)

        LyricsStore.onSongChanged(
            PlaybackExtensionPoint.MediaItem(
                "title", "artist", "album", "albumArtist", "C:/Music/test.flac"
            )
        )

        assertEquals("C:/Music/test.flac", LyricsStore.song?.path)
        assertTrue(LyricsStore.lines.isEmpty(), "换到真实歌曲后应清掉临时累加的行")
    }

    @Test
    fun `position advance marks playback as playing but seek does not`() {
        LyricsStore.clear()
        LyricsStore.onIsPlayingChanged(false)

        LyricsStore.onSeekTo(1000)
        assertFalse(LyricsStore.playing, "seek 的位置跳变不能当成正在播放")

        LyricsStore.onPositionUpdated(2000)
        assertTrue(LyricsStore.playing, "位置在推进，说明启动时漏掉的 isPlaying=true 应被补上")
    }

    private fun apiLine(start: Long, end: Long, text: String) =
        PlaybackExtensionPoint.LyricsLine(start, end, emptyList(), text, null)
}
