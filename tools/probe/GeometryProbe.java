import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.SecondLineKind;
import com.spwtb.taskbarlyrics.render.TaskbarLayerWindow;
import com.spwtb.taskbarlyrics.render.Win32;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;

import java.awt.image.BufferedImage;
import java.util.ArrayList;

/**
 * 验证「调设置时窗口不消失」。
 *
 * 以前的做法是几何一变就把窗口 destroy 再 CreateWindowEx，中间要黑屏 600ms——
 * 拖 xOffset / widthPercent 这类滑块时，用户等于在盲调。
 * 现在改成「同一个窗口 Move/Resize」，这里逐项确认：
 *
 *   1. 移动后 hwnd 不变（窗口没有被重建）
 *   2. GetWindowRect 跟上新的 x/y/宽/高
 *   3. 改尺寸后幕后 buffer 重建成功，pump + push 一帧后**回读得到内容**
 *      （这一步最关键：分层窗口的尺寸和 UpdateLayeredWindow 的 psize 必须同步，
 *        不同步的话 UpdateLayeredWindow 会直接失败，画面全黑）
 *   4. 连续多次改动（模拟拖滑块）全程不黑
 *
 * 用法：GeometryProbe
 */
public class GeometryProbe {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        Pointer tray = Win32.U.FindWindowW(new WString("Shell_TrayWnd"), null);
        if (tray == null) {
            System.out.println("找不到 Shell_TrayWnd（任务栏不在或没运行）");
            System.exit(2);
        }

        Win32.RECT trayRect = Win32.rectOf(tray);
        int height = trayRect.height();
        System.out.printf("任务栏：%dx%d @ (%d,%d)%n", trayRect.width(), height, trayRect.left, trayRect.top);

        LyricsPainter painter = new LyricsPainter();
        RenderSettings s = new RenderSettings();

        TaskbarLayerWindow win = null;
        try {
            int x0 = trayRect.left + 200;
            win = new TaskbarLayerWindow(tray, x0, trayRect.top, 300, height, true);
            Pointer hwnd0 = win.handle();
            System.out.printf("起始窗口 %dx%d @ x=%d  hwnd=%s%n", 300, height, x0, hwnd0);

            // 挡𝟙’)第一帧：pump 一下，让窗口真正挂进任务栏
            paintOnce(win, painter, s, height, 300, "初始位置");
            check("初始 hwnd 有效", win.isAlive(), "窗口没建起来");

            // ① 只挪位置
            int x1 = x0 + 60;
            win.setBounds(x1, trayRect.top, 300, height);
            paintOnce(win, painter, s, height, 300, "右移 60px");
            check("移动后 hwnd 没变", win.handle() == hwnd0, "窗口被重建了：" + win.handle());
            check("移动后 GetWindowRect 跟上", rectLeft(win) == convertX(win, tray, x1),
                    "实际 left=" + rectLeft(win));
            check("移动后画面还在", ink(win, 300, height) > 0, "回读到 0 个不透明像素");

            // ② 改宽度（幕后 DIB 要重建）
            int w2 = 520;
            int x2 = x1 - 40;
            win.setBounds(x2, trayRect.top, w2, height);
            paintOnce(win, painter, s, height, w2, "宽度 300 → 520");
            check("改宽度后 hwnd 没变", win.handle() == hwnd0, "窗口被重建了：" + win.handle());
            Win32.RECT r2 = Win32.rectOf(win.handle());
            check("改宽度后尺寸跟上", r2 != null && r2.width() == w2,
                    "实际 " + (r2 == null ? "null" : r2.width() + "x" + r2.height()));
            check("改宽度后画面还在", ink(win, w2, height) > 0, "画面全黑，UpdateLayeredWindow 大概失败了");

            // ③ 连续拖动：模拟一路拖滑块
            int worst = Integer.MAX_VALUE;
            for (int step = 0; step <= 10; step++) {
                int w = 300 + step * 20;
                int x = trayRect.left + 160 + step * 12;
                win.setBounds(x, trayRect.top, w, height);
                paintOnce(win, painter, s, height, w, null);
                int inkNow = ink(win, w, height);
                worst = Math.min(worst, inkNow);
            }
            System.out.printf("   连续 11 次改几何，墨量最少的一帧 = %d 像素%n", worst);
            check("拖动全程画面不黑", worst > 0, "有帧是全黑的");
            check("连续改动后仍是同一个窗口", win.handle() == hwnd0, "窗口被重建了");

            // ④ 没有变化时不折腾 Windows
            Win32.RECT before = Win32.rectOf(win.handle());
            boolean touched = win.setBounds(
                    trayRect.left + 160 + 10 * 12, trayRect.top, 300 + 10 * 20, height);
            Win32.RECT after = Win32.rectOf(win.handle());
            check("几何没变时不做多余操作", !touched, "仍然调用了 SetWindowPos");
            check("几何没变时窗口位置没漂",
                    before != null && after != null && before.left == after.left,
                    "left 从 " + (before == null ? "?" : before.left) + " 变成 "
                            + (after == null ? "?" : after.left));
        } finally {
            if (win != null) {
                win.destroy();
            }
        }

