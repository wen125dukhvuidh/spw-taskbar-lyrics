package com.spwtb.taskbarlyrics.render

import com.spwtb.taskbarlyrics.lyrics.LyricCell
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 第二行是「哪种」内容——原版对这三种用了不同的透明度。 */
enum class SecondLineKind {
    /** 翻译行：foreground × 0.70 */
    TRANSLATION,

    /** 下一句歌词：foreground × 0.60 × 0.72 */
    NEXT,

    /** 没有翻译时把下一句当占位显示：foreground × 0.70 × 0.55 */
    PLACEHOLDER
}

/** 一帧要画的内容。 */
data class LyricsFrame(
    val mainText: String,
    val secondText: String?,
    val cover: BufferedImage?,
    val cells: List<LyricCell>,
    val positionMs: Long,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val secondKind: SecondLineKind = SecondLineKind.NEXT
)

/** 一行文字排好版之后的**栅格化几何**（未做缩放/位移动画）。 */
private data class RowGeo(
    val text: String,
    val left: Int,
    val baseline: Int,
    val width: Int,
    val ellipsized: Boolean
)

/**
 * 某一瞬间要画的一行。
 *
 * `transform` 是这一行的**视觉变换**（缩放 + 位移），栅格化几何仍用未变换的
 * `left/baseline`——这样同一行在整个过渡期间只栅格化一次，后面每帧只是换个
 * 变换把缓存好的掩膜贴上去。
 */
private class Row(
    val text: String,
    val font: Font,
    val left: Int,
    val baseline: Int,
    val width: Int,
    /** 未唱部分的合成 alpha。 */
    val unsungAlpha: Double,
    /** 已唱叠加层的 alpha；null = 这一行不做已唱扫描（第二行）。 */
    val overlayAlpha: Double?,
    /**
     * 扫描头的横坐标（相对行首，逻辑像素）。
     *
     * **必须是浮点**：原版的进度是连续量（`--word-scan-progress` 是百分比），
     * 取整之后又会被量化回「一次亮一个字」的台阶——这是这次要修的东西本身。
     */
    val sungPx: Double,
    val feather: Double,
    val transform: AffineTransform?,
    /** 阴影额外乘的系数：元素不透明度 / 0.98，离场时阴影要跟着一起淡。 */
    val shadowScale: Double
)

/** 掩膜缓存条目；阴影与掩膜同生命周期，懒生成。 */
private class MaskEntry(val mask: BufferedImage) {
    var shadow: BufferedImage? = null
}

/**
 * 把一帧歌词画进 `TYPE_INT_ARGB_PRE` 位图。
 *
 * 这里所有透明度、间距、羽化值、字重补偿都是**逐项对着原版源码与本机 Chromium 实测**定的：
 * - `TaskbarLyrics.App/ForegroundColorPolicy.cs`    → 透明度体系与扫描叠加补偿公式
 * - `TaskbarLyrics.App/LyricsStyleScriptFactory.cs` → 注入给前端的实际样式值
 * - `Web/Lyrics/style.css` + `app.js`               → 字重、阴影、羽化宽度、两层结构、过渡曲线
 * - 本机 Edge 无头渲染同款 CSS                       → 字重基准
 *
 * 原版**没有任何字体描边**（全 CSS 无 `-webkit-text-stroke` / `paint-order`）。
 * 任何"字看起来被描了边"的现象都是实现走样的结果，不是设计。
 *
 * 绘制链路：先把一帧拆成若干「行」（[buildRows]），再统一画阴影、画文字。
 * 静止帧只有 1~2 行；行切换过渡期间最多 4 行（见 [LineTransition]）。
 */
class LyricsPainter {

    private data class FontKey(val family: String, val size: Float, val bold: Boolean)

    private data class BaseFontKey(val requested: String, val bold: Boolean)

    /** 字形掩膜缓存键。掩膜只随文字、字体、位置、布局缩放变化，不随扫描进度变化。 */
    private data class MaskKey(
        val text: String,
        val left: Int,
        val baseline: Int,
        val family: String,
        val size: Float,
        val bold: Boolean,
        val color: Int,
        val w: Int,
        val h: Int,
        val gamma: Int,
        val lightOnDark: Boolean,
        val layoutMilli: Int
    )

    private val fontCache = ConcurrentHashMap<FontKey, Font>()
    private val baseFontCache = ConcurrentHashMap<BaseFontKey, Font>()
    private var availableFamilies: Set<String>? = null

    private var maskScratch: BufferedImage? = null
    private var layerScratch: BufferedImage? = null

    /**
     * 扫描头的 90ms 追赶器。
     *
     * 它是**逐实例**的：渲染循环全程持有同一个 painter，跨帧状态才连得上。
     * 探针里每次 new 一个 painter，状态从零开始——这不影响单帧断言
     * （第一次目标必然是硬切，见 [ScanSmoother.retarget]）。
     */
    private val scanSmoother = ScanSmoother()

