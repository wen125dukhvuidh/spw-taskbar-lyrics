import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「插件自己画任务栏歌词」的可行性验证。
 *
 * 验证内容：
 * 1. JVM 内不依赖任何外部程序，创建 Win32 分层窗口（逐像素 alpha）
 * 2. 把窗口嵌进 Shell_TrayWnd，落在任务栏的空闲区
 * 3. 用 Java2D 画歌词（含「已唱/未唱」扫描高亮）
 * 4. 截屏回读，确认真的画到了任务栏上
 *
 * 用法：TbDemo [embed|overlay]
 */
public class TbDemo {

    // ---------------------------------------------------------------- Win32

    public interface User32 extends StdCallLibrary {
        User32 I = Native.load("user32", User32.class);

        interface WndProc extends StdCallCallback {
            long invoke(Pointer hwnd, int msg, long wParam, long lParam);
        }

        interface EnumProc extends StdCallCallback {
            boolean invoke(Pointer hwnd, Pointer lParam);
        }

        Pointer FindWindowW(WString cls, WString name);

        Pointer FindWindowExW(Pointer parent, Pointer after, WString cls, WString name);

        int GetClassNameW(Pointer hwnd, char[] buf, int maxCount);

        boolean GetWindowRect(Pointer hwnd, RECT rect);

        boolean GetClientRect(Pointer hwnd, RECT rect);

        boolean ClientToScreen(Pointer hwnd, POINT pt);

        boolean IsWindowVisible(Pointer hwnd);

        int GetDpiForWindow(Pointer hwnd);

        short RegisterClassExW(WNDCLASSEXW wc);

        Pointer CreateWindowExW(int exStyle, WString className, WString windowName, int style,
                                int x, int y, int w, int h,
                                Pointer parent, Pointer menu, Pointer instance, Pointer param);

        boolean DestroyWindow(Pointer hwnd);

        Pointer DefWindowProcW(Pointer hwnd, int msg, long wParam, long lParam);

        boolean SetWindowPos(Pointer hwnd, Pointer insertAfter, int x, int y, int cx, int cy, int flags);

        long SetWindowLongPtrW(Pointer hwnd, int index, long value);

        long GetWindowLongPtrW(Pointer hwnd, int index);

        boolean ShowWindow(Pointer hwnd, int cmd);

        boolean PrintWindow(Pointer hwnd, Pointer hdcBlt, int flags);

        Pointer GetWindow(Pointer hwnd, int cmd);

        Pointer GetParent(Pointer hwnd);

        int GetWindowThreadProcessId(Pointer hwnd, int[] pid);

        Pointer GetDC(Pointer hwnd);

        int ReleaseDC(Pointer hwnd, Pointer hdc);

        int GetSystemMetrics(int index);

        Pointer LoadCursorW(Pointer instance, Pointer cursorName);

        boolean UpdateLayeredWindow(Pointer hwnd, Pointer hdcDst, POINT pptDst, SIZE psize,
                                    Pointer hdcSrc, POINT pptSrc, int crKey, BLENDFUNCTION blend, int flags);

        int PeekMessageW(MSG msg, Pointer hwnd, int filterMin, int filterMax, int removeMsg);

        boolean TranslateMessage(MSG msg);

        Pointer DispatchMessageW(MSG msg);
    }

    public interface Gdi32 extends StdCallLibrary {
        Gdi32 I = Native.load("gdi32", Gdi32.class);

        Pointer CreateCompatibleDC(Pointer hdc);

        boolean DeleteDC(Pointer hdc);

        Pointer CreateDIBSection(Pointer hdc, BITMAPINFO bmi, int usage,
                                 PointerByReference bits, Pointer section, int offset);

        Pointer SelectObject(Pointer hdc, Pointer obj);

        boolean DeleteObject(Pointer obj);
    }

    public interface Kernel32 extends StdCallLibrary {
        Kernel32 I = Native.load("kernel32", Kernel32.class);

