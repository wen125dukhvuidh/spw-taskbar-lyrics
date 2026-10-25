package com.spwtb.taskbarlyrics.render;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Win32 原生层：JNA 声明 + 任务栏几何。
 *
 * 这一层用 Java 而不是 Kotlin，是因为 JNA 的 Structure 依赖**公开字段反射**，
 * 而 Kotlin 属性默认生成私有字段 + getter，必须逐个加 @JvmField 才能工作。
 * 这里的代码移植自已实测通过的 tools/probe/TbDemo.java，保持原样最稳。
 *
 * 全部原生库都是**延迟加载**：加载失败不影响插件启动，只是渲染器不可用，
 * 由上层降级为提示而不是抛异常。
 */
public final class Win32 {

    private Win32() {
    }

    // ─────────────────────────────────────────────────────────── 原生库

    public interface User32 extends StdCallLibrary {

        interface WndProc extends StdCallCallback {
            long invoke(Pointer hwnd, int msg, long wParam, long lParam);
        }

        Pointer FindWindowW(WString cls, WString name);

        Pointer FindWindowExW(Pointer parent, Pointer after, WString cls, WString name);

        int GetClassNameW(Pointer hwnd, char[] buf, int maxCount);

        boolean GetWindowRect(Pointer hwnd, RECT rect);

        boolean IsWindow(Pointer hwnd);

        boolean IsWindowVisible(Pointer hwnd);

        int GetDpiForWindow(Pointer hwnd);

        short RegisterClassExW(WNDCLASSEXW wc);

        Pointer CreateWindowExW(int exStyle, WString className, WString windowName, int style,
                                int x, int y, int w, int h,
                                Pointer parent, Pointer menu, Pointer instance, Pointer param);

        boolean DestroyWindow(Pointer hwnd);

        Pointer DefWindowProcW(Pointer hwnd, int msg, long wParam, long lParam);

        boolean SetWindowPos(Pointer hwnd, Pointer insertAfter, int x, int y, int cx, int cy, int flags);

        boolean ShowWindow(Pointer hwnd, int cmd);

        boolean PrintWindow(Pointer hwnd, Pointer hdcBlt, int flags);

        Pointer GetDC(Pointer hwnd);

        int ReleaseDC(Pointer hwnd, Pointer hdc);

        Pointer LoadCursorW(Pointer instance, Pointer cursorName);

        boolean ClientToScreen(Pointer hwnd, POINT pt);

        boolean UpdateLayeredWindow(Pointer hwnd, Pointer hdcDst, POINT pptDst, SIZE psize,
                                   Pointer hdcSrc, POINT pptSrc, int crKey, BLENDFUNCTION blend, int flags);

        int PeekMessageW(MSG msg, Pointer hwnd, int filterMin, int filterMax, int removeMsg);

        boolean TranslateMessage(MSG msg);

        Pointer DispatchMessageW(MSG msg);

        Pointer GetWindow(Pointer hwnd, int cmd);

        Pointer GetParent(Pointer hwnd);

        /** 取主显示器的当前显示模式，用来拿刷新率（`DEVMODEW.dmDisplayFrequency`）。 */
        boolean EnumDisplaySettingsW(String deviceName, int modeNum, Pointer devMode);
    }

    public interface Gdi32 extends StdCallLibrary {

        Pointer CreateCompatibleDC(Pointer hdc);

        /** 改窗口大小时用来把旧 DIB 从 DC 里顶出来的替身。 */
        Pointer CreateCompatibleBitmap(Pointer hdc, int width, int height);

        boolean DeleteDC(Pointer hdc);

        Pointer CreateDIBSection(Pointer hdc, BITMAPINFO bmi, int usage,
                                 com.sun.jna.ptr.PointerByReference bits, Pointer section, int offset);

        Pointer SelectObject(Pointer hdc, Pointer obj);

        /**
         * 从屏幕拷像素出来。
         *
         * 用在自检/探针里：分层窗口的整体透明度（`SourceConstantAlpha`）是 DWM
         * 合成时才生效的，没有 API 能读回来，只能把**屏幕**上那一块拷下来看。
         */
        boolean BitBlt(Pointer hdcDest, int destX, int destY, int width, int height,
                       Pointer hdcSrc, int srcX, int srcY, int rop);

        boolean DeleteObject(Pointer obj);
    }

    public interface Kernel32 extends StdCallLibrary {

        Pointer GetModuleHandleW(WString name);
    }

    public interface Advapi32 extends StdCallLibrary {

        int RegGetValueW(Pointer hkey, WString subKey, WString value, int flags,
                         com.sun.jna.ptr.IntByReference type,
                         com.sun.jna.ptr.IntByReference data,
                         com.sun.jna.ptr.IntByReference size);
    }

