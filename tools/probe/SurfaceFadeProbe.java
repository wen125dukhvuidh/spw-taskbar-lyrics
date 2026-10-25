import com.spwtb.taskbarlyrics.render.SurfaceFade;

/**
 * 显隐过渡（[SurfaceFade]）的机械验证。
 *
 * 验证的都是能算出来的量，不靠眼睛看动画：
 * 1. 时长是 300ms（原版 --layer-transition-duration）；
 * 2. 每一刻的程度严格等于 `from + (to - from) × cubic-bezier(0.2, 0.9, 0.1, 1)(t/300ms)`；
 * 3. **可逆**：淡出到一半改为显示，程度从当前值接着往上走，而不是沉到 0 再浮上来；
 * 4. 幂等：重复设置同一个目标不会重启时间线（否则动画永远走不完）；
 * 5. 位移随程度线性：完全隐藏 -2px（× 布局缩放），完全显示 0。
 *
 * 进度用虚拟时钟钉死，不依赖真实耗时，所以这些断言是**确定性**的。
 */
public class SurfaceFadeProbe {

    static int passed = 0;
    static int failed = 0;

    /** 虚拟时钟起点，随意一个正数即可。 */
    static final long T0 = 1_000_000_000L;

    static final long MS = 1_000_000L;

    public static void main(String[] args) {
        System.out.println("=== 显隐淡入淡出（SurfaceFade）===");
        System.out.println("参数：300ms / 上移 2px / cubic-bezier(0.2, 0.9, 0.1, 1)");
        System.out.println("出处：原版 Web/Lyrics/style.css 的 --layer-transition-duration、");
        System.out.println("      --layer-transition-offset、.lyrics-layer 的 transition\n");

        // ── 1) 初始状态：完全显示 ────────────────────────────────────────
        SurfaceFade f = new SurfaceFade();
        check("初始是完全显示", f.level(T0) == 1.0, "level=" + f.level(T0));
        check("初始已静止", f.settled(T0), "不该在动");
        check("初始位移为 0", SurfaceFade.offsetPx(f.level(T0), 1.5) == 0.0,
                "offset=" + SurfaceFade.offsetPx(f.level(T0), 1.5));

        // ── 2) 淡出：时长与曲线 ──────────────────────────────────────────
        System.out.println("--- 淡出（隐藏）---");
        f.retarget(false, T0);
        check("刚发起时不等于旧值", f.level(T0) == 1.0, "起点应是 1.0");
        check("刚开始动画", f.animating(T0), "应该在动");

        // 用一条独立的 cubic-bezier 复算期望值，避免拿实现自己验证自己
        double maxErr = 0;
        for (int i = 0; i <= 10; i++) {
            double t = i / 10.0;
            double expected = 1.0 - bezier(t);
            double actual = f.level(T0 + (long) (t * 300 * MS));
            maxErr = Math.max(maxErr, Math.abs(expected - actual));
        }
        check("淡出曲线 = 1 − cubic-bezier(0.2,0.9,0.1,1)", maxErr < 1e-4,
                "最大误差 " + maxErr);

        check("300ms 后完全隐藏", f.level(T0 + 300 * MS) == 0.0,
                "level=" + f.level(T0 + 300 * MS));
        check("300ms 后已静止", f.settled(T0 + 300 * MS), "不该还在动");
        check("300ms 后判定为 hidden", f.hidden(T0 + 300 * MS), "应该算完全隐藏");
        check("超过 300ms 不会越界", f.level(T0 + 5000 * MS) == 0.0,
                "level 应被夹在 0");

        // 单调性：取样要做，因为 cubic-bezier(0.2,0.9,0.1,1) 不是线性，
        // 但程度必须**单调递减**（不能有回弹）
        boolean monotonic = true;
        double prev = 2.0;
        for (int i = 0; i <= 60; i++) {
            double v = f.level(T0 + (long) (i * 5 * MS));
            if (v > prev + 1e-9) {
                monotonic = false;
            }
            prev = v;
        }
        check("淡出过程单调不回弹", monotonic, "出现了回升");

        // ── 3) 位移 ─────────────────────────────────────────────────────
        System.out.println("--- 位移 ---");
        double hiddenOffset = SurfaceFade.offsetPx(0.0, 1.5);
        check("完全隐藏时上移 2px × 1.5 = 3px", hiddenOffset == -3.0,
                "实际 " + hiddenOffset + "（负值 = 向上）");
        check("显示时位移 0", SurfaceFade.offsetPx(1.0, 1.5) == 0.0, "");
        check("半程位移 -1.5px", SurfaceFade.offsetPx(0.5, 1.5) == -1.5, "");

        // ── 4) 淡入 ─────────────────────────────────────────────────────
        System.out.println("--- 淡入（恢复显示）---");
        f.retarget(true, T0 + 300 * MS);
        check("淡入 300ms 后完全显示", f.level(T0 + 600 * MS) == 1.0,
                "level=" + f.level(T0 + 600 * MS));
        double inHalf = f.level(T0 + 300 * MS + 150 * MS);
        double expectHalf = bezier(0.5);
        check("淡入中点 = cubic-bezier(0.5)", Math.abs(inHalf - expectHalf) < 1e-4,
                "实际 " + inHalf + "，期望 " + expectHalf);

        // ── 5) 可逆性：这是"丝滑"的关键 ──────────────────────────────────
        System.out.println("--- 中期反向（暂停到一半又播放）---");
        SurfaceFade g = new SurfaceFade();
        g.retarget(false, T0);
        double mid = g.level(T0 + 150 * MS);
        check("150ms 时既非 0 也非 1", mid > 0.01 && mid < 0.99, "level=" + mid);

        // 从这一刻改为显示
        g.retarget(true, T0 + 150 * MS);
        double justAfter = g.level(T0 + 150 * MS);
        check("反向瞬间不跳变（接着走，不是沉到 0）",
                Math.abs(justAfter - mid) < 1e-9,
                "反向前 " + mid + "，反向后瞬间 " + justAfter);
        double after75 = g.level(T0 + 225 * MS);
        check("反向后继续上升", after75 > mid, "level " + mid + " → " + after75);
        check("反向后 300ms 到达完全显示",
                g.level(T0 + 150 * MS + 300 * MS) == 1.0,
                "level=" + g.level(T0 + 150 * MS + 300 * MS));

        // 再从半路往消失方向反一次
        SurfaceFade h = new SurfaceFade();
        h.retarget(false, T0);
        double m2 = h.level(T0 + 100 * MS);
        h.retarget(true, T0 + 100 * MS);
        double upMid = h.level(T0 + 100 * MS + 120 * MS);
        h.retarget(false, T0 + 220 * MS);
        double back = h.level(T0 + 220 * MS);
        check("往上走到一半再往下也不跳变", Math.abs(back - upMid) < 1e-9,
                "往上的值 " + upMid + "，反向瞬间 " + back);
        check("二次反向仍不到 0", upMid > m2, "应该在 m2=" + m2 + " 之上");
        check("二次反向后 300ms 到 0",
                h.level(T0 + 220 * MS + 300 * MS) == 0.0,
                "level=" + h.level(T0 + 220 * MS + 300 * MS));

        // ── 6) 幂等 ─────────────────────────────────────────────────────
        System.out.println("--- 幂等（同一目标重复设）---");
        SurfaceFade i2 = new SurfaceFade();
        i2.retarget(false, T0);
        double beforeRepeat = i2.level(T0 + 200 * MS);
        i2.retarget(false, T0 + 200 * MS);   // 目标没变，不该重启
        double afterRepeat = i2.level(T0 + 200 * MS);
        check("重复设同一目标不重启时间线", beforeRepeat == afterRepeat,
                "重复前后 " + beforeRepeat + " / " + afterRepeat);
        check("重复设同一目标后仍能按时走完",
                i2.level(T0 + 300 * MS) == 0.0, "level=" + i2.level(T0 + 300 * MS));

        // ── 7) snapTo ───────────────────────────────────────────────────
        SurfaceFade j = new SurfaceFade();
        j.retarget(false, T0);
        j.snapTo(false);
        check("snapTo(false) 立刻为 0", j.level(T0 + 1 * MS) == 0.0, "");
        check("snapTo 后已静止", j.settled(T0 + 1 * MS), "");
        j.snapTo(true);
        check("snapTo(true) 立刻为 1", j.level(T0 + 2 * MS) == 1.0, "");

        System.out.println();
        System.out.println(failed == 0
                ? "全部通过：" + passed + " 项"
                : failed + " 项失败 / " + passed + " 项通过");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 独立实现的 cubic-bezier(0.2, 0.9, 0.1, 1) 求值，用来复算期望值。
     *
     * 不用被测代码里的那个实现——拿同一个函数验证自己是循环论证。
     * 这里用最笨的二分：只为求出满足 x(u)=t 的那个 u，二分足够精确到 1e-12。
     */
    static double bezier(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        double x1 = 0.2, x2 = 0.1;
        double y1 = 0.9, y2 = 1.0;
        double lo = 0, hi = 1;
        for (int i = 0; i < 60; i++) {
            double u = (lo + hi) / 2;
            double x = 3 * (1 - u) * (1 - u) * u * x1 + 3 * (1 - u) * u * u * x2 + u * u * u;
            if (x < t) lo = u; else hi = u;
        }
        double u = (lo + hi) / 2;
        return 3 * (1 - u) * (1 - u) * u * y1 + 3 * (1 - u) * u * u * y2 + u * u * u;
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + (detail.isEmpty() ? "" : "  —— " + detail));
        }
    }
}
