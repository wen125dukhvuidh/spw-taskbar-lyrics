import com.spwtb.taskbarlyrics.lyrics.LyricCell;
import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.SecondLineKind;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 端到端：把逐字扫描**画到画布上**，量出扫描头到底是一格一格跳还是连续滑。
 *
 * `WordScanProbe` 验的是数值，这一支验的是像素——数值对了而像素仍是台阶
 * 是完全可能的（比如把连续的进度又取整回 `Int`，就白修了）。
 *
 * 判据不靠眼看，靠两条统计量：
 * 1. **有多少帧画面真的变了**。整字累加时，一个字唱完之前画面纹丝不动，
 *    只有字边界那一帧才变一下——10 个字的行只会有 10 帧有变化。
 * 2. **有没有尖峰**。连续滑动时每帧的变化量都差不多；有台阶时会出现
 *    个别帧的变化量是平常的好几倍。
 *
 * 时间用**虚时钟**显式喂给 `paint`：`ScanSmoother` 的 90ms 追赶是挂在时钟上的，
 * 若让它读真实时间，探针里 175 帧总共才跑几十毫秒，追赶根本走不完，
 * 量出来的就不是稳态行为了。
 *
 * 用法：ScanPaintProbe
 */
public class ScanPaintProbe {

    static int passed = 0;
    static int failed = 0;

    static final int W = 420;
    static final int H = 72;
    static final double LAYOUT = 1.5;

    /** 一帧的时长：按 60fps 取 16ms。 */
    static final int STEP_MS = 16;

    public static void main(String[] args) {
        // 10 个汉字，每个 300ms，整行 3000ms
        String text = "这一行有十个汉字正好";
        List<LyricCell> cells = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            cells.add(new LyricCell(i * 300L, (i + 1) * 300L, text.substring(i, i + 1)));
        }

        RenderSettings s = new RenderSettings().withWordScanning(true).withTextShadow(false);
        LyricsPainter painter = new LyricsPainter();

        // 预热：首次渲染要初始化字体子系统（几百毫秒），否则第一帧和后面不可比
        painter.paint(canvas(), frame(cells, text, 0L), s, LAYOUT, null, 0.0, 0L);

        // 逐帧渲染，记录相邻帧的像素差
        // 起点 100ms：跳过开头的硬切（第一次目标必然是硬切，见 ScanSmoother）
        int from = 100;
        int to = 2900;
        List<int[]> frames = new ArrayList<>();
        List<Double> diffs = new ArrayList<>();
        int[] prev = null;
        for (int ms = from; ms <= to; ms += STEP_MS) {
            int[] cur = grab(painter, cells, text, ms, s);
            if (prev != null) {
                diffs.add(meanAbsDiff(prev, cur));
            }
            frames.add(cur);
            prev = cur;
        }

        System.out.println("  帧数 = " + frames.size() + "，步长 " + STEP_MS + "ms");

        // ── 1) 绝大多数帧画面都在变 ──────────────────────────────────────
        //     整字累加下，10 个字的行只有 10 帧左右会变（约 6%）。
        int moving = 0;
        for (double d : diffs) {
            if (d > 1e-9) {
                moving++;
            }
        }
        double movingRatio = (double) moving / diffs.size();
        System.out.printf("  有变化的帧 = %d/%d（%.1f%%）%n", moving, diffs.size(), movingRatio * 100);
        check("绝大多数帧画面都在变（不是只在字边界跳）",
                movingRatio >= 0.80,
                String.format("只有 %.1f%% 的帧在变，整字累加约 6%%", movingRatio * 100));

        // ── 2) 没有尖峰：最大值不超过中位数的 3 倍 ────────────────────────
        double[] nonZero = new double[moving];
        int k = 0;
        for (double d : diffs) {
            if (d > 1e-9) {
                nonZero[k++] = d;
            }
        }
        Arrays.sort(nonZero);
        double median = nonZero[nonZero.length / 2];
        double max = nonZero[nonZero.length - 1];
        System.out.printf("  单帧变化量：中位数 %.4f，最大 %.4f（比值 %.2f）%n",
                median, max, max / Math.max(median, 1e-9));
        check("变化量没有尖峰（最大值 ≤ 中位数的 3 倍）",
                max <= median * 3.0 + 1e-9,
                String.format("最大 %.4f 是中位 %.4f 的 %.2f 倍", max, median,
                        max / Math.max(median, 1e-9)));

        // ── 3) 单调：扫描头只往右走，画面不该往回变 ───────────────────────
        //     用「已唱墨量」单调性来判：整行的加权亮度随播放推进只增不减。
        boolean monotonic = true;
        double previousInk = -1;
        for (int[] f : frames) {
            double ink = inkOf(f);
            if (previousInk >= 0 && ink < previousInk - 0.5) {
                monotonic = false;
                break;
            }
            previousInk = ink;
        }
        check("已唱部分的墨量单调增加（扫描头不回缩）", monotonic, "出现回缩");

        // ── 4) 关掉逐字扫描后，画面应当是匀速推进而不是停住 ────────────────
        //     这条是防「修了逐字却把退路走坏」：没有字级数据时走匀速分支。
        //     开关仍然是**开着**的——关掉开关本来就该不动，那不叫退路。
        RenderSettings noScan = new RenderSettings().withWordScanning(true).withTextShadow(false);
        LyricsPainter p2 = new LyricsPainter();
        p2.paint(canvas(), frame(new ArrayList<>(), text, 0L), noScan, LAYOUT, null, 0.0, 0L);
        List<Double> diffs2 = new ArrayList<>();
        int[] prev2 = null;
        for (int ms = from; ms <= to; ms += STEP_MS) {
            BufferedImage img = canvas();
            p2.paint(img, frame(new ArrayList<>(), text, (long) ms), noScan, LAYOUT,
                    null, 0.0, ms * 1_000_000L);
            int[] cur = pixels(img);
            if (prev2 != null) {
                diffs2.add(meanAbsDiff(prev2, cur));
            }
            prev2 = cur;
        }
        int moving2 = 0;
        for (double d : diffs2) {
            if (d > 1e-9) {
                moving2++;
            }
        }
        double ratio2 = (double) moving2 / diffs2.size();
        System.out.printf("  无字级数据（匀速退路）有变化的帧 = %.1f%%%n", ratio2 * 100);
        check("无字级数据时走匀速退路，画面同样连续推进",
                ratio2 >= 0.80,
                String.format("只有 %.1f%% 的帧在变", ratio2 * 100));

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

    static LyricsFrame frame(List<LyricCell> cells, String text, long positionMs) {
        return new LyricsFrame(text, null, null, cells, positionMs, 0L, 3000L,
                SecondLineKind.NEXT);
    }

    static int[] grab(LyricsPainter painter, List<LyricCell> cells, String text,
            int ms, RenderSettings s) {
        BufferedImage img = canvas();
        painter.paint(img, frame(cells, text, (long) ms), s, LAYOUT,
                null, 0.0, ms * 1_000_000L);
        return pixels(img);
    }

    static int[] pixels(BufferedImage img) {
        return img.getRGB(0, 0, W, H, null, 0, W);
    }

    /** 两帧之间的平均绝对差（只看 alpha 通道：扫描改变的是覆盖层的合成强度）。 */
    static double meanAbsDiff(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs((a[i] >>> 24) - (b[i] >>> 24));
        }
        return (double) sum / a.length;
    }

    /** 整幅的 alpha 总和，用来判「已唱的墨量」是否单调增加。 */
    static double inkOf(int[] px) {
        long sum = 0;
        for (int v : px) {
            sum += (v >>> 24);
        }
        return sum;
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
