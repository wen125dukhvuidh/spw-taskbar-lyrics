@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwtb.taskbarlyrics.config

import com.spwtb.taskbarlyrics.render.CoverSide
import com.spwtb.taskbarlyrics.render.ForegroundMode
import com.spwtb.taskbarlyrics.render.RenderSettings
import com.spwtb.taskbarlyrics.render.SecondLineMode
import com.spwtb.taskbarlyrics.render.TaskbarAnchor
import com.spwtb.taskbarlyrics.render.TextAlign
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import java.io.File
import java.util.function.Consumer

/**
 * 插件设置的读取封装。
 *
 * 设置按五组拆分，每组对应 `preference_config.json` 里的一个配置组、
 * 以及插件数据目录下的一个 json 文件。这样配置界面会有清晰的分节，
 * 也避免把几十个键挤在同一个文件里。
 *
 * 取值用 `ConfigHelper.get(key, default)`：**当键缺失或类型不符时返回默认值**，
 * 所以即使界面把类型写错也不会抛异常，最差是回落成默认值。
 */
class PluginSettings private constructor(private val manager: ConfigManager) {

    private val general: ConfigHelper = manager.getConfig(FILE_GENERAL)
    private val display: ConfigHelper = manager.getConfig(FILE_DISPLAY)
    private val typography: ConfigHelper = manager.getConfig(FILE_TYPOGRAPHY)
    private val appearance: ConfigHelper = manager.getConfig(FILE_APPEARANCE)
    private val cover: ConfigHelper = manager.getConfig(FILE_COVER)

    /**
     * 所有数值设置的取值范围，与 `preference_config.json` 的 min/max **逐项一致**。
     *
     * 宿主的 seekbar 是连续浮点，滑一下会得到 13.025173 这种值；它的 JSON schema
     * 里没有 step 字段（官方示例只有 min/max/default_value），改不了它的步进，
     * 所以这里反过来做：值一落盘就把它四舍五入写回自身（见 [snapNumericSettings]），
     * 界面上的数字也就跟着吸附到整数了。
     */
    private val numberKeys: List<NumberKey> by lazy {
        listOf(
            NumberKey(general, KEY_X_OFFSET, MIN_X_OFFSET..MAX_X_OFFSET),
            NumberKey(general, KEY_Y_OFFSET, MIN_Y_OFFSET..MAX_Y_OFFSET),
            NumberKey(display, KEY_WIDTH_PERCENT, MIN_WIDTH_PERCENT..MAX_WIDTH_PERCENT),
            NumberKey(display, KEY_FIXED_WIDTH, 0..MAX_FIXED_WIDTH),
            NumberKey(display, KEY_LAYOUT_SCALE, MIN_LAYOUT_SCALE..MAX_LAYOUT_SCALE),
            NumberKey(typography, KEY_FONT_SIZE, MIN_FONT_SIZE..MAX_FONT_SIZE),
            NumberKey(typography, KEY_TEXT_GAMMA, MIN_TEXT_GAMMA..MAX_TEXT_GAMMA),
            NumberKey(typography, KEY_LYRIC_OFFSET, MIN_LYRIC_OFFSET..MAX_LYRIC_OFFSET),
            NumberKey(appearance, KEY_BG_OPACITY, 0..100),
            NumberKey(appearance, KEY_CORNER, 0..MAX_CORNER),
            NumberKey(cover, KEY_COVER_SIZE, MIN_COVER_SIZE..MAX_COVER_SIZE),
            NumberKey(cover, KEY_COVER_GAP, 0..MAX_COVER_GAP),
            NumberKey(cover, KEY_COVER_CORNER, 0..MAX_COVER_CORNER)
        )
    }

    /** 一次数值键的定义：[helper] 是它所在的分组，[range] 取自配置界面的 min/max。 */
    private class NumberKey(
        val helper: ConfigHelper,
        val key: String,
        val range: IntRange
    )

    /** 插件数据目录（日志等放这里）。五组配置在同一目录，取任意一个都行。 */
    val dataDir: File
        get() {
            // 注意：getConfigPath() 返回 java.nio.file.Path，不是 String；
            // 而且 ConfigHelper 是 Kotlin 接口，必须显式调用方法，不能当属性访问
            val parent = general.getConfigPath().parent
            return parent?.toFile()
                ?: File(System.getProperty("java.io.tmpdir"), "spw-taskbar-lyrics")
        }

