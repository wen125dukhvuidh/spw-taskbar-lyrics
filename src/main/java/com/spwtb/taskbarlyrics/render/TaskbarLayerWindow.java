package com.spwtb.taskbarlyrics.render;

import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * 逐像素透明的分层窗口。
 *
 * 两种形态：
 * - **embed**（默认）：建成任务栏的 WS_CHILD 子窗口，自动隐藏、z 序、随任务栏移动
 *   全部由 Explorer 托管，我们只管画。
 * - **overlay**：独立的 WS_EX_TOPMOST 顶层窗口，贴在任务栏上方。
 *
 * 渲染方式是 `UpdateLayeredWindow` + 32bpp DIB，天然支持逐像素 alpha，
 * 所以文字边缘不会有色块（前提是上层用灰度抗锯齿绘制）。
 *
 * 注意：**所有方法都必须由创建窗口的那个线程调用**（Win32 的要求），
 * 上层用一个专用渲染线程保证这一点。
 */
public final class TaskbarLayerWindow {

    private static final String CLASS_NAME = Win32.LYRICS_WINDOW_CLASS;

    /** 必须静态持有：一旦被 GC，原生回调会指向已释放的内存。 */
    private static Win32.User32.WndProc wndProc;
    private static boolean classRegistered;

    private final Pointer parent;
    private final boolean embed;
    private int x;
    private int y;
    private int width;
    private int height;

    private Pointer hwnd;
    private Pointer screenDc;
    private Pointer memDc;
    private Pointer dib;
    private Pointer dibBits;

    private byte[] scratch;
    /** scratch 的 int 视图：小端下 0xAARRGGBB 的内存布局正好是 DIB 要的 B,G,R,A。 */
    private IntBuffer scratchInts;
    private boolean pushFailedLogged;

    // 每帧都会用到的结构体，提成字段避免每帧分配原生内存。
    private final Win32.BLENDFUNCTION blend = new Win32.BLENDFUNCTION();
    private final Win32.SIZE surfaceSize = new Win32.SIZE(0, 0);
    private final Win32.POINT sourceOrigin = new Win32.POINT(0, 0);

    /**
     * 最后一次推送时用的窗口整体透明度。
     *
     * `UpdateLayeredWindow` 的 alpha 是**写进去就取不出来**的——没有对应的 getter，
     * 分层窗口也不支持从 API 读回它。留个副本是给自检与探针用的：
     * 万一画面没淡下去，至少能分清是「值没传对」还是「系统没用上」。
     */
    private int lastWindowAlpha = 255;

    public TaskbarLayerWindow(Pointer parent, int x, int y, int width, int height, boolean embed) {
        this.parent = parent;
        this.embed = embed;
        this.width = width;
        this.height = height;

        registerClassOnce();

        // 除 sourceConstantAlpha 外都是固定值；后者每帧按显隐程度更新。
        blend.blendOp = Win32.AC_SRC_OVER;
        blend.blendFlags = 0;
        blend.alphaFormat = Win32.AC_SRC_ALPHA;

        int exStyle = Win32.WS_EX_LAYERED | Win32.WS_EX_TOOLWINDOW
                | Win32.WS_EX_NOACTIVATE | Win32.WS_EX_TRANSPARENT;
        int style;
        Pointer parentArg;
        Win32.POINT rel = toWindowOrigin(x, y);

        if (embed) {
            style = Win32.WS_CHILD | Win32.WS_VISIBLE;
            parentArg = parent;
        } else {
            exStyle |= Win32.WS_EX_TOPMOST;
            style = Win32.WS_POPUP | Win32.WS_VISIBLE;
            parentArg = null;
        }

        hwnd = Win32.U.CreateWindowExW(exStyle, new com.sun.jna.WString(CLASS_NAME),
                new com.sun.jna.WString(""), style, rel.x, rel.y, width, height,
                parentArg, null, Win32.K.GetModuleHandleW(null), null);

        if (hwnd == null) {
            throw new IllegalStateException("CreateWindowExW 失败，err=" + Native.getLastError());
        }

        screenDc = Win32.U.GetDC(null);
        memDc = Win32.G.CreateCompatibleDC(screenDc);
        createSurface(width, height);

        this.x = x;
        this.y = y;
        // 提到同级最前，避免被 Win11 的 XAML 合成层盖住
        applyPosition();
    }

