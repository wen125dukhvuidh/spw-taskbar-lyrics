import com.spwtb.taskbarlyrics.render.Win32;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「看看任务栏现在到底长什么样」——三步：
 * 1) 枚举 Shell_TrayWnd 下的所有子窗口：插件窗口 / 原版窗口有没有同时存在、各自在哪。
 * 2) 用 Robot 抓屏幕。
 * 3) 裁出任务栏，原尺寸 + 2 倍放大各存一张。
 *
 * 用法：ScreenShot <输出目录>
 */
public class ScreenShot {

    interface U extends com.sun.jna.win32.StdCallLibrary {
        U I = (U) com.sun.jna.Native.load("user32", U.class);

        interface EnumProc extends StdCallLibrary.StdCallCallback {
            boolean invoke(Pointer hwnd, Pointer lParam);
        }

        Pointer FindWindowW(WString cls, WString name);

        boolean EnumChildWindows(Pointer parent, EnumProc cb, Pointer lParam);

        int GetClassNameW(Pointer hwnd, char[] buf, int maxCount);

        int GetWindowTextW(Pointer hwnd, char[] buf, int maxCount);

        boolean GetWindowRect(Pointer hwnd, Win32.RECT rect);

        boolean IsWindowVisible(Pointer hwnd);

        int GetWindowLongW(Pointer hwnd, int index);

        boolean IsWindow(Pointer hwnd);
    }

    static final int GWL_EXSTYLE = -20;
    static final int WS_EX_LAYERED = 0x00080000;
    static final int WS_EX_TRANSPARENT = 0x00000020;
    static final int WS_EX_TOOLWINDOW = 0x00000080;
    static final int WS_EX_TOPMOST = 0x00000008;

    static class Kid {
        Pointer hwnd;
        String cls;
        String text;
        Win32.RECT rect;
        int ex;
        boolean visible;

        @Override
        public String toString() {
            return String.format(
                "%-42s %-14s visible=%-5s rect=%d,%d %dx%d ex=%s",
                cls.isEmpty() ? "(无类名)" : cls,
                text.isEmpty() ? "-" : "\"" + text + "\"",
                visible,
                rect == null ? -1 : rect.left, rect == null ? -1 : rect.top,
                rect == null ? -1 : rect.width(), rect == null ? -1 : rect.height(),
                flags(ex));
        }
    }

    static String flags(int ex) {
        List<String> l = new ArrayList<>();
        if ((ex & WS_EX_LAYERED) != 0) l.add("LAYERED");
        if ((ex & WS_EX_TRANSPARENT) != 0) l.add("TRANSPARENT");
        if ((ex & WS_EX_TOOLWINDOW) != 0) l.add("TOOLWINDOW");
        if ((ex & WS_EX_TOPMOST) != 0) l.add("TOPMOST");
        return String.join("|", l);
    }

    static String cls(Pointer h) {
        char[] b = new char[256];
        int n = U.I.GetClassNameW(h, b, 256);
        return new String(b, 0, Math.max(n, 0));
    }