    /**
     * 直接从 `typography.json` 读 `bold`。
     *
     * 宿主的 `ConfigHelper.reload()` 不会刷新插件侧实例里的嵌套值：文件已经写成
     * `false`，`get("typography.bold")` 仍返回旧值——表现就是改加粗必须重启客户端
     * （重启时新建的 ConfigManager 在构造阶段重新读文件）。文件本身是实时写盘的，
     * 所以这一个布尔值直接读文件，绕过那层陈旧缓存。
     *
     * 解析失败（文件正在写、格式异常）返回 null，调用方回退到 helper 的旧值；
     * 下一个 250ms 周期会再试一次。
     */
    private fun boldFromDisk(): Boolean? = try {
        val text = java.nio.file.Files.readString(typography.getConfigPath())
        BOLD_PATTERN.find(text)?.groupValues?.get(1)?.trim('"')?.toBooleanStrictOrNull()
    } catch (_: Throwable) {
        null
    }

    /** 取一份渲染设置快照。每次重绘前取一次，避免绘制中配置变化导致状态撕裂。 */
    fun snapshot(): RenderSettings = RenderSettings(
        // 总开关不再作为设置暴露：不想用时在工坊里停用/卸载插件即可，
        // 再放一个"启用"开关只是多一层容易忘的中间态。恒为 true。
        enabled = true,
        // 显示方式不再作为设置暴露，恒为嵌入任务栏。这里**不读** general.mode：
        // 老配置里存着 floating 的用户升级后也一并回到嵌入，否则界面上没有开关、
        // 行为却还是浮动条，用户没法改回来。
        embed = true,
        anchor = enumOf(general.get(KEY_ANCHOR, DEFAULT_ANCHOR), TaskbarAnchor.CENTER),
        autoHideWhenNoPlayback = general.get(KEY_AUTO_HIDE, true),
        // 淡入淡出 / 换行滚动 / 动画帧率不再作为开关暴露给用户，固定用默认值（开 / 自动）。
        // 不删 RenderSettings 里对应的字段：渲染器还按它分支，留常量比散落魔法值清楚。
        // 数值项一律先取整再夹范围：界面写进来可能是 13.025173，
        // 直接用的话字号、圆角、偏移量都会带着一串没意义的小数
        xOffset = int(general, KEY_X_OFFSET, 0, MIN_X_OFFSET..MAX_X_OFFSET),
        yOffset = int(general, KEY_Y_OFFSET, 0, MIN_Y_OFFSET..MAX_Y_OFFSET),

        widthPercent = int(display, KEY_WIDTH_PERCENT, 100, MIN_WIDTH_PERCENT..MAX_WIDTH_PERCENT),
        fixedWidthDp = int(display, KEY_FIXED_WIDTH, 0, 0..MAX_FIXED_WIDTH),
        layoutScalePercent = int(display, KEY_LAYOUT_SCALE, 100, MIN_LAYOUT_SCALE..MAX_LAYOUT_SCALE),
        secondLine = enumOf(display.get(KEY_SECOND_LINE, "auto"), SecondLineMode.AUTO),

        fontFamily = typography.get(KEY_FONT_FAMILY, RenderSettings.DEFAULT_FONT_FAMILY)
            .takeIf { it.isNotBlank() } ?: RenderSettings.DEFAULT_FONT_FAMILY,
        fontSize = int(typography, KEY_FONT_SIZE, 14, MIN_FONT_SIZE..MAX_FONT_SIZE).toFloat(),
        bold = boldFromDisk() ?: typography.get(KEY_BOLD, true),
        // 文字抗锯齿的 gamma 补偿：Java2D 的线性混合比 Chromium 的 gamma 校正偏细，
        // 130 是与 Chromium 实测对齐的值（见 RenderSettings.textGammaPercent 的说明）
        textGammaPercent = int(typography, KEY_TEXT_GAMMA, 130, MIN_TEXT_GAMMA..MAX_TEXT_GAMMA),
        textAlign = enumOf(typography.get(KEY_ALIGN, "left"), TextAlign.LEFT),
        wordScanning = typography.get(KEY_WORD_SCAN, true),
        lyricOffsetMs = int(typography, KEY_LYRIC_OFFSET, 0, MIN_LYRIC_OFFSET..MAX_LYRIC_OFFSET),

        foregroundMode = enumOf(appearance.get(KEY_FG_MODE, "system"), ForegroundMode.SYSTEM),
        foregroundColor = appearance.get(KEY_FG_COLOR, "#FFFFFF"),
        // 下面这几个默认值必须与 preference_config.json 的 default_value **一致**，
        // 否则用户没打开过配置页时，落到这里的默认值会和界面上显示的不一样：
        // get() 在键不存在时返回这里的默认值，于是"界面上关着、实际开着"。
        // showBackground 原来写的是 true（原版是 false），会凭空多画一个背景胶囊。
        showBackground = appearance.get(KEY_SHOW_BG, false),
        backgroundOpacity = int(appearance, KEY_BG_OPACITY, 55, 0..100),
        backgroundColor = appearance.get(KEY_BG_COLOR, "#121218"),
        showBorder = appearance.get(KEY_SHOW_BORDER, false),
        showTextShadow = appearance.get(KEY_SHADOW, true),
        cornerRadius = int(appearance, KEY_CORNER, 8, 0..MAX_CORNER),

        showCover = cover.get(KEY_SHOW_COVER, true),
        coverSize = int(cover, KEY_COVER_SIZE, 34, MIN_COVER_SIZE..MAX_COVER_SIZE),
        coverGap = int(cover, KEY_COVER_GAP, 8, 0..MAX_COVER_GAP),
        coverCornerRadius = int(cover, KEY_COVER_CORNER, 6, 0..MAX_COVER_CORNER),
        coverSide = enumOf(cover.get(KEY_COVER_SIDE, "left"), CoverSide.LEFT)
    )

