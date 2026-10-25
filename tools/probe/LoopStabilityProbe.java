import com.spwtb.taskbarlyrics.render.SlotChooser;
import com.spwtb.taskbarlyrics.render.TaskbarAnchor;
import com.spwtb.taskbarlyrics.render.TaskbarLayerWindow;
import com.spwtb.taskbarlyrics.render.Win32;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;

/**
 * 端到端：用**修复后的那套生产代码**跑一遍渲染循环的几何部分，
 * 看歌词窗口还会不会横跳。
 *
 * 复刻 TaskbarRenderer 里那段：
 *   每 500ms → Win32.freeSlots(taskbar) → SlotChooser.choose(anchor)
 *            → 算 x/width → win.setBounds(...)
 * 全程在真实任务栏上操作真窗口，最后录下 x 序列。
 *
 * 用法：LoopStabilityProbe [轮数] [间隔ms]
 */
public class LoopStabilityProbe {

    static final int MIN_SLOT_WIDTH = 140;
    static final int MIN_WINDOW_WIDTH = 120;
    static final int SCAN_INTERVAL_MS = 500;

    public static void main(String[] args) throws Exception {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        int gap = args.length > 1 ? Integer.parseInt(args[1]) : SCAN_INTERVAL_MS;

        List<Win32.Taskbar> bars = Win32.findTaskbars();
        Win32.Taskbar bar = bars.get(0);
        System.out.printf("任务栏 %d..%d（宽 %d）dpi=%d%n",
                bar.left, bar.right, bar.width(), bar.dpi);

        SlotChooser chooser = new SlotChooser(MIN_SLOT_WIDTH);
        TaskbarAnchor anchor = TaskbarAnchor.WIDEST;   // 用户当前设置
        int widthPercent = 96;                          // 用户当前设置
        int xOffsetDp = -7;                             // 用户当前设置
        int yOffsetDp = -1;

        TaskbarLayerWindow win = null;
        List<Integer> xs = new ArrayList<>();
        List<Integer> ws = new ArrayList<>();
        int x = 0, w = 0;

        try {
            for (int i = 0; i < rounds; i++) {
                List<Win32.Slot> slots = Win32.freeSlots(bar);   // 修复版：不含自身
                Win32.Slot slot = chooser.choose(slots, anchor, bar);
                if (slot == null) {
                    System.out.printf("%2d) 没有合格空闲区，画面保持原样%n", i);
                    Thread.sleep(gap);
                    continue;
                }
                double scale = bar.scale();
                int nw = (int) (slot.width * widthPercent / 100.0);
                nw = Math.max(nw, MIN_WINDOW_WIDTH);
                int nx = slot.start + (slot.width - nw) / 2 + (int) (xOffsetDp * scale);
                int ny = bar.top + (int) (yOffsetDp * scale);

                if (win == null) {
                    win = new TaskbarLayerWindow(bar.hwnd, nx, ny, nw, bar.height(), true);
                    fill(win, nw, bar.height());
                } else {
                    win.setBounds(nx, ny, nw, bar.height());
                    fill(win, nw, bar.height());
                }
                Win32.RECT r = rectOf(win);
                xs.add(r.left);
                ws.add(r.width());
                System.out.printf("%2d) 空闲区=%s -> x=%d w=%d%n", i, fmt(slots), r.left, r.width());
                Thread.sleep(gap);
            }
        } finally {
            if (win != null) {
                win.destroy();
            }
        }

        System.out.println();
        System.out.println("=== 窗口位置序列 ===");
        System.out.println("x: " + xs);
        System.out.println("w: " + ws);
        long dx = xs.stream().distinct().count();
        long dw = ws.stream().distinct().count();
        int min = xs.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = xs.stream().mapToInt(Integer::intValue).max().orElse(0);
        int wmin = ws.stream().mapToInt(Integer::intValue).min().orElse(0);
        int wmax = ws.stream().mapToInt(Integer::intValue).max().orElse(0);
        System.out.printf("%d 轮：x 不同取值 %d 个（%d..%d，跨度 %d px），w 不同取值 %d 个（%d..%d）%n",
                xs.size(), dx, min, max, max - min, dw, wmin, wmax);
        System.out.println();
        System.out.println("---- 判定 ----");
        boolean stable = dx <= 1 && max - min <= 2;
        System.out.println(stable
                ? "✓ 端到端稳定：整个循环里窗口没有横跳（修复前同一台机器上是 559↔1577 来回，跨度 1018px）"
                : "✗ 仍然不稳定，需要修");
    }

    static Win32.RECT rectOf(TaskbarLayerWindow win) throws Exception {
        java.lang.reflect.Field f = TaskbarLayerWindow.class.getDeclaredField("hwnd");
        f.setAccessible(true);
        com.sun.jna.Pointer h = (com.sun.jna.Pointer) f.get(win);
        Win32.RECT r = new Win32.RECT();
        Win32.U.GetWindowRect(h, r);
        return r;
    }

    static void fill(TaskbarLayerWindow win, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        java.awt.Graphics2D g = img.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(new java.awt.Color(255, 255, 255, 40));
        g.fillRect(0, 0, w, h);
        g.dispose();
        win.pushFrame((int[]) ((DataBufferInt) img.getRaster().getDataBuffer()).getData());
    }

    static String fmt(List<Win32.Slot> all) {
        StringBuilder sb = new StringBuilder();
        for (Win32.Slot s : all) {
            sb.append(String.format("[%d..%d w=%d] ", s.start, s.start + s.width, s.width));
        }
        return sb.toString();
    }
}
