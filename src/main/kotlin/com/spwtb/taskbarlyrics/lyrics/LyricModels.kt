package com.spwtb.taskbarlyrics.lyrics

/**
 * 歌词数据模型。
 *
 * 时间统一为毫秒、绝对时间；渲染器侧再换算成它自己的相对偏移
 * （ANYNC 的 LyricSyllable 用相对偏移）。
 */
data class LyricSong(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val path: String
)

/** 逐字单元。没有真实字级数据时由 [SyllableSynthesizer] 合成。 */
data class LyricCell(val startMs: Long, val endMs: Long, val text: String)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String?,
    val cells: List<LyricCell>
)

enum class LyricsState {
    /** 正在加载或刚换曲——渲染器应继续等待，不要判定为无歌词 */
    PENDING,

    /** 已有可用时间轴 */
    READY,

    /** 确认没有可用歌词 */
    NONE
}

/** 歌词来源，便于排查「任务栏和播放器显示不一致」这类问题 */
enum class LyricsOrigin {
    /** 从音频文件内嵌标签解析（完整时间轴） */
    EMBEDDED,

    /** 从同目录 .lrc 解析（完整时间轴） */
    SIDECAR,

    /** 由播放扩展点逐行累加（可能不完整） */
    ACCUMULATED,

    NONE
}
