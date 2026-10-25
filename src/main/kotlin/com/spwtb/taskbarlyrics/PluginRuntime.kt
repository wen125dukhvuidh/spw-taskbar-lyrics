@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwtb.taskbarlyrics

import com.spwtb.taskbarlyrics.config.PluginSettings
import com.spwtb.taskbarlyrics.lyrics.CoverArt
import com.spwtb.taskbarlyrics.lyrics.LyricsStore
import com.spwtb.taskbarlyrics.render.RenderSettings
import com.spwtb.taskbarlyrics.render.TaskbarRenderer
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.util.function.Consumer

/**
 * 插件运行期状态。
 *
 * 配置界面里的按钮是静态入口（`on_click` 要求公开静态无参方法），拿不到插件实例，
 * 所以这里用单例把运行时状态暴露出去。
 *
 * 渲染完全在插件进程内完成，**不需要任何外部程序**，因此这里没有子进程管理，
 * 只有渲染线程的启停与配置监听。
 */
object PluginRuntime {

    private val renderer = TaskbarRenderer { currentSettings() }

    @Volatile
    private var settings: PluginSettings? = null

    @Volatile
    private var listeners: List<Consumer<ConfigHelper>> = emptyList()

    @Volatile
    private var cachedSettings: RenderSettings = RenderSettings()

    @Volatile
    private var cacheStamp: Long = 0L

    @Volatile
    private var pluginVersion: String = "unknown"

    fun attach(settings: PluginSettings, pluginVersion: String) {
        this.settings = settings
        this.pluginVersion = pluginVersion
    }

    fun detach() {
        settings = null
        cachedSettings = RenderSettings()
        cacheStamp = 0L
    }

    /** 返回是否成功启动。 */
    fun start(notify: Boolean): Boolean {
        val current = settings ?: return false

        // ⚠️ 这里**不要**调 Win32.isAvailable()。
        //
        // PF4J 的插件启动是同步的，start() 就跑在宿主的插件加载线程（也就是 UI 线程）
        // 上；而 Win32 的静态初始化要解压并加载 JNA 原生库——本机实测冷启动 196ms。
        // 宿主刚打开时自己也在加载类、读曲库，这 196ms 就叠在最前面，
        // 表现就是"每次打开 SFW 都要卡一下"。
        //
        // 原生接口检查已经挪到渲染线程（见 TaskbarRenderer.loop 的第一行），
        // 失败原因照样能在设置里的"显示当前状态"看到。

        // 注册配置监听：改设置后立刻让缓存失效，下一帧就用新值，不必等轮询
        if (listeners.isEmpty()) {
            listeners = current.observe {
                cacheStamp = 0L
                // 界面的滑块是连续浮点，拖动会存下 13.025173 这种值；
                // 趁它刚落盘把整数写回去，界面上的数字也跟着变成整数
                current.snapNumericSettings()
            }
        }

        // 启动时先扫一遍：把上一版插件留下的小数值一次性抹平
        current.snapNumericSettings()

        // 显示位置从四档收成三档，老配置里的 "widest" 要改写成 "center"，
        // 否则设置界面的下拉框找不到对应项、那一行是空的
        current.migrateLegacyAnchor()

        refreshSettings(current)

        // 插件可能被反复启停：停用时解析线程会被关掉，启用时再拉起来。
        LyricsStore.start()

        if (!cachedSettings.enabled) {
            if (notify) {
                WorkshopApi.ui.toast("任务栏歌词：已在设置中关闭", WorkshopApi.Ui.ToastType.Warning)
            }
            return false
        }

        renderer.start()
        if (notify) {
            WorkshopApi.ui.toast("任务栏歌词已启动", WorkshopApi.Ui.ToastType.Success)
        }
        return true
    }

    /**
     * 强制重建任务栏窗口，用于排查显示异常。
     *
     * 这个入口是设置界面里的按钮，同样跑在宿主 UI 线程上，而 stop() 里要 join
     * 渲染线程（最长 STOP_TIMEOUT_MS）。把 stop+start 丢到一个短命线程上做，
     * 宿主线程立刻返回——否则点一下"重启任务栏歌词"，界面就僵在那。
     * 顺序执行是必须的：start() 复用同一个 running / generation 标志，必须先等旧线程退出。
     */
    fun restart(): Boolean {
        if (settings == null) {
            return false
        }
        cacheStamp = 0L
        Thread({ renderer.restart() }, "spw-taskbar-lyrics-restart").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * 把所有设置写回默认值（设置界面「还原默认设置」按钮的入口）。
     *
     * 写盘是同步做的——五组配置都是几十字节的小文件，而且用户点完就等着看变化。
     * 重建窗口照 [restart] 的做法丢到短命线程上：reset 之后几何缓存、颜色缓存、
     * 字体缓存全都要按新值重算，直接 restart 一次最省心。
     *
     * @return true 表示全部写成功
     */
    fun resetSettings(): Boolean {
        val current = settings ?: return false
        val ok = current.resetToDefaults()
        restart()
        return ok
    }

    /** 还原后要报给用户看的几个默认值。 */
    fun defaultsSummary(): String = settings?.defaultsSummary() ?: ""

    fun stop() {
        renderer.stop()
        val current = settings
        if (current != null && listeners.isNotEmpty()) {
            current.stopObserving(listeners)
        }
        listeners = emptyList()
        LyricsStore.clear()
        CoverArt.clear()
    }

    /** 歌词或播放状态刚变过，让渲染线程立刻画一帧（换行动画的起始延迟全靠它）。 */
    fun wake() {
        renderer.wake()
    }

    fun describe(): String {
        val current = settings ?: return "插件未启用"
        val base = "${renderer.status()}；设置：${current.describe()}"
        val err = renderer.error()
        return if (err == null) base else "$base；错误：$err"
    }

    // ── 设置缓存 ────────────────────────────────────────────────────────

    /**
     * 渲染线程每帧都会取一次设置。直连配置系统 30 次/秒没必要，
     * 这里做 250ms 的缓存；配置监听器会主动把缓存打失效，所以响应依然是即时的。
     *
     * 另外每 250ms 顺手比对一次五个配置文件的修改时间：宿主偶尔会漏发变更通知
     * （见 [PluginSettings.reloadIfFilesChanged]），漏一次就会"改了设置要重启才生效"。
     */
    private fun currentSettings(): RenderSettings {
        val current = settings ?: return cachedSettings
        val now = System.currentTimeMillis()
        if (now - cacheStamp <= CACHE_TTL_MS) {
            return cachedSettings
        }

        // 只有缓存过期时才比对文件时间戳。渲染线程每帧至少取一次设置，
        // 写在真循环外的 stat 就会变成每帧 5 次系统调用（30fps 下约 150 次/秒），
        // 而 250ms 的节奏完全够用——配置监听器还会让缓存立即失效。
        current.reloadIfFilesChanged()
        cachedSettings = current.snapshot()
        cacheStamp = now
        return cachedSettings
    }

    private fun refreshSettings(source: PluginSettings) {
        cachedSettings = source.snapshot()
        cacheStamp = System.currentTimeMillis()
    }

    private const val CACHE_TTL_MS = 250L
}