    /**
     * 多媒体计时器：把系统时钟粒度从默认的 15.625ms 提到 1ms。
     *
     * **这是我们能不能跑顺动画的关键。** Windows 默认定时器粒度是 15.625ms，
     * `Thread.sleep(16)` 会被向上取整到 31.25ms，60fps 直接掉成 32fps 而且抖动剧烈——
     * 肉眼看就是「一顿一顿」。游戏和视频播放器都是靠 `timeBeginPeriod(1)` 解决的。
     */
    public interface Winmm extends StdCallLibrary {

        int timeBeginPeriod(int uPeriod);

        int timeEndPeriod(int uPeriod);
    }

    // ───────────────────────────────────────────────────────────── 结构体

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

    // ─────────────────────────────────────────────────────────────── 常量

    /** 歌词窗口的窗口类名。空闲区扫描靠它把自己排除掉，见 {@link #freeSlots}。 */
    public static final String LYRICS_WINDOW_CLASS = "SpwTaskbarLyricsWnd";

    public static final int GWL_EXSTYLE = -20;
    public static final int GWL_STYLE = -16;

    public static final int WS_EX_LAYERED = 0x00080000;
    public static final int WS_EX_TOOLWINDOW = 0x00000080;
    public static final int WS_EX_NOACTIVATE = 0x08000000;
    public static final int WS_EX_TRANSPARENT = 0x00000020;
    public static final int WS_EX_TOPMOST = 0x00000008;

    public static final int WS_POPUP = 0x80000000;
    public static final int WS_CHILD = 0x40000000;
    public static final int WS_VISIBLE = 0x10000000;

    public static final int SWP_NOSIZE = 0x0001;
    public static final int SWP_NOMOVE = 0x0002;
    public static final int SWP_NOZORDER = 0x0004;
    public static final int SWP_NOACTIVATE = 0x0010;
    public static final int SWP_SHOWWINDOW = 0x0040;

    public static final int ULW_ALPHA = 0x00000002;
    public static final byte AC_SRC_OVER = 0x00;
    public static final byte AC_SRC_ALPHA = 0x01;
    public static final byte[] BLEND = {AC_SRC_OVER, 0, (byte) 0xFF, AC_SRC_ALPHA};

    public static final int PM_REMOVE = 0x0001;

    /** PrintWindow 的 PW_RENDERFULLCONTENT：分层窗口必须带这个标志才能抓到内容 */
    public static final int PW_RENDERFULLCONTENT = 0x00000002;

    /** `BitBlt` 的 SRCCOPY：直接把源拷到目标。 */
    public static final int SRCCOPY = 0x00CC0020;

    public static final int GW_HWNDFIRST = 0;
    public static final int GW_HWNDNEXT = 2;

    public static final Pointer HWND_TOP = Pointer.createConstant(0);
    public static final Pointer HWND_TOPMOST = Pointer.createConstant(-1);

    public static final int COLOR_KEY = 0;

    /** IDC_ARROW */
    private static final Pointer IDC_ARROW = Pointer.createConstant(32512);

    // ───────────────────────────────────────────────────────────── 加载

    public static final User32 U;
    public static final Gdi32 G;
    public static final Kernel32 K;
    public static final Advapi32 A;
    public static final Winmm M;

    private static final String LOAD_ERROR;