        Pointer GetModuleHandleW(WString name);
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
    }

    @Structure.FieldOrder({"x", "y"})
    public static class POINT extends Structure {
        public int x, y;

        public POINT() {
        }

        public POINT(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    @Structure.FieldOrder({"cx", "cy"})
    public static class SIZE extends Structure {
        public int cx, cy;

        public SIZE(int cx, int cy) {
            this.cx = cx;
            this.cy = cy;
        }
    }

    @Structure.FieldOrder({"blendOp", "blendFlags", "sourceConstantAlpha", "alphaFormat"})
    public static class BLENDFUNCTION extends Structure {
        public byte blendOp;
        public byte blendFlags;
        public byte sourceConstantAlpha;
        public byte alphaFormat;
    }

    @Structure.FieldOrder({"biSize", "biWidth", "biHeight", "biPlanes", "biBitCount",
            "biCompression", "biSizeImage", "biXPelsPerMeter", "biYPelsPerMeter",
            "biClrUsed", "biClrImportant"})
    public static class BITMAPINFOHEADER extends Structure {
        public int biSize, biWidth, biHeight;
        public short biPlanes, biBitCount;
        public int biCompression, biSizeImage, biXPelsPerMeter, biYPelsPerMeter, biClrUsed, biClrImportant;
    }

    @Structure.FieldOrder({"bmiHeader", "bmiColors"})
    public static class BITMAPINFO extends Structure {
        public BITMAPINFOHEADER bmiHeader = new BITMAPINFOHEADER();
        public byte[] bmiColors = new byte[4];
    }

    @Structure.FieldOrder({"hwnd", "message", "wParam", "lParam", "time", "pt"})
    public static class MSG extends Structure {
        public Pointer hwnd;
        public int message;
        public long wParam;
        public long lParam;
        public int time;
        public POINT pt = new POINT();
    }

    @Structure.FieldOrder({"cbSize", "style", "lpfnWndProc", "cbClsExtra", "cbWndExtra",
            "hInstance", "hIcon", "hCursor", "hbrBackground", "lpszMenuName",
            "lpszClassName", "hIconSm"})
    public static class WNDCLASSEXW extends Structure {
        public int cbSize, style;
        public Pointer lpfnWndProc;
        public int cbClsExtra, cbWndExtra;
        public Pointer hInstance, hIcon, hCursor, hbrBackground;
        public WString lpszMenuName, lpszClassName;
        public Pointer hIconSm;
    }

    // ------------------------------------------------------------ 常量

    static final int GWL_EXSTYLE = -20;
    static final long WS_EX_LAYERED = 0x00080000L;
    static final long WS_EX_TOOLWINDOW = 0x00000080L;
    static final long WS_EX_NOACTIVATE = 0x08000000L;
    static final long WS_EX_TRANSPARENT = 0x00000020L;
    static final long WS_EX_TOPMOST = 0x00000008L;

    static final int WS_POPUP = 0x80000000;
    static final int WS_CHILD = 0x40000000;
    static final int WS_VISIBLE = 0x10000000;

    static final int SWP_NOSIZE = 0x0001;
    static final int SWP_NOMOVE = 0x0002;
    static final int SWP_NOACTIVATE = 0x0010;
    static final int SWP_SHOWWINDOW = 0x0040;
    static final int SWP_NOZORDER = 0x0004;

    static final Pointer HWND_TOP = Pointer.createConstant(0);
    static final Pointer HWND_TOPMOST = Pointer.createConstant(-1);

    static final int ULW_ALPHA = 0x00000002;
    static final byte AC_SRC_OVER = 0x00;
    static final byte AC_SRC_ALPHA = 0x01;

    static final int PM_REMOVE = 0x0001;

    static final int PW_RENDERFULLCONTENT = 0x00000002;
    static final int GW_HWNDFIRST = 0;
    static final int GW_HWNDNEXT = 2;

    // ------------------------------------------------------------ 状态

    static final User32 U = User32.I;
    static final Gdi32 G = Gdi32.I;

    static Pointer ourHwnd;
    static User32.WndProc wndProc;   // 必须强引用，否则被 GC 后原生回调会崩

    static int winW, winH;
    static BufferedImage surface;
    static Pointer dibBits, dibDib, memDc, screenDc;

    static volatile boolean running = true;
    static volatile int frame;

    // ------------------------------------------------------------ 工具

    static String className(Pointer hwnd) {
        char[] buf = new char[256];
        int n = U.GetClassNameW(hwnd, buf, buf.length);
        return n <= 0 ? "" : new String(buf, 0, n);
    }

    static RECT rectOf(Pointer hwnd) {
        RECT r = new RECT();
        return U.GetWindowRect(hwnd, r) ? r : null;
    }

    /**
     * 找出任务栏上的空闲横条：枚举 Shell_TrayWnd 的直接子窗口，
     * 排除不可见 / 零尺寸 / 全宽透明容器，再在任务栏范围内求最大空隙。
     */
    static int[] findFreeSlot(Pointer tray, RECT trayRect) {
        List<int[]> occupied = new ArrayList<>();
        Pointer child = U.FindWindowExW(tray, null, null, null);
        while (child != null) {
            RECT r = rectOf(child);
            String cls = className(child);
            boolean skip = !U.IsWindowVisible(child)
                    || r == null || r.width() <= 1
                    // 这层覆盖整个任务栏，是 XAML 容器，不代表实际占用
                    || cls.contains("DesktopWindowContentBridge");
            if (!skip) {
                occupied.add(new int[]{r.left, r.right});
                System.out.printf("      占用: %-30s %s%n", cls, describe(r));
            }
            child = U.FindWindowExW(tray, child, null, null);
        }
        occupied.sort((a, b) -> Integer.compare(a[0], b[0]));

        int bestStart = -1, bestWidth = 0;
        int cursor = trayRect.left;
        System.out.println("      空隙推算：");
        for (int[] seg : occupied) {
            if (seg[0] - cursor > bestWidth) {
                bestWidth = seg[0] - cursor;
                bestStart = cursor;
            }
            System.out.printf("        [%4d → %4d] 空闲 %d%n", cursor, seg[0], seg[0] - cursor);
            cursor = Math.max(cursor, seg[1]);
        }
        if (trayRect.right - cursor > bestWidth) {
            bestWidth = trayRect.right - cursor;
            bestStart = cursor;
        }
        System.out.printf("        [%4d → %4d] 空闲 %d%n", cursor, trayRect.right, trayRect.right - cursor);
        System.out.printf("      → 选定空隙: x=%d 宽=%d%n", bestStart, bestWidth);
        return new int[]{bestStart, bestWidth};
    }

    static String describe(RECT r) {
        return String.format("(%4d,%4d)-(%4d,%4d) %4dx%-4d", r.left, r.top, r.right, r.bottom,
                r.width(), r.height());
    }

    // ------------------------------------------------------------ 渲染

    /** 画一帧歌词到 surface。mode：0=已唱部分用亮色扫描 */
    static void renderLyrics(String main, String sub, double progress) {
        Graphics2D g = surface.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, winW, winH);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 透明底上必须用灰度抗锯齿，用 LCD 子像素抗锯齿会在边缘产生彩色描边
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double scale = winH / 48.0;              // 以 48 逻辑像素为基准
        int padX = (int) Math.round(10 * scale);

        // 背景胶囊
        int radius = (int) Math.round(14 * scale);
        g.setColor(new Color(18, 18, 22, 165));
        g.fillRoundRect(0, (int) Math.round(6 * scale),
                winW, winH - (int) Math.round(12 * scale), radius, radius);

        String fontName = pickFont();
        int mainSize = (int) Math.round(15 * scale);
        Font mainFont = new Font(fontName, Font.PLAIN, mainSize);
        Font subFont = new Font(fontName, Font.PLAIN, (int) Math.round(11 * scale));

        g.setFont(mainFont);
        FontMetrics fm = g.getFontMetrics();
        int mainW = fm.stringWidth(main);
        int maxW = winW - padX * 2;
        if (mainW > maxW) {
            main = ellipsize(g, main, maxW);
            mainW = fm.stringWidth(main);
        }

        boolean twoLine = sub != null && !sub.isEmpty() && winH >= 60;
        int mainBaseline = twoLine
                ? (int) Math.round(winH * 0.40) + fm.getAscent() / 2
                : (winH + fm.getAscent() - fm.getDescent()) / 2;
        int mainLeft = (winW - mainW) / 2;

        // 底：未唱部分
        g.setColor(new Color(255, 255, 255, 120));
        g.drawString(main, mainLeft, mainBaseline);

        // 已唱部分：用 clip 做扫描高亮
        int sungWidth = (int) Math.round(mainW * Math.min(1.0, Math.max(0.0, progress)));
        Shape oldClip = g.getClip();
        g.setClip(mainLeft, 0, sungWidth, winH);
        g.setColor(new Color(255, 255, 255, 255));
        g.drawString(main, mainLeft, mainBaseline);
        g.setClip(oldClip);

        if (twoLine) {
            g.setFont(subFont);
            FontMetrics fm2 = g.getFontMetrics();
            String s = fm2.stringWidth(sub) > maxW ? ellipsize(g, sub, maxW) : sub;
            g.setColor(new Color(255, 255, 255, 95));
            g.drawString(s, (winW - fm2.stringWidth(s)) / 2,
                    (int) Math.round(winH * 0.78) + fm2.getAscent() / 2);
        }

        g.dispose();
    }

