package com.spwtb.taskbarlyrics.render

import com.spwtb.taskbarlyrics.lyrics.LyricLine
import com.spwtb.taskbarlyrics.lyrics.LyricSong
import com.spwtb.taskbarlyrics.lyrics.LyricsState
import java.awt.image.BufferedImage

internal data class FrameUpdate(val frame: LyricsFrame?, val key: String?)

/** 在歌词前奏、加载及无歌词期间保留歌曲信息；仅暂停隐藏策略能隐藏整块画面。 */
internal object FrameComposer {
    fun compose(
        song: LyricSong?, lines: List<LyricLine>, hasTranslation: Boolean,
        state: LyricsState, playing: Boolean, position: Long, settings: RenderSettings,
        coverProvider: () -> BufferedImage?
    ): FrameUpdate {
        if (song == null || (settings.autoHideWhenNoPlayback && !playing)) {
            return FrameUpdate(null, null)
        }
        val selection = LyricLineSelector.pick(lines, position, settings.secondLine, hasTranslation)
        val cover = if (settings.showCover) coverProvider() else null
        if (selection == null) {
            val phase = when {
                lines.isNotEmpty() && position < lines.first().startMs -> "暂无歌词"
                lines.isNotEmpty() -> "间奏"
                state == LyricsState.PENDING -> "歌词加载中"
                else -> "暂无歌词"
            }
            val title = song.title.takeIf { it.isNotBlank() }
                ?: song.path.substringAfterLast('/').substringAfterLast('\\')
                    .substringBeforeLast('.').takeIf { it.isNotBlank() }
                ?: "正在播放"
            val detail = if (settings.secondLine == SecondLineMode.NONE) null else
                listOf(song.artist, phase).filter { it.isNotBlank() }.joinToString(" · ")
            val frame = LyricsFrame(title, detail, cover, emptyList(), 0L, 0L, 0L)
            return FrameUpdate(frame, "${song.key}#info#$phase#$title")
        }
        val line = lines[selection.index]
        return FrameUpdate(
            LyricsFrame(selection.mainText, selection.secondText, cover, line.cells,
                position, line.startMs, line.endMs, selection.secondKind),
            "${song.key}#${selection.index}#${selection.mainText}"
        )
    }
}