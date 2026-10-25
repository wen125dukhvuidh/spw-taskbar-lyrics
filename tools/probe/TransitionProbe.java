import com.spwtb.taskbarlyrics.render.CubicBezierEasing;
import com.spwtb.taskbarlyrics.render.LineTransition;
import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsLayout;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.SecondLineKind;
import com.spwtb.taskbarlyrics.render.TransitionMode;
import com.spwtb.taskbarlyrics.render.TransitionState;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;

/**
 * 行切换过渡的**机械校验**：不看图，只量数字。
 *
 * 思路是每次只让**一行**可见（其余行给空文本，绘制器会跳过空文本），
 * 量出来的墨迹质心 y 就是这一行的位置，直接和「按原版公式算出的期望值」比。
 *
 * 被校验的公式（全部来自 `Web/Lyrics/app.js` + `style.css`，出处见 LineTransition.kt）：
 *   位移    = 行距 × cubicBezier(0.22, 0.72, 0.24, 1)(t)
 *   离场    旧主行：y = 旧位置 − 行距 × e，元素不透明度 0.98 → 0.16，缩放 1 → 0.972
 *   提升    新主行：y = 第二行位置 → 主行位置，元素不透明度 0.72 → 0.98，缩放 0.92 → 1
 *   滚入    新二行：y = 第二行位置 + 行距 → 第二行位置，不透明度不变
 *   翻译对  整对移动 2 倍行距（760ms），不做透明度动画
 *
 * ⚠️ 被视口裁掉的行，质心是**偏的**（缺了半截墨），所以凡是墨迹贴到视口边缘的
 * 采样点只打印不校验，避免拿一个必然偏的数去判定通过与否。
 *
 * 参数：<输出目录>
 */
public class TransitionProbe {

    static final int W = 420;
    static final int H = 72;          // 150% 缩放下的任务栏物理高度
    static final double DPI = 1.5;
    static final CubicBezierEasing EASING = new CubicBezierEasing(0.22, 0.72, 0.24, 1.0);

    static int failures = 0;
    static double vTop;
    static double vBottom;

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        new File(outDir).mkdirs();

        // 关阴影：量的是纯字形的墨迹质心，阴影挂在字的下方会把质心整体拉低约 1px。
        RenderSettings s = new RenderSettings().withTextShadow(false);
        double layout = DPI * s.getLayoutScalePercent() / 100.0;
        kotlin.Pair<Double, Double> centers = LyricsLayout.INSTANCE.rowCenters(H, layout);
        double rowTop = centers.getFirst();
        double rowSecond = centers.getSecond();
        double pitch = rowSecond - rowTop;
        Rectangle2D vp = LyricsLayout.INSTANCE.viewportRect(W, H, layout);
        vTop = vp.getMinY();
        vBottom = vp.getMaxY();
        System.out.printf("几何：行1中心=%.2f  行2中心=%.2f  行距=%.2f  视口=[%.1f, %.1f]%n",
                rowTop, rowSecond, pitch, vTop, vBottom);
        System.out.println();

        LyricsPainter painter = new LyricsPainter();
        LyricsFrame none = frame("", null, SecondLineKind.NEXT);

        // 预热：第一次渲染要加载系统字体，实测要几百毫秒——比 560ms 的过渡还长。
        // 不先跑一帧的话，第一帧的过渡会被当成"已经播完"直接跳到终点。
        long warm = System.nanoTime();
        shot(painter, s, "预热", null, SecondLineKind.NEXT, null, null, 0);
        shot(painter, s, "预热", null, SecondLineKind.NEXT, TransitionMode.SINGLE, none, 0.5);
        System.out.printf("（预热耗时 %.0f ms）%n", (System.nanoTime() - warm) / 1e6);


        // ── 1) 提升行 ──────────────────────────────────────────────────────
        // 起点：双行布局下 t=0 必须正好落在第二行的位置（此时新第二行还在视口外）
        System.out.println("=== 1) 提升行（新主行）===");
        BufferedImage startTwoLine = shot(painter, s, "新的一行歌词来了啊", "再下一句歌词",
                SecondLineKind.NEXT, TransitionMode.SINGLE, none, 0.0);
        double[] m0 = measure(startTwoLine);
        System.out.printf("  双行布局 t=0：期望 y=%.2f（第二行位置），实测 %.2f%n", rowSecond, m0[0]);
        check("提升行起点 = 第二行位置", Math.abs(m0[0] - rowSecond) <= 1.5,
                String.format("误差 %.2fpx > 1.5px", Math.abs(m0[0] - rowSecond)));

        // 轨迹：无第二行的行（主行静止位置在第一格——原版轨道固定两格，
// 下一行空着也占位，最后一句不居中），整条轨迹都不被裁剪
        System.out.println("  单行布局轨迹：");
        System.out.println("    t      期望y     实测y     误差    墨量/墨量(t=0)   裁剪");
        double ink0 = 0;
        for (int i = 0; i < 8; i++) {
            double raw = i / 7.0;
            double e = EASING.value(raw);
            // 提升行按静止位置栅格化、再缩放位移回第二行：
            // 中心 = 行2 − 缩放(e) × (行2 − 静止位置) × e
            double scale = 0.92 + 0.08 * e;
            double expected = rowSecond - scale * (rowSecond - rowTop) * e;
            BufferedImage img = shot(painter, s, "新的一行歌词来了啊", null, SecondLineKind.NEXT,
                    TransitionMode.SINGLE, none, raw);
            double[] m = measure(img);
            if (i == 0) ink0 = m[1];
            double err = Math.abs(m[0] - expected);
            boolean clipped = m[2] <= vTop + 0.5 || m[3] >= vBottom - 0.5;
            System.out.printf("  %4.2f  %8.2f  %8.2f  %6.2f      %.3f        %s%n",
                    raw, expected, m[0], err, m[1] / ink0, clipped ? "是" : "否");
            if (!clipped) {
                check("提升行位置 t=" + String.format("%.2f", raw), err <= 1.5,
                        String.format("误差 %.2fpx > 1.5px", err));
            }
            write(outDir, img, String.format("promote-%.2f.png", raw));
        }
        // 墨量 = 元素不透明度(0.432 → 0.588，×1.361) × 缩放面积(0.92² → 1，×1.181)
        double ratio = measure(shot(painter, s, "新的一行歌词来了啊", null, SecondLineKind.NEXT,
                TransitionMode.SINGLE, none, 1.0))[1] / ink0;
        System.out.printf("  墨量比 t=1 / t=0 = %.3f（期望 ≈ %.3f）%n", ratio, 1.361 * 1.181);
        check("提升行墨量比", Math.abs(ratio - 1.608) <= 0.18, String.format("实测 %.3f 偏离 1.608 超过 0.18", ratio));
        System.out.println();

        // ── 2) 离场行 ──────────────────────────────────────────────────────
        System.out.println("=== 2) 离场行（旧主行）===");
        BufferedImage prevRest = shot(painter, s, "旧的一行歌词在这里", null, SecondLineKind.NEXT, null, null, 0);
        BufferedImage leaving0 = shot(painter, s, "", null, SecondLineKind.NEXT,
                TransitionMode.SINGLE, frame("旧的一行歌词在这里", null, SecondLineKind.NEXT), 0.0);
        int[] diff = maxDiff(prevRest, leaving0);
        System.out.printf("  t=0 与上一帧静止渲染：最大像素差 %d/255，平均 %.4f%n", diff[0], diff[1] / 255.0);
        // t=0 时真实经过的时间不为 0（几十微秒），会有约 0.03px 的位移，
        // 抗锯齿边缘的 alpha 因此有几个单位的浮动，这是量得到的下限，不是偏差。
        check("离场行起点 = 上一帧静止", diff[0] <= 8, "最大像素差 " + diff[0] + " > 8");
        double inkLeaving0 = measure(leaving0)[1];
        double inkLeaving4 = measure(shot(painter, s, "", null, SecondLineKind.NEXT,
                TransitionMode.SINGLE, frame("旧的一行歌词在这里", null, SecondLineKind.NEXT), 0.4))[1];
        System.out.printf("  墨量 t=0.4 / t=0 = %.3f（原版 t=0.4 时元素不透明度已降到 0.24）%n",
                inkLeaving4 / inkLeaving0);
        check("离场行确实在淡出", inkLeaving4 / inkLeaving0 < 0.45, "t=0.4 时墨量没有明显下降");
        System.out.println();