    /**
     * 换个位置或换个大小，**不重建窗口**。
     *
     * 调整显示位置、宽度这类设置时，画面必须全程可见：销毁再建会有几百毫秒的黑屏，
     * 用户等于是在盲调。WPF（原版）改 Window.Left/Top/Width 也只是 Move/Resize，
     * 没有任何理由在这里动窗口本身。
     *
     * 只有尺寸真的变了才重建幕后 buffer——那是 DIB，尺寸是定的。
     *
     * @return 是否真的动了（没动就什么都不用做）
     */
    public boolean setBounds(int newX, int newY, int newWidth, int newHeight) {
        if (hwnd == null) {
            return false;
        }
        // 1px 以内的抖动直接忽略。
        // 任务栏上的托盘时钟、网速浮窗宽度会随时间变化，算出来的位置每 500ms
        // 就会漂几像素。跟着漂有两个代价：1) 尺寸变了要重建 DIB（贵）；
        // 2) 画面会持续抖。肉眼无感的移动，不值得动窗口。
        boolean sizeChanged = Math.abs(newWidth - width) > 1 || Math.abs(newHeight - height) > 1;
        boolean moved = Math.abs(newX - x) > 1 || Math.abs(newY - y) > 1;
        if (!sizeChanged && !moved) {
            return false;
        }
        if (sizeChanged) {
            resizeSurface(newWidth, newHeight);
        }
        x = newX;
        y = newY;
        applyPosition();
        return true;
    }

    /** 屏幕坐标 → 传给 CreateWindow/SetWindowPos 的坐标（子窗口是相对父窗口客户区的）。 */
    private Win32.POINT toWindowOrigin(int screenX, int screenY) {
        if (embed) {
            Win32.POINT origin = Win32.clientOrigin(parent);
            return new Win32.POINT(screenX - origin.x, screenY - origin.y);
        }
        return new Win32.POINT(screenX, screenY);
    }

    private void applyPosition() {
        Win32.POINT rel = toWindowOrigin(x, y);
        Win32.U.SetWindowPos(hwnd, Win32.HWND_TOP, rel.x, rel.y, width, height,
                Win32.SWP_NOACTIVATE | Win32.SWP_SHOWWINDOW);
    }

    /** 创建指定尺寸的幕后 DIB 并选入内存 DC。 */
    private void createSurface(int surfaceWidth, int surfaceHeight) {
        // DIB：32bpp BI_RGB。biHeight 用正数 => 自下而上，写像素时按行倒序。
        Win32.BITMAPINFO bmi = new Win32.BITMAPINFO();
        bmi.bmiHeader.biSize = bmi.bmiHeader.size();
        bmi.bmiHeader.biWidth = surfaceWidth;
        bmi.bmiHeader.biHeight = surfaceHeight;
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = 0;
        bmi.bmiHeader.biSizeImage = surfaceWidth * surfaceHeight * 4;

        PointerByReference bitsRef = new PointerByReference();
        Pointer newDib = Win32.G.CreateDIBSection(screenDc, bmi, 0, bitsRef, null, 0);
        if (newDib == null) {
            destroy();
            throw new IllegalStateException("CreateDIBSection 失败，err=" + Native.getLastError());
        }
        Win32.G.SelectObject(memDc, newDib);
        dib = newDib;
        dibBits = bitsRef.getValue();
        scratch = new byte[surfaceWidth * surfaceHeight * 4];
        // Windows 全是小端：0xAARRGGBB 直接按 int 写进 byte[]，内存里就是 B,G,R,A，
        // DIB 要的正是这个顺序。比逐字节拆 4 次快一个量级。
        scratchInts = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        surfaceSize.cx = surfaceWidth;
        surfaceSize.cy = surfaceHeight;
    }

    private void resizeSurface(int surfaceWidth, int surfaceHeight) {
        Pointer oldDib = dib;
        // 被选入 DC 的位图删不掉：先把一个 1×1 的替身选进去，把旧图换出来
        Pointer stub = Win32.G.CreateCompatibleBitmap(screenDc, 1, 1);
        Win32.G.SelectObject(memDc, stub);
        if (oldDib != null) {
            Win32.G.DeleteObject(oldDib);
        }
        dib = null;
        // createSurface 会把新 DIB 选进同一个 DC，替身随之换出
        createSurface(surfaceWidth, surfaceHeight);
        Win32.G.DeleteObject(stub);
        width = surfaceWidth;
        height = surfaceHeight;
    }

