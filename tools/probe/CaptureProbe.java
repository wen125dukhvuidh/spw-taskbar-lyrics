import com.spwtb.taskbarlyrics.render.Win32;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 抓取任务栏上「歌词窗口」的真实画面。
 *
 * 用途：本机同时跑着本插件与原版 TaskbarLyrics 时，
 * 把两者的窗口内容各抓一张 PNG，做同尺寸的客观比较（尤其是封面）。
 *
 * 用 PrintWindow + PW_RENDERFULLCONTENT 而不是屏幕截图：
 * 沙箱环境里截屏会被挡成纯黑，而 PrintWindow 是让目标窗口自己把内容画到给定 DC 上。
 */
public class CaptureProbe {

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";

        Pointer tray = Win32.U.FindWindowW(new WString("Shell_TrayWnd"), null);
        if (tray == null) {
            System.out.println("找不到 Shell_TrayWnd");
            return;
        }

        System.out.println("任务栏子窗口中宽度 > 40 的可见窗口：");
        Pointer child = Win32.U.FindWindowExW(tray, null, null, null);
        while (child != null) {
            String cls = className(child);
            Win32.RECT r = Win32.rectOf(child);
            if (r != null && r.width() > 40 && Win32.U.IsWindowVisible(child)) {
                System.out.printf("  %-52s %4dx%-3d  @ %d%n",
                        shorten(cls), r.width(), r.height(), r.left);
                String file = null;
                if (cls.contains("SpwTaskbarLyrics")) {
                    file = "window-ours.png";
                } else if (cls.contains("TaskbarLyrics.App")) {
                    file = "window-anync.png";
                }
                if (file != null) {
                    captureStable(child, r.width(), r.height(), new File(outDir, file), outDir, file);
                    System.out.println("        → 已抓取 " + file);
                }
            }
            child = Win32.U.FindWindowExW(tray, child, null, null);
        }
    }

    /**
     * 抓一帧**静止**的画面。
     *
     * 原版切行有 560ms 的 opacity + scale 过渡（`.current-line.leaving` 会缩到 0.972
     * 并降到 0.16），如果抓在动画中间帧，两行字形会叠在一起——看上去比实际更粗更糊。
     * 所以连抓若干帧、比较相邻帧的差异，取差异最小的那一帧作为"稳定帧"。
     */
    private static void captureStable(Pointer hwnd, int w, int h, File out,
                                      String outDir, String baseName) throws Exception {
        final int frames = 5;
        final long gapMs = 320;
        java.util.List<int[]> shots = new java.util.ArrayList<>();
        for (int i = 0; i < frames; i++) {
            shots.add(render(hwnd, w, h));
            if (i < frames - 1) Thread.sleep(gapMs);
        }

        int bestIdx = 0;
        long bestDiff = Long.MAX_VALUE;
        StringBuilder diffs = new StringBuilder();
        for (int i = 0; i < frames - 1; i++) {
            long d = diff(shots.get(i), shots.get(i + 1));
            diffs.append("f").append(i).append("→").append(i + 1).append('=').append(d).append(' ');
            if (d < bestDiff) {
                bestDiff = d;
                bestIdx = i;
            }
        }
        System.out.println("        相邻帧差异: " + diffs);
        System.out.printf("        取第 %d 帧（差异 %d）%n", bestIdx, bestDiff);

        write(shots.get(bestIdx), w, h, out);
        // 同时保留每一帧，便于人工核对动画影响
        for (int i = 0; i < frames; i++) {
            write(shots.get(i), w, h, new File(outDir,
                    baseName.replace(".png", "") + "-f" + i + ".png"));
        }
    }

    private static void write(int[] px, int w, int h, File out) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(img, "png", out);
    }

    private static long diff(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(((a[i] >> 16) & 0xFF) - ((b[i] >> 16) & 0xFF));
            sum += Math.abs(((a[i] >> 8) & 0xFF) - ((b[i] >> 8) & 0xFF));
            sum += Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
        }
        return sum;
    }

    private static int[] render(Pointer hwnd, int w, int h) throws Exception {
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

        boolean ok = Win32.U.PrintWindow(hwnd, bdc, Win32.PW_RENDERFULLCONTENT);
        if (!ok) System.out.println("        PrintWindow 失败");

        byte[] buf = ref.getValue().getByteArray(0, w * h * 4);
        int[] px = new int[w * h];
        for (int row = 0; row < h; row++) {
            int srcRow = (h - 1 - row) * w * 4;   // DIB 自下而上
            for (int col = 0; col < w; col++) {
                int o = srcRow + col * 4;
                px[row * w + col] = ((buf[o + 3] & 0xFF) << 24)
                        | ((buf[o + 2] & 0xFF) << 16)
                        | ((buf[o + 1] & 0xFF) << 8)
                        | (buf[o] & 0xFF);
            }
        }

        Win32.G.DeleteObject(bmp);
        Win32.G.DeleteDC(bdc);
        Win32.U.ReleaseDC(null, dc);
        return px;
    }

    private static void capture(Pointer hwnd, int w, int h, File out) throws Exception {
        write(render(hwnd, w, h), w, h, out);
    }

    private static String className(Pointer hwnd) {
        char[] buf = new char[512];
        int n = Win32.U.GetClassNameW(hwnd, buf, buf.length);
        return n <= 0 ? "" : new String(buf, 0, n);
    }

    private static String shorten(String cls) {
        return cls.length() <= 52 ? cls : cls.substring(0, 49) + "...";
    }
}