    static {
        User32 u = null;
        Gdi32 g = null;
        Kernel32 k = null;
        Advapi32 a = null;
        Winmm m = null;
        String err = null;
        try {
            u = Native.load("user32", User32.class);
            g = Native.load("gdi32", Gdi32.class);
            k = Native.load("kernel32", Kernel32.class);
            a = Native.load("advapi32", Advapi32.class);
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        // winmm 缺失不影响主体功能：拿不到 1ms 时钟就退回默认粒度，动画会抖但还能跑
        try {
            m = Native.load("winmm", Winmm.class);
        } catch (Throwable ignored) {
        }
        U = u;
        G = g;
        K = k;
        A = a;
        M = m;
        LOAD_ERROR = err;
    }

    public static boolean isAvailable() {
        return LOAD_ERROR == null;
    }

    public static String loadError() {
        return LOAD_ERROR == null ? "" : LOAD_ERROR;
    }

    // ─────────────────────────────────────────────────── 帧调度用的计时设施

    /** `EnumDisplaySettingsW` 的 modeNum：当前生效的显示模式。 */
    private static final int ENUM_CURRENT_SETTINGS = -1;

    /**
     * `DEVMODEW` 里 `dmSize` / `dmDisplayFrequency` 的字节偏移。
     *
     * 这里没用 JNA 的 Structure 去映射整个 DEVMODEW：它的真实大小是 220 字节，
     * 而 JNA 的结构体大小由声明的字段推算——只声明前半段字段会让它分配出 92 字节的
     * 缓冲区，系统却按 dmSize=220 往里写，**直接越界**。所以按偏移量手动读写。
     */
    private static final int DEVMODEW_SIZE = 220;
    private static final int OFF_DM_SIZE = 68;
    private static final int OFF_DM_DISPLAY_FREQUENCY = 88;

    /**
     * 主显示器的刷新率（Hz）。拿不到就返回 60。
     *
     * 动画的帧预算按它来定：DWM 按 vsync 合成，画得比刷新率更快没有意义，
     * 画得比它慢才会掉帧。原版是 Chromium 的合成器，天然就是跟着刷新率走的。
     */
    public static int displayRefreshHz() {
        if (U == null) {
            return 60;
        }
        try {
            Memory dm = new Memory(DEVMODEW_SIZE);
            dm.clear();
            dm.setShort(OFF_DM_SIZE, (short) DEVMODEW_SIZE);
            if (U.EnumDisplaySettingsW(null, ENUM_CURRENT_SETTINGS, dm)) {
                int hz = dm.getInt(OFF_DM_DISPLAY_FREQUENCY);
                if (hz >= 24 && hz <= 480) {
                    return hz;
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就按 60Hz 走，不拖累主流程
        }
        return 60;
    }

    /**
     * 把系统时钟粒度提到 1ms。
     *
     * Windows 默认粒度 15.625ms，`Thread.sleep(16)` 实际会睡 31.25ms——
     * 这就是「明明设了 60fps，动画还是一顿一顿」的根因。
     *
     * 必须与 [endHighResolutionTimer] 成对调用，否则会持续影响整机功耗。
     */
    public static void beginHighResolutionTimer() {
        if (M != null) {
            try {
                M.timeBeginPeriod(1);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void endHighResolutionTimer() {
        if (M != null) {
            try {
                M.timeEndPeriod(1);
            } catch (Throwable ignored) {
            }
        }
    }

    // ────────────────────────────────────────────────────── 几何 / 探查

    /** 任务栏描述。 */
    public static final class Taskbar {
        public final Pointer hwnd;
        public final int left, top, right, bottom;
        public final int dpi;
        public final boolean secondary;

        Taskbar(Pointer hwnd, RECT r, int dpi, boolean secondary) {
            this.hwnd = hwnd;
            this.left = r.left;
            this.top = r.top;
            this.right = r.right;
            this.bottom = r.bottom;
            this.dpi = dpi;
            this.secondary = secondary;
        }

        public int height() {
            return bottom - top;
        }

        public int width() {
            return right - left;
        }

        /** 以 48 逻辑像素为基准的缩放比（Win11 任务栏的标准高度）。 */
        public double scale() {
            return Math.max(0.5, height() / 48.0);
        }

        @Override
        public String toString() {
            return String.format("%s (%d,%d)-(%d,%d) %dx%d dpi=%d",
                    secondary ? "副屏任务栏" : "主任务栏",
                    left, top, right, bottom, width(), height(), dpi);
        }
    }

    /** 任务栏上的一段空闲横条。 */
    public static final class Slot {
        public final int start;
        public final int width;

        Slot(int start, int width) {
            this.start = start;
            this.width = width;
        }

        @Override
        public String toString() {
            return "x=" + start + " 宽=" + width;
        }
    }

    private static String className(Pointer hwnd) {
        char[] buf = new char[256];
        int n = U.GetClassNameW(hwnd, buf, buf.length);
        return n <= 0 ? "" : new String(buf, 0, n);
    }

    public static RECT rectOf(Pointer hwnd) {
        RECT r = new RECT();
        return U.GetWindowRect(hwnd, r) ? r : null;
    }

    /** 枚举任务栏（主 + 所有副屏）。 */
    public static List<Taskbar> findTaskbars() {
        List<Taskbar> list = new ArrayList<>();
        Pointer primary = U.FindWindowW(new WString("Shell_TrayWnd"), null);
        if (primary != null) {
            RECT r = rectOf(primary);
            if (r != null) {
                list.add(new Taskbar(primary, r, U.GetDpiForWindow(primary), false));
            }
        }
        // 副屏任务栏可能不止一个，用 FindWindowExW 逐个找
        Pointer after = null;
        while (true) {
            Pointer h = U.FindWindowExW(null, after, new WString("Shell_SecondaryTrayWnd"), null);
            if (h == null) {
                break;
            }
            RECT r = rectOf(h);
            if (r != null) {
                list.add(new Taskbar(h, r, U.GetDpiForWindow(h), true));
            }
            after = h;
        }
        return list;
    }

    /**
     * 算出任务栏上的空闲横条。
     *
     * 关键在于**排除全宽的 XAML 合成层**
     * （`Windows.UI.Composition.DesktopWindowContentBridge`）——它覆盖整条任务栏，
     * 若算作占用就永远找不到空隙。
     */
    /**
     * 找出任务栏上没被别的窗口占住的空横条。
     *
     * **必须排除歌词窗口自己**：embed 模式下它本身就是任务栏的子窗口，而且可见。
     * 把它算进「占用」就会出现自我指涉——每轮重扫都把歌词当前占的地盘划走，
     * 下一轮只能选另一块空闲区，再下一轮又跳回来。实测在 2560px 宽的任务栏上
     * 会让歌词窗口以 1 秒周期在左右两边来回闪。
     */
    public static List<Slot> freeSlots(Taskbar taskbar) {
        return freeSlots(taskbar, true);
    }

    /**
     * @param excludeOwn 是否把歌词窗口自身从「占用」里剔除。生产路径恒为 true，
     *                   只有探针做「修复前 / 修复后」对照时才传 false。
     */
    public static List<Slot> freeSlots(Taskbar taskbar, boolean excludeOwn) {
        List<int[]> occupied = new ArrayList<>();
        Pointer child = U.FindWindowExW(taskbar.hwnd, null, null, null);
        while (child != null) {
            RECT r = rectOf(child);
            String cls = className(child);
            boolean skip = !U.IsWindowVisible(child)
                    || r == null
                    || r.width() <= 1
                    || cls.contains("DesktopWindowContentBridge")
                    || (excludeOwn && LYRICS_WINDOW_CLASS.equals(cls));
            if (!skip) {
                occupied.add(new int[]{r.left, r.right});
            }
            child = U.FindWindowExW(taskbar.hwnd, child, null, null);
        }
        Collections.sort(occupied, (a, b) -> Integer.compare(a[0], b[0]));

        List<Slot> slots = new ArrayList<>();
        int cursor = taskbar.left;
        for (int[] seg : occupied) {
            if (seg[0] - cursor > 0) {
                slots.add(new Slot(cursor, seg[0] - cursor));
            }
            cursor = Math.max(cursor, seg[1]);
        }
        if (taskbar.right - cursor > 0) {
            slots.add(new Slot(cursor, taskbar.right - cursor));
        }
        return slots;
    }

    /** 该窗口是否仍然有效（Explorer 重启后任务栏句柄会失效）。 */
    public static boolean isAlive(Pointer hwnd) {
        return hwnd != null && U.IsWindow(hwnd);
    }

    /** 取父窗口客户区原点，用于把屏幕坐标换算成子窗口局部坐标。 */
    public static POINT clientOrigin(Pointer parent) {
        POINT origin = new POINT(0, 0);
        U.ClientToScreen(parent, origin);
        return origin;
    }

    static Pointer loadCursor() {
        return U.LoadCursorW(null, IDC_ARROW);
    }

    /** HKEY_CURRENT_USER */
    private static final Pointer HKEY_CURRENT_USER = Pointer.createConstant(0x80000001L);

    /** RRF_RT_REG_DWORD */
    private static final int RRF_RT_REG_DWORD = 0x00000010;

    private static Boolean lightThemeCache;
    private static long lightThemeCachedAt;

    /**
     * 系统是否使用浅色主题。
     *
     * 读 `HKCU\...\Themes\Personalize\SystemUsesLightTheme`。
     * 结果缓存 5 秒——绘制时每帧都要用，不能每次都打注册表。
     */
    public static boolean isSystemLightTheme() {
        long now = System.currentTimeMillis();
        if (lightThemeCache != null && now - lightThemeCachedAt < 5000) {
            return lightThemeCache;
        }
        boolean light = false;
        if (A != null) {
            try {
                com.sun.jna.ptr.IntByReference type = new com.sun.jna.ptr.IntByReference();
                com.sun.jna.ptr.IntByReference data = new com.sun.jna.ptr.IntByReference();
                com.sun.jna.ptr.IntByReference size = new com.sun.jna.ptr.IntByReference(4);
                int rc = A.RegGetValueW(HKEY_CURRENT_USER,
                        new WString("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"),
                        new WString("SystemUsesLightTheme"),
                        RRF_RT_REG_DWORD, type, data, size);
                light = rc == 0 && data.getValue() == 1;
            } catch (Throwable ignored) {
                light = false;
            }
        }
        lightThemeCache = light;
        lightThemeCachedAt = now;
        return light;
    }
}
