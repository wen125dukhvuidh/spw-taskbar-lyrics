package com.spwtb.taskbarlyrics.playback

import com.spwtb.taskbarlyrics.PluginRuntime
import com.spwtb.taskbarlyrics.lyrics.LyricsStore
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

/**
 * 播放扩展点：把 SPW 的播放事件搬进 [LyricsStore]。
 *
 * 这里只覆写 SPW 1.18.x 真实存在的方法（已用 dev20 的 API jar 逐个核对过）。
 * 特别地，**不要**覆写 `onLyricsLinesUpdated`——那要 SPW 1.19.0，现役版本没有。
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

    /** 当前歌词行变化。SPW 1.18.x 上这是唯一的歌词数据来源，需要自行累加。 */
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

    override fun onSeekTo(position: Long) {
        // 跳到新位置后立刻刷新，避免渲染器在下一次每秒回调前显示旧行
        LyricsStore.onPositionUpdated(position)
        PluginRuntime.wake()
    }
}