    static String pickFont() {
        String[] candidates = {"Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑", "SimHei", "SansSerif"};
        List<String> available = java.util.Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String c : candidates) {
            if (available.contains(c)) {
                return c;
            }
        }
        return Font.SANS_SERIF;
    }

    static String ellipsize(Graphics2D g, String text, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (fm.stringWidth(sb.toString() + c + "…") > maxWidth) {
                break;
            }
            sb.append(c);
        }
        return sb + "…";
    }

    /** 把 surface 推送到分层窗口 */
    static void pushFrame() {
        int[] px = ((DataBufferInt) surface.getRaster().getDataBuffer()).getData();
        byte[] bytes = new byte[px.length * 4];
        // DIB 是 BGRA 顺序；BufferedImage ARGB_PRE 的 int 是 0xAARRGGBB
        // DIB 自下而上（biHeight 为正），所以按行倒序写
        int stride = winW * 4;
        for (int y = 0; y < winH; y++) {
            int srcRow = y * winW;
            int dstRow = (winH - 1 - y) * stride;
            for (int x = 0; x < winW; x++) {
                int v = px[srcRow + x];
                int o = dstRow + x * 4;
                bytes[o] = (byte) (v);          // B
                bytes[o + 1] = (byte) (v >> 8); // G
                bytes[o + 2] = (byte) (v >> 16);// R
                bytes[o + 3] = (byte) (v >> 24);// A
            }
        }
        dibBits.write(0, bytes, 0, bytes.length);

        BLENDFUNCTION blend = new BLENDFUNCTION();
        blend.blendOp = AC_SRC_OVER;
        blend.blendFlags = 0;
        blend.sourceConstantAlpha = (byte) 255;
        blend.alphaFormat = AC_SRC_ALPHA;

        SIZE size = new SIZE(winW, winH);
        POINT src = new POINT(0, 0);
        // hdcDst / pptDst 传 null：位置与尺寸由 SetWindowPos 管，这里只更新内容
        boolean ok = U.UpdateLayeredWindow(ourHwnd, null, null, size, memDc, src, 0, blend, ULW_ALPHA);
        if (frame == 0) {
            System.out.println("  UpdateLayeredWindow 首次调用: " + (ok ? "成功" : "失败 err=" + Native.getLastError()));
        }
    }

    // ------------------------------------------------------------ 主流程

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "embed";
        String baseDir = args.length > 1 ? args[1] : ".";

        System.out.println("=== 1) 找任务栏 ===");
        Pointer tray = U.FindWindowW(new WString("Shell_TrayWnd"), null);
        if (tray == null) {
            System.out.println("找不到 Shell_TrayWnd，退出");
            return;
        }
        RECT trayRect = rectOf(tray);
        int dpi = U.GetDpiForWindow(tray);
        System.out.printf("  Shell_TrayWnd=0x%X  rect=%s  dpi=%d (缩放 %.0f%%)%n",
                Pointer.nativeValue(tray), describe(trayRect), dpi, dpi / 96.0 * 100);

        System.out.println();
        System.out.println("=== 2) 计算空闲区 ===");
        int[] slot = findFreeSlot(tray, trayRect);
        int slotX = slot[0], slotW = slot[1];

        // 留一点左右边距，别贴着图标
        double scale = trayRect.height() / 48.0;
        int margin = (int) Math.round(6 * scale);
        int x = slotX + margin;
        int w = slotW - margin * 2;
        int h = trayRect.height();
        if (w < 120) {
            System.out.println("  空闲区太窄，退出");
            return;
        }
        winW = w;
        winH = h;
        System.out.printf("  窗口: x=%d y=%d w=%d h=%d (scale=%.2f)%n", x, trayRect.top, w, h, scale);

        System.out.println();
        System.out.println("=== 3) 创建分层窗口 (mode=" + mode + ") ===");
        screenDc = U.GetDC(null);

        // DIB：32bpp BI_RGB，高度为正 => 自下而上
        BITMAPINFO bmi = new BITMAPINFO();
        bmi.bmiHeader.biSize = bmi.bmiHeader.size();
        bmi.bmiHeader.biWidth = winW;
        bmi.bmiHeader.biHeight = winH;
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = 0;
        bmi.bmiHeader.biSizeImage = winW * winH * 4;

        PointerByReference bitsRef = new PointerByReference();
        dibDib = G.CreateDIBSection(screenDc, bmi, 0, bitsRef, null, 0);
        if (dibDib == null) {
            System.out.println("CreateDIBSection 失败 err=" + Native.getLastError());
            return;
        }
        dibBits = bitsRef.getValue();
        memDc = G.CreateCompatibleDC(screenDc);
        G.SelectObject(memDc, dibDib);
        System.out.println("  DIB 创建成功 bits=" + dibBits);

        surface = new BufferedImage(winW, winH, BufferedImage.TYPE_INT_ARGB_PRE);

        // 注册窗口类
        String clsName = "SpwTaskbarLyricsProbeWnd";
        wndProc = (hwnd, msg, wParam, lParam) -> {
            if (msg == 0x0002 || msg == 0x0082) {   // WM_DESTROY / WM_NCDESTROY
                return 0;
            }
            return Pointer.nativeValue(U.DefWindowProcW(hwnd, msg, wParam, lParam));
        };
        WNDCLASSEXW wc = new WNDCLASSEXW();
        wc.cbSize = wc.size();
        wc.style = 0;
        wc.lpfnWndProc = CallbackReference.getFunctionPointer(wndProc);
        wc.hInstance = Kernel32.I.GetModuleHandleW(null);
        wc.hCursor = U.LoadCursorW(null, Pointer.createConstant(32512));
        wc.hbrBackground = null;      // 必须 null：分层窗口自己管背景
        wc.lpszClassName = new WString(clsName);
        short atom = U.RegisterClassExW(wc);
        System.out.println("  RegisterClassExW atom=" + atom + " err=" + Native.getLastError());

        long exStyle = WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TRANSPARENT;

        if ("embed".equals(mode)) {
            // 直接建成任务栏的子窗口：自动隐藏 / z 序 / 移动全部由任务栏托管
            // 先求父窗口客户区原点，再把屏幕坐标换算成子窗口局部坐标
            POINT origin = new POINT(0, 0);
            U.ClientToScreen(tray, origin);
            int localX = x - origin.x;
            int localY = trayRect.top - origin.y;
            System.out.printf("  父窗口客户区原点=(%d,%d)  子窗口局部坐标=(%d,%d)%n",
                    origin.x, origin.y, localX, localY);

            ourHwnd = U.CreateWindowExW((int) exStyle, new WString(clsName), new WString(""),
                    WS_CHILD | WS_VISIBLE, localX, localY, winW, winH, tray, null, wc.hInstance, null);
        } else {
            ourHwnd = U.CreateWindowExW((int) (exStyle | WS_EX_TOPMOST), new WString(clsName), new WString(""),
                    WS_POPUP | WS_VISIBLE, x, trayRect.top, winW, winH, null, null, wc.hInstance, null);
        }
        System.out.println("  CreateWindowExW hwnd=" + ourHwnd + " err=" + Native.getLastError());
        if (ourHwnd == null) {
            System.out.println("创建窗口失败，退出");
            return;
        }

        // 提到同级最前
        U.SetWindowPos(ourHwnd, HWND_TOP, 0, 0, winW, winH,
                SWP_NOMOVE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
        U.ShowWindow(ourHwnd, 5);

        System.out.println();
        System.out.println("=== 4) 启动消息泵 + 渲染线程 ===");
        Thread pump = new Thread(TbDemo::messageLoop, "win32-pump");
        pump.setDaemon(true);
        pump.start();

        Thread.sleep(900);
        System.out.println();
        System.out.println("=== 5) 自检 ===");

        Pointer found = U.FindWindowExW(tray, null, new WString(clsName), null);
        System.out.println("  任务栏下能找到本窗口: "
                + (found != null && Pointer.nativeValue(found) == Pointer.nativeValue(ourHwnd)));
        RECT selfRect = rectOf(ourHwnd);
        System.out.println("  本窗口屏幕矩形: " + (selfRect == null ? "?" : describe(selfRect)));
        System.out.println("  IsWindowVisible: " + U.IsWindowVisible(ourHwnd));
        System.out.println("  父窗口就是任务栏: "
                + (Pointer.nativeValue(U.GetParent(ourHwnd)) == Pointer.nativeValue(tray)));

        System.out.println("  任务栏子窗口 z 序（越靠前越在上层）：");
        // Shell_TrayWnd 本身是顶层窗口，它的兄弟是别的顶层窗口；
        // 要遍历它的「子窗口」，得先取到第一个子窗口再走兄弟链
        Pointer walk = U.GetWindow(U.FindWindowExW(tray, null, null, null), GW_HWNDFIRST);
        int idx = 0;
        while (walk != null && idx < 20) {
            boolean isOurs = Pointer.nativeValue(walk) == Pointer.nativeValue(ourHwnd);
            RECT r = rectOf(walk);
            System.out.printf("    %2d. %-58s %s%n",
                    idx, isOurs ? "★ " + clsName : className(walk),
                    isOurs ? describe(r) + "  ← 本插件窗口" : (r == null ? "" : describe(r)));
            walk = U.GetWindow(walk, GW_HWNDNEXT);
            idx++;
        }

        System.out.println();
        System.out.println("=== 6) 存出渲染结果（沙箱挡住了屏幕截图，用回读代替）===");
        BufferedImage copy = new BufferedImage(winW, winH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D cg = copy.createGraphics();
        cg.drawImage(surface, 0, 0, null);
        cg.dispose();
        File designed = new File(baseDir, "rendered.png");
        ImageIO.write(copy, "png", designed);
        System.out.println("  设计稿已保存: " + designed.getName());

        File rb = new File(baseDir, "readback.png");
        readbackWindow(rb);
        System.out.println("  窗口回读已保存: " + rb.getName());

        int holdSec = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        System.out.println();
        System.out.println("=== 7) 窗口保持 " + holdSec + " 秒，现在请看任务栏 ===");
        Thread.sleep(holdSec * 1000L);

        running = false;
        U.DestroyWindow(ourHwnd);
        G.DeleteObject(dibDib);
        G.DeleteDC(memDc);
        U.ReleaseDC(null, screenDc);
        System.out.println("已清理并退出");
    }

    /** 用 PrintWindow 把窗口真实内容回读到内存位图并存 PNG，验证像素确实进了窗口 */
    static void readbackWindow(File out) throws Exception {
        Pointer dc = U.GetDC(null);
        BITMAPINFO bmi = new BITMAPINFO();
        bmi.bmiHeader.biSize = bmi.bmiHeader.size();
        bmi.bmiHeader.biWidth = winW;
        bmi.bmiHeader.biHeight = winH;
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = 0;
        bmi.bmiHeader.biSizeImage = winW * winH * 4;

        PointerByReference ref = new PointerByReference();
        Pointer bmp = G.CreateDIBSection(dc, bmi, 0, ref, null, 0);
        Pointer bdc = G.CreateCompatibleDC(dc);
        G.SelectObject(bdc, bmp);

        boolean ok = U.PrintWindow(ourHwnd, bdc, PW_RENDERFULLCONTENT);
        System.out.println("  PrintWindow: " + (ok ? "成功" : "失败 err=" + Native.getLastError()));

        byte[] buf = ref.getValue().getByteArray(0, winW * winH * 4);
        int[] px = new int[winW * winH];
        for (int y = 0; y < winH; y++) {
            int srcRow = (winH - 1 - y) * winW * 4;   // DIB 自下而上
            for (int x = 0; x < winW; x++) {
                int o = srcRow + x * 4;
                px[y * winW + x] = ((buf[o + 3] & 0xFF) << 24)
                        | ((buf[o + 2] & 0xFF) << 16)
                        | ((buf[o + 1] & 0xFF) << 8)
                        | (buf[o] & 0xFF);
            }
        }
        BufferedImage img = new BufferedImage(winW, winH, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, winW, winH, px, 0, winW);
        ImageIO.write(img, "png", out);

        int opaque = 0, semi = 0, bright = 0;
        for (int v : px) {
            int a = v >>> 24;
            if (a > 200) opaque++;
            else if (a > 10) semi++;
            int lum = ((v >> 16 & 0xFF) + (v >> 8 & 0xFF) + (v & 0xFF)) / 3;
            if (a > 128 && lum > 180) bright++;
        }
        System.out.printf("  像素统计: 总=%d  不透明=%d  半透明=%d  亮文字像素=%d%n",
                px.length, opaque, semi, bright);

        G.DeleteObject(bmp);
        G.DeleteDC(bdc);
        U.ReleaseDC(null, dc);
    }

    static void messageLoop() {
        MSG msg = new MSG();
        String[][] song = {
                {"还没好好的感受", "雪花绽放的气候"},
                {"我们一起颤抖", "会更明白什么是温柔"},
                {"还没跟你牵着手", "走过荒芜的沙丘"},
                {"可能从此以后", "学会珍惜天长和地久"},
        };
        long start = System.currentTimeMillis();
        while (running) {
            while (U.PeekMessageW(msg, null, 0, 0, PM_REMOVE) != 0) {
                U.TranslateMessage(msg);
                U.DispatchMessageW(msg);
            }
            try {
                long elapsed = System.currentTimeMillis() - start;
                int idx = (int) ((elapsed / 2600) % song.length);
                double progress = (elapsed % 2600) / 2600.0;
                renderLyrics(song[idx][0], song[idx][1], progress);
                pushFrame();
                frame++;
                Thread.sleep(33);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                System.out.println("渲染异常: " + t);
                t.printStackTrace();
                return;
            }
        }
    }
}