    /**
     * 把所有数值设置吸附到整数，必要时写回宿主。
     *
     * 宿主的 seekbar 拖出来是浮点（实测存成 `"fontSize": 13.025173`），
     * 界面也就跟着显示一长串没意义的小数。这里在每次配置变更后把它抹平：
     * 先 [round] 再按 min/max 夹一下，写回同一个键。宿主的监听器会收到自己的写入，
     * 但那时值已经是整数了，第二轮直接跳过，不会来回抖。
     *
     * @return 实际写回了几个键（正常是 0：只在用户刚拖过滑块时才不是 0）
     */
    @Volatile
    private var snapping = false

    @Synchronized
    fun snapNumericSettings(): Int {
        // 写回会触发自己的监听器，监听器又会回调到这里（@Synchronized 是可重入的，
        // 挡不住自己）。认出一轮就够：嵌套的那次直接返回，让外层把整轮走完。
        if (snapping) {
            return 0
        }
        snapping = true
        var written = 0
        try {
            for (key in numberKeys) {
                if (snap(key)) {
                    written++
                }
            }
        } finally {
            snapping = false
        }
        return written
    }

    /** 吸附单个键；返回是否发生了写入。 */
    private fun snap(key: NumberKey): Boolean {        // NaN 当哨兵：键不存在、或值的类型不是数字时 get 都会返回这个默认值，
        // 两种情况都不能写——写了等于凭空造出一个键
        val raw = key.helper.get(key.key, Float.NaN)
        if (raw.isNaN()) {
            return false
        }
        val snapped = kotlin.math.round(raw).toInt().coerceIn(key.range)
        // 已经是整数就千万别写：save() 会再触发一次配置监听，来回绕
        if (kotlin.math.abs(raw - snapped) < EPS) {
            return false
        }
        writeNumber(key.helper, key.key, snapped)
        return true
    }

    /**
     * 写回一个整数值。
     *
     * 优先按 **整数** 写，这样界面上显示的是 `130` 而不是 `130.0`；
     * 写完立刻读回来核对——如果宿主从头到尾只认浮点（`get` 会返回默认值），
     * 就退一步按浮点再写一次，保证读到的值一定是对的。
     */
    private fun writeNumber(helper: ConfigHelper, key: String, value: Int) {
        val ok = write(helper, key, value) && helper.get(key, Float.NaN) == value.toFloat()
        if (!ok) {
            write(helper, key, value.toFloat())
        }
    }

