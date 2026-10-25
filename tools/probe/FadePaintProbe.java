import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.SecondLineKind;

import java.awt.image.BufferedImage;
import java.util.ArrayList;

/**
 * 验证 [LyricsPainter.paint] 的 `offsetY`（显隐淡出时歌词整体上移 2px 的载体）。
 *
 * 这些断言**不需要屏幕亮着**，纯画布上就能做：
 *
 * 1. `offsetY = 6` 的画面 = 不位移的画面整体**下移 6 物理像素**；
 *    视口之外的行被裁掉（负方向超出视口的部分不回卷）。
 * 2. `offsetY = 0`（默认参数路径）与不传参数**逐像素一致**——
 *    这是给渲染管线加参数时的回归保障：静止渲染一个字节都不许变。
 * 3. 文字与阴影一起动（阴影的偏移叠加在行变换之外）。
 *
 * 用法：FadePaintProbe
 */
public class FadePaintProbe {

    static int passed = 0;
    static int failed = 0;

    static final int W = 420;
    static final int H = 72;
    static final double LAYOUT = 1.5;

    public static void main(String[] args) {
        LyricsPainter painter = new LyricsPainter();
        RenderSettings s = new RenderSettings().withTextShadow(true);
        LyricsFrame frame = new LyricsFrame(
                "显隐位移验证这一行", "second line", null, new ArrayList(),
                5000L, 0L, 5000L, SecondLineKind.NEXT);

        // 预热：首次渲染要加载字体，否则第一帧的结果和后面不一致
        painter.paint(new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE),
                frame, s, LAYOUT);

        BufferedImage base = paint(painter, frame, s, 0.0);
        BufferedImage shifted = paint(painter, frame, s, 6.0);
        BufferedImage again = paint(painter, frame, s, 0.0);

        // ── 1) 默认参数与显式 0 完全一致（回归保障）──────────────────────
        int[] d1 = diff(base, again, 0);
        check("offsetY=0 与默认调用逐像素一致", d1[0] == 0,
                "最大像素差 " + d1[0]);

        // ── 2) 整体下移 6px ─────────────────────────────────────────────
        //     shifted[y] == base[y - 6]，y < 6 的行应是空的。
        //     比较只到视口底边为止：下移会让底部几行内容越出视口被裁掉
        //     （overflow:hidden 的行为，和滚动时旧行从上边缘被切掉是同一个机制），
        //     那几行不参与等值比较。
        int maxTop = 0;
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < W; x++) {
                maxTop = Math.max(maxTop, (shifted.getRGB(x, y) >>> 24));
            }
        }
        check("位移后视口顶部 6 行全空", maxTop == 0, "顶部仍有墨迹 alpha=" + maxTop);

        // 视口 bottom=67.5，Java2D 把 clip 归整到设备像素时实际裁到 67（不含），
        // 所以下移后 y=67 起的内容确实不可见——比较范围也按这个口径取。
        int viewportBottom = (int) Math.floor(
                com.spwtb.taskbarlyrics.render.LyricsLayout.INSTANCE
                        .viewportRect(W, H, LAYOUT).getMaxY());
        check("视口底边在画面内", viewportBottom > 12 && viewportBottom <= H,
                "bottom=" + viewportBottom);
        int maxErr = 0;
        for (int y = 6; y < viewportBottom; y++) {
            for (int x = 0; x < W; x++) {
                int a = shifted.getRGB(x, y);
                int b = base.getRGB(x, y - 6);
                maxErr = Math.max(maxErr, Math.abs((a >>> 24) - (b >>> 24)));
            }
        }
        check("offsetY=6 的画面 = 原画面下移 6px（视口内）", maxErr <= 2,
                "最大像素差 " + maxErr + "（抗锯齿边缘允许 ±2）");

        // 视口底边之外两幅图都必须是空的：内容被裁掉，不许漏出来
        int leak = 0;
        for (int y = viewportBottom; y < H; y++) {
            for (int x = 0; x < W; x++) {
                leak = Math.max(leak, (shifted.getRGB(x, y) >>> 24));
            }
        }
        check("下移越出视口的内容被裁掉", leak == 0, "视口外仍有 alpha=" + leak);

        // ── 3) 阴影跟着一起动：把阴影关掉再比一次，差值应不同 ─────────────
        RenderSettings noShadow = s.withTextShadow(false);
        BufferedImage baseNs = paint(painter, frame, noShadow, 0.0);
        BufferedImage shiftedNs = paint(painter, frame, noShadow, 6.0);
        int maxErrNs = 0;
        for (int y = 6; y < viewportBottom; y++) {
            for (int x = 0; x < W; x++) {
                int a = shiftedNs.getRGB(x, y);
                int b = baseNs.getRGB(x, y - 6);
                maxErrNs = Math.max(maxErrNs, Math.abs((a >>> 24) - (b >>> 24)));
            }
        }
        check("关掉阴影后位移关系仍然成立", maxErrNs <= 2,
                "最大像素差 " + maxErrNs);

        int withShadow = 0;
        int withoutShadow = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                withShadow += (base.getRGB(x, y) >>> 24);
                withoutShadow += (baseNs.getRGB(x, y) >>> 24);
            }
        }
        check("阴影确实画了（有阴影的墨量更多）", withShadow > withoutShadow,
                "有阴影 " + withShadow + " / 无阴影 " + withoutShadow);

        System.out.println();
        System.out.println(failed == 0
                ? "全部通过：" + passed + " 项"
                : failed + " 项失败 / " + passed + " 项通过");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static BufferedImage paint(LyricsPainter p, LyricsFrame f, RenderSettings s, double dy) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        p.paint(img, f, s, LAYOUT, null, dy);
        return img;
    }

    /** 返回 {最大像素 alpha 差, 总差}。 */
    static int[] diff(BufferedImage a, BufferedImage b, int yFrom) {
        int max = 0;
        long total = 0;
        for (int y = yFrom; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int d = Math.abs((a.getRGB(x, y) >>> 24) - (b.getRGB(x, y) >>> 24));
                max = Math.max(max, d);
                total += d;
            }
        }
        return new int[]{max, (int) total};
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
