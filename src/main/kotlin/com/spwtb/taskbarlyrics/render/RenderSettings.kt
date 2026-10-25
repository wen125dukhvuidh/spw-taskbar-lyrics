package com.spwtb.taskbarlyrics.render

/**
 * 渲染设置的**不可变快照**。
 *
 * 每次重绘前取一次快照，避免绘制过程中用户改配置导致半新半旧的状态。
 * 字段语义与命名尽量对齐 TaskbarLyrics（ANYNC）的 AppSettings，
 * 便于对照它的界面与文档。
 */
data class RenderSettings(

    // ── 通用 ─────────────────────────────────────────────────────────────
    /**
     * 总开关。关掉后不创建任何窗口。
     *
     * 界面上不再暴露：不想用时在工坊里停用/卸载插件就行，多一个开关只是多一层
     * 容易忘的中间态。恒为 `true`。
     */
    val enabled: Boolean = true,

    /**
     * true = 嵌进任务栏（默认）；false = 独立置顶浮条。
     *
     * 界面上不再暴露「显示方式」，恒为 `true`；字段留着是为了让渲染器的分支读起来有名字。
     */
    val embed: Boolean = true,

    /** 在任务栏空闲区里的水平锚点。 */
    val anchor: TaskbarAnchor = TaskbarAnchor.CENTER,

    /** 无播放（或没有歌词）时隐藏，不占任务栏。 */
    val autoHideWhenNoPlayback: Boolean = true,

    /**
     * 显隐时是否播放淡入淡出。
     *
     * 时长与位移写死，跟原版 `.lyrics-layer` 一致：300ms、上移 2px、
     * `cubic-bezier(0.2, 0.9, 0.1, 1)`（见 [SurfaceFade]）。
     * 关掉就是直接出现 / 直接消失。
     *
     * 界面上不再暴露开关，恒为 `true`；字段留着是为了让渲染器的分支读起来有名字。
     */
    val animateAutoHide: Boolean = true,

    /** 水平微调，逻辑像素。 */
    val xOffset: Int = 0,

    /** 垂直微调，逻辑像素。 */
    val yOffset: Int = 0,

    // ── 显示 ─────────────────────────────────────────────────────────────
    /** 占空闲区宽度的百分比。当 [fixedWidthDp] 为 0 时生效。 */
    val widthPercent: Int = 100,

    /**
     * 固定窗口宽度（逻辑像素）。0 表示不启用。
     *
     * 对齐原版 TaskbarLyrics 的 `WindowWidth`（默认 420 DIP）——它的窗口宽度是**绝对值**，
     * 不是占满任务栏空闲区。超过空闲区会被截断。
     */
    val fixedWidthDp: Int = 0,

    /** 整体布局缩放百分比（字号、封面、内边距一起缩放）。 */
    val layoutScalePercent: Int = 100,

    /**
     * 第二行内容来源。
     *
     * 默认 [SecondLineMode.AUTO]，与原版配置一致：原版 `ShowLyricTranslation = true`，
     * 有翻译就显示翻译，没有就把下一句当「翻译占位」显示（透明度更低的那一档）。
     */
    val secondLine: SecondLineMode = SecondLineMode.AUTO,

    /**
     * 换行时是否播放滚动过渡。
     *
     * 时长与曲线是写死的，跟原版一致：单行 560ms、翻译对 760ms、
     * `cubic-bezier(0.22, 0.72, 0.24, 1)`（见 [LineTransition]）。
     * 关掉就是原版的 `reducedMotion` 分支——直接换文本，不做动画。
     *
     * 界面上不再暴露开关，恒为 `true`。
     */
    val animateLineChange: Boolean = true,

    /**
     * 行切换动画的帧率。
     *
     * 默认 [AnimationFrameRate.AUTO]：跟着主显示器的刷新率走（DWM 按 vsync 合成，
     * 画得比刷新率更快没有意义，画得比它慢才会掉帧）。原版是 Chromium 合成器，
     * 天然就是这条节奏。
     *
     * 帧预算之外还有一件事更关键：Windows 默认定时器粒度是 15.625ms，
     * `sleep(16)` 会被向上取整到 31.25ms，60fps 直接变 32fps 且抖动剧烈。
     * 动画期间会调 `timeBeginPeriod(1)` 把粒度压到 1ms（见 [Win32] 与 `setHiResTimer`）。
     *
     * 界面上不再暴露选择，恒为 [AnimationFrameRate.AUTO]。
     */
    val animationFrameRate: AnimationFrameRate = AnimationFrameRate.AUTO,

    // ── 排版 ─────────────────────────────────────────────────────────────
    val fontFamily: String = DEFAULT_FONT_FAMILY,

    /** 字号，逻辑像素（96 DPI 下）。 */
    val fontSize: Float = 14f,

    /** 是否加粗。 */
    val bold: Boolean = true,

    /**
     * 文字抗锯齿的 gamma 补偿，百分比（100 = 不补偿）。
     *
     * Java2D 用**线性混合**做文字抗锯齿，而 Chromium / DirectWrite 用 **gamma 校正混合**，
     * 深色背景上的浅色文字后者明显更"实"。以本机 Edge 无头渲染同款 CSS 作基准，
     * 实测同一款字、同一句话（「微带着后悔」）：
     *
     * | 渲染路径 | 归一墨量 | 笔画均值 |
     * | --- | --- | --- |
     * | Chromium 无头（原版渲染路径） | 1194.2 | 5.92px |
     * | Java2D 不补偿（100）          | 1124.2 | 5.53px |
     * | Java2D 120                    | 1165.8 | 5.92px |
     * | Java2D 130（默认）            | 1183.5 | 5.99px |
     * | Java2D 135                    | 1192.3 | 6.03px |
     *
     * 取 130：归一墨量与笔画宽度都落在参考值 1% 以内。
     * 注意口径要一致——上面都是**整行已唱**（0.886）下测的；
     * 拿半行已唱去比整行，未唱档 0.588 会把均值拉下来，得出"细 20%"的假结论。
     */
    val textGammaPercent: Int = 130,

    /** 主行文本对齐。默认左对齐，与原版 `LyricsTextAlignment.Left` 一致。 */
    val textAlign: TextAlign = TextAlign.LEFT,

    /** 逐字扫描高亮；关闭则退化为整行匀速扫描。 */
    val wordScanning: Boolean = true,

    /** 歌词时间偏移，毫秒。负=提前，正=延后。 */
    val lyricOffsetMs: Int = 0,

    // ── 外观 ─────────────────────────────────────────────────────────────
    val foregroundMode: ForegroundMode = ForegroundMode.SYSTEM,

    /** 自定义前景色，#RRGGBB 或 #AARRGGBB。仅 foregroundMode=CUSTOM 时使用。 */
    val foregroundColor: String = "#FFFFFF",

    /**
     * 是否绘制背景底。
     *
     * 默认**关**，与原版 TaskbarLyrics 的 `ShowBackground = false` 一致——
     * 歌词是直接浮在任务栏上的。
     */
    val showBackground: Boolean = false,

    /** 背景不透明度，0..100。 */
    val backgroundOpacity: Int = 55,

    /** 背景颜色。原版用 rgb(18,18,24)。 */
    val backgroundColor: String = "#121218",

    val showBorder: Boolean = false,

    val showTextShadow: Boolean = true,

    /** 背景圆角，逻辑像素。原版 `BaseSurfaceRadius = 8`。 */
    val cornerRadius: Int = 8,

    // ── 封面 ─────────────────────────────────────────────────────────────
    val showCover: Boolean = true,

    /** 封面边长，逻辑像素。 */
    val coverSize: Int = 34,

    /** 封面与歌词的间距，逻辑像素。 */
    val coverGap: Int = 8,

    /**
     * 封面圆角**半径**，逻辑像素。
     *
     * 默认 6 与原版 TaskbarLyrics 的 `DefaultCoverCornerRadius = 6`（DIP）一致，
     * 在 150% 缩放下即 9 物理像素。
     */
    val coverCornerRadius: Int = 6,

    val coverSide: CoverSide = CoverSide.LEFT
) {
    /** 只覆盖字体族名，供探针做字体对照。 */
    fun withFontFamily(family: String): RenderSettings = copy(fontFamily = family)

    /** 只覆盖文字 gamma 补偿，供探针做"补偿前/后"的同帧对照。 */
    fun withTextGamma(percent: Int): RenderSettings = copy(textGammaPercent = percent)

    /** 只覆盖文字阴影开关，供探针量「纯字形」的墨迹质心时排除阴影干扰。 */
    fun withTextShadow(enabled: Boolean): RenderSettings = copy(showTextShadow = enabled)

    /** 只覆盖封面开关，供探针测单帧耗时分解时排除贴图成本。 */
    fun withShowCover(enabled: Boolean): RenderSettings = copy(showCover = enabled)

    /** 只覆盖逐字扫描开关。 */
    fun withWordScanning(enabled: Boolean): RenderSettings = copy(wordScanning = enabled)

    /**
     * 只覆盖「显隐是否放动画」。
     *
     * 关掉之后应当恢复成**直接出现 / 直接消失**，探针用它当对照组：
     * 同一套波形的另一半，能证明 300ms 的等待确实是这段动画带来的，
     * 而不是单纯的停工延迟。
     */
    fun withAnimateAutoHide(enabled: Boolean): RenderSettings = copy(animateAutoHide = enabled)

    /**
     * 影响窗口几何的字段集合。
     * 变化时重算窗口位置与尺寸（Move/Resize），不需要重建窗口。
     */
    fun geometryKey(taskbarHandle: Long, dpi: Int, taskbarHeight: Int): String =
        "$enabled|$embed|$anchor|$widthPercent|$fixedWidthDp|$layoutScalePercent|" +
            "$xOffset|$yOffset|$dpi|$taskbarHeight|$taskbarHandle"

    companion object {
        /**
         * 默认字体。原版 TaskbarLyrics 打包并默认使用「Source Han Sans SC」（思源黑体）。
         * 本机若装有同名或同族字体就直接用，否则 `LyricsPainter` 会按候选顺序回退。
         */
        const val DEFAULT_FONT_FAMILY = "Source Han Sans SC"
    }
}