    static String text(Pointer h) {
        char[] b = new char[256];
        int n = U.I.GetWindowTextW(h, b, 256);
        return new String(b, 0, Math.max(n, 0));
    }

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : ".");
        outDir.mkdirs();

        Pointer tray = U.I.FindWindowW(new WString("Shell_TrayWnd"), null);
        System.out.println("Shell_TrayWnd = " + tray + "  有效=" + (tray != null && U.I.IsWindow(tray)));

        Win32.RECT trayRect = new Win32.RECT();
        U.I.GetWindowRect(tray, trayRect);
        System.out.printf("任务栏矩形 = %d,%d  %dx%d%n",
            trayRect.left, trayRect.top, trayRect.width(), trayRect.height());

        List<Win32.Taskbar> bars = Win32.findTaskbars();
        System.out.println("findTaskbars() 返回 " + bars.size() + " 个");
        for (int i = 0; i < bars.size(); i++) {
            Win32.Taskbar t = bars.get(i);
            System.out.printf("  [%d] hwnd=%s left=%d top=%d w=%d h=%d dpi=%d%n",
                i, t.hwnd, t.left, t.top, t.width(), t.height(), t.dpi);
        }

        System.out.println();
        System.out.println("=== Shell_TrayWnd 的子窗口 ===");
        List<Kid> kids = new ArrayList<>();
        U.I.EnumChildWindows(tray, (h, l) -> {
            Kid k = new Kid();
            k.hwnd = h;
            k.cls = cls(h);
            k.text = text(h);
            k.rect = new Win32.RECT();
            U.I.GetWindowRect(h, k.rect);
            k.visible = U.I.IsWindowVisible(h);
            k.ex = U.I.GetWindowLongW(h, GWL_EXSTYLE);
            kids.add(k);
            return true;
        }, null);
        for (Kid k : kids) {
            System.out.println("  " + k);
        }

        System.out.println();
        System.out.println("=== 全屏窗口里所有 LAYERED / TOPMOST 的顶层窗口（找别的歌词窗口） ===");
        Pointer[] roots = new Pointer[1];
        roots[0] = tray;
        // 顺带看看有没有不属于任务栏的浮动歌词窗口
        List<Kid> maybeLyrics = new ArrayList<>();
        for (Kid k : kids) {
            boolean layered = (k.ex & WS_EX_LAYERED) != 0;
            boolean notSystem = !k.cls.contains("Tray")
                && !k.cls.contains("Button")
                && !k.cls.contains("Toolbar")
                && !k.cls.contains("ReBar")
                && !k.cls.contains("MSTask")
                && !k.cls.contains("Sys")
                && !k.cls.contains("Start")
                && !k.cls.contains("Notify")
                && !k.cls.contains("Xaml")
                && !k.cls.contains("Windows.UI")
                && !k.cls.contains("ApplicationFrame")
                && !k.cls.contains("DirectUI")
                && !k.cls.contains("ATL")
                && !k.cls.contains("Static")
                && !k.cls.contains("WorkerW")
                && !k.cls.contains("Search")
                && !k.cls.contains("Task");
            if (layered || notSystem) {
                maybeLyrics.add(k);
            }
        }
        if (maybeLyrics.isEmpty()) {
            System.out.println("  （没有疑似歌词窗口）");
        } else {
            for (Kid k : maybeLyrics) {
                System.out.println("  " + k);
            }
        }

        // ── 抓屏 ─────────────────────────────────────────────────────────
        Robot robot = new Robot();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        System.out.println();
        System.out.printf("屏幕 %dx%d%n", screen.width, screen.height);

        BufferedImage full = robot.createScreenCapture(new Rectangle(screen));
        javax.imageio.ImageIO.write(full, "png", new File(outDir, "screen-full.png"));
        System.out.println("已存 screen-full.png");

        // Robot 抓到的是**逻辑**坐标（JVM 没声明 DPI-aware，150% 下 2560→1707），
        // 而 findTaskbars() 给的是物理坐标。这里按 dpi/96 换回逻辑 Making 再裁。
        Win32.Taskbar bar = bars.isEmpty() ? null : bars.get(0);
        double scale = bar == null ? 1.0 : 96.0 / bar.dpi;
        int x = 0, y = 0, w = screen.width, h = (int) Math.round((bar == null ? 48 : bar.height()) * scale);
        if (bar != null) {
            x = (int) Math.round(bar.left * scale);
            y = (int) Math.round(bar.top * scale);
            w = (int) Math.round(bar.width() * scale);
        }
        System.out.printf("换算到逻辑坐标 scale=%.4f -> x=%d y=%d w=%d h=%d%n", scale, x, y, w, h);
        // 往上下各多留 40px，方便看清窗口有没有溢出
        int pad = 40;
        int cx = Math.max(0, x);
        int cy = Math.max(0, y - pad);
        int cw = Math.min(screen.width - cx, w);
        int ch = Math.min(screen.height - cy, h + pad * 2);
        BufferedImage crop = full.getSubimage(cx, cy, cw, ch);
        javax.imageio.ImageIO.write(crop, "png", new File(outDir, "taskbar-raw.png"));

        BufferedImage zoom = new BufferedImage(cw * 2, ch * 2, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = zoom.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(crop, 0, 0, cw * 2, ch * 2, null);
        g.dispose();
        javax.imageio.ImageIO.write(zoom, "png", new File(outDir, "taskbar-zoom2x.png"));
        System.out.printf("已存 taskbar-raw.png (%dx%d) 与 taskbar-zoom2x.png%n", cw, ch);
    }
}
