package com.spwtb.taskbarlyrics.playback

import com.spwtb.taskbarlyrics.PluginRuntime
import com.spwtb.taskbarlyrics.lyrics.LyricsStore
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

/**
 * 播放扩展点：把 SPW 的播放事件搬进 [LyricsStore]。
 *
 * 编译目标是官方 dev22（SPW 1.19.x API）。1.19+ 的 `onLyricsLinesUpdated`
 * 会在插件启动或重新启用后异步重放当前完整歌词——这正是首曲在扩展注册前
 * 加载完时的补偿入口。
 *
 * 官方约束：
 * - `onBeforeLoadLyrics` / `onAfterLoadLyrics` 在 IO 线程执行，只做状态写入，不做耗时操作。
 * - `onPositionUpdated` 每秒才回调一次，只用于对齐，实际进度以渲染器时间轴为准。
 */
@Extension
class SpwPlaybackExtension : PlaybackExtensionPoint {

    /**
     * 换曲时机。返回 null 表示沿用 SPW 默认的歌词加载逻辑
     * （本插件不向 SPW 提供歌词，只是搭便车取歌曲身份）。
     */
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        LyricsStore.onSongChanged(mediaItem)
        return null
    }

    /** 当前歌词行变化。1.18.x 上是唯一数据源；1.19+ 还有完整列表重放。 */
    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        LyricsStore.onLyricLineUpdated(lyricsLine)
        // 收到回调说明歌词加载流程已经跑起来了，顺手做一次「确实没有歌词」的判定
        LyricsStore.onSongSettled()
        // 换行了：叫醒渲染线程立刻起滚动动画。不叫的话最坏要等一整拍（33ms）
        // 才被发现，动画就成了"先顿一下再动"。
        PluginRuntime.wake()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        LyricsStore.onIsPlayingChanged(isPlaying)
        PluginRuntime.wake()
    }

    override fun onPositionUpdated(position: Long) {
        LyricsStore.onPositionUpdated(position)
        LyricsStore.onSongSettled()
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        if (state == PlaybackExtensionPoint.State.Idle) {
            LyricsStore.onIsPlayingChanged(false)
        }
    }

    /**
     * SPW 1.19+ 的完整歌词重放。首曲在扩展注册前就加载完时，靠它补回来。
     */
    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        LyricsStore.onLyricsLinesReplayed(lyricsLines)
        PluginRuntime.wake()
    }

    override fun onSeekTo(position: Long) {
        // 跳到新位置后立刻刷新，避免渲染器在下一次每秒回调前显示旧行。
        // 走 onSeekTo 而不是 onPositionUpdated：seek 不能被当成「正在播放」的证据。
        LyricsStore.onSeekTo(position)
        PluginRuntime.wake()
    }
}