    private fun write(helper: ConfigHelper, key: String, value: Any): Boolean = try {
        helper.set(key, value)
        helper.save()
        true
    } catch (t: Throwable) {
        // 写不回去不影响功能：读取侧本来就会取整，最坏是界面上继续显示小数
        false
    }

    /**
     * 老配置迁移：显示位置以前有四档（多了一个"最宽的一段" = `widest`），
     * 现在只剩 左侧 / 居中 / 右侧。
     *
     * 不改的话，列表控件在 `entry_values` 里找不到 `widest`，那一行会显示成空白。
     * 启动时改写成 `center` 并落盘一次，之后就是空操作。
     *
     * @return 是否发生了迁移
     */
    fun migrateLegacyAnchor(): Boolean {
        if (general.get(KEY_ANCHOR, DEFAULT_ANCHOR) != LEGACY_ANCHOR_WIDEST) {
            return false
        }
        return write(general, KEY_ANCHOR, DEFAULT_ANCHOR)
    }

    /**
     * 把所有设置写回默认值，供设置界面的「还原默认设置」按钮调用。
     *
     * 默认值取自 [RenderSettings] 的默认构造实例——那是代码侧的唯一出处，
     * `preference_config.json` 的 `default_value` 按约定与它逐项一致（见 README），
     * 所以这里**不再手抄一份表**，改了默认值也不会漏。
     *
     * 写法是「一个文件的键全部 set 完，再 save 一次」：逐键 save 会写盘二十多次，
     * 而且宿主每次 save 都会触发一轮配置变更回调。
     *
     * @return true 表示全部写成功
     */
    fun resetToDefaults(): Boolean {
        val d = RenderSettings()
        val groups: List<Pair<ConfigHelper, List<Pair<String, Any>>>> = listOf(
            general to listOf(
                KEY_ANCHOR to d.anchor.name.lowercase(),
                KEY_AUTO_HIDE to d.autoHideWhenNoPlayback,
                KEY_X_OFFSET to d.xOffset,
                KEY_Y_OFFSET to d.yOffset
            ),
            display to listOf(
                KEY_WIDTH_PERCENT to d.widthPercent,
                KEY_FIXED_WIDTH to d.fixedWidthDp,
                KEY_LAYOUT_SCALE to d.layoutScalePercent,
                KEY_SECOND_LINE to d.secondLine.name.lowercase()
            ),
            typography to listOf(
                KEY_FONT_FAMILY to d.fontFamily,
                KEY_FONT_SIZE to d.fontSize,
                KEY_BOLD to d.bold,
                KEY_TEXT_GAMMA to d.textGammaPercent,
                KEY_ALIGN to d.textAlign.name.lowercase(),
                KEY_WORD_SCAN to d.wordScanning,
                KEY_LYRIC_OFFSET to d.lyricOffsetMs
            ),
            appearance to listOf(
                KEY_FG_MODE to d.foregroundMode.name.lowercase(),
                KEY_FG_COLOR to d.foregroundColor,
                KEY_SHOW_BG to d.showBackground,
                KEY_BG_OPACITY to d.backgroundOpacity,
                KEY_BG_COLOR to d.backgroundColor,
                KEY_SHOW_BORDER to d.showBorder,
                KEY_SHADOW to d.showTextShadow,
                KEY_CORNER to d.cornerRadius
            ),
            cover to listOf(
                KEY_SHOW_COVER to d.showCover,
                KEY_COVER_SIZE to d.coverSize,
                KEY_COVER_GAP to d.coverGap,
                KEY_COVER_CORNER to d.coverCornerRadius,
                KEY_COVER_SIDE to d.coverSide.name.lowercase()
            )
        )
        var ok = true
        for ((helper, pairs) in groups) {
            for ((key, value) in pairs) {
                try {
                    helper.set(key, value)
                } catch (t: Throwable) {
                    ok = false
                }
            }
            ok = persist(helper) && ok
        }
        return ok
    }

    /** 只落盘、不改值；失败不抛，交给调用方汇总。 */
    private fun persist(helper: ConfigHelper): Boolean = try {
        helper.save()
    } catch (t: Throwable) {
        false
    }

    // ── 变更兜底 ────────────────────────────────────────────────────────

    /** 五个配置文件上次看到的修改时间（按 helper 身份区分，避免用哈希冲突）。 */
    private val lastStamps = java.util.IdentityHashMap<ConfigHelper, Long>()

