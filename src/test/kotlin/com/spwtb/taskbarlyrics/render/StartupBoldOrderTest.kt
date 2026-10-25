package com.spwtb.taskbarlyrics.render

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.Assume.assumeTrue

/**
 * 由 Gradle 的 `forkEvery = 1` 保证每个测试类在**全新 JVM**里跑
 * （否则同 JVM 里其他测试注册的常规面会掩盖问题）：
 *
 * 模拟启动时 bold=true：先加载并注册粗体面，然后关掉 bold。
 * 只注册粗体面时，`Font("Source Han Sans SC", PLAIN)` 可能仍落回粗体。
 */
class StartupBoldOrderTest {

    @Test
    fun `bold can be turned off after starting with bold`() {
        val boldFont = FontLoader.loadByName("Source Han Sans SC", true)
        assumeTrue("本机没有思源黑体，跳过", boldFont != null)

        val painter = LyricsPainter()
        val bold = render(painter, baseSettings().copy(bold = true))
        val plain = render(painter, baseSettings().copy(bold = false))

        val differ = bold.indices.count { bold[it] != plain[it] }
        println("[startup-bold] differing pixels: $differ / ${bold.size}")
        assertFalse(bold.contentEquals(plain), "启动时是粗体，关掉 bold 后仍然渲染成粗体")
    }

    private fun baseSettings() = RenderSettings(
        foregroundMode = ForegroundMode.LIGHT,
        showTextShadow = false,
        showBackground = false,
        showCover = false,
        wordScanning = false
    )

    private fun render(painter: LyricsPainter, settings: RenderSettings): IntArray {
        val frame = LyricsFrame(
            mainText = "加粗测试",
            secondText = null,
            cover = null,
            cells = emptyList(),
            positionMs = 0L,
            lineStartMs = 0L,
            lineEndMs = 1000L
        )
        val image = BufferedImage(240, 48, BufferedImage.TYPE_INT_ARGB_PRE)
        painter.paint(image, frame, settings, 1.0, null, 0.0)
        return (image.raster.dataBuffer as DataBufferInt).data.copyOf()
    }
}