    private static synchronized void registerClassOnce() {
        if (classRegistered) {
            return;
        }
        wndProc = new Win32.User32.WndProc() {
            @Override
            public long invoke(Pointer h, int msg, long wParam, long lParam) {
                // 分层窗口的内容来自 UpdateLayeredWindow，不需要处理 WM_PAINT；
                // 其余消息一律交给系统默认处理，保持「什么都不做」的纯粹性。
                Pointer result = Win32.U.DefWindowProcW(h, msg, wParam, lParam);
                return Pointer.nativeValue(result);
            }
        };

        Win32.WNDCLASSEXW wc = new Win32.WNDCLASSEXW();
        wc.cbSize = wc.size();
        wc.style = 0;
        wc.lpfnWndProc = CallbackReference.getFunctionPointer(wndProc);
        wc.hInstance = Win32.K.GetModuleHandleW(null);
        wc.hCursor = Win32.loadCursor();
        // 背景刷必须为 null：分层窗口自己管背景，给了刷子反而会闪
        wc.hbrBackground = null;
        wc.lpszClassName = new com.sun.jna.WString(CLASS_NAME);
        Win32.U.RegisterClassExW(wc);
        classRegistered = true;
    }

    /**
     * 推送一帧，整体不透明度为 255（完全不透明）。
     *
     * @see pushFrame(int[], int)
     */
    public void pushFrame(int[] premultipliedArgb) {
        pushFrame(premultipliedArgb, 255);
    }

    /**
     * 推送一帧。入参是**预乘 alpha** 的 0xAARRGGBB 数组，
     * 内部转换成 DIB 需要的 BGRA 自下而上布局。
     *
     * @param windowAlpha 整个窗口的整体不透明度，0..255，写进
     *     `BLENDFUNCTION.sourceConstantAlpha`。
     *
     *     这是**显隐淡入淡出的实现点**：Win32 分层窗口自带一层系统级合成的
     *     整体透明度，由 DWM 完成乘法，我们不必为了淡出去重画任何像素
     *     —— 每帧的开销就是同一个 `UpdateLayeredWindow` 调用。
     *     反过来，若是在画布里按像素乘 alpha，各图层之间已经 alpha 混合过了，
     *     再单独乘每一层是不等价的，只能是「离屏合成后再整体乘」，
     *     多一次全画布 blit。
     */
    public void pushFrame(int[] premultipliedArgb, int windowAlpha) {
        if (hwnd == null || dibBits == null) {
            return;
        }
        // DIB 是自下而上存的，源图是自上而下：逐行倒序拷贝，行内按 int 整段写。
        for (int y = 0; y < height; y++) {
            scratchInts.position((height - 1 - y) * width);
            scratchInts.put(premultipliedArgb, y * width, width);
        }
        dibBits.write(0, scratch, 0, scratch.length);

        blend.sourceConstantAlpha = (byte) (windowAlpha & 0xFF);
        lastWindowAlpha = windowAlpha & 0xFF;

        // hdcDst / pptDst 传 null：位置与尺寸由 CreateWindowExW / SetWindowPos 负责，
        // 这里只更新内容，避免「子窗口坐标是相对父窗口还是屏幕」的歧义
        boolean ok = Win32.U.UpdateLayeredWindow(hwnd, null, null,
                surfaceSize, memDc, sourceOrigin,
                Win32.COLOR_KEY, blend, Win32.ULW_ALPHA);

        if (!ok && !pushFailedLogged) {
            pushFailedLogged = true;
            System.out.println("[TaskbarLyrics] UpdateLayeredWindow 失败，err=" + Native.getLastError());
        }
    }

    /** 处理本线程待处理的消息。必须在窗口所属线程调用。 */
    public void pumpMessages() {
        Win32.MSG msg = new Win32.MSG();
        while (Win32.U.PeekMessageW(msg, null, 0, 0, Win32.PM_REMOVE) != 0) {
            Win32.U.TranslateMessage(msg);
            Win32.U.DispatchMessageW(msg);
        }
    }

    /** 承载它的任务栏是否还在（Explorer 重启后会失效，需要重建）。 */
    public boolean parentGone() {
        return !Win32.isAlive(parent);
    }

    public boolean isAlive() {
        return Win32.isAlive(hwnd);
    }

    public int width() {
        return width;
    }

    /** 窗口是否作为任务栏的子窗口存在（换它需要重建，移动不需要）。 */
    public boolean embed() {
        return embed;
    }

    /** 见字段 [lastWindowAlpha]。 */
    public int lastWindowAlpha() {
        return lastWindowAlpha;
    }

    public int height() {
        return height;
    }

    /** 原生窗口句柄，供自检与 PrintWindow 回读使用。 */
    public Pointer handle() {
        return hwnd;
    }

    public void destroy() {
        if (hwnd != null) {
            Win32.U.DestroyWindow(hwnd);
            hwnd = null;
        }
        if (dib != null) {
            Win32.G.DeleteObject(dib);
            dib = null;
        }
        if (memDc != null) {
            Win32.G.DeleteDC(memDc);
            memDc = null;
        }
        if (screenDc != null) {
            Win32.U.ReleaseDC(null, screenDc);
            screenDc = null;
        }
        dibBits = null;
    }
}
