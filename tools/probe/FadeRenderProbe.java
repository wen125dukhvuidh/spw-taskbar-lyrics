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
 * 端到端验证「歌词窗口真的会淡入淡出」。
 *
 * 这一步不能省：状态机算对了不等于画面淡下去了。真正让整窗变淡的是
 * `UpdateLayeredWindow` 的 `BLENDFUNCTION.sourceConstantAlpha`，而这个值
 * **没有任何 API 能读回来**——所以只能看落到屏幕上的结果。
 *
 * 试过两条路，有一条不通，记下来免得重复踩：
 *
 * - `PrintWindow` **不行**。它读到的是窗口自己的位图（合成之前的内容），
 *   每个像素 alpha 恒为 255，跟 `sourceConstantAlpha` 一点关系都没有
 *   （实测 alpha=0 与 alpha=255 的回读墨量完全相同，都是 6609600）。
 * - **直接拷屏幕**行。`BitBlt` 从屏幕 DC 取窗口那一块矩形，拿到的是 DWM
 *   合成之后的结果。再拿「窗口还没建立时」的同一块矩形做基线，
 *   两者之差的绝对值就是歌词实际贡献的量。
 *
 * 于是这块验证变得很干净：**画布一次都不改，只改窗口整体透明度**，
 * 如果屏幕上的差值跟着单调变小，那只能是整体透明度生效了。
 *
 * 用法：FadeRenderProbe
 */
public class FadeRenderProbe {

    static int passed = 0;
    static int failed = 0;

    static final int W = 360;

    /** DWM 合成是异步的，推完帧要给它一点时间真的画到屏幕上。 */
    static final int SETTLE_MS = 120;

    public static void main(String[] args) throws Exception {
        Pointer tray = Win32.U.FindWindowW(new WString("Shell_TrayWnd"), null);
        if (tray == null) {
            System.out.println("找不到 Shell_TrayWnd");
            System.exit(2);
        }

        Win32.RECT trayRect = Win32.rectOf(tray);
        int height = trayRect.height();
        int x;
        int y = trayRect.top;
        // 用最宽的一段空闲区：随手放的坐标可能落在开始按钮上，被它盖住
        Win32.Slot slot = null;
        for (Win32.Slot s2 : Win32.freeSlots(Win32.findTaskbars().get(0))) {
            if (slot == null || s2.width > slot.width) {
                slot = s2;
            }
        }
        x = slot.start + 20;
        System.out.printf("任务栏：%dx%d @ (%d,%d)%n",
                trayRect.width(), height, trayRect.left, trayRect.top);
        System.out.printf("窗口矩形：%dx%d @ (%d,%d)%n", W, height, x, y);

        LyricsPainter painter = new LyricsPainter();
        RenderSettings s = new RenderSettings();
        LyricsFrame frame = new LyricsFrame(
                "显隐淡入淡出验证", "fade in out", null, new ArrayList(),
                5000L, 0L, 5000L, SecondLineKind.NEXT);

        BufferedImage img = new BufferedImage(W, height, BufferedImage.TYPE_INT_ARGB_PRE);
        painter.paint(img, frame, s, 1.5);
        int[] pixels = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();

        ScreenGrab grab = new ScreenGrab(W, height);

        // ── 基线：窗口还没建立时，这一块屏幕长什么样 ──────────────────────
        int[] baseline = grab.grab(x, y);
        double selfDiff = meanAbsDiff(baseline, grab.grab(x, y));
        check("屏幕帧两次抓取一致（基线可靠）", selfDiff <= 2.0,
                "两次抓同一块屏幕的平均差 " + String.format("%.3f", selfDiff) + "，说明背景自己在变");

        // 熄屏 / 锁屏时抓到的就是一整块黑（或锁屏画面），这种情况只能退回到
        // 参数级验证——屏幕此刻给不出任何证据，不管窗口有没有显示。
        boolean screenAlive = !allDark(baseline);
        System.out.println();
        if (!screenAlive) {
            System.out.println("⚠ 抓到的屏幕内容是纯黑：显示器当前熄屏或锁屏，");
            System.out.println("  屏幕级验证此刻给不出证据，降级为参数级验证。");
            System.out.println("  等屏幕亮起来再跑一次，就有完整的端到端证据。");
        }

        TaskbarLayerWindow win = null;
        try {
            win = new TaskbarLayerWindow(tray, x, y, W, height, true);
            win.pumpMessages();
            check("窗口建起来了", win.isAlive(), "CreateWindowEx 失败");

            System.out.println();
            System.out.println("同一份画布，只改窗口整体透明度，看屏幕上到底淡了多少：");
            System.out.println("  窗口alpha   与基线的平均差   相对不透明时的比例");
            System.out.println("  ---------  ---------------  ------------------");

            int[] alphas = {255, 224, 192, 128, 64, 16, 0};
            double[] diffs = new double[alphas.length];
            for (int i = 0; i < alphas.length; i++) {
                win.pushFrame(pixels, alphas[i]);
                win.pumpMessages();
                Thread.sleep(SETTLE_MS);
                diffs[i] = meanAbsDiff(baseline, grab.grab(x, y));
                double ratio = diffs[0] == 0 ? 0 : diffs[i] / diffs[0];
                System.out.printf("  %7d   %14.3f   %.4f%n", alphas[i], diffs[i], ratio);
            }
            System.out.println();

            if (!screenAlive) {
                System.out.println("  （屏幕级断言跳过：屏幕当前熄屏 / 锁屏，抓不到合成结果）");
            } else {
            boolean supported = diffs[0] > 1.0
                    && diffs[alphas.length - 1] < diffs[0] * 0.1;
            check("完全不透明时画面确实出现在屏幕上", diffs[0] > 1.0,
                    "与基线的差只有 " + diffs[0] + "，歌词根本没显示出来");
            check("alpha=0 时从屏幕上消失", supported,
                    "alpha=0 时仍有差值 " + diffs[alphas.length - 1] + " / " + diffs[0]);

            if (supported) {
                boolean monotonic = true;
                for (int i = 1; i < diffs.length; i++) {
                    // 允许 1 个灰阶的噪声：任务栏本身可能有时钟秒针、动画
                    if (diffs[i] > diffs[i - 1] + 1.0) {
                        monotonic = false;
                        System.out.println("    （alpha " + alphas[i - 1] + " → " + alphas[i]
                                + " 出现回升：" + diffs[i - 1] + " → " + diffs[i] + "）");
                    }
                }
                check("随 alpha 单调变淡", monotonic, "出现了回升");

                double midRatio = diffs[3] / diffs[0];
                check("alpha=128 时约为一半 ±0.15", Math.abs(midRatio - 0.5) < 0.15,
                        "实际比例 " + String.format("%.3f", midRatio));

                // 恢复：推回 255，必须回到原值（证明真的可逆，没有残留）
                win.pushFrame(pixels, 255);
                win.pumpMessages();
                Thread.sleep(SETTLE_MS);
                double restored = meanAbsDiff(baseline, grab.grab(x, y));
                check("推回 255 完全恢复", Math.abs(restored - diffs[0]) < diffs[0] * 0.05,
                        "恢复后 " + String.format("%.3f", restored)
                                + " / 原值 " + String.format("%.3f", diffs[0]));

                System.out.println();
                System.out.println("整条链路通的：SurfaceFade 算出来的程度 → 窗口整体透明度 →");
                System.out.println("DWM 合成 → 屏幕上真的淡下去了。");
            }
            }

            // 不论回读能不能看到，「值确实写进了 BLENDFUNCTION」这条必须成立
            win.pushFrame(pixels, 255);
            check("窗口记录了最后推送的整体透明度（255）",
                    win.lastWindowAlpha() == 255, "实际 " + win.lastWindowAlpha());
            win.pushFrame(pixels, 77);
            check("窗口记录了最后推送的整体透明度（77）",
                    win.lastWindowAlpha() == 77, "实际 " + win.lastWindowAlpha());
        } finally {
            if (win != null) {
                win.destroy();
            }
            grab.dispose();
        }

        System.out.println();
        System.out.println(failed == 0
                ? "全部通过：" + passed + " 项"
                : failed + " 项失败 / " + passed + " 项通过");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 整块像素是不是接近纯黑（平均值 < 1 个灰阶且最大值 < 8）。 */
    static boolean allDark(int[] px) {
        long sum = 0;
        int max = 0;
        for (int v : px) {
            int r = (v >> 16) & 0xFF, g = (v >> 8) & 0xFF, b = v & 0xFF;
            int luma = (r + g + b) / 3;
            sum += luma;
            max = Math.max(max, luma);
        }
        return max < 8 && sum / (double) px.length < 1.0;
    }

    /** 两块屏幕快照的平均逐通道绝对差。 */
    static double meanAbsDiff(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            int pa = a[i];
            int pb = b[i];
            sum += Math.abs(((pa >>> 16) & 0xFF) - ((pb >>> 16) & 0xFF));
            sum += Math.abs(((pa >>> 8) & 0xFF) - ((pb >>> 8) & 0xFF));
            sum += Math.abs((pa & 0xFF) - (pb & 0xFF));
        }
        return sum / (double) (a.length * 3L);
    }

