import com.spwtb.taskbarlyrics.lyrics.LyricsStore;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.TaskbarRenderer;
import com.spwtb.taskbarlyrics.render.Win32;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 窗口「生」与「死」两端到底有没有动画——两端各跑一遍**真实的渲染线程**来量。
 *
 * 为什么不能靠截图对比：
 * 1. `PrintWindow` 读的是 DWM 合成**之前**的内容，每像素 alpha 恒 255，
 *    `sourceConstantAlpha` 完全验不出来（实测 alpha=0 与 255 抓出来墨量都是 6609600）；
 * 2. 显示器关/锁屏时从屏幕 DC `BitBlt` 只能抓到全黑。
 *
 * 所以看两个机器读数，合起来才算证据：
 * - **窗口整体透明度的逐帧留档**（渲染线程自己写的），证明透明度确实按曲线在变；
 * - **`stop()` 到 `destroy` 之间真实经过的时间**，证明窗口是被**等**完动画才拆的。
 *   只补一段动画却先把窗口拆了，画面上一帧都看不到——这两件事必须分开验。
 *
 * 对照组：`animateAutoHide = false` 时这两项必须同时塌掉（stop 立刻返回、
 * 留档里没有过渡段），否则那 300ms 的来源没法归因到这段动画头上。
 *
 * 判据不许来自被测代码：期望值一律由探针里这份**独立实现**的 cubic-bezier 算出。
 */
public class WindowFadeProbe {

    interface U extends com.sun.jna.win32.StdCallLibrary {
        U I = (U) com.sun.jna.Native.load("user32", U.class);

        Pointer FindWindowExW(Pointer parent, Pointer after, WString cls, WString name);
    }