    /** 刚写过的文件先等一会儿再读，避免读到写了一半的 JSON。 */
    private val freshWriteGuardMs = 60L

    /**
     * 文件时间戳变了就 `reload()`，**不依赖宿主的变更通知**。
     *
     * 宿主的 `VoxzenConfigManager` 确实带 WatchService，但它只在「该文件名有人注册了
     * 监听器」时才 reload，而且通知本身也可能丢（OVERFLOW、目录被重建、监听没起来）。
     * 一旦漏一次，插件的内存树就一直是旧的——表现就是**改了设置要重启客户端才生效**。
     *
     * 渲染线程每 250ms 取一次设置，这里顺带 stat 五个小文件（几十字节），成本可以忽略。
     *
     * @return 是否真的重载过（调用方据此让设置缓存失效）
     */
    fun reloadIfFilesChanged(): Boolean {
        var reloaded = false
        for (helper in listOf(general, display, typography, appearance, cover)) {
            val path = helper.getConfigPath()
            val mtime = try {
                java.nio.file.Files.getLastModifiedTime(path).toMillis()
            } catch (t: Throwable) {
                continue
            }
            val last = lastStamps.put(helper, mtime)
            if (last == null) {
                // 第一次见到这个文件：也重载一次。构造时那次 reload 可能早于文件写完
                // （插件启动瞬间正好有人在写配置），白白丢一次变更。
                if (helper.reload()) {
                    reloaded = true
                }
                continue
            }
            if (last == mtime) {
                continue
            }
            if (System.currentTimeMillis() - mtime < freshWriteGuardMs) {
                // 可能正在写。把旧时间戳放回去，下一轮再判断
                if (last != null) lastStamps[helper] = last
                continue
            }
            if (helper.reload()) {
                reloaded = true
            }
        }
        return reloaded
    }

    /**
     * 几项最能看出差别的默认值，用于「还原默认设置」后的提示。
     *
     * 宿主的配置页在打开时读一次值、之后**不订阅配置变更**（`ModConfigViewModel`
     * 没有注册 `addConfigChangeListener`），所以插件写盘后当前页面仍显示旧值。
     * 提示里直接报出这几个数字，用户即使看不到控件变化也知道确实写进去了。
     */
    fun defaultsSummary(): String = RenderSettings().let {
        "宽度 ${it.widthPercent}% · 字号 ${it.fontSize.toInt()} · 笔画补偿 ${it.textGammaPercent}"
    }

    /**
     * 监听五组配置文件的改动。
     *
     * 必须把返回的监听器**留着**，停用时交给 [stopObserving]，
     * 否则每次插件启停都会重复注册。
     */
    fun observe(onChange: () -> Unit): List<Consumer<ConfigHelper>> {
        val files = listOf(FILE_GENERAL, FILE_DISPLAY, FILE_TYPOGRAPHY, FILE_APPEARANCE, FILE_COVER)
        return files.map { file ->
            // 每个文件一个独立实例：removeConfigChangeListener 按实例匹配
            val listener = Consumer<ConfigHelper> { onChange() }
            manager.addConfigChangeListener(file, listener)
            listener
        }
    }

    fun stopObserving(listeners: List<Consumer<ConfigHelper>>) {
        listeners.forEach { manager.removeConfigChangeListener(it) }
    }

    /** 描述当前设置，供「显示状态」按钮使用。 */
    fun describe(): String = snapshot().let {
        // 显示方式已不再是设置，这里不再报「模式=」
        "位置=${it.anchor.name.lowercase()}，" +
            "字号=${it.fontSize.toInt()}，" +
            "背景=${if (it.showBackground) "${it.backgroundOpacity}%" else "无"}，" +
            "封面=${if (it.showCover) "开" else "关"}"
    }

    /** 取数值设置：四舍五入到整数，再夹进界面上写的 min/max 之间。 */
    private fun int(helper: ConfigHelper, key: String, default: Int, range: IntRange): Int =
        kotlin.math.round(helper.get(key, default.toFloat())).toInt().coerceIn(range)

