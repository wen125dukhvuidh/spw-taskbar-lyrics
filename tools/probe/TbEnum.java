import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务栏结构探测器：用 JNA 直接读 Win32，确认
 * 1) JVM 里能不能加载原生库并调 Win32（这是「插件自己画任务栏」的前提）
 * 2) Shell_TrayWnd / Shell_SecondaryTrayWnd 的真实位置与子窗口构成
 * 3) 居中对齐时，任务栏上的空闲区域到底在哪
 */
public class TbEnum {

    public interface User32 extends StdCallLibrary {

        User32 I = Native.load("user32", User32.class);

        interface EnumProc extends StdCallLibrary.StdCallCallback {
            boolean invoke(Pointer hwnd, Pointer lParam);
        }

        boolean EnumWindows(EnumProc cb, Pointer lParam);

        boolean EnumChildWindows(Pointer parent, EnumProc cb, Pointer lParam);

        Pointer FindWindowW(WString cls, WString name);

        Pointer FindWindowExW(Pointer parent, Pointer after, WString cls, WString name);

        int GetClassNameW(Pointer hwnd, char[] buf, int maxCount);

        int GetWindowTextW(Pointer hwnd, char[] buf, int maxCount);

        boolean GetWindowRect(Pointer hwnd, RECT rect);

        boolean IsWindowVisible(Pointer hwnd);

        int GetDpiForWindow(Pointer hwnd);

        int GetWindowThreadProcessId(Pointer hwnd, int[] pid);
    }

    @Structure.FieldOrder({"left", "top", "right", "bottom"})
    public static class RECT extends Structure {
        public int left, top, right, bottom;

        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }

        @Override
        public String toString() {
            return String.format("(%4d,%4d)-(%4d,%4d)  %4dx%-4d", left, top, right, bottom, width(), height());
        }
    }

    private static String className(Pointer hwnd) {
        char[] buf = new char[256];
        int n = User32.I.GetClassNameW(hwnd, buf, buf.length);
        return n <= 0 ? "" : new String(buf, 0, n);
    }

    private static String windowText(Pointer hwnd) {
        char[] buf = new char[256];
        int n = User32.I.GetWindowTextW(hwnd, buf, buf.length);
        return n <= 0 ? "" : new String(buf, 0, n);
    }

    private static RECT rectOf(Pointer hwnd) {
        RECT r = new RECT();
        return User32.I.GetWindowRect(hwnd, r) ? r : null;
    }

    private static void dumpChildTree(Pointer parent, int depth, int maxDepth) {
        if (depth > maxDepth) {
            return;
        }
        // 用 FindWindowExW 走一遍兄弟链，拿到精确的直接子窗口
        List<Pointer> direct = new ArrayList<>();
        Pointer child = User32.I.FindWindowExW(parent, null, null, null);
        while (child != null) {
            direct.add(child);
            child = User32.I.FindWindowExW(parent, child, null, null);
        }

        for (Pointer c : direct) {
            boolean visible = User32.I.IsWindowVisible(c);
            RECT r = rectOf(c);
            String cls = className(c);
            String text = windowText(c);
            System.out.printf("%s%-34s visible=%-5s %s%s%n",
                    "  ".repeat(depth + 1),
                    cls.isEmpty() ? "(无类名)" : cls,
                    visible,
                    r == null ? "(取不到 rect)" : r.toString(),
                    text.isEmpty() ? "" : "   text=\"" + text + "\"");
            dumpChildTree(c, depth + 1, maxDepth);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 1) JNA 原生调用是否可用 ===");
        System.out.println("jna 版本: " + Native.VERSION + "  native 版本: " + Native.VERSION_NATIVE);
        System.out.println();

        System.out.println("=== 2) 进程 DPI 感知 ===");
        System.out.println("GetDpiForWindow(桌面) 稍后随任务栏一起打印");
        System.out.println();

        System.out.println("=== 3) 任务栏窗口 ===");
        String[] classes = {"Shell_TrayWnd", "Shell_SecondaryTrayWnd"};
        for (String cls : classes) {
            Pointer tray = User32.I.FindWindowW(new WString(cls), null);
            if (tray == null) {
                System.out.println(cls + ": (未找到)");
                continue;
            }
            RECT r = rectOf(tray);
            int[] pid = new int[1];
            User32.I.GetWindowThreadProcessId(tray, pid);
            System.out.printf("%s  hwnd=0x%X  pid=%d  dpi=%d%n",
                    cls, Pointer.nativeValue(tray), pid[0], User32.I.GetDpiForWindow(tray));
            System.out.printf("      rect=%s  visible=%s%n",
                    r == null ? "?" : r.toString(), User32.I.IsWindowVisible(tray));
            System.out.println("      子窗口树：");
            dumpChildTree(tray, 0, 2);
            System.out.println();
        }

        System.out.println("=== 4) 所有含 \"Tray\" 的顶层窗口（含隐藏）===");
        User32.I.EnumWindows((hwnd, lParam) -> {
            String cls = className(hwnd);
            if (cls.toLowerCase().contains("tray")) {
                RECT r = rectOf(hwnd);
                System.out.printf("  %-30s visible=%-5s %s%n", cls, User32.I.IsWindowVisible(hwnd),
                        r == null ? "?" : r.toString());
            }
            return true;
        }, null);

        System.out.println();
        System.out.println("=== 5) 结论用：任务栏空闲区推算 ===");
        Pointer tray = User32.I.FindWindowW(new WString("Shell_TrayWnd"), null);
        if (tray != null) {
            RECT tr = rectOf(tray);
            System.out.println("Shell_TrayWnd 全宽: " + tr);
            System.out.println("→ 若图标区居中，空闲区在左右两侧；靠左则空闲区在图标区右侧。");
        }
    }
}
