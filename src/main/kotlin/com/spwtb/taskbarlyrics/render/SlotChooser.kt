package com.spwtb.taskbarlyrics.render

/**
 * 空闲区选择：**粘住上一次的选择**，不让歌词窗口左右横跳。
 *
 * ## 为什么要这一步
 *
 * 每轮重扫都按锚点重新挑「最好的」那一块是不够稳的。任务栏上的东西随时在变：
 * 网速浮窗多一位数字、托盘时钟从 `9:05` 变 `12:34`、输入法图标进出——空闲区的
 * 边界会跟着抖几个像素。只要两块空闲区的宽窄接近，每次抖动都可能让「最宽的
 * 那块」换人，歌词就闪到另一边去了。
 *
 * 实测过一个 2560px 宽的任务栏上有 `[558..1116]` 和 `[1578..2017]` 两块，
 * 每 500ms 交替成为「最宽」，歌词 10 秒内横跳 20 次，跨度 1018px。
 *
 * ## 怎么做
 *
 * 加迟滞：当前这一块还放得下就继续用它，只有另一块**明显**更好才过去
 * （同时满足 [switchMinPx] 和 [switchMarginPercent]），或者当前这块已经没了。
 * 用户改锚点时是例外——那时候必须听他的。
 */
class SlotChooser @JvmOverloads constructor(
    /** 窄于这个宽度的空闲区不予考虑。 */
    private val minSlotWidth: Int = DEFAULT_MIN_SLOT_WIDTH,
    /** 换过去的门槛之一：至少要宽出这么多像素。 */
    private val switchMinPx: Int = DEFAULT_SWITCH_MIN_PX,
    /** 换过去的门槛之二：还要宽出这个百分比。 */
    private val switchMarginPercent: Double = DEFAULT_SWITCH_MARGIN_PERCENT
) {

    private var last: Win32.Slot? = null
    private var lastAnchor: TaskbarAnchor? = null

    /**
     * 从 [rawSlots] 里挑一块。返回 null 表示一块合格的都没有。
     *
     * @param taskbar 只用于 `CENTER` 锚点算屏幕中心
     */
    fun choose(
        rawSlots: List<Win32.Slot>,
        anchor: TaskbarAnchor,
        taskbar: Win32.Taskbar
    ): Win32.Slot? {
        val candidates = rawSlots.filter { it.width >= minSlotWidth }
        if (candidates.isEmpty()) {
            return null
        }
        val ideal = pickSlot(candidates, anchor, taskbar)

        // 用户刚改过锚点：这次必须听他的，不能被迟滞粘在老地方
        val changed = lastAnchor != anchor
        lastAnchor = anchor
        if (changed) {
            last = ideal
            return ideal
        }

        val previous = last
        val keep = if (previous == null) {
            null
        } else {
            // 不看起点是否完全相同——任务栏上任何元素宽度变化都会挪动边界。
            // 看**重叠**：新列表里跟上一块重叠最多的那块，就是「还是它」。
            candidates.maxByOrNull { overlap(it, previous) }
                ?.takeIf { overlap(it, previous) >= previous.width / 2 }
        }

        if (keep != null) {
            val worthwhile = ideal.start != keep.start
                && ideal.width - keep.width >= switchMinPx
                && ideal.width >= keep.width * (1.0 + switchMarginPercent / 100.0)
            if (!worthwhile) {
                last = keep
                return keep
            }
        }
        last = ideal
        return ideal
    }

    /** 忘掉上一次的选择（下一轮会按锚点重新挑）。 */
    fun reset() {
        last = null
        lastAnchor = null
    }

    private fun overlap(a: Win32.Slot, b: Win32.Slot): Int {
        val aEnd = a.start + a.width
        val bEnd = b.start + b.width
        return (minOf(aEnd, bEnd) - maxOf(a.start, b.start)).coerceAtLeast(0)
    }

    companion object {

        const val DEFAULT_MIN_SLOT_WIDTH = 140

        const val DEFAULT_SWITCH_MIN_PX = 60

        const val DEFAULT_SWITCH_MARGIN_PERCENT = 25.0

        /** 按锚点挑一块出来。 */
        fun pickSlot(
            slots: List<Win32.Slot>, anchor: TaskbarAnchor, taskbar: Win32.Taskbar
        ): Win32.Slot = when (anchor) {
            TaskbarAnchor.LEFT -> slots.first()
            TaskbarAnchor.RIGHT -> slots.last()
            TaskbarAnchor.CENTER -> {
                val center = taskbar.left + taskbar.width() / 2
                slots.minBy { kotlin.math.abs(it.start + it.width / 2 - center) }
            }
        }
    }
}
