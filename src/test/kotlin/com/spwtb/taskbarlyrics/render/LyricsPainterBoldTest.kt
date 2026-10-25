package com.spwtb.taskbarlyrics.render

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.Assume.assumeTrue

/**
 * 回归测试：同一份文本在 bold=true / false 下必须渲染出不同像素。
 *
 * 这条测试把「粗体设置不生效」的排查范围一分为二：
 * - 如果失败 → 字体解析/掩膜缓存没有区分粗体，是渲染侧的问题；
 * - 如果通过 → 渲染侧没问题，问题在「设置值有没有传到渲染线程」。
 */
class LyricsPainterBoldTest {

    @Test
    fun `bold changes rendered glyphs`() {
        val frame = LyricsFrame(
            mainText = "加粗测试",
            secondText = null,
            cover = null,
            cells = emptyList(),
            positionMs = 0L,
            lineStartMs = 0L,
            lineEndMs = 1000L
        )
        val base = RenderSettings(
            foregroundMode = ForegroundMode.LIGHT,
            showTextShadow = false,
            showBackground = false,
            showCover = false,
            wordScanning = false
        )
        // 同一个 painter 实例：顺便验证掩膜缓存会不会把 bold=true 的掩膜
        // 错误地复用到 bold=false 上。
        val painter = LyricsPainter()
        val bold = render(painter, frame, base.copy(bold = true))
        val plain = render(painter, frame, base.copy(bold = false))

        val differ = bold.indices.count { bold[it] != plain[it] }
        println("[bold-test] differing pixels: $differ / ${bold.size}")
        assertFalse(bold.contentEquals(plain), "bold=true 与 bold=false 的渲染结果完全相同")
    }

    /**
     * 复现用户报告的不对称现象：
     * 启动时先加载/注册**粗体**面，之后把 bold 关掉仍显示粗体。
     *
     * FontLoader.loadByName 会 registerFont，把家族表里只留下当前加载的那一个字面；
     * 之后按家族名取 PLAIN 时没有常规面可回退，就还是粗体。
     */
    @Test
    fun `bold can be turned off after the bold face was registered first`() {
        val boldFont = FontLoader.loadByName("Source Han Sans SC", true)
        assumeTrue("本机没有思源黑体，跳过", boldFont != null)

        val painter = LyricsPainter()
        val bold = render(painter, testFrame(), baseSettings().copy(bold = true))
        val plain = render(painter, testFrame(), baseSettings().copy(bold = false))

        val differ = bold.indices.count { bold[it] != plain[it] }
        println("[bold-test] startup-bold differing pixels: $differ / ${bold.size}")
        assertFalse(
            bold.contentEquals(plain),
            "先注册粗体后，bold=false 仍然渲染成粗体"
        )
    }

    private fun testFrame() = LyricsFrame(
        mainText = "加粗测试",
        secondText = null,
        cover = null,
        cells = emptyList(),
        positionMs = 0L,
        lineStartMs = 0L,
        lineEndMs = 1000L
    )

    private fun baseSettings() = RenderSettings(
        foregroundMode = ForegroundMode.LIGHT,
        showTextShadow = false,
        showBackground = false,
        showCover = false,
        wordScanning = false
    )

    private fun render(painter: LyricsPainter, frame: LyricsFrame, settings: RenderSettings): IntArray {
        val image = BufferedImage(240, 48, BufferedImage.TYPE_INT_ARGB_PRE)
        painter.paint(image, frame, settings, 1.0, null, 0.0)
        return (image.raster.dataBuffer as DataBufferInt).data.copyOf()
    }
}
