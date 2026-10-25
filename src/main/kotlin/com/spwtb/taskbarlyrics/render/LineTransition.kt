package com.spwtb.taskbarlyrics.render

import kotlin.math.abs

/**
 * 行切换过渡：**逐值照抄原版 TaskbarLyrics 的前端实现**，不是拍脑袋调的曲线。
 *
 * 出处（原版 `Web/Lyrics/`）：
 *
 * | 量 | 原版位置 | 值 |
 * | --- | --- | --- |
 * | 单行滚动时长 | `app.js` `transitionDurationMs` | **560ms** |
 * | 翻译对滚动时长 | `app.js` `translationPairTransitionDurationMs` | **760ms** |
 * | 滚动/缩放缓动 | `style.css` `.track.animating` | `cubic-bezier(0.22, 0.72, 0.24, 1)` |
 * | 滚动距离 | `app.js` `setTrackOffset(n)` = `-linePitchPx * n` | 单行 1 行、翻译对 2 行 |
 * | 离场行终态 | `style.css` `.current-line.leaving` | `opacity: 0.16`、`scale(0.972)` |
 * | 离场行透明度曲线 | `app.js` `getFadeOutEase` | `easeOutCubic(t / 0.74)`，≥0.97 收敛到 1 |
 * | 提升行透明度曲线 | `app.js` `getFadeInEase` | `easeOutCubic(t / 0.72)`，≥0.96 收敛到 1 |
 * | 提升行起始缩放 | `app.js` `promotionStartScale` | `nextSize / currentSize` = **0.92** |
 * | 当前行静止不透明度 | `app.js` `currentLineRestOpacity` | **0.98** |
 * | 第二行静止不透明度 | `style.css` `.next-line` | **0.72** |
 *
 * 两点容易搞错、原版特意处理过的细节：
 *
 * 1. **位移与透明度用的不是同一条曲线**。位移走 CSS 的 cubic-bezier，
 *    透明度是 JS 每帧算的 easeOutCubic，而且两条曲线的收敛点还不一样。
 *    所以下面把 `move` 与 `fadeOut/fadeIn` 分开给，别混用。
 * 2. **翻译对模式只有位移，没有淡入淡出**（`startTranslationPairTransition`
 *    不会给当前行/下一行加 `leaving` / `promoting`，只把整条 track 上移两行）。
 */
enum class TransitionMode(val durationMs: Long) {

    /** 单行滚动：`SINGLE_ROLL`，560ms，上移 1 行。 */
    SINGLE(560L),

    /** 翻译对滚动：`TRANSLATION_PAIR_ROLL`，760ms，上移 2 行。 */
    TRANSLATION_PAIR(760L)
}

/** 某一时刻过渡的各个进度分量。** */
data class TransitionState(
    /** 线性时间进度 0..1。 */
    val raw: Double,

    /** 位移/缩放用：cubic-bezier(0.22, 0.72, 0.24, 1) 后的进度。 */
    val move: Double,

    /** 离场行透明度用：0.98 → 0.16 的插值系数。 */
    val fadeOut: Double,

    /** 提升行透明度用：0.72 → 0.98 的插值系数。 */
    val fadeIn: Double
)

/**
 * 一次正在进行的行切换。
 *
 * [previous] 是**过渡开始时**那一帧的快照（数据类，不可变），
 * 里面带着它当时的 `positionMs`，所以离场行的已唱高亮会像原版一样「冻住」，
 * 不会在离场过程中继续推进。
 */
open class LineTransition(
    val mode: TransitionMode,
    val previous: LyricsFrame,
    private val startNanos: Long = System.nanoTime()
) {

    // open：探针要靠覆盖这两个方法把动画进度钉在某个固定值上。
    //
    // 生产环境永远走实时时钟；但探针要验证「某个确定进度的那一帧画得对不对」，
    // 不能容忍几十微秒的抖动（见 tools/probe/TransitionProbe.java 的说明）。
    open fun state(nowNanos: Long = System.nanoTime()): TransitionState = at(rawProgress(nowNanos))

    /** 按给定线性进度取状态。探针用它做确定性渲染（不依赖真实时钟）。 */
    fun at(rawProgress: Double): TransitionState {
        val t = rawProgress.coerceIn(0.0, 1.0)
        return TransitionState(
            raw = t,
            move = ROLL_EASING.value(t),
            fadeOut = fadeOutEase(t),
            fadeIn = fadeInEase(t)
        )
    }

    open fun finished(nowNanos: Long = System.nanoTime()): Boolean = rawProgress(nowNanos) >= 1.0

    private fun rawProgress(nowNanos: Long): Double =
        ((nowNanos - startNanos) / 1_000_000.0 / mode.durationMs).coerceIn(0.0, 1.0)

    companion object {

        /** `cubic-bezier(0.22, 0.72, 0.24, 1)`：`style.css` 的 `.track.animating`。 */
        val ROLL_EASING = CubicBezierEasing(0.22, 0.72, 0.24, 1.0)

        /** `app.js` `getFadeOutEase`。 */
        fun fadeOutEase(t: Double): Double {
            val n = (t / 0.74).coerceIn(0.0, 1.0)
            return if (n >= 0.97) 1.0 else easeOutCubic(n)
        }

        /** `app.js` `getFadeInEase`。 */
        fun fadeInEase(t: Double): Double {
            val n = (t / 0.72).coerceIn(0.0, 1.0)
            return if (n >= 0.96) 1.0 else easeOutCubic(n)
        }

        /** `app.js` `easeOutCubic`。 */
        fun easeOutCubic(t: Double): Double {
            val x = 1.0 - t.coerceIn(0.0, 1.0)
            return 1.0 - x * x * x
        }

        /** 没有上一帧时用的空帧：离场行是空文本，等于「只滚入、不滚出」。 */
        fun emptyFrame(): LyricsFrame =
            LyricsFrame("", null, null, emptyList(), 0L, 0L, 0L)
    }
}

/**
 * CSS cubic-bezier 求值。
 *
 * 先把 x(u) = t 解出参数 u（牛顿迭代，兜底二分），再算 y(u)。
 * 只在换行时用一次，精度取 1e-6 足够（1e-6 × 行距 15px = 0.000015px）。
 */
class CubicBezierEasing(
    private val x1: Double,
    private val y1: Double,
    private val x2: Double,
    private val y2: Double
) {
    fun value(t: Double): Double {
        if (t <= 0.0) return 0.0
        if (t >= 1.0) return 1.0

        var u = t
        var i = 0
        while (i < 8) {
            val dx = bezier(u, x1, x2) - t
            if (abs(dx) < 1e-6) return bezier(u, y1, y2)
            val d = bezierDerivative(u, x1, x2)
            if (abs(d) > 1e-9) {
                u = (u - dx / d).coerceIn(0.0, 1.0)
            }
            i++
        }

        var lo = 0.0
        var hi = 1.0
        var j = 0
        while (j < 24) {
            val mid = (lo + hi) / 2.0
            if (bezier(mid, x1, x2) < t) lo = mid else hi = mid
            j++
        }
        return bezier((lo + hi) / 2.0, y1, y2)
    }

    private fun bezier(u: Double, a: Double, b: Double): Double {
        val v = 1.0 - u
        return 3.0 * v * v * u * a + 3.0 * v * u * u * b + u * u * u
    }

    private fun bezierDerivative(u: Double, a: Double, b: Double): Double {
        val v = 1.0 - u
        return 3.0 * a * (v * v - 2.0 * v * u) +
            3.0 * b * (2.0 * v * u - u * u) +
            3.0 * u * u
    }
}