    private inline fun <reified T : Enum<T>> enumOf(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: fallback

    companion object {
        const val FILE_GENERAL = "general.json"
        const val FILE_DISPLAY = "display.json"
        const val FILE_TYPOGRAPHY = "typography.json"
        const val FILE_APPEARANCE = "appearance.json"
        const val FILE_COVER = "cover.json"

        private const val KEY_ANCHOR = "general.anchor"

        /** 显示位置的默认值（与 preference_config.json 的 default_value 保持一致）。 */
        private const val DEFAULT_ANCHOR = "center"

        /** 旧版本的第四档取值，已从界面上撤掉，只用于迁移。 */
        private const val LEGACY_ANCHOR_WIDEST = "widest"
        private const val KEY_AUTO_HIDE = "general.autoHideWhenNoPlayback"
        private const val KEY_X_OFFSET = "general.xOffset"
        private const val KEY_Y_OFFSET = "general.yOffset"

        private const val KEY_WIDTH_PERCENT = "display.widthPercent"
        private const val KEY_FIXED_WIDTH = "display.fixedWidth"
        private const val KEY_LAYOUT_SCALE = "display.layoutScalePercent"
        private const val KEY_SECOND_LINE = "display.secondLine"

        private const val KEY_FONT_FAMILY = "typography.fontFamily"
        private const val KEY_FONT_SIZE = "typography.fontSize"
        private const val KEY_BOLD = "typography.bold"
        private const val KEY_TEXT_GAMMA = "typography.textGammaPercent"
        private const val KEY_ALIGN = "typography.textAlign"
        private const val KEY_WORD_SCAN = "typography.wordScanning"
        private const val KEY_LYRIC_OFFSET = "typography.lyricOffsetMs"

        private const val KEY_FG_MODE = "appearance.foregroundMode"
        private const val KEY_FG_COLOR = "appearance.foregroundColor"
        private const val KEY_SHOW_BG = "appearance.showBackground"
        private const val KEY_BG_OPACITY = "appearance.backgroundOpacity"
        private const val KEY_BG_COLOR = "appearance.backgroundColor"
        private const val KEY_SHOW_BORDER = "appearance.showBorder"
        private const val KEY_SHADOW = "appearance.showTextShadow"
        private const val KEY_CORNER = "appearance.cornerRadius"

        private const val KEY_SHOW_COVER = "cover.show"
        private const val KEY_COVER_SIZE = "cover.size"
        private const val KEY_COVER_GAP = "cover.gap"
        private const val KEY_COVER_CORNER = "cover.cornerRadius"
        private const val KEY_COVER_SIDE = "cover.position"

        // 取值范围必须与 preference_config.json 的 min/max 一致：
        // 界面夹到哪，这里就得夹到哪，否则会出现"拖到底又被拉回来"的错位
        private const val MIN_X_OFFSET = -400
        private const val MAX_X_OFFSET = 400
        private const val MIN_Y_OFFSET = -200
        private const val MAX_Y_OFFSET = 200
        private const val MIN_WIDTH_PERCENT = 20
        private const val MAX_WIDTH_PERCENT = 100
        private const val MAX_FIXED_WIDTH = 1400
        private const val MIN_LAYOUT_SCALE = 25
        private const val MAX_LAYOUT_SCALE = 300
        private const val MIN_FONT_SIZE = 6
        private const val MAX_FONT_SIZE = 96
        private const val MIN_TEXT_GAMMA = 100
        private const val MAX_TEXT_GAMMA = 260
        private const val MIN_LYRIC_OFFSET = -5000
        private const val MAX_LYRIC_OFFSET = 5000
        private const val MAX_CORNER = 40
        private const val MIN_COVER_SIZE = 12
        private const val MAX_COVER_SIZE = 200
        private const val MAX_COVER_GAP = 240
        private const val MAX_COVER_CORNER = 60

        /** 判定"已经是整数"的容差：浮点比较必须留余量，否则会在整数字上反复写。 */
        private const val EPS = 0.0005f

        /**
         * typography.json 的结构是 `{"typography":{...,"bold":true,...}}`。
         * 只抓这一个键；兼容带引号的 `"true"` / `"false"`。
         */
        private val BOLD_PATTERN = Regex("\"bold\"\\s*:\\s*(\"?(?:true|false)\"?)")

        /** 必须在插件运行期调用：它会向宿主识别当前插件。 */
        fun load(): PluginSettings =
            PluginSettings(WorkshopApi.manager.createConfigManager())
    }
}