enum class TaskbarAnchor {
    /** 最左侧的空闲段 */
    LEFT,

    /** 最靠近屏幕中心的空闲段 */
    CENTER,

    /** 最右侧的空闲段 */
    RIGHT
}

enum class SecondLineMode {
    /** 只显示一行 */
    NONE,

    /**
     * 第二行显示下一句歌词。
     *
     * 等价于原版在「整首没有翻译」时的 SINGLE 布局：
     * `.next-line { color: var(--secondary) 0.60; opacity: 0.72 }`。
     */
    NEXT,

    /**
     * 第二行显示翻译。
     *
     * 对应原版的翻译模式（`TRANSLATION_PAIR` 布局）：**只取本句翻译，不回落成下一句**
     * （`app.js`: `visibleSecondary = 翻译模式 ? currentTranslation : next`）。
     * 本句没有翻译时渲染成 `…` 占位，透明度 0.70 × 0.55。
     */
    TRANSLATION,

    /**
     * 自动：完全复刻原版 `translationMode = ShowLyricTranslation && HasTrackTranslation` 的行为。
     *
     * 1. 本句有翻译 → 显示译文
     * 2. 整首有翻译、但本句没有（内嵌 LRC 里通常写成 `[mm:ss.xx]//`）→ 显示 `…` 占位
     * 3. 整首都没有翻译 → 显示下一句
     *
     * 这是默认值。你的音乐库 294 首里 18 首是双语歌词，其余走第 3 条。
     */
    AUTO
}

