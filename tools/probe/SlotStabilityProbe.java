import com.spwtb.taskbarlyrics.render.TaskbarLayerWindow;
import com.spwtb.taskbarlyrics.render.Win32;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「歌词窗口在任务栏左右两边闪现」的复现与验证探针。
 *
 * 怀疑：{@code Win32.freeSlots()} 把任务栏的**所有**可见子窗口当成占用区，
 * 而歌词窗口自己在 embed 模式下就是任务栏的子窗口。于是每轮重扫都把自己的
 * 地盘划走，下一轮只能选另一块空闲区 —— 下一轮它又跳回来。
 *
 * 本探针在真实任务栏上跑一遍这条链路（建窗口 → 反复重扫选 using 最大空闲区 →
 * 记录 x），把左右横跳量化出来；再对照「排除自身窗口」的稳定版本。
 */
public class SlotStabilityProbe {

    interface U extends com.sun.jna.win32.StdCallLibrary {
        U I = (U) com.sun.jna.Native.load("user32", U.class);

        Pointer FindWindowW(WString cls, WString name);

        Pointer FindWindowExW(Pointer parent, Pointer after, WString cls, WString name);

        int GetClassNameW(Pointer hwnd, char[] buf, int maxCount);

        boolean GetWindowRect(Pointer hwnd, Win32.RECT rect);

        boolean IsWindowVisible(Pointer hwnd);
    }

    static final String OWN_CLASS = "SpwTaskbarLyricsWnd";
    static final int MIN_SLOT_WIDTH = 140;

    static String cls(Pointer h) {
        char[] b = new char[256];
        int n = U.I.GetClassNameW(h, b, 256);
        return new String(b, 0, Math.max(n, 0));
    }

    /** 取最宽的空闲区，>= MIN_SLOT_WIDTH。 */
    static Win32.Slot widest(List<Win32.Slot> all) {
        Win32.Slot best = null;
        for (Win32.Slot s : all) {
            if (s.width < MIN_SLOT_WIDTH) continue;
            if (best == null || s.width > best.width) best = s;
        }
        return best;
    }

    static String fmt(List<Win32.Slot> all) {
        StringBuilder sb = new StringBuilder();
        for (Win32.Slot s : all) {
            sb.append(String.format("[%d..%d w=%d] ", s.start, s.start + s.width, s.width));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        List<Win32.Taskbar> bars = Win32.findTaskbars();
        if (bars.isEmpty()) {
            System.out.println("找不到任务栏，退出");
            return;
        }
        Win32.Taskbar bar = bars.get(0);
        System.out.printf("任务栏 hwnd=%s left=%d right=%d w=%d h=%d dpi=%d%n",
                bar.hwnd, bar.left, bar.right, bar.width(), bar.height(), bar.dpi);

        System.out.println();
        System.out.println("=== 1) 没有歌词窗口时的空闲区（基线）===");
        List<Win32.Slot> base = Win32.freeSlots(bar, false);
        System.out.println("  全部: " + fmt(base));
        Win32.Slot first = widest(base);
        System.out.printf("  最宽: start=%d w=%d%n", first.start, first.width);

        // 按锚点 widest 算出窗口该放的位置
        int width = (int) (first.width * 96.0 / 100.0);      // widthPercent=96
        int x = first.start + (first.width - width) / 2 + (int) (-7 * bar.scale()); // xOffset=-7
        System.out.printf("  窗口 x=%d w=%d h=%d%n", x, width, bar.height());

        System.out.println();
        System.out.println("=== 2) 放进歌词窗口，然后按每轮「重扫 → 选最宽 → 挪过去」迭代 ===");
        TaskbarLayerWindow win = new TaskbarLayerWindow(
                bar.hwnd, x, bar.top, width, bar.height(), true);
        // 推一帧不透明内容，确保窗口真的存在且可见
        BufferedImage img = new BufferedImage(width, bar.height(), BufferedImage.TYPE_INT_ARGB_PRE);
        java.awt.Graphics2D g = img.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(new java.awt.Color(255, 255, 255, 60));
        g.fillRect(0, 0, width, bar.height());
        g.dispose();
        win.pushFrame((int[]) (img.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferInt db
                ? db.getData() : null));
        Thread.sleep(120);

        List<Integer> xs = new ArrayList<>();
        int cx = x, cw = width;
        for (int i = 0; i < 8; i++) {
            // 复刻渲染线程的逻辑：重扫 → 选最宽 → setBounds
            List<Win32.Slot> all = Win32.freeSlots(bar, false);
            Win32.Slot pick = widest(all);
            if (pick == null) {
                System.out.printf("  轮 %d: 没有 >= %d 的空闲区%n", i, MIN_SLOT_WIDTH);
                break;
            }
            int nw = (int) (pick.width * 96.0 / 100.0);
            int nx = pick.start + (pick.width - nw) / 2 + (int) (-7 * bar.scale());
            win.setBounds(nx, bar.top, nw, bar.height());
            Thread.sleep(80);
            System.out.printf("  轮 %d: 空闲区=%s%n", i, fmt(all));
            System.out.printf("       选中 start=%d w=%d -> 窗口挪到 x=%d  (上一轮 x=%d, 跳了 %d px)%n",
                    pick.start, pick.width, nx, cx, Math.abs(nx - cx));
            xs.add(nx);
            cx = nx;
            cw = nw;
        }

        System.out.println();
        System.out.println("=== 3) 结论：x 的取值序列 ===");
        System.out.println("  " + xs);
        long distinct = xs.stream().distinct().count();
        System.out.printf("  共 %d 次位置，去重后 %d 个不同位置%n", xs.size(), distinct);
        if (distinct >= 2) {
            int min = xs.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = xs.stream().mapToInt(Integer::intValue).max().orElse(0);
            System.out.printf("  !! 复现成功：窗口在 x=%d 与 x=%d 之间来回，跨度 %d px（约 %.0f%% 屏宽）%n",
                    min, max, max - min, (max - min) * 100.0 / bar.width());
        } else {
            System.out.println("  窗口位置稳定，没有复现。");
        }

        System.out.println();
        System.out.println("=== 4) 对照：把歌词窗口从「占用」里排除后再迭代 ===");
        List<Integer> ys = new ArrayList<>();
        int px = cx;
        for (int i = 0; i < 8; i++) {
            List<Win32.Slot> all = Win32.freeSlots(bar, true);
            Win32.Slot pick = widest(all);
            if (pick == null) {
                System.out.printf("  轮 %d: 没有空闲区%n", i);
                break;
            }
            int nw = (int) (pick.width * 96.0 / 100.0);
            int nx = pick.start + (pick.width - nw) / 2 + (int) (-7 * bar.scale());
            win.setBounds(nx, bar.top, nw, bar.height());
            Thread.sleep(80);
            ys.add(nx);
            System.out.printf("  轮 %d: start=%d w=%d -> x=%d (跳 %d px)%n",
                    i, pick.start, pick.width, nx, Math.abs(nx - px));
            px = nx;
        }
        long d2 = ys.stream().distinct().count();
        System.out.printf("  排除自身后：%d 次位置，去重 %d 个%n", ys.size(), d2);

        win.destroy();
        System.out.println();
        System.out.println("---- 判定 ----");
        System.out.printf(Locale.ROOT, "   复现（含自身）: 不同位置 %d 个 %s%n", distinct,
                distinct >= 2 ? "=> BUG 确认" : "=> 未复现");
        System.out.printf(Locale.ROOT, "   修复（排除自身）: 不同位置 %d 个 %s%n", d2,
                d2 == 1 ? "=> 稳定，修复有效" : "=> 仍不稳定");
    }
}