        // ── 3) 新第二行滚入 ────────────────────────────────────────────────
        System.out.println("=== 3) 新第二行滚入 ===");
        System.out.println("    t      期望y     实测y     误差    裁剪");
        for (double raw : new double[]{0.3, 0.4, 0.5, 0.6, 0.7, 0.85, 1.0}) {
            double expected = rowSecond + pitch * (1.0 - EASING.value(raw));
            BufferedImage img = shot(painter, s, "", "再下一句歌词", SecondLineKind.NEXT,
                    TransitionMode.SINGLE, none, raw);
            double[] m = measure(img);
            double err = Math.abs(m[0] - expected);
            boolean clipped = m[2] <= vTop + 0.5 || m[3] >= vBottom - 0.5;
            System.out.printf("  %4.2f  %8.2f  %8.2f  %6.2f    %s%n",
                    raw, expected, m[0], err, clipped ? "是" : "否");
            if (!clipped) {
                check("新第二行位置 t=" + String.format("%.2f", raw), err <= 1.5,
                        String.format("误差 %.2fpx > 1.5px", err));
            }
        }
        System.out.println();

        // ── 4) 过渡结束必须与静止帧一致 ────────────────────────────────────
        System.out.println("=== 4) t=1 与静止帧的一致性 ===");
        BufferedImage rest = shot(painter, s, "新的一行歌词来了啊", "再下一句歌词", SecondLineKind.NEXT, null, null, 0);
        BufferedImage end = shot(painter, s, "新的一行歌词来了啊", "再下一句歌词", SecondLineKind.NEXT,
                TransitionMode.SINGLE, frame("旧的一行歌词在这里", "更早的第二行", SecondLineKind.NEXT), 1.0);
        int[] d = maxDiff(rest, end);
        System.out.printf("  最大像素差 %d/255，平均 %.4f%n", d[0], d[1] / 255.0);
        check("t=1 与静止帧一致", d[0] <= 4, "最大像素差 " + d[0] + " > 4");
        write(outDir, rest, "rest.png");
        write(outDir, end, "single-end.png");
        // 开阴影再比一次。离场行滚到视口上方后，它的阴影还会在视口顶边留下
        // 极淡的一点残留（原版也一样，阴影是被 .viewport 裁的），所以从第 12 行起比。
        RenderSettings shadowOn = new RenderSettings();
        BufferedImage restS = shot(painter, shadowOn, "新的一行歌词来了啊", "再下一句歌词", SecondLineKind.NEXT, null, null, 0);
        BufferedImage endS = shot(painter, shadowOn, "新的一行歌词来了啊", "再下一句歌词", SecondLineKind.NEXT,
                TransitionMode.SINGLE, frame("旧的一行歌词在这里", "更早的第二行", SecondLineKind.NEXT), 1.0);
        int[] ds = maxDiff(restS, endS, 12);
        System.out.printf("  带阴影（自第 12 行起比）：最大像素差 %d/255%n", ds[0]);
        check("t=1 与静止帧一致（带阴影）", ds[0] <= 6, "最大像素差 " + ds[0] + " > 6");
        System.out.println();

