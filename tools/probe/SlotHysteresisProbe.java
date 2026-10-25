import com.spwtb.taskbarlyrics.render.SlotChooser;
import com.spwtb.taskbarlyrics.render.TaskbarAnchor;
import com.spwtb.taskbarlyrics.render.Win32;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * 验证空闲区选择的迟滞，防止歌词窗口左右横跳。
 *
 * 场景直接取自真实机器的 observed 数据：2560px 宽的任务栏上有
 * [558..1116 w=558] 和 [1578..2017 w=439] 两块空闲区，anchor=WIDEST。
 * 每 500ms 重扫一次，两块交替成为「最宽」（因为窗口自己占掉一块，见
 * SlotStabilityProbe），歌词就在两边来回闪。
 */
public class SlotHysteresisProbe {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name + (detail.isEmpty() ? "" : "  — " + detail));
        } else {
            fail++;
            System.out.println("  ✗ " + name + "  — " + detail);
        }
    }

    /** 反射造一个 Slot —— 构造函数是包级私有的，探针在默认包里 new 不了。 */
    static Win32.Slot slot(int start, int width) throws Exception {
        Constructor<Win32.Slot> c = Win32.Slot.class.getDeclaredConstructor(int.class, int.class);
        c.setAccessible(true);
        return c.newInstance(start, width);
    }

    /** Taskbar 的构造函数是包级私有的，反射造一个。只有 CENTER 锚点会读 left/right。 */
    static Win32.Taskbar taskbar(int left, int right) throws Exception {
        Win32.RECT r = new Win32.RECT();
        r.left = left;
        r.top = 0;
        r.right = right;
        r.bottom = 72;
        java.lang.reflect.Constructor<?> c = Win32.Taskbar.class.getDeclaredConstructors()[0];
        c.setAccessible(true);
        return (Win32.Taskbar) c.newInstance((com.sun.jna.Pointer) null, r, 144, false);
    }

    public static void main(String[] args) throws Exception {
        Win32.Taskbar bar = taskbar(0, 2560);

        System.out.println("=== 1) 复现场景：两块空闲区，anchor=WIDEST ===");
        // 真实观察到的两块
        Win32.Slot a = slot(558, 558);
        Win32.Slot b = slot(1578, 439);
        List<Win32.Slot> both = List.of(a, b);
        System.out.printf("  空闲区：[%d..%d w=%d]  与  [%d..%d w=%d]%n",
                a.start, a.start + a.width, a.width, b.start, b.start + b.width, b.width);

        SlotChooser chooser = new SlotChooser();
        List<Integer> seq = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Win32.Slot pick = chooser.choose(both, TaskbarAnchor.WIDEST, bar);
            seq.add(pick.start);
        }
        System.out.println("  连续 20 轮选择：" + seq);
        long distinct = seq.stream().distinct().count();
        check("20 轮选择恒定在同一块空闲区", distinct == 1,
                "不同取值 " + distinct + " 个");
        check("选的是最宽的那块（558）", seq.get(0) == 558, "实际选了 " + seq.get(0));

        System.out.println();
        System.out.println("=== 2) 边界抖动：两块宽度来回微小变化 ===");
        // 模拟托盘时钟/网速浮窗宽度变化导致的 ±6px 抖动
        chooser.reset();
        List<Integer> jitter = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int wobble = (i % 2 == 0) ? 6 : -6;
            List<Win32.Slot> s = List.of(
                    slot(558, 558 + wobble),
                    slot(1578, 439 - wobble));
            jitter.add(chooser.choose(s, TaskbarAnchor.WIDEST, bar).start);
        }
        long jd = jitter.stream().distinct().count();
        System.out.println("  30 轮抖动后的选择：" + jitter);
        check("±6px 抖动下位置不换", jd == 1, "不同取值 " + jd + " 个");

        System.out.println();
        System.out.println("=== 3) 真的大变布局：当前这块没了，必须让位 ===");
        chooser.reset();
        chooser.choose(both, TaskbarAnchor.WIDEST, bar);   // 先占住 558
        // 现在任务栏上开了一堆窗口，558 那块被压缩到不足 140
        Win32.Slot squeezed = slot(558, 100);
        Win32.Slot survivor = slot(1800, 300);
        Win32.Slot moved = chooser.choose(List.of(squeezed, survivor), TaskbarAnchor.WIDEST, bar);
        check("当前块放不下时让位给下一块", moved.start == 1800, "实际选了 " + moved.start);

        System.out.println();
        System.out.println("=== 4) 另一块真的明显更宽时，应该过去 ===");
        // 语义：歌词一直待在 small 那块（当时只有它能用），后来 huge 那块才空出来
        chooser.reset();
        Win32.Slot small = slot(558, 200);
        Win32.Slot huge = slot(1800, 900);          // 宽出 700px，远超 60px 和 25%
        Win32.Slot before4 = chooser.choose(List.of(small), TaskbarAnchor.WIDEST, bar);
        check("先占住小块", before4.start == 558, "实际 " + before4.start);
        Win32.Slot after = chooser.choose(List.of(small, huge), TaskbarAnchor.WIDEST, bar);
        check("明显更宽时选择最优块", after.start == 1800, "实际选了 " + after.start);

        System.out.println();
        System.out.println("=== 5) 迟滞阈值边界 ===");
        // 略超 60px 但未达 25%：不该切
        chooser.reset();
        Win32.Slot cur = slot(500, 600);
        Win32.Slot cand = slot(1500, 670);          // +70px，但只多 11.7% < 25%
        chooser.choose(List.of(cur), TaskbarAnchor.WIDEST, bar);
        Win32.Slot keep1 = chooser.choose(List.of(cur, cand), TaskbarAnchor.WIDEST, bar);
        check("只宽出 11.7% 时不切换", keep1.start == 500, "实际 " + keep1.start);
        // 达到 25% 但仅差 52px：不该切
        chooser.reset();
        Win32.Slot cur2 = slot(500, 200);
        Win32.Slot cand2 = slot(1500, 252);         // 多 26%，但只多 52px < 60
        chooser.choose(List.of(cur2), TaskbarAnchor.WIDEST, bar);
        Win32.Slot keep2 = chooser.choose(List.of(cur2, cand2), TaskbarAnchor.WIDEST, bar);
        check("多 26% 但不足 60px 时不切换", keep2.start == 500, "实际 " + keep2.start);
        // 同时满足：该切
        chooser.reset();
        Win32.Slot cur3 = slot(500, 400);
        Win32.Slot cand3 = slot(1500, 560);         // +160px (+40%)
        chooser.choose(List.of(cur3), TaskbarAnchor.WIDEST, bar);
        Win32.Slot sw3 = chooser.choose(List.of(cur3, cand3), TaskbarAnchor.WIDEST, bar);
        check("同时满足两档门槛才切换", sw3.start == 1500, "实际 " + sw3.start);

        System.out.println();
        System.out.println("=== 6) 用户改锚点：必须立刻听他的 ===");
        chooser.reset();
        chooser.choose(both, TaskbarAnchor.WIDEST, bar);   // 占住 558（最宽）
        Win32.Slot left = chooser.choose(both, TaskbarAnchor.LEFT, bar);
        check("切到 LEFT 立刻生效", left.start == 558, "实际 " + left.start);
        chooser.reset();
        chooser.choose(both, TaskbarAnchor.WIDEST, bar);
        Win32.Slot right = chooser.choose(both, TaskbarAnchor.RIGHT, bar);
        check("切到 RIGHT 立刻生效", right.start == 1578, "实际 " + right.start);

        System.out.println();
        System.out.println("=== 7) 一块都没有时返回 null ===");
        chooser.reset();
        Win32.Slot none = chooser.choose(List.of(slot(0, 50), slot(100, 80)),
                TaskbarAnchor.WIDEST, bar);
        check("全都不合格时返回 null", none == null, "实际 " + none);

        System.out.println();
        System.out.printf("结果：%d 项通过，%d 项失败%n", pass, fail);
        System.out.println(fail == 0
                ? "✓ 迟滞逻辑成立，歌词窗口不会再左右横跳"
                : "✗ 存在失败项，需要修");
    }
}
