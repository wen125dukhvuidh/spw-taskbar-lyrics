import com.spwtb.taskbarlyrics.render.Win32;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 连续录像：每 250ms 记录一次歌词窗口的屏幕坐标，跑 40 次（10 秒）。
 * 用来把「歌词窗口左右两边闪现」的现象录成一条位置序列。
 *
 * 用法：WatchWindow [次数] [间隔毫秒] [输出目录]
 */
public class WatchWindow {

    interface U extends com.sun.jna.win32.StdCallLibrary {
        U I = (U) com.sun.jna.Native.load("user32", U.class);

        Pointer FindWindowW(WString cls, WString name);

        Pointer FindWindowExW(Pointer parent, Pointer after, WString cls, WString name);

        boolean GetWindowRect(Pointer hwnd, Win32.RECT rect);

        boolean IsWindowVisible(Pointer hwnd);
    }

    public static void main(String[] args) throws Exception {
        int times = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        int gapMs = args.length > 1 ? Integer.parseInt(args[1]) : 250;
        File outDir = new File(args.length > 2 ? args[2] : "shots/live");
        outDir.mkdirs();

        Pointer tray = U.I.FindWindowW(new WString("Shell_TrayWnd"), null);
        System.out.println("Shell_TrayWnd = " + tray);

        List<String> log = new ArrayList<>();
        List<Integer> xs = new ArrayList<>();
        List<Integer> ws = new ArrayList<>();
        Win32.Taskbar bar = Win32.findTaskbars().get(0);

        for (int i = 0; i < times; i++) {
            // 直接找我们自己那个窗口：它是任务栏的子窗口
            Pointer win = null;
            Pointer child = U.I.FindWindowExW(tray, null, null, null);
            while (child != null) {
                Win32.RECT r = new Win32.RECT();
                U.I.GetWindowRect(child, r);
                if (U.I.IsWindowVisible(child) && r.width() > 1
                        && Win32.LYRICS_WINDOW_CLASS.equals(classOf(child))) {
                    win = child;
                    break;
                }
                child = U.I.FindWindowExW(tray, child, null, null);
            }

            List<Win32.Slot> own = Win32.freeSlots(bar, true);
            List<Win32.Slot> inc = Win32.freeSlots(bar, false);
            String slotsInc = fmt(inc);

            if (win == null) {
                System.out.printf("%2d) 窗口不存在 | 排除自身空闲区=%s%n", i, fmt(own));
                log.add(String.format("%2d) none", i));
            } else {
                Win32.RECT r = new Win32.RECT();
                U.I.GetWindowRect(win, r);
                xs.add(r.left);
                ws.add(r.width());
                System.out.printf("%2d) x=%-5d w=%-5d | 排除自身空闲区=%s%n",
                        i, r.left, r.width(), fmt(own));
                log.add(String.format("%2d) x=%d w=%d", i, r.left, r.width()));
            }
            Thread.sleep(gapMs);
        }

        System.out.println();
        System.out.println("=== 位置序列 ===");
        System.out.println("x: " + xs);
        long distinctX = xs.stream().distinct().count();
        long distinctW = ws.stream().distinct().count();
        if (xs.size() >= 2) {
            int min = xs.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = xs.stream().mapToInt(Integer::intValue).max().orElse(0);
            int wmin = ws.stream().mapToInt(Integer::intValue).min().orElse(0);
            int wmax = ws.stream().mapToInt(Integer::intValue).max().orElse(0);
            System.out.printf("共 %d 次采样：x 取值范围 %d..%d（跨度 %d px，占任务栏 %.1f%%），不同位置 %d 个%n",
                    xs.size(), min, max, max - min, (max - min) * 100.0 / bar.width(), distinctX);
            System.out.printf("宽度取值范围 %d..%d，不同宽度 %d 个%n", wmin, wmax, distinctW);
            if (distinctX >= 2) {
                // 统计"跳变"：相邻两次差超过 20px 算一次跳
                int jumps = 0;
                for (int i = 1; i < xs.size(); i++) {
                    if (Math.abs(xs.get(i) - xs.get(i - 1)) > 20) {
                        jumps++;
                        System.out.printf("  跳变 @%d: %d -> %d (%+d px)%n",
                                i, xs.get(i - 1), xs.get(i), xs.get(i) - xs.get(i - 1));
                    }
                }
                System.out.printf("10 秒内发生 %d 次大跳（>20px）%n", jumps);
                System.out.println("!! 确认症状：窗口在任务栏上来回闪现");
            } else {
                System.out.println("窗口位置稳定，本次采样未捕捉到闪现。");
            }
        }
        java.nio.file.Files.write(new File(outDir, "window-positions.txt").toPath(), log);
        System.out.println("序列已存 " + new File(outDir, "window-positions.txt").getPath());
    }

    static String classOf(Pointer h) {
        char[] b = new char[256];
        int n = Win32.U.GetClassNameW(h, b, 256);
        return n <= 0 ? "" : new String(b, 0, n);
    }

    static String fmt(List<Win32.Slot> all) {
        StringBuilder sb = new StringBuilder();
        for (Win32.Slot s : all) {
            if (s.width >= 20) {
                sb.append(String.format("[%d..%d w=%d] ", s.start, s.start + s.width, s.width));
            }
        }
        return sb.toString();
    }
}