        // ── 5) 翻译对 ──────────────────────────────────────────────────────
        System.out.println("=== 5) 翻译对（760ms，位移 2 行）===");
        System.out.println("    t      期望y     实测y     误差    裁剪");
        for (double raw : new double[]{0.25, 0.4, 0.6, 0.8, 1.0}) {
            double expected = rowTop + 2.0 * pitch * (1.0 - EASING.value(raw));
            BufferedImage img = shot(painter, s, "新的一行歌词来了啊", null, SecondLineKind.TRANSLATION,
                    TransitionMode.TRANSLATION_PAIR, none, raw);
            double[] m = measure(img);
            double err = Math.abs(m[0] - expected);
            boolean clipped = m[2] <= vTop + 0.5 || m[3] >= vBottom - 0.5;
            System.out.printf("  %4.2f  %8.2f  %8.2f  %6.2f    %s%n",
                    raw, expected, m[0], err, clipped ? "是" : "否");
            if (!clipped) {
                check("翻译对主行位置 t=" + String.format("%.2f", raw), err <= 1.5,
                        String.format("误差 %.2fpx > 1.5px", err));
            }
            write(outDir, img, String.format("pair-%.2f.png", raw));
        }
        System.out.println();

        System.out.println(failures == 0 ? "全部通过 ✓" : ("失败 " + failures + " 项 ✗"));
        if (failures > 0) System.exit(1);
    }

    static LyricsFrame frame(String main, String second, SecondLineKind kind) {
        return new LyricsFrame(main, second, null, new ArrayList(), 1000L, 0L, 5000L, kind);
    }

    /** 渲一帧。previous 为 null 或 mode 为 null 时渲静止帧。 */
    static BufferedImage shot(
            LyricsPainter painter, RenderSettings s,
            String mainText, String secondText, SecondLineKind kind,
            TransitionMode mode, LyricsFrame previous, double raw
    ) {
        LyricsFrame f = frame(mainText, secondText, kind);
        LineTransition transition = null;
        if (mode != null) {
            long startNanos = System.nanoTime() - (long) (raw * mode.getDurationMs() * 1e6);
            transition = new LineTransition(mode, previous, startNanos) {
                // 把进度钉死。
                //
                // LineTransition.state() 是拿 System.nanoTime() 减 startNanos 算进度的，
                // 也就是说实际进度取决于「构造到绘制之间过了多久」——几十到几百微秒不定。
                // raw=0 时就不是确定的 0，离场行会有零点几像素的位移，抗锯齿边缘的
                // alpha 因此浮动几个单位。把进度钉死后比较才有确定答案。
                @Override
                public TransitionState state(long nowNanos) {
                    return super.at(raw);
                }

                @Override
                public boolean finished(long nowNanos) {
                    return false;
                }
            };
        }
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        painter.paint(image, f, s, DPI, transition);
        BufferedImage argb = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return argb;
    }

    /** 返回 {墨迹质心 y, 总墨量, 墨迹最小 y, 墨迹最大 y}。空图返回 {NaN,0,-1,-1}。 */
    static double[] measure(BufferedImage img) {
        double sum = 0;
        double weighted = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int a = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (a > 0) {
                    sum += a;
                    weighted += a * y;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (sum == 0) return new double[]{Double.NaN, 0, -1, -1};
        return new double[]{weighted / sum, sum, minY, maxY};
    }

    static int[] maxDiff(BufferedImage a, BufferedImage b) {
        return maxDiff(a, b, 0);
    }

    /** 返回 {最大像素 alpha 差, 总差}。yFrom 之前的行不参与比较。 */
    static int[] maxDiff(BufferedImage a, BufferedImage b, int yFrom) {
        int max = 0;
        long total = 0;
        for (int y = yFrom; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int da = ((a.getRGB(x, y) >>> 24) & 0xFF) - ((b.getRGB(x, y) >>> 24) & 0xFF);
                max = Math.max(max, Math.abs(da));
                total += Math.abs(da);
            }
        }
        return new int[]{max, (int) total};
    }

    static void write(String dir, BufferedImage img, String name) throws Exception {
        ImageIO.write(img, "png", new File(dir, name));
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) {
            System.out.println("  ✓ " + what);
        } else {
            failures++;
            System.out.println("  ✗ " + what + " —— " + detail);
        }
    }
}
