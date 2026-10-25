package com.spwtb.taskbarlyrics.render

/**
 * 歌词区的显隐过渡：**淡出并轻微上移**，进场原路返回。
 *
 * 参数逐项照抄原版 `Web/Lyrics/style.css`，没有自己调：
 *
 * | 量 | 原版位置 | 值 |
 * | --- | --- | --- |
 * | 时长 | `--layer-transition-duration` | **300ms** |
 * | 位移 | `--layer-transition-offset` | **2px** |
 * | 曲线 | `.lyrics-layer` / `.spectrum-layer` 的 `transition` | `cubic-bezier(0.2, 0.9, 0.1, 1)` |
 * | 隐藏态 | `.layout.spectrum-mode .lyrics-layer` | `opacity: 0` + `translateY(-2px)` |
 * | 显示态 | `.lyrics-layer` | `opacity: 1` + `translateY(0)` |
 *
 * 也就是说：原版的歌词层「隐藏」不是消失，而是**淡到透明并往上挪 2px**；
 * 显示就是这条线原路走回来。两条 `__Layer` 规则用的是同一条 cubic-bezier，
 * 所以这里 opacity 与位移共用同一个插值系数 [level]。
 *
 * **必须可逆。** 暂停到一半又点播放时，如果从 0 重新淡入，会先"沉下去再浮上来"，
 * 看起来就是卡了一下。做法是切换目标时把**当前实际程度**当成新起点，
 * 而不是把它按到端点再走。
 */
class SurfaceFade(private val durationMs: Long = LAYER_TRANSITION_MS) {

    /** 当前目标：1 = 显示，0 = 隐藏。 */
    private var target = 1.0

    /** 起点的显现程度。 */
    private var from = 1.0

    private var startNanos = 0L

    /**
     * 切换显示目标。已经在往这个目标走时**不重启时间线**（幂等），
     * 否则每一帧都调一次的话动画永远走不完。
     */
    fun retarget(visible: Boolean, nowNanos: Long = System.nanoTime()) {
        val to = if (visible) 1.0 else 0.0
        if (to == target) {
            return
        }
        // 从当前实际程度继续，保证中途反向也平滑
        from = level(nowNanos)
        target = to
        startNanos = nowNanos
    }

    /**
     * 显现程度，0..1。1 = 完全显示（不透明、无位移），0 = 完全隐藏。
     */
    fun level(nowNanos: Long = System.nanoTime()): Double {
        if (from == target) {
            return target
        }
        val elapsed = (nowNanos - startNanos) / 1_000_000.0
        val t = (elapsed / durationMs).coerceIn(0.0, 1.0)
        return from + (target - from) * EASING.value(t)
    }

    /** 过渡是否已经走完。走完之后就不必再按动画帧率画了。 */
    fun settled(nowNanos: Long = System.nanoTime()): Boolean =
        kotlin.math.abs(level(nowNanos) - target) < 1e-4

    /** 是否正在动画中。 */
    fun animating(nowNanos: Long = System.nanoTime()): Boolean = !settled(nowNanos)

    /** 是否已经完全隐藏。 */
    fun hidden(nowNanos: Long = System.nanoTime()): Boolean =
        target == 0.0 && settled(nowNanos)

    /** 直接落到某个状态（不等动画）。 */
    fun snapTo(visible: Boolean) {
        target = if (visible) 1.0 else 0.0
        from = target
        startNanos = System.nanoTime()
    }

    /**
     * 显式设定起点、目标与起始时刻。
     *
     * 常规路径用 [retarget] 就够了，但**第一帧**必须走这里：那一帧的绘制本身
     * 可能吃掉几百毫秒，而过渡应该从画面**真正上屏**的那一刻开始计时。
     * 否则时间线早在观众看见任何东西之前就跑完了。
     */
    fun begin(from: Double, to: Double, nowNanos: Long = System.nanoTime()) {
        this.from = from
        this.target = to
        this.startNanos = nowNanos
    }

    /**
     * 新窗口刚建好：从「完全隐藏」起步。
     *
     * 和 [snapTo] 的区别在这一句上——这里连 **目标** 也是隐藏，
     * 于是随后的第一次 [retarget] 能正儿八经起一条 300ms 的淡入时间线。
     * 用 [snapTo] 是不行的：它会把 target 设成 false 之后立刻相等，
     * 第一帧照样满亮度。
     *
     * **必须在第一帧之前调用。** 少了这一步，窗口的第一帧就是满的，
     * 用户看到的就是「啪一下弹出来」——这正是要修的东西。
     */
    fun beginHidden(nowNanos: Long = System.nanoTime()) {
        from = 0.0
        target = 0.0
        startNanos = nowNanos
    }

    companion object {

        /** `style.css` 的 `--layer-transition-duration`。 */
        const val LAYER_TRANSITION_MS = 300L

        /** `style.css` 的 `--layer-transition-offset`，逻辑像素。 */
        const val LAYER_TRANSITION_OFFSET = 2.0

        /** `style.css` 的 `.lyrics-layer` 过渡曲线。 */
        val EASING = CubicBezierEasing(0.2, 0.9, 0.1, 1.0)

        /**
         * 竖直位移（物理像素，负值向上）。
         *
         * 完全显示时为 0，完全隐藏时为 `-2px × 布局缩放`。
         * 位移与透明度共用同一个插值系数，和原版两条 `__Layer` 规则的行为一致。
         */
        @JvmStatic
        fun offsetPx(level: Double, layout: Double): Double =
            -LAYER_TRANSITION_OFFSET * (1.0 - level) * layout
    }
}