    /**
     * 掩膜缓存。
     *
     * 过渡期间同一帧最多有 4 行文字（离场行 + 提升行 + 新第二行 + 翻译对），
     * 原来的「主行 / 第二行」两个固定槽位不够用，改成 LRU。
     * 上限 8 条约 2MB（掩膜 + 阴影各一份），足够覆盖一次过渡里的全部行。
     */
    private val maskCache = object : LinkedHashMap<MaskKey, MaskEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MaskKey, MaskEntry>?): Boolean =
            size > 8
    }

    /**
     * 把一帧歌词画进 `TYPE_INT_ARGB_PRE` 位图。
     *
     * [offsetY] 是**整个歌词区**的竖直位移（物理像素，负值向上）。显隐过渡淡出时
     * 歌词会一边变淡一边上移 2px，走的就是这个参数。
     *
     * ⚠️ 它**不走** `Graphics2D.translate()`。`Graphics2D.setClip(Shape)` 会把入参用
     * **当前** transform 变换一遍，而这份代码里 `getClip()` / `setClip()` 反复配对使用
     * （视口裁剪、扫描区域的裁剪矩形），一旦图形上下文带着平移，每配一次就累积一次
     * 漂移。所以位移是显式加到各绘制点的 y 与 `drawImage` 的变换上的，
     * clip 永远留在未平移的坐标系里。
     */
    @JvmOverloads
    fun paint(
        image: BufferedImage,
        frame: LyricsFrame,
        s: RenderSettings,
        dpiScale: Double,
        transition: LineTransition? = null,
        offsetY: Double = 0.0,
        nowNanos: Long = System.nanoTime()
    ) {
        val w = image.width
        val h = image.height
        val g = image.createGraphics()
        try {
            g.composite = AlphaComposite.Src
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, w, h)

            applyTextHints(g)
            g.composite = AlphaComposite.SrcOver

            // 布局基准：以 48 逻辑像素的任务栏高度为 1.0，再乘用户设定的整体缩放。
            // 公式与渲染器共用 LyricsLayout，保证封面的取图尺寸与贴图尺寸严格一致。
            val layout = LyricsLayout.scale(dpiScale, s)
            val boxTop = LyricsLayout.boxTop(layout) + offsetY
            val boxH = LyricsLayout.boxHeight(h, layout)
            val radius = (s.cornerRadius * layout).toFloat()

            drawBackground(g, w, boxTop, boxH, radius, s)
            drawBorder(g, w, boxTop, boxH, radius, s, layout)

            // 文本可用区域（扣掉封面）。
            // 起始位置 = 宿主水平内边距 8 + 歌词面板左内边距 2（都来自原版度量）
            val paneLeft =
                (LyricsLayout.HOST_HORIZONTAL_PADDING + LyricsLayout.PANE_LEFT_PADDING) * layout
            var textLeft = paneLeft.coerceAtLeast(2.0)
            var textRight = w - textLeft
            val cover = if (s.showCover) frame.cover else null
            if (cover != null) {
                val size = LyricsLayout.coverSizePx(s, layout, boxH)
                val gap = s.coverGap * layout
                val cy = (boxTop + (boxH - size) / 2.0).toInt()
                val cx = if (s.coverSide == CoverSide.LEFT) {
                    textLeft.toInt().also { textLeft = it + size + gap }
                } else {
                    (textRight - size).toInt().also { textRight = it - size - gap }
                }
                drawCover(g, cover, cx, cy, size)
            }
            if (textRight - textLeft < 16) {
                return
            }

            val fg = resolveForeground(s)
            val mainFont = font(s.fontFamily, s.fontSize * layout, s.bold)
            val secondFont = font(s.fontFamily, s.fontSize * layout * SECOND_FONT_RATIO, s.bold)
            val gamma = (s.textGammaPercent / 100.0).coerceIn(0.5, 3.0)
            val lightOnDark = luma(fg) > 128

            val rows = buildRows(
                g, h, boxH, frame, s, layout, textLeft, textRight, mainFont, secondFont,
                transition, nowNanos
            )

            // ── 文字与阴影 ────────────────────────────────────────────────
            // 阴影必须在文字之前画，让它只加深背景，不改动文字本身的亮度。
            // 两者都裁在歌词视口里：滚动时旧行是从视口上边缘被裁掉的。
            val oldClip = g.clip
            g.clip = LyricsLayout.viewportRect(w, h, layout)
            try {
                if (s.showTextShadow) {
                    drawShadows(g, w, h, layout, rows, fg, gamma, lightOnDark, offsetY)
                }
                for (row in rows) {
                    drawRow(g, w, h, row, fg, gamma, lightOnDark, layout, offsetY)
                }
            } finally {
                g.clip = oldClip
            }
        } finally {
            g.dispose()
        }
    }

    // ── 把一帧拆成若干「行」 ──────────────────────────────────────────────

    /**
     * 组装这一瞬间要画的所有行。
     *
     * 静止帧：主行 + 第二行。
     *
     * 单行滚动（[TransitionMode.SINGLE]，560ms）：
     * 1. 离场行：旧主行上移 1 行，透明度 0.98 → 0.16，缩放 1 → 0.972
     * 2. 提升行：新主行从第二行的位置滚上来，缩放 0.92 → 1，透明度 0.72 → 0.98
     * 3. 新第二行：从视口下方滚入，透明度不变
     *
     * 翻译对滚动（[TransitionMode.TRANSLATION_PAIR]，760ms）：
     * 旧的一对整体上移 2 行、新的一对从下方滚入，**全程不做透明度动画**
     * （原版 `startTranslationPairTransition` 不加 `leaving` / `promoting`）。
     */
    private fun buildRows(
        g: Graphics2D,
        h: Int,
        boxH: Double,
        frame: LyricsFrame,
        s: RenderSettings,
        layout: Double,
        textLeft: Double,
        textRight: Double,
        mainFont: Font,
        secondFont: Font,
        transition: LineTransition?,
        nowNanos: Long
    ): List<Row> {
        val (rowTop, rowSecond) = LyricsLayout.rowCenters(h, layout)
        val pitch = rowSecond - rowTop              // 相邻两行的间距 = rowHeight + rowGap
        val mid = (rowTop + rowSecond) / 2.0
        // 原版 `--line-transform-origin`：`${对齐} center`，即行盒左/中/右 + 行盒垂直中心
        val originX = when (s.textAlign) {
            TextAlign.LEFT -> textLeft
            TextAlign.CENTER -> (textLeft + textRight) / 2.0
            TextAlign.RIGHT -> textRight
        }

        val second = frame.secondText?.takeIf { it.isNotBlank() }
        // 空间放得下两行时，主行**永远**停在第一格。
        //
        // 原版的轨道固定两格，下一行没有内容就显示一个空格占位
        // （`toDisplayLine(normalized.next, " ")`），格子位置不因内容为空而变，
        // `.current-line` 的 `top` 恒定。最后一句歌词因此和其他句子在同一位置。
        //
        // 此前写成「没有第二行就把主行落到两格中间」，用户截图抓出来：
        // 最后一句浮在中间，跟其他句子的位置对不上。
        // 只有任务栏矮到两行都放不下时才退成单行居中——那时根本没有第二格。
        val hasRoom = boxH >= mainFont.size * 1.8
        val secondLine = if (hasRoom) second else null
        val mainRestY = if (hasRoom) rowTop else mid

        val rows = ArrayList<Row>(4)

        if (transition == null) {
            val geo = rowGeo(g, frame.mainText, mainFont, textLeft, textRight, s.textAlign, mainRestY)
            val scan = scanFraction(s, frame, nowNanos)
            rows += mainRow(
                g, frame, s, layout, mainFont, geo, LINE_ELEMENT_OPACITY, null, 1.0, scan
            )
            if (secondLine != null) {
                val sgeo =
                    rowGeo(g, secondLine, secondFont, textLeft, textRight, s.textAlign, rowSecond)
                rows += secondRow(sgeo, secondFont, secondLineAlpha(frame.secondKind), null)
            }
            return rows
        }

        val st = transition.state()
        val prev = transition.previous
        val prevSecond = prev.secondText?.takeIf { it.isNotBlank() }
        // 离场行的主行位置与静止行同一格（见上面 hasRoom 的说明）：
        // 它当年显示时就在第一格，离场时也得从第一格滚出去，
        // 否则过渡的起点就先跳一格。
        val prevMainY = mainRestY

        if (transition.mode == TransitionMode.TRANSLATION_PAIR) {
            // ── 翻译对：只滚不淡，整对移动 2 行 ────────────────────────────
            val dyOut = -2.0 * pitch * st.move
            val dyIn = 2.0 * pitch * (1.0 - st.move)
            if (prev.mainText.isNotBlank()) {
                val geo = rowGeo(g, prev.mainText, mainFont, textLeft, textRight, s.textAlign, prevMainY)
                rows += mainRow(
                    g, prev, s, layout, mainFont, geo, LINE_ELEMENT_OPACITY, dy(dyOut), 1.0,
                    leavingFraction(s, prev)
                )
            }
            if (prevSecond != null) {
                val geo = rowGeo(g, prevSecond, secondFont, textLeft, textRight, s.textAlign, rowSecond)
                rows += secondRow(geo, secondFont, secondLineAlpha(prev.secondKind), dy(dyOut))
            }
            val geo = rowGeo(g, frame.mainText, mainFont, textLeft, textRight, s.textAlign, mainRestY)
            rows += mainRow(
                g, frame, s, layout, mainFont, geo, LINE_ELEMENT_OPACITY, dy(dyIn), 1.0,
                scanFraction(s, frame, nowNanos)
            )
            if (secondLine != null) {
                val sgeo = rowGeo(g, secondLine, secondFont, textLeft, textRight, s.textAlign, rowSecond)
                rows += secondRow(sgeo, secondFont, secondLineAlpha(frame.secondKind), dy(dyIn))
            }
            return rows
        }

        // ── 单行滚动 ──────────────────────────────────────────────────────
        // 1) 离场行
        if (prev.mainText.isNotBlank()) {
            val geo = rowGeo(g, prev.mainText, mainFont, textLeft, textRight, s.textAlign, prevMainY)
            val opacity = LINE_ELEMENT_OPACITY +
                (LEAVING_END_OPACITY - LINE_ELEMENT_OPACITY) * st.fadeOut
            val scale = 1.0 + (LEAVING_END_SCALE - 1.0) * st.move
            rows += mainRow(
                g, prev, s, layout, mainFont, geo, opacity,
                transform(scale, originX, prevMainY, -pitch * st.move),
                opacity / LINE_ELEMENT_OPACITY,
                leavingFraction(s, prev)
            )
        }

        // 2) 提升行：原版是「先用最终字号排版，再只动画缩放」。
        //
        // 这里**按静止位置栅格化**，再用变换把它先摆回第二行的位置、随后滚上来。
        // 反着写（按第二行位置栅格化再平移）也行，但那样 t=1 的落点会带着
        // 「两次取整」的误差——两行中心都不是整数时，平移量不等于基线差，
        // 动画结束会再跳半像素（实测边缘像素差到 132/255）。
        // 按静止位置栅格化后，t=1 时缩放=1、位移=0，变换退化成恒等，
        // 落点与静止帧**逐像素一致**，且与静止帧共用同一份掩膜缓存。
        val promotedGeo =
            rowGeo(g, frame.mainText, mainFont, textLeft, textRight, s.textAlign, mainRestY)
        val promotedOpacity = SECOND_ROW_ELEMENT_OPACITY +
            (LINE_ELEMENT_OPACITY - SECOND_ROW_ELEMENT_OPACITY) * st.fadeIn
        val promotedScale = SECOND_FONT_RATIO + (1.0 - SECOND_FONT_RATIO) * st.move
        // 缩放原点是它「原本所在的那一格」的中心，即第二行的中心
        val promotedDy = promotedScale * (rowSecond - mainRestY) * (1.0 - st.move)
        rows += mainRow(
            g, frame, s, layout, mainFont, promotedGeo, promotedOpacity,
            transform(promotedScale, originX, rowSecond, promotedDy),
            promotedOpacity / LINE_ELEMENT_OPACITY,
            scanFraction(s, frame, nowNanos)
        )

        // 3) 新第二行：从视口下方随 track 一起滚入，透明度保持静止值
        if (secondLine != null) {
            val sgeo = rowGeo(g, secondLine, secondFont, textLeft, textRight, s.textAlign, rowSecond)
            rows += secondRow(sgeo, secondFont, secondLineAlpha(frame.secondKind), dy(pitch * (1.0 - st.move)))
        }
        return rows
    }

    /** 排一行：截断、对齐、算基线。 */
    private fun rowGeo(
        g: Graphics2D,
        text: String,
        font: Font,
        textLeft: Double,
        textRight: Double,
        align: TextAlign,
        centerY: Double
    ): RowGeo {
        g.font = font
        val fm = g.fontMetrics
        val maxText = (textRight - textLeft).toInt()
        var out = text
        var ellipsized = false
        if (fm.stringWidth(out) > maxText) {
            out = ellipsize(g, out, maxText)
            ellipsized = true
        }
        val width = fm.stringWidth(out)
        val left = alignX(textLeft, textRight, width.toDouble(), align).roundToInt()
        val baseline = (centerY + (fm.ascent - fm.descent) / 2.0).roundToInt()
        return RowGeo(out, left, baseline, width, ellipsized)
    }

    /**
     * 主行：未唱底层 + 已唱叠加层。
     *
     * @param scanFraction 扫描头走到整行的几成（0~1），null = 这一行不画扫描。
     *     它是**相对整行文本**的比例而不是像素，因为各行宽度不同（离场行与提升行
     *     用的字号不一样），同一帧里只算一次、各行按自己的宽度换算。
     */
    private fun mainRow(
        g: Graphics2D,
        frame: LyricsFrame,
        s: RenderSettings,
        layout: Double,
        font: Font,
        geo: RowGeo,
        elementOpacity: Double,
        transform: AffineTransform?,
        shadowScale: Double,
        scanFraction: Double?
    ): Row {
        val unsung = SECONDARY_TEXT_RATIO * elementOpacity
        val overlay = WORD_SCAN_OVERLAY_ALPHA * (elementOpacity / LINE_ELEMENT_OPACITY)
        // 文本被省略号截断时，进度失去参照（原版此情形也只剩匀速可走）
        val fraction = if (geo.ellipsized) proportionalFraction(frame) else scanFraction
        val sungPx = if (fraction == null) {
            0.0
        } else {
            (fraction * geo.width).coerceIn(0.0, geo.width.toDouble())
        }
        val feather = minOf(
            WORD_SCAN_FEATHER_EM * s.fontSize * layout,
            sungPx,
            (geo.width - sungPx)
        )
        return Row(
            geo.text, font, geo.left, geo.baseline, geo.width,
            unsung, overlay, sungPx, feather, transform, shadowScale
        )
    }

    private fun secondRow(
        geo: RowGeo,
        font: Font,
        alpha: Double,
        transform: AffineTransform?
    ): Row = Row(
        geo.text, font, geo.left, geo.baseline, geo.width,
        alpha, null, 0.0, 0.0, transform, 1.0
    )

    /**
     * 一行的视觉变换。
     *
     * 对齐原版 CSS `transform: translateY(a) scale(s)` + `transform-origin`：
     * 先按原点缩放，再整体位移。两个分量共用同一条 cubic-bezier。
     */
    private fun transform(scale: Double, originX: Double, originY: Double, dy: Double): AffineTransform? {
        if (scale == 1.0 && dy == 0.0) return null
        val at = AffineTransform()
        at.translate(0.0, dy)
        at.translate(originX, originY)
        at.scale(scale, scale)
        at.translate(-originX, -originY)
        return at
    }

    private fun dy(value: Double): AffineTransform? =
        if (value == 0.0) null else AffineTransform.getTranslateInstance(0.0, value)

    // ── 掩膜与合成 ──────────────────────────────────────────────────────

    private fun drawRow(
        g: Graphics2D,
        w: Int,
        h: Int,
        row: Row,
        fg: Int,
        gamma: Double,
        lightOnDark: Boolean,
        layout: Double,
        offsetY: Double = 0.0
    ) {
        if (row.text.isEmpty()) return
        val mask = maskEntry(w, h, row, fg, gamma, lightOnDark, layout).mask
        val old = g.composite

        // 未唱底层：整行，fg × 0.60 × 元素不透明度
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, row.unsungAlpha.toFloat())
        blit(g, mask, row.transform, offsetY)

        // 已唱叠加层：合成后回到 fg × 0.90 × 元素不透明度
        if (row.overlayAlpha != null && row.sungPx > 0) {
            drawScanOverlay(g, w, h, mask, row, offsetY)
        }
        g.composite = old
    }

    /**
     * 把掩膜按行变换贴上去。[offsetY] 是额外叠加的整体竖直位移。
     *
     * 顺序一定是「先做行变换、再整体位移」——前者是行自身的动画（含缩放），
     * 后者是窗口层面的偏移，不该被行缩放影响。写成 `AffineTransform(row.transform)`
     * 再 `.translate()` 的话会反过来（位移被缩放吃掉），两者不同。
     */
    private fun blit(g: Graphics2D, img: BufferedImage, at: AffineTransform?, offsetY: Double = 0.0) {
        if (offsetY == 0.0) {
            if (at == null) g.drawImage(img, 0, 0, null) else g.drawImage(img, at, null)
            return
        }
        val t = AffineTransform.getTranslateInstance(0.0, offsetY)
        if (at != null) {
            t.concatenate(at)
        }
        g.drawImage(img, t, null)
    }

    /** 取（或生成并缓存）某行的掩膜条目。 */
    private fun maskEntry(
        w: Int, h: Int, row: Row, fg: Int, gamma: Double, lightOnDark: Boolean, layout: Double
    ): MaskEntry {
        val key = MaskKey(
            row.text, row.left, row.baseline, row.font.family, row.font.size2D,
            row.font.isBold, fg, w, h, (gamma * 1000).roundToInt(), lightOnDark,
            (layout * 1000).roundToInt()
        )
        maskCache[key]?.let { return it }

        val scratch = scratch(maskScratch, w, h).also { maskScratch = it }
        val mg = scratch.createGraphics()
        try {
            mg.composite = AlphaComposite.Src
            mg.color = Color(0, 0, 0, 0)
            mg.fillRect(0, 0, w, h)
            applyTextHints(mg)
            // 先画成白色，只用它的覆盖率；RGB 随后统一换成前景色
            mg.color = Color.WHITE
            mg.font = row.font
            mg.drawString(row.text, row.left, row.baseline)
        } finally {
            mg.dispose()
        }

        val pixels = IntArray(w * h)
        scratch.getRGB(0, 0, w, h, pixels, 0, w)
        val lut = gammaLut(gamma, lightOnDark)
        val rgb = ((fg shr 16 and 0xFF) shl 16) or ((fg shr 8 and 0xFF) shl 8) or (fg and 0xFF)
        for (i in pixels.indices) {
            val a = (pixels[i] ushr 24) and 0xFF
            pixels[i] = (lut[a] shl 24) or rgb
        }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, w, h, pixels, 0, w)

        val entry = MaskEntry(out)
        maskCache[key] = entry
        return entry
    }

    private fun scratch(current: BufferedImage?, w: Int, h: Int): BufferedImage =
        if (current != null && current.width == w && current.height == h) {
            current
        } else {
            BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        }

    /**
     * 已唱部分的扫描覆盖层。
     *
     * 复刻原版用 `mask-image: linear-gradient(...)` 实现的**羽化边界**：
     * 靠左完全不透明（覆盖），靠右完全透明（露出底色），中间 [feather] 宽度内线性过渡。
     */
    private fun drawScanOverlay(
        g: Graphics2D,
        w: Int, h: Int,
        mask: BufferedImage,
        row: Row,
        offsetY: Double = 0.0
    ) {
        val alpha = row.overlayAlpha ?: return
        val oldComposite = g.composite
        val constant = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.toFloat())
        val feather = row.feather
        val sungWidth = row.sungPx
        val fullWidth = row.width

        if (feather < 0.5 || sungWidth >= fullWidth) {
            if (sungWidth < fullWidth) {
                val oldClip = g.clip
                // 裁剪矩形要在**未变换**的坐标里，随后整幅一起做变换
                // 无羽化的快路径才需要取整：有羽化时边界是浮点渐变，本来就是连续的
                g.clip = java.awt.Rectangle(row.left, 0, ceil(sungWidth).toInt(), h)
                g.composite = constant
                blit(g, mask, row.transform, offsetY)
                g.clip = oldClip
            } else {
                // 整行已唱，羽化区间落到窗口外，整幅画一遍即可
                g.composite = constant
                blit(g, mask, row.transform, offsetY)
            }
            g.composite = oldComposite
            return
        }

        val layer = scratch(layerScratch, w, h).also { layerScratch = it }
        val lg = layer.createGraphics()
        try {
            lg.composite = AlphaComposite.Src
            lg.color = Color(0, 0, 0, 0)
            lg.fillRect(0, 0, w, h)
            lg.composite = constant
            lg.drawImage(mask, 0, 0, null)

            // DstIn：目标（刚画上去的掩膜）乘以源的 alpha。
            // 整个矩形都被填充，所以全图都会被处理——不会出现
            // 「只在图元包围盒内生效」那种坑（封面圆角那次就栽在这）。
            lg.composite = AlphaComposite.DstIn
            val boundary = (row.left + sungWidth).toFloat()
            lg.paint = LinearGradientPaint(
                Point2D.Float(boundary - feather.toFloat(), 0f),
                Point2D.Float(boundary + feather.toFloat(), 0f),
                floatArrayOf(0f, 1f),
                arrayOf(Color(255, 255, 255, 255), Color(255, 255, 255, 0))
            )
            lg.fillRect(0, 0, w, h)
        } finally {
            lg.dispose()
        }

        // 注意：AlphaComposite.SRC_OVER 是 Int 规则码，这里要的是实例
        g.composite = AlphaComposite.SrcOver
        blit(g, layer, row.transform, offsetY)
        g.composite = oldComposite
    }

    /**
     * 文字抗锯齿的 gamma 查找表。
     *
     * **这是"我们的字比原版细"的根治点。**
     *
     * Java2D 的文字抗锯齿走**线性混合**，而 Chromium / DirectWrite 走
     * **gamma 校正混合**。深色背景上的浅色文字，后者明显更"实"。
     * 以本机 Edge 无头渲染同款 CSS 作基准，实测同一款字、同一句话：
     *
     * | 渲染路径 | 归一化墨量 | 笔画均值 |
     * | --- | --- | --- |
     * | Chromium + 思源黑体 Bold | 1194.2 | 5.92px |
     * | Java2D 不补偿（γ=1.0）    | 1057.8 | 4.91px |
     * | Java2D γ=1.8              | 1185.7 | 6.00px |
     *
     * 补偿方式是把覆盖率 c 重映射：浅色文字在深底上 `c^(1/γ)`（变粗），
     * 深色文字在浅底上 `1-(1-c)^(1/γ)`（变细）。两者物理含义一致——
     * 都是在 sRGB 空间里还原线性光混合的结果。
     */
    private fun gammaLut(gamma: Double, lightOnDark: Boolean): IntArray {
        val key = (gamma * 1000).roundToInt() * 2 + if (lightOnDark) 1 else 0
        lutCache[key]?.let { return it }
        val lut = IntArray(256)
        val inv = 1.0 / gamma
        for (i in 0..255) {
            val c = i / 255.0
            val v = if (lightOnDark) Math.pow(c, inv) else 1.0 - Math.pow(1.0 - c, inv)
            lut[i] = (v * 255).roundToInt().coerceIn(0, 255)
        }
        lutCache[key] = lut
        return lut
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    // ── 阴影 ────────────────────────────────────────────────────────────

    /**
     * 逐行画文字阴影。
     *
     * 原版是 CSS `text-shadow: 0 1px 2px rgba(0, 0, 0, 0.36)`：
     * 偏移 (0, 1) DIP、模糊半径 2 DIP、黑色、alpha 0.36。
     *
     * CSS 的模糊半径 r 对应高斯 σ = r / 2，所以这里 σ = 1 DIP（乘布局缩放变物理像素）。
     * 用三次分离式 box blur 近似高斯——Java2D 没有现成的高斯阴影。
     *
     * ⚠️ 不能写成"把字形硬拷贝一份往下挪 1px"：中文字形横画密集，
     * 硬拷贝会在每道横画下缘压出一条实边，看上去就像给每个字描了一圈轮廓。
     *
     * 另外要记住：阴影是黑色半透明的，**压在纯黑背景上本来就看不见**，
     * 不要用"离屏渲染到黑底后有没有阴影像素"来判断它是否生效。
     *
     * 阴影跟着掩膜一起缓存（同一行的阴影在整个过渡期间不变），
     * 只在绘制时乘上该行的 `shadowScale`——原版 `opacity` 是作用在**整个元素**上的，
     * 离场时阴影要跟文字一起淡下去。
     *
     * [offsetY] 是显隐过渡的整体竖直位移，叠加在 `text-shadow` 自己的偏移之外。
     */
    private fun drawShadows(
        g: Graphics2D, w: Int, h: Int, layout: Double,
        rows: List<Row>, fg: Int, gamma: Double, lightOnDark: Boolean,
        offsetY: Double = 0.0
    ) {
        val shadowDy = (SHADOW_OFFSET_Y * layout).roundToInt().toDouble()
        val old = g.composite
        for (row in rows) {
            if (row.text.isEmpty()) continue
            val shadow = shadowFor(w, h, row, fg, gamma, lightOnDark, layout)
            val at = shadowShift(offsetY, shadowDy, row.transform)
            g.composite = if (row.shadowScale >= 1.0) {
                AlphaComposite.SrcOver
            } else {
                AlphaComposite.getInstance(AlphaComposite.SRC_OVER, row.shadowScale.toFloat())
            }
            g.drawImage(shadow, at, null)
        }
        g.composite = old
    }

    /**
     * 阴影的变换。`text-shadow` 的偏移写在**元素自己的坐标系**里，所以它在行的
     * 缩放之内（先位移、再行变换）；而 [fadeDy] 是窗口层面的，在外层（后作用）。
     *
     * 合成结果 = `T(fadeDy) × 行变换 × T(shadowDy)`，[fadeDy] 为 0 时与原来完全一致。
     */
    private fun shadowShift(fadeDy: Double, shadowDy: Double, at: AffineTransform?): AffineTransform {
        val t = AffineTransform(at ?: AffineTransform())
        t.translate(0.0, shadowDy)
        if (fadeDy == 0.0) {
            return t
        }
        return AffineTransform.getTranslateInstance(0.0, fadeDy).apply { concatenate(t) }
    }

    private fun shadowFor(
        w: Int, h: Int, row: Row, fg: Int, gamma: Double, lightOnDark: Boolean, layout: Double
    ): BufferedImage {
        val entry = maskEntry(w, h, row, fg, gamma, lightOnDark, layout)
        entry.shadow?.let { return it }

        val sigma = SHADOW_BLUR_RADIUS * 0.5 * layout
        val radius = boxRadiusFor(sigma)

        val pixels = IntArray(w * h)
        entry.mask.getRGB(0, 0, w, h, pixels, 0, w)
        val alpha = IntArray(w * h)
        for (i in pixels.indices) {
            alpha[i] = (pixels[i] ushr 24) and 0xFF
        }

        if (radius > 0) {
            val tmp = IntArray(w * h)
            repeat(3) {
                boxBlurH(alpha, tmp, w, h, radius)
                boxBlurV(tmp, alpha, w, h, radius)
            }
        }

        val out = IntArray(w * h)
        val scale = SHADOW_ALPHA * LINE_ELEMENT_OPACITY
        for (i in alpha.indices) {
            out[i] = ((alpha[i] * scale).roundToInt().coerceIn(0, 255)) shl 24
        }
        val shadow = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        shadow.setRGB(0, 0, w, h, out, 0, w)
        entry.shadow = shadow
        return shadow
    }

    /** 由目标 σ 反解 box 半径：三次 box blur 的方差 = 3×(w²−1)/12。 */
    private fun boxRadiusFor(sigma: Double): Int {
        if (sigma <= 0.0) return 0
        val width = sqrt(4.0 * sigma * sigma + 1.0).roundToInt()
        return ((width - 1) / 2).coerceAtLeast(1)
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val window = radius * 2 + 1
        for (y in 0 until h) {
            val base = y * w
            var sum = 0
            for (i in -radius..radius) sum += src[base + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[base + x] = sum / window
                sum += src[base + (x + radius + 1).coerceIn(0, w - 1)] -
                    src[base + (x - radius).coerceIn(0, w - 1)]
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val window = radius * 2 + 1
        for (x in 0 until w) {
            var sum = 0
            for (i in -radius..radius) sum += src[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / window
                sum += src[(y + radius + 1).coerceIn(0, h - 1) * w + x] -
                    src[(y - radius).coerceIn(0, h - 1) * w + x]
            }
        }
    }

    private fun drawBackground(
        g: Graphics2D, w: Int, top: Double, boxH: Double, radius: Float, s: RenderSettings
    ) {
        if (!s.showBackground || s.backgroundOpacity <= 0) return
        val bg = multiplyAlpha(parseColor(s.backgroundColor, 0xFF000000.toInt()), s.backgroundOpacity / 100.0)
        g.color = argb(bg)
        g.fill(RoundRectangle2D.Double(0.0, top, w.toDouble(), boxH, radius.toDouble(), radius.toDouble()))
    }

    private fun drawBorder(
        g: Graphics2D, w: Int, top: Double, boxH: Double, radius: Float,
        s: RenderSettings, layout: Double
    ) {
        if (!s.showBorder) return
        val fg = resolveForeground(s)
        g.color = argb(multiplyAlpha(fg, BORDER_ALPHA))
        // 注意类型：Int - Float 会得到 Float，而 RoundRectangle2D.Double 只接受 Double
        val strokeWidth = (1.0 * layout).coerceAtLeast(1.0)
        g.stroke = BasicStroke(strokeWidth.toFloat())
        val half = strokeWidth / 2.0
        g.draw(
            RoundRectangle2D.Double(
                half, top + half, w - strokeWidth, boxH - strokeWidth,
                radius.toDouble(), radius.toDouble()
            )
        )
    }

    /**
     * 贴封面。
     *
     * 圆角已经在 [com.spwtb.taskbarlyrics.lyrics.CoverArt.forSong] 里烘焙进 alpha 通道了，
     * 这里**不要再用 clip 去裁**——Java2D 的非矩形 clip 不做抗锯齿，会把光滑的圆角又裁成硬边。
     */
    private fun drawCover(g: Graphics2D, cover: BufferedImage, x: Int, y: Int, size: Int) {
        if (cover.width == size && cover.height == size) {
            g.drawImage(cover, x, y, null)     // 1:1，完全不过插值
        } else {
            g.drawImage(cover, x, y, size, size, null)
        }
    }

    // ── 逐字 / 扫描 ─────────────────────────────────────────────────────

    /**
     * 当前帧的扫描比例（0~1，相对整行文本），null = 这一帧不做扫描。
     *
     * **一帧只准调一次**：它会推进 [scanSmoother] 的时间线，同一帧里调两次
     * 会让离场行和提升行互相抢扫描头（原版同一时刻也只有一个元素在扫）。
     *
     * 取的是**平滑后**的值：字级时间给的是原始比例，90ms 追赶器负责把
     * 宿主每秒一次的位置校正摊开（见 [WordScan] 的说明）。
     */
    private fun scanFraction(s: RenderSettings, frame: LyricsFrame, nowNanos: Long): Double? {
        if (!s.wordScanning) {
            scanSmoother.reset()
            return null
        }
        val raw = WordScan.progress(frame, frame.mainText)
        if (raw == null) {
            // 没有字级数据：退回按行内时间匀速推进。
            //
            // 原版这一步是 `wordScanProgress = null` → 干脆不画扫描层；
            // 但那样一来，凡是没带字级时间的歌（大多数内嵌歌词就是）整行会纹丝不动，
            // 比不装插件还像坏了。匀速退路是这边的既有行为，保留。
            //
            // 匀速本身就是连续的，不必再过 90ms 追赶器——那只会白白多出一点滞后。
            scanSmoother.reset()
            return proportionalFraction(frame)
        }
        scanSmoother.retarget(raw, nowNanos)
        return scanSmoother.level(nowNanos)
    }

    /**
     * 离场行的扫描比例：走的是**未平滑**的原始值。
     *
     * 这一行已经唱完了，它身上的扫描头应该停在原处被一起带走；
     * 若也走平滑器，它会跟着新一行的进度重新动，而且会和当前行抢同一份状态。
     */
    private fun leavingFraction(s: RenderSettings, frame: LyricsFrame): Double? {
        if (!s.wordScanning) {
            return null
        }
        return WordScan.progress(frame, frame.mainText) ?: proportionalFraction(frame)
    }

    /** 没有字级数据时的退路：按行内时间比例匀速推进。 */
    private fun proportionalFraction(frame: LyricsFrame): Double? {
        val span = frame.lineEndMs - frame.lineStartMs
        if (span <= 0) return 1.0
        return ((frame.positionMs - frame.lineStartMs).toDouble() / span).coerceIn(0.0, 1.0)
    }

    // ── 字体 ────────────────────────────────────────────────────────────

    private fun font(family: String, size: Double, bold: Boolean): Font {
        val px = size.coerceIn(6.0, 200.0).toFloat()
        val base = baseFont(family, bold)
        val key = FontKey(base.fontName, px, bold)
        return fontCache.getOrPut(key) { base.deriveFont(px) }
    }

    /**
     * 解析出一个基准字体（大小 12，供 deriveFont 用）。
     *
     * **逐名字轮着来；每个名字先按文件找精确字重，再退回已注册家族。**
     *
     * 顺序不能反过来。`FontLoader` 加载字体会调用 `registerFont`，而它只让家族表里
     * 出现**当前加载的那一个字面**：启动时 bold=true 只注册了粗体面，之后
     * `Font(name, PLAIN)` 仍会落回粗体——表现就是「启动时是粗体，关掉加粗没反应；
     * 启动时不是粗体则正常（常规面 + 合成粗体）」。
     *
     * 先按文件找还顺带覆盖了 `SourceHanSansSC-Bold.otf` 这类「文件在、但没注册进
     * 系统」的情况。逐名字处理也保留了原来的回退顺序：第一个候选名字有文件/家族
     * 就直接用，不会因为先扫了整张家族表而误命中回退链里的 Noto。
     */
    private fun baseFont(requested: String, bold: Boolean): Font {
        val key = BaseFontKey(requested, bold)
        baseFontCache[key]?.let { return it }

        val families = availableFamilies ?: loadFamilies().also { availableFamilies = it }
        val names = buildList {
            requested.split(',').forEach { add(it.trim().trim('"', '\'')) }
            addAll(FALLBACK_FAMILIES)
        }

        val resolved = run {
            for (name in names) {
                if (name.isEmpty()) continue
                // 先按文件取精确字重，避免命中「只注册了一个字面」的不完整家族
                FontLoader.loadByName(name, bold)?.let { return@run it }
                if (families.contains(name)) {
                    return@run Font(name, if (bold) Font.BOLD else Font.PLAIN, 12)
                }
            }
            Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, 12)
        }
        baseFontCache[key] = resolved
        return resolved
    }

    private fun loadFamilies(): Set<String> = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    private fun ellipsize(g: Graphics2D, text: String, maxWidth: Int): String {
        val fm = g.fontMetrics
        val sb = StringBuilder()
        for (ch in text) {
            if (fm.stringWidth("$sb$ch…") > maxWidth) break
            sb.append(ch)
        }
        return if (sb.isEmpty()) "" else "$sb…"
    }

    private fun alignX(left: Double, right: Double, contentWidth: Double, align: TextAlign): Double =
        when (align) {
            TextAlign.LEFT -> left
            TextAlign.CENTER -> left + (right - left - contentWidth) / 2.0
            TextAlign.RIGHT -> right - contentWidth
        }

    // ── 颜色 ────────────────────────────────────────────────────────────

    /** 解析 #RRGGBB / #AARRGGBB；非法输入返回 fallback。 */
    private fun parseColor(spec: String, fallback: Int): Int {
        val t = spec.trim().removePrefix("#")
        return try {
            when (t.length) {
                6 -> (0xFF000000.toInt()) or t.toInt(16)
                8 -> t.toLong(16).toInt()
                else -> fallback
            }
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun resolveForeground(s: RenderSettings): Int = when (s.foregroundMode) {
        ForegroundMode.LIGHT -> LIGHT_FOREGROUND
        ForegroundMode.DARK -> DARK_FOREGROUND
        ForegroundMode.CUSTOM -> parseColor(s.foregroundColor, LIGHT_FOREGROUND)
        ForegroundMode.SYSTEM -> if (Win32.isSystemLightTheme()) DARK_FOREGROUND else LIGHT_FOREGROUND
    }

    /**
     * 第二行的实际透明度。
     *
     * 原版对三种内容用了三套不同的透明度（见 companion 里的出处），
     * 所以这里要按类型取，不能用一个常量。
     */
    private fun secondLineAlpha(kind: SecondLineKind): Double = when (kind) {
        SecondLineKind.TRANSLATION -> TRANSLATION_LINE_ALPHA
        SecondLineKind.NEXT -> NEXT_LINE_ALPHA
        SecondLineKind.PLACEHOLDER -> PLACEHOLDER_LINE_ALPHA
    }

    private fun multiplyAlpha(color: Int, factor: Double): Int {
        val a = ((color ushr 24) and 0xFF) * factor.coerceIn(0.0, 1.0)
        return (a.toInt().coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
    }

    private fun argb(color: Int): Color =
        Color(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF, color ushr 24 and 0xFF)

    /**
     * 绘制用的渲染提示。
     *
     * - **灰度抗锯齿**（`VALUE_TEXT_ANTIALIAS_ON`）：透明底上用 LCD 子像素抗锯齿，
     *   半透明像素边缘会出现彩色描边。
     * - **分数度量**（`VALUE_FRACTIONALMETRICS_ON`）：对齐原版 CSS 的
     *   `text-rendering: geometricPrecision`——不做度量取整，字符推进用分数值。
     */
    private fun applyTextHints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        )
        g.setRenderingHint(
            RenderingHints.KEY_FRACTIONALMETRICS,
            RenderingHints.VALUE_FRACTIONALMETRICS_ON
        )
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
    }

    companion object {
        // ── 透明度体系：逐个对着原版 TaskbarLyrics 的源码抄 ──────────────────
        //
        // 来源：TaskbarLyrics.App/ForegroundColorPolicy.cs
        //   PrimaryTextOpacityRatio     = 0.90   （当前行）
        //   SecondaryTextOpacityRatio   = 0.60   （未唱）
        //   TranslationTextOpacityRatio = 0.70   （翻译）
        // 再叠一层 CSS 的 opacity：
        //   .current-line { opacity: 0.98 }
        //   .next-line    { opacity: 0.72 }
        //   翻译占位行    { opacity: 0.55 }

        /** 当前行的**基础不透明度**：`app.js` 的 `currentLineRestOpacity`。 */
        const val LINE_ELEMENT_OPACITY = 0.98

        /** 第二行的**基础不透明度**：`style.css` 的 `.next-line`。 */
        const val SECOND_ROW_ELEMENT_OPACITY = 0.72

        /** 原版 `SecondaryTextOpacityRatio`：未唱部分的颜色倍率。 */
        const val SECONDARY_TEXT_RATIO = 0.60

        /** 原版 `PrimaryTextOpacityRatio`：已唱部分的颜色倍率。 */
        const val PRIMARY_TEXT_RATIO = 0.90

        /** 当前行「已唱」的**合成结果**：0.90 × 0.98 = 0.882（只用于校验，不直接绘制）。 */
        const val SUNG_ALPHA = PRIMARY_TEXT_RATIO * LINE_ELEMENT_OPACITY

        /** 当前行「未唱」：0.60 × 0.98 = 0.588 */
        const val UNSUNG_ALPHA = SECONDARY_TEXT_RATIO * LINE_ELEMENT_OPACITY

        /**
         * 扫描覆盖层的 alpha：0.745 × 0.98 = 0.730。
         *
         * **不是** primary 的值。原版由「叠加补偿」公式反解：
         * `overlay = (primaryAlpha − secondaryAlpha) × 255 / (255 − secondaryAlpha)`
         * ⇒ `(229 − 153) × 255 / 102 = 190`，即 0.745。
         *
         * 叠加后：0.588 + 0.730 × (1 − 0.588) = 0.889 ≈ 0.882 ✓
         */
        const val WORD_SCAN_OVERLAY_ALPHA = (190.0 / 255.0) * LINE_ELEMENT_OPACITY

        /** 翻译行：0.70 × 1.0 */
        const val TRANSLATION_LINE_ALPHA = 0.70

        /** 下一句歌词：0.60 × 0.72 = 0.432 */
        const val NEXT_LINE_ALPHA = SECONDARY_TEXT_RATIO * SECOND_ROW_ELEMENT_OPACITY

        /** 无翻译时把下一句当占位：0.70 × 0.55 = 0.385 */
        const val PLACEHOLDER_LINE_ALPHA = 0.70 * 0.55

        // ── 行切换过渡：全部来自 `Web/Lyrics/app.js` 与 `style.css` ──────────

        /** `.current-line.leaving { opacity: 0.16 }` */
        const val LEAVING_END_OPACITY = 0.16

        /** `.current-line.leaving { transform: scale(0.972) }` */
        const val LEAVING_END_SCALE = 0.972

        /** 扫描边界的羽化宽度（em）：原版 `--word-scan-feather-width: 0.12em`。 */
        const val WORD_SCAN_FEATHER_EM = 0.12

        // ── 文字阴影：原版 CSS `text-shadow: 0 1px 2px rgba(0, 0, 0, 0.36)` ──

        const val SHADOW_ALPHA = 0.36
        const val SHADOW_OFFSET_Y = 1.0

        /** CSS 的 blur radius，对应高斯 σ = radius / 2，即 σ = 1 DIP。 */
        const val SHADOW_BLUR_RADIUS = 2.0

        const val BORDER_ALPHA = 0.35

        /**
         * 第二行字号相对主行的比例。
         * 来源：原版 `Web/Lyrics/app.js` 的 `updateMetrics()`：
         * `nextSize = max(9 * scale, currentSize * 0.92)`
         *
         * 它同时就是提升动画的**起始缩放**（`promotionStartScale = nextSize / currentSize`）。
         */
        const val SECOND_FONT_RATIO = 0.92

        const val LIGHT_FOREGROUND = 0xFFFFFFFF.toInt()
        const val DARK_FOREGROUND = 0xFF111827.toInt()

        val lutCache = ConcurrentHashMap<Int, IntArray>()

        /**
         * 字体回退链。
         *
         * 原版自带的是 **思源黑体 / Source Han Sans SC Bold**。
         * 本机装的 `SourceHanSansSC-*.otf` 注册后**家族名是本地化的「思源黑体」**，
         * 只写英文名一行都命中不了，会静默退到 Noto——字重因此差一档。
         * 「Noto Sans SC」与思源黑体是同一款字体的两个名字（Adobe × Google 合作命名）。
         */
        val FALLBACK_FAMILIES = listOf(
            "思源黑体", "Source Han Sans SC", "Source Han Sans CN", "思源黑体 CN",
            "Noto Sans SC", "Noto Sans SC Medium", "Noto Sans CJK SC",
            "Microsoft YaHei UI", "微软雅黑",
            "SimHei", "黑体", "SimSun", "Segoe UI"
        )

        /** 预热是否已经做过。这是**进程级**的一次性成本，做一次就够。 */
        @Volatile
        private var preloaded = false

        /**
         * 预热：把 Java2D 第一次文字布局的开销挪到**没有观众**的时候付掉。
         *
         * 本机实测（见 `FirstPaintCostProbe`）：第一帧 **636ms**，之后每帧 **0.4ms**，
         * 差三个数量级；而且它是**全局**的——再 new 一个绘制器，它的第一帧也只要 7ms。
         * 花掉的基本是字体子系统的初始化。
         *
         * 非预热不可的理由：这笔开销落点正好是**第一帧与第二帧之间**，
         * 而淡入的时间线是从第一帧起算的。于是首帧以全透明上屏，
         * 七百多毫秒后第二帧一算进度——已经 100%，直接满亮度。
         * 用户看到的仍旧是"啪一下出现"，那 300ms 的过渡等于白写。
         *
         * 调用时机必须是**建窗口之前**（上层 `TaskbarRenderer` 负责）：
         * 那时窗口都还不存在，画面上一个像素都没有，这几百毫秒纯粹是白等，
         * 谁也看不出来。
         */
        fun preload(s: RenderSettings) {
            if (preloaded) {
                return
            }
            preloaded = true
            val frame = LyricsFrame(
                mainText = PRELOAD_TEXT,
                secondText = null,
                cover = null,
                cells = emptyList(),
                positionMs = 0L,
                lineStartMs = 0L,
                lineEndMs = 1L,
                secondKind = SecondLineKind.NEXT
            )
            try {
                val probe = BufferedImage(
                    PRELOAD_SIZE, PRELOAD_SIZE, BufferedImage.TYPE_INT_ARGB_PRE
                )
                LyricsPainter().paint(probe, frame, s, 1.0, null, 0.0)
            } catch (t: Throwable) {
                // 预热失败不耽误正事：最坏情况是退回"第一帧慢"，不至于画不出来
                println("[TaskbarLyrics] 预热失败（忽略）：${t.message}")
            }
        }

        /** 预热用的画布尺寸，够触发一次完整的排版与掩膜流程即可。 */
        private const val PRELOAD_SIZE = 64

        /** 预热用的文本：含 CJK 字符，确保命中的正是歌词那套字形路径。 */
        private const val PRELOAD_TEXT = "预热字形"
    }
}