enum class TextAlign {
    LEFT, CENTER, RIGHT
}

/**
 * 行切换动画的帧率档位。
 *
 * [AUTO] 是默认，也是最接近原版的选择：Chromium 的合成器跟着 vsync 走，
 * 我们照着主显示器刷新率定帧预算，就能拿到和它一样的中间帧数量。
 */
enum class AnimationFrameRate {

    /** 跟随主显示器刷新率（60Hz 屏 = 60fps，560ms 的滚动有 34 帧）。 */
    AUTO,

    /** 30fps。最省电，但滚动只有 17 帧，能看出台阶。 */
    FPS_30,

    /** 固定 60fps。 */
    FPS_60,

    /** 固定 120fps。高刷屏上更顺，代价是过渡期间多烧一点 CPU。 */
    FPS_120;

    /** 解析成具体帧率；[AUTO] 传入实测刷新率。 */
    fun fps(refreshHz: Int): Int = when (this) {
        AUTO -> refreshHz.coerceIn(24, 240)
        FPS_30 -> 30
        FPS_60 -> 60
        FPS_120 -> 120
    }

    companion object {
        fun of(value: String?): AnimationFrameRate = when (value?.trim()?.lowercase()) {
            "30" -> FPS_30
            "60" -> FPS_60
            "120" -> FPS_120
            else -> AUTO
        }
    }
}

enum class ForegroundMode {
    /** 跟随系统明暗主题 */
    SYSTEM,

    /** 深色文字（浅色任务栏） */
    DARK,

    /** 浅色文字（深色任务栏） */
    LIGHT,

    /** 使用自定义颜色 */
    CUSTOM
}

enum class CoverSide {
    LEFT, RIGHT
}