    /** 从屏幕 DC 拷一块矩形出来，返回 0x00RRGGBB。 */
    static final class ScreenGrab {
        private final int w;
        private final int h;
        private final Pointer screenDc;
        private final Pointer memDc;
        private final Pointer bitmap;
        private final Pointer bits;

        ScreenGrab(int w, int h) {
            this.w = w;
            this.h = h;
            screenDc = Win32.U.GetDC(null);
            Win32.BITMAPINFO bmi = new Win32.BITMAPINFO();
            bmi.bmiHeader.biSize = bmi.bmiHeader.size();
            bmi.bmiHeader.biWidth = w;
            bmi.bmiHeader.biHeight = h;
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = 0;
            bmi.bmiHeader.biSizeImage = w * h * 4;
            PointerByReference ref = new PointerByReference();
            bitmap = Win32.G.CreateDIBSection(screenDc, bmi, 0, ref, null, 0);
            bits = ref.getValue();
            memDc = Win32.G.CreateCompatibleDC(screenDc);
            Win32.G.SelectObject(memDc, bitmap);
        }

        int[] grab(int screenX, int screenY) {
            Win32.G.BitBlt(memDc, 0, 0, w, h, screenDc, screenX, screenY, Win32.SRCCOPY);
            byte[] buf = bits.getByteArray(0, w * h * 4);
            int[] out = new int[w * h];
            for (int row = 0; row < h; row++) {
                int srcRow = (h - 1 - row) * w * 4;
                for (int col = 0; col < w; col++) {
                    int o = srcRow + col * 4;
                    out[row * w + col] = ((buf[o + 2] & 0xFF) << 16)
                            | ((buf[o + 1] & 0xFF) << 8)
                            | (buf[o] & 0xFF);
                }
            }
            return out;
        }

        void dispose() {
            Win32.G.DeleteObject(bitmap);
            Win32.G.DeleteDC(memDc);
            Win32.U.ReleaseDC(null, screenDc);
        }
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + (detail.isEmpty() ? "" : "  —— " + detail));
        }
    }
}