        System.out.println();
        System.out.printf("%d 项通过，%d 项失败%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 画一帧并推给窗口。尺寸必须和窗口当前大小一致。 */
    static void paintOnce(TaskbarLayerWindow win, LyricsPainter painter, RenderSettings s,
                          int height, int width, String label) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        LyricsFrame frame = new LyricsFrame(
                "调整设置时窗口不该消失", "这一行用来确认第二行也还在", null,
                new ArrayList(), 1000L, 0L, 5000L, SecondLineKind.NEXT);
        painter.paint(img, frame, s, 1.5);
        win.pumpMessages();
        win.pushFrame(((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData());
        win.pumpMessages();
    }

    /** 回读窗口真实内容，数一下有多少非透明像素。 */
    static int ink(TaskbarLayerWindow win, int w, int h) {
        int[] px = render(win.handle(), w, h);
        int n = 0;
        for (int v : px) {
            if ((v >>> 24) != 0) {
                n++;
            }
        }
        return n;
    }

    static int rectLeft(TaskbarLayerWindow win) {
        Win32.RECT r = Win32.rectOf(win.handle());
        return r == null ? Integer.MIN_VALUE : r.left;
    }

    /** 窗口参数里的屏幕 x → GetWindowRect 的 left（子窗口两套坐标系不一样）。 */
    static int convertX(TaskbarLayerWindow win, Pointer parent, int screenX) {
        return screenX;   // 子窗口的 GetWindowRect 返回的就是屏幕坐标
    }

    static int[] render(Pointer hwnd, int w, int h) {
        Pointer dc = Win32.U.GetDC(null);
        Win32.BITMAPINFO bmi = new Win32.BITMAPINFO();
        bmi.bmiHeader.biSize = bmi.bmiHeader.size();
        bmi.bmiHeader.biWidth = w;
        bmi.bmiHeader.biHeight = h;
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = 0;
        bmi.bmiHeader.biSizeImage = w * h * 4;

        PointerByReference ref = new PointerByReference();
        Pointer bmp = Win32.G.CreateDIBSection(dc, bmi, 0, ref, null, 0);
        Pointer bdc = Win32.G.CreateCompatibleDC(dc);
        Win32.G.SelectObject(bdc, bmp);

        Win32.U.PrintWindow(hwnd, bdc, Win32.PW_RENDERFULLCONTENT);

        byte[] buf = ref.getValue().getByteArray(0, w * h * 4);
        int[] px = new int[w * h];
        for (int row = 0; row < h; row++) {
            int srcRow = (h - 1 - row) * w * 4;
            for (int col = 0; col < w; col++) {
                int o = srcRow + col * 4;
                px[row * w + col] = ((buf[o + 3] & 0xFF) << 24)
                        | ((buf[o + 2] & 0xFF) << 16)
                        | ((buf[o + 1] & 0xFF) << 8)
                        | (buf[o] & 0xFF);
            }
        }
        Win32.G.DeleteDC(bdc);
        Win32.G.DeleteObject(bmp);
        Win32.U.ReleaseDC(null, dc);
        return px;
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + "  → " + detail);
        }
    }
}