    /** 原版 `.lyrics-layer` 的曲线控制点与时长。 */
    static final double X1 = 0.2, Y1 = 0.9, X2 = 0.1, Y2 = 1.0;
    static final long DURATION_MS = 300L;

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 0) 环境 ===");
        System.out.println("  Win32.isAvailable = " + Win32.isAvailable()
                + (Win32.isAvailable() ? "" : "  " + Win32.loadError()));
        if (!Win32.isAvailable()) {
            return;
        }
        List<Win32.Taskbar> bars = Win32.findTaskbars();
        if (bars.isEmpty()) {
            System.out.println("  找不到任务栏，退出");
            return;
        }
        Win32.Taskbar bar = bars.get(0);
        System.out.println("  " + bar);

        System.out.println();
        System.out.println("=== 1) 理论判据（独立算出来的，不碰被测代码）===");
        System.out.println("  归一化收尾时刻 = " + String.format("%.4f", predictedEndT()));
        System.out.println("  对应时长 = " + String.format("%.1f", predictedEndT() * DURATION_MS) + "ms");

        seedLyrics();

        scenario("A) animateAutoHide = true（应当有动画）", bar, true);
        scenario("B) animateAutoHide = false（对照组，应当一刀切）", bar, false);

        System.out.println();
        System.out.println("==== 汇总：" + passed + " 项通过，" + failed + " 项失败 ====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void scenario(String name, Win32.Taskbar bar, boolean animate) throws Exception {
        System.out.println();
        System.out.println("=== " + name + " ===");

        final RenderSettings[] holder = { new RenderSettings().withAnimateAutoHide(animate) };
        TaskbarRenderer renderer = new TaskbarRenderer(() -> holder[0]);

        // 启动前先记下任务栏上**已经存在**的歌词窗口句柄。
        //
        // 不能只以「任务栏下有没有这个类名」判断存亡：用户很可能正开着 SPW，
        // 真插件的窗口就挂在那儿，那样 stop() 之后照样能找到一个，误判成没拆。
        // （本机实测就撞上了：SPW 在跑，19 项里这两条全红，与被测代码无关。）
        // 正确做法是认**自己这一个**句柄：启动前的集合 + 启动后多出来的那个。
        java.util.Set<Pointer> before = new java.util.HashSet<>(allLyricsWindows(bar.hwnd));
        System.out.println("  启动前任务栏上已有 " + before.size() + " 个歌词窗口（多半是真插件的）");

        long t0 = System.nanoTime();
        renderer.start();

        Pointer hwnd = null;
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            hwnd = firstNewWindow(bar.hwnd, before);
            if (hwnd != null) {
                break;
            }
            Thread.sleep(5);
        }
        if (hwnd == null) {
            check(false, "窗口必须出现", "8 秒内没等到新窗口");
            renderer.stop();
            return;
        }
        System.out.println("  窗口出现用了 " + (System.nanoTime() - t0) / 1_000_000L + "ms");

        // 跑够长：让整段淡入落进留档并稳定下来
        Thread.sleep(1500);

        List<TaskbarRenderer.AlphaSample> boot = renderer.alphaTrace();
        System.out.println("  启动段留档 " + boot.size() + " 条：" + compact(boot));
        checkBootFade(boot, animate);

        List<TaskbarRenderer.AlphaSample> all = renderer.alphaTrace();
        int stableAlpha = all.get(all.size() - 1).getAlpha();
        System.out.println("  稳定时的整体透明度 = " + stableAlpha);
        check(stableAlpha == 255, "稳定后必须完全不透明", "实际 " + stableAlpha);

        // ── 关闭 ──────────────────────────────────────────────────────
        long s0 = System.nanoTime();
        renderer.stop();
        long stopMs = (System.nanoTime() - s0) / 1_000_000L;
        System.out.println("  stop() 耗时 " + stopMs + "ms");

        boolean gone = !allLyricsWindows(bar.hwnd).contains(hwnd);
        check(gone, "stop() 返回后窗口必须已经拆掉（按句柄认，不是按类名）",
                "句柄 " + hwnd + " 仍在");

        List<TaskbarRenderer.AlphaSample> exit = tailSince(renderer.alphaTrace(), s0);
        System.out.println("  退出段留档 " + exit.size() + " 条：" + compact(exit));

        if (animate) {
            check(stopMs >= 250, "开动画时 stop() 必须等满淡出（≥250ms）", "实际 " + stopMs + "ms");
            check(!exit.isEmpty() && exit.get(0).getAlpha() == 255,
                    "淡出起点必须是完全不透明",
                    exit.isEmpty() ? "没有留档" : "实际 " + exit.get(0).getAlpha());
            check(exit.size() >= 5, "淡出必须推过多个不同的透明度（不是一步到 0）",
                    "只有 " + exit.size() + " 条");
            checkMonotone(exit, false);
            check(!exit.isEmpty() && exit.get(exit.size() - 1).getAlpha() == 0,
                    "淡出必须收尾到 0", exit.isEmpty() ? "没有留档" : "没有");
            compareMidBand(exit, "淡出", false);
        } else {
            check(stopMs <= 200, "关动画时 stop() 不该等动画（≤200ms）", "实际 " + stopMs + "ms");
            check(exit.size() <= 1, "关动画时不该出现渐进的透明度序列",
                    "却有 " + exit.size() + " 条：" + compact(exit));
        }
    }

    // ── 断言 ──────────────────────────────────────────────────────────

    static void checkBootFade(List<TaskbarRenderer.AlphaSample> trace, boolean animate) {
        if (trace.isEmpty()) {
            check(false, "启动段必须有透明度留档", "一条都没有");
            return;
        }
        int first = trace.get(0).getAlpha();
        System.out.println("  首帧透明度 = " + first);
        if (!animate) {
            check(first == 255, "关动画时首帧就应当是完全不透明", "实际 " + first);
            check(trace.size() == 1, "关动画时不该有淡入过程", "却有 " + trace.size() + " 条");
            return;
        }
        check(first <= 60, "开动画时首帧必须是接近全透明的起点", "实际 " + first);
        checkMonotone(trace, true);
        check(trace.size() >= 5, "淡入必须经过多个不同的透明度", "只有 " + trace.size() + " 条");
        int last = trace.get(trace.size() - 1).getAlpha();
        check(last == 255, "淡入必须走到完全不透明", "实际 " + last);
        compareMidBand(trace, "淡入", true);
    }

    /**
     * 拿中段两个点的**时间差**跟理论曲线对表。
     *
     * 不用「首帧 → 收尾」这个跨度，因为两头都不干净：
     * - 首帧那边，时间线的原点是"画面落地"那一刻（必要时会被重新设定），
     *   比留档里第一条可能晚几十毫秒；
     * - 收尾那边，8 位 alpha 早就饱和了（`程度 × 255 < 0.5` 即归零），
     *   而状态机要收到 1e-4 才算停，尾巴上会多挂几帧同值的记录。
     *
     * 取 192 与 64 这两个点相减，两头都被绕开，而且这个差值与"时间线从哪开始"无关，
     * 可以直接和独立算出来的曲线比。
     */
    static void compareMidBand(List<TaskbarRenderer.AlphaSample> trace, String what, boolean rising) {
        int iFirst = -1, iSecond = -1;
        for (int i = 0; i < trace.size(); i++) {
            int a = trace.get(i).getAlpha();
            boolean hit = rising ? a >= 64 : a <= 192;
            if (hit && iFirst < 0) {
                iFirst = i;
            }
            boolean hit2 = rising ? a >= 192 : a <= 64;
            if (hit2 && iSecond < 0) {
                iSecond = i;
            }
        }
        if (iFirst < 0 || iSecond < 0) {
            check(false, what + "必须完整经过 α=192 → α=64 这一段", compact(trace));
            return;
        }
        long measuredMs = (trace.get(iSecond).getNanos() - trace.get(iFirst).getNanos()) / 1_000_000L;
        // 把 alpha 换回「显现程度」再反查时刻：淡出方向上程度和 alpha 是反的
        // （alpha = round(程度 × 255)，而程度 = 1 - 曲线(t)），不换回来就会算出负的时间。
        double firstLevel = rising ? 64.0 / 255 : 1 - 192.0 / 255;
        double secondLevel = rising ? 192.0 / 255 : 1 - 64.0 / 255;
        long expectMs = Math.round(
                (invLevel(secondLevel) - invLevel(firstLevel)) * DURATION_MS * 1000) / 1000L;
        System.out.println("  " + what + "中段 192→64 实测 " + measuredMs + "ms（理论 " + expectMs + "ms）");
        // 容差取一帧多一点：帧间隔本身就是 16~20ms，留档只到帧粒度
        check(Math.abs(measuredMs - expectMs) <= 35,
                what + "的中段节奏应与理论曲线吻合（±35ms）",
                "实测 " + measuredMs + "ms，理论 " + expectMs + "ms");
    }

    /** 反查：给定的「显现程度」出现在哪个归一化时刻。曲线单调，二分即可。 */
    static double invLevel(double level) {
        double lo = 0.0, hi = 1.0;
        for (int i = 0; i < 80; i++) {
            double m = (lo + hi) / 2;
            if (bezier(m) < level) {
                lo = m;
            } else {
                hi = m;
            }
        }
        return (lo + hi) / 2;
    }

    static void checkMonotone(List<TaskbarRenderer.AlphaSample> trace, boolean rising) {
        boolean ok = true;
        for (int i = 1; i < trace.size(); i++) {
            int a = trace.get(i).getAlpha(), b = trace.get(i - 1).getAlpha();
            if (rising ? a < b : a > b) {
                ok = false;
                break;
            }
        }
        check(ok, (rising ? "启动段" : "退出段") + "透明度必须单调" + (rising ? "不降" : "不升"),
                compact(trace));
    }

    /**
     * 归一化的「拖到看不见」时刻：最小的 t 使得 `1 - 曲线(t) < 0.5/255`。
     *
     * 淡入与淡出是同一条曲线的两个方向，所以同一个 t 对两段都成立。
     */
    static double predictedEndT() {
        double lo = 0.0, hi = 1.0;
        for (int i = 0; i < 80; i++) {
            double m = (lo + hi) / 2;
            if (bezier(m) > 1.0 - 0.5 / 255.0) {
                hi = m;
            } else {
                lo = m;
            }
        }
        return (lo + hi) / 2;
    }

    /** 独立实现的 cubic-bezier(x1,y1,x2,y2)：给定 x 求 y。 */
    static double bezier(double x) {
        double lo = 0.0, hi = 1.0;
        for (int i = 0; i < 80; i++) {
            double m = (lo + hi) / 2;
            if (cubic(m, X1, X2) < x) {
                lo = m;
            } else {
                hi = m;
            }
        }
        return cubic((lo + hi) / 2, Y1, Y2);
    }

    static double cubic(double u, double a, double b) {
        double v = 1.0 - u;
        return 3 * v * v * u * a + 3 * v * u * u * b + u * u * u;
    }

    static List<TaskbarRenderer.AlphaSample> tailSince(
            List<TaskbarRenderer.AlphaSample> all, long fromNanos) {
        List<TaskbarRenderer.AlphaSample> out = new ArrayList<>();
        for (TaskbarRenderer.AlphaSample s : all) {
            if (s.getNanos() >= fromNanos) {
                out.add(s);
            }
        }
        return out;
    }

    static String compact(List<TaskbarRenderer.AlphaSample> trace) {
        StringBuilder sb = new StringBuilder("[");
        int n = trace.size();
        long base = n > 0 ? trace.get(0).getNanos() : 0L;
        for (int i = 0; i < n; i++) {
            if (n > 14 && i >= 6 && i < n - 6) {
                if (i == 6) {
                    sb.append("… ");
                }
                continue;
            }
            if (i > 0) {
                sb.append(' ');
            }
            sb.append((trace.get(i).getNanos() - base) / 1_000_000L)
                    .append("ms:").append(trace.get(i).getAlpha());
        }
        return sb.append(']').toString();
    }

    static Pointer findLyricsWindow(Pointer tray) {
        return U.I.FindWindowExW(tray, null, new WString(Win32.LYRICS_WINDOW_CLASS), null);
    }

    /**
     * 任务栏下**所有**类名为歌词窗口的子窗口句柄。
     *
     * `FindWindowExW` 传入上一个句柄就能接着往下枚举，用它代替枚举回调，
     * 不必给 `Win32` 再挂 `EnumChildWindows`。
     */
    static java.util.List<Pointer> allLyricsWindows(Pointer tray) {
        java.util.List<Pointer> out = new java.util.ArrayList<>();
        Pointer prev = null;
        WString cls = new WString(Win32.LYRICS_WINDOW_CLASS);
        while (true) {
            Pointer next = U.I.FindWindowExW(tray, prev, cls, null);
            if (next == null) {
                break;
            }
            out.add(next);
            prev = next;
        }
        return out;
    }

    /** 启动后多出来的那一个窗口；没有就返回 null。 */
    static Pointer firstNewWindow(Pointer tray, java.util.Set<Pointer> before) {
        for (Pointer p : allLyricsWindows(tray)) {
            if (!before.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 往真正的数据源里塞一首「正在播放」的歌。
     *
     * 走的是宿主回调的那几个入口（不是探针自己造 frame），所以渲染线程、行选择、
     * 申请画布走的都是生产路径。行跨度给到 10 分钟，避免探测期间换行。
     */
    static void seedLyrics() {
        LyricsStore.INSTANCE.onSongChanged(new PlaybackExtensionPoint.MediaItem(
                "WindowFadeProbe", "探针", "自检", "自检",
                "Z:/__nonexistent__/fade-probe.flac"));
        LyricsStore.INSTANCE.onLyricLineUpdated(new PlaybackExtensionPoint.LyricsLine(
                0L, 600_000L, Collections.emptyList(), "窗口生灭字幕渐变自检", null));
        LyricsStore.INSTANCE.onIsPlayingChanged(true);
        LyricsStore.INSTANCE.onPositionUpdated(0L);
        System.out.println("  已注入歌词：" + LyricsStore.INSTANCE.getLines().size() + " 行，"
                + "playing=" + LyricsStore.INSTANCE.getPlaying());
    }

    static void check(boolean ok, String name, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + " —— " + detail);
        }
    }
}
