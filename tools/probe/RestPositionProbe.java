import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsLayout;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.SecondLineKind;

import java.awt.image.BufferedImage;
import java.util.ArrayList;

/**
 * 最后一句歌词的位置必须和其他句子相同。
 *
 * 用户报告的现象：唱到最后一句（没有下一行）时，歌词只升到大概中间的位置，
 * 跟有下一行时的位置对不上。原因是把「主行静止位置」写成了
 * 「有第二行 → 第一格，没有 → 两格中间」。
 *
 * 原版的轨道固定两格：下一行没有内容就显示一个空格占位
 * （`toDisplayLine(normalized.next, " ")`），格子位置不因内容为空而变，
 * `.current-line` 的 `top` 恒定——`style.css` 462 行。
 *
 * 判据直接量化：渲染「单行」和「双行」两种 frame，量主行墨迹的垂直质心，
 * 两者必须都落在第一格中心上，差不超过半个行高的一半（1px 量级）。
 * 质心与几何中心有既有的系统差（约 1px，字体上下不对称所致），
 * 所以比较的是**两种 frame 之间的差**必须接近 0，对格心的绝对差放宽到 1.5px。
 *
 * 用法：RestPositionProbe
 */
public class RestPositionProbe {

    static int passed = 0;
    static int failed = 0;

    static final int W = 420;
    static final int H = 72;
    static final double LAYOUT = 1.5;

    public static void main(String[] args) {
        RenderSettings s = new RenderSettings().withTextShadow(false);
        LyricsPainter painter = new LyricsPainter();

        kotlin.Pair<Double, Double> centers =
                LyricsLayout.INSTANCE.rowCenters(H, LAYOUT);
        double rowTop = centers.getFirst();
        double rowSecond = centers.getSecond();
        System.out.printf("几何：行1中心=%.2f  行2中心=%.2f%n", rowTop, rowSecond);

        // 预热（首次渲染初始化字体子系统，几百毫秒，且结果与后续不一致）
        painter.paint(canvas(), frame("预热", "占位"), s, LAYOUT);

        BufferedImage single = canvas();
        painter.paint(single, frame("永不开封的汽水 让我抱在怀内吻下去", null), s, LAYOUT);
        BufferedImage dual = canvas();
        painter.paint(dual, frame("两个人 同时占有的快乐", "每分钟与你挥霍"), s, LAYOUT);

        // 主行质心只统计**上半窗**（y < 两格中点）：双行画面里第二行也有墨迹，
        // 全图质心会落到两行之间，那量的是两行的平均而不是主行的位置。
        double mid0 = (rowTop + rowSecond) / 2.0;
        double cySingle = centroidY(single, 0, (int) Math.round(mid0));
        double cyDual = centroidY(dual, 0, (int) Math.round(mid0));
        System.out.printf("主行质心：单行（最后一句）=%.2f  双行=%.2f%n", cySingle, cyDual);

        // ── 1) 两者位置必须一致：这才是用户报的毛病本身 ──────────────────
        check("最后一句与其他句子的主行位置相同（差 ≤0.5px）",
                Math.abs(cySingle - cyDual) <= 0.5,
                String.format("单行 %.2f vs 双行 %.2f，差 %.2fpx", cySingle, cyDual,
                        Math.abs(cySingle - cyDual)));

        // ── 2) 两者都必须落在第一格上（不是两格中间）─────────────────────
        double mid = (rowTop + rowSecond) / 2.0;
        System.out.printf("参照：行1=%.2f  两格中点=%.2f  行2=%.2f%n", rowTop, mid, rowSecond);
        check("主行落在第一格（不是两格中间）",
                Math.abs(cySingle - rowTop) <= 1.5,
                String.format("质心 %.2f 偏离行1中心 %.2fpx", cySingle, Math.abs(cySingle - rowTop)));
        check("且明显不在两格中间（中点与第一格差得够远，判据才有区分度）",
                Math.abs(mid - rowTop) > 4,
                String.format("中点 %.2f 与行1 %.2f 只差 %.2fpx，此判据无效", mid, rowTop,
                        Math.abs(mid - rowTop)));

        // ── 3) 有下一行时，第二行仍在第二格 ───────────────────────────────
        double cySecond = secondRowCentroid(dual, cyDual);
        check("第二行仍在第二格", Math.abs(cySecond - rowSecond) <= 1.5,
                String.format("第二行质心 %.2f 偏离行2中心 %.2fpx", cySecond,
                        Math.abs(cySecond - rowSecond)));

        System.out.println();
        System.out.printf("%s：%d 项通过，%d 项失败%n",
                failed == 0 ? "全部通过" : "存在失败", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    static BufferedImage canvas() {
        return new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
    }

    static LyricsFrame frame(String main, String second) {
        return new LyricsFrame(main, second, null, new ArrayList<>(),
                1000L, 0L, 3000L, SecondLineKind.NEXT);
    }

    /** 墨迹（alpha 加权）在 [yFrom, yTo) 内的垂直质心。 */
    static double centroidY(BufferedImage img, int yFrom, int yTo) {
        double sum = 0, weight = 0;
        for (int y = yFrom; y < Math.min(yTo, img.getHeight()); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int a = img.getRGB(x, y) >>> 24;
                sum += a * y;
                weight += a;
            }
        }
        return weight == 0 ? -1 : sum / weight;
    }

    /** 第二行的质心：只统计主行质心下方的墨迹。 */
    static double secondRowCentroid(BufferedImage img, double mainCentroidY) {
        double sum = 0, weight = 0;
        int boundary = (int) Math.round((mainCentroidY + rowSecondGuess()) / 2);
        for (int y = boundary; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int a = img.getRGB(x, y) >>> 24;
                sum += a * y;
                weight += a;
            }
        }
        return weight == 0 ? -1 : sum / weight;
    }

    static double rowSecondGuess() {
        return LyricsLayout.INSTANCE.rowCenters(H, LAYOUT).getSecond();
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + " —— " + detail);
        }
    }
}
