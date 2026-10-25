import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 量化对比两首歌词窗口的**文字墨量**。
 *
 * 目的是客观判断"字形是不是被加粗/描边了"，而不是靠眼睛看：
 *
 * - 行高：反映字号
 * - 平均亮度：反映 alpha
 * - 墨量（ink）：该行所有文字像素亮度之和
 * - 墨密度：墨量 ÷（文字横向范围 × 行高）——**同尺寸同文字时，
 *   这个值只受"字形覆盖率"影响**。同一个字形被半透明地画两遍，
 *   抗锯齿边缘会按 a₁+a₂(1−a₁) 累积，墨密度必然偏高。
 * - 亮度直方图：边缘过渡像素的分布，双次绘制会把中间档挤向高档
 */
public class InkProbe {

    static final double LUMA_THRESHOLD = 26;

    /**
     * 切行用的阈值，比墨量阈值高。
     *
     * 原因是文字阴影会填满两行之间的空隙（阴影峰值约 0.36×255≈92，
     * 模糊后更低），用低阈值切行会把两行并成一行。
     * 字形像素最暗也在 150 以上（0.385 的白叠在阴影上），所以 110 能干净分开。
     */
    static final double SEG_THRESHOLD = 110;

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String fileA = args.length > 1 ? args[1] : "frame-render.png";
        String fileB = args.length > 2 ? args[2] : "window-anync.png";
        String outName = args.length > 3 ? args[3] : "ink-compare.txt";
        // 封面色块在左侧，亮度很高会填满两行之间的空隙导致切行失败，
        // 所以测量范围从它右边开始（原版的封面也是在 x≈12..62）。
        int startX = args.length > 4 ? Integer.parseInt(args[4]) : 70;

        BufferedImage a = flatten(ImageIO.read(new File(dir, fileA)));
        BufferedImage b = flatten(ImageIO.read(new File(dir, fileB)));

        StringBuilder sb = new StringBuilder();
        sb.append("A = ").append(fileA)
          .append("  ").append(a.getWidth()).append('x').append(a.getHeight()).append('\n');
        sb.append("B = ").append(fileB)
          .append("  ").append(b.getWidth()).append('x').append(b.getHeight()).append('\n');
        sb.append("(A=我们，B=原版实际渲染)\n\n");

        List<Band> ba = bands(a, 2, startX);
        List<Band> bb = bands(b, 2, startX);
        sb.append("行数: A=").append(ba.size()).append("  B=").append(bb.size()).append('\n');
        int n = Math.min(ba.size(), bb.size());
        for (int i = 0; i < n; i++) {
            Band x = ba.get(i);
            Band y = bb.get(i);
            sb.append("── 第 ").append(i + 1).append(" 行 ────────────────────────────\n");
            sb.append(String.format("  行高        A=%2d    B=%2d   (A y%d..%d / B y%d..%d)%n",
                    x.height(), y.height(), x.top, x.bottom, y.top, y.bottom));
            sb.append(String.format("  平均亮度    A=%5.1f  B=%5.1f%n", x.meanLuma, y.meanLuma));
            sb.append(String.format("  横向范围    A=%3d..%3d(宽%3d)  B=%3d..%3d(宽%3d)%n",
                    x.x0, x.x1, x.x1 - x.x0 + 1, y.x0, y.x1, y.x1 - y.x0 + 1));
            sb.append(String.format("  墨密度      A=%6.3f  B=%6.3f   比值=%.3f%n",
                    x.density(), y.density(), y.density() == 0 ? 0 : x.density() / y.density()));
            sb.append("  笔画宽度    A=").append(strokeStats(a, x)).append('\n');
            sb.append("              B=").append(strokeStats(b, y)).append('\n');
            sb.append("  边缘占比    A=").append(String.format("%.1f%%", edgeRatio(a, x)))
              .append("   B=").append(String.format("%.1f%%", edgeRatio(b, y)))
              .append("   ← 中暗像素/总墨像素\n");
            sb.append("  亮度直方图（占该行墨量的百分比，A / B）\n");
            for (int k = 0; k < 8; k++) {
                sb.append(String.format("    %3d-%3d   %5.1f%%  %5.1f%%%n",
                        k * 32, k * 32 + 31, x.bucket(k), y.bucket(k)));
            }
            sb.append('\n');
        }

        sb.append("逐行最大亮度（x>=").append(startX).append("）\n");
        sb.append("        A: ").append(rowProfile(a, startX)).append('\n');
        sb.append("        B: ").append(rowProfile(b, startX)).append('\n');
        sb.append('\n');

        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(new File(dir, outName)), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
    }

    /** 把带 alpha 的图压到黑底上——直接 getRGB 读到的是预乘值，会把不透明度混进来。 */
    static BufferedImage flatten(BufferedImage src) {
        BufferedImage out = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    static class Band {
        int top, bottom, x0, x1;
        double meanLuma, ink;
        double[] hist = new double[8];

        int height() {
            return bottom - top + 1;
        }

        double density() {
            int w = x1 - x0 + 1;
            int h = height();
            return w <= 0 || h <= 0 ? 0 : ink / (double) (w * h);
        }

        double bucket(int k) {
            return ink <= 0 ? 0 : hist[k] / ink * 100.0;
        }
    }

    static List<Band> bands(BufferedImage img, int gapTolerance, int startX) {
        int w = img.getWidth();
        int h = img.getHeight();
        // 切行只看高亮度像素（排除阴影），墨量则用低阈值累计（保留抗锯齿边缘）
        boolean[] rowHasText = new boolean[h];
        for (int y = 0; y < h; y++) {
            for (int x = startX; x < w; x++) {
                int p = img.getRGB(x, y);
                double luma = 0.299 * ((p >> 16) & 0xFF)
                        + 0.587 * ((p >> 8) & 0xFF)
                        + 0.114 * (p & 0xFF);
                if (luma > SEG_THRESHOLD) {
                    rowHasText[y] = true;
                    break;
                }
            }
        }

        List<Band> out = new ArrayList<>();
        int start = -1;
        int blank = 0;
        for (int y = 0; y <= h; y++) {
            boolean has = y < h && rowHasText[y];
            if (has) {
                if (start < 0) start = y;
                blank = 0;
            } else if (start >= 0) {
                blank++;
                if (blank > gapTolerance || y >= h) {
                    out.add(make(img, start, y - blank, startX));
                    start = -1;
                    blank = 0;
                }
            }
        }
        return out;
    }

    static Band make(BufferedImage img, int top, int bottom, int startX) {
        Band band = new Band();
        band.top = top;
        band.bottom = bottom;
        int w = img.getWidth();
        band.x0 = w;
        band.x1 = startX;
        double ink = 0;
        long count = 0;
        double[] hist = new double[8];
        for (int y = top; y <= bottom; y++) {
            for (int x = startX; x < w; x++) {
                int p = img.getRGB(x, y);
                double luma = 0.299 * ((p >> 16) & 0xFF)
                        + 0.587 * ((p >> 8) & 0xFF)
                        + 0.114 * (p & 0xFF);
                if (luma <= LUMA_THRESHOLD) continue;
                ink += luma;
                count++;
                if (x < band.x0) band.x0 = x;
                if (x > band.x1) band.x1 = x;
                hist[Math.min(7, (int) (luma / 32.0))] += luma;
            }
        }
        band.ink = ink;
        band.meanLuma = count == 0 ? 0 : ink / count;
        band.hist = hist;
        return band;
    }

    /** 每行的最大亮度，只输出有内容的行，格式 `y=max`。 */
    static String rowProfile(BufferedImage img, int startX) {
        StringBuilder sb = new StringBuilder();
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y++) {
            int max = 0;
            for (int x = startX; x < w; x++) {
                int p = img.getRGB(x, y);
                int luma = (int) (0.299 * ((p >> 16) & 0xFF)
                        + 0.587 * ((p >> 8) & 0xFF)
                        + 0.114 * (p & 0xFF));
                if (luma > max) max = luma;
            }
            if (max > 0) {
                sb.append(y).append('=').append(max).append(' ');
            }
        }
        return sb.toString();
    }

    /**
     * 笔画宽度：在行内以「峰值的一半」为阈值做水平游程统计，取中位数。
     *
     * 这是**与文字内容无关**的字重指标——不同汉字的横竖笔画粗细是同一套设计值，
     * 所以两边的分布可以直接比。比"墨密度"可靠得多（后者受笔画密度影响）。
     */
    static String strokeStats(BufferedImage img, Band band) {
        int w = img.getWidth();
        double peak = 0;
        for (int y = band.top; y <= band.bottom; y++) {
            for (int x = 0; x < w; x++) {
                double l = luma(img, x, y);
                if (l > peak) peak = l;
            }
        }
        double cut = peak * 0.5;
        java.util.List<Integer> runs = new java.util.ArrayList<>();
        int totalInk = 0;
        int midTone = 0;
        for (int y = band.top; y <= band.bottom; y++) {
            int run = 0;
            for (int x = 0; x < w; x++) {
                double l = luma(img, x, y);
                if (l > cut) {
                    run++;
                    totalInk++;
                } else {
                    if (run > 0) runs.add(run);
                    run = 0;
                }
                if (l > 0.1 * peak && l < 0.85 * peak) midTone++;
            }
            if (run > 0) runs.add(run);
        }
        if (runs.isEmpty()) return "(无)";
        java.util.Collections.sort(runs);
        double median = runs.get(runs.size() / 2);
        double mean = runs.stream().mapToInt(Integer::intValue).average().orElse(0);
        StringBuilder hist = new StringBuilder();
        int[] buckets = new int[7];
        for (int r : runs) buckets[Math.min(6, r - 1)]++;
        for (int i = 0; i < buckets.length; i++) {
            hist.append(i + 1).append(':').append(buckets[i]).append(' ');
        }
        return String.format("中位=%.0fpx 均值=%.2fpx 游程数=%d  [%s]",
                median, mean, runs.size(), hist.toString().trim());
    }

    /** 边缘像素占总墨像素的比例——越高说明抗锯齿过渡带越宽（观感更软更重）。 */
    static double edgeRatio(BufferedImage img, Band band) {
        int w = img.getWidth();
        double peak = 0;
        for (int y = band.top; y <= band.bottom; y++) {
            for (int x = 0; x < w; x++) {
                double l = luma(img, x, y);
                if (l > peak) peak = l;
            }
        }
        int mid = 0;
        int total = 0;
        for (int y = band.top; y <= band.bottom; y++) {
            for (int x = 0; x < w; x++) {
                double l = luma(img, x, y);
                if (l <= 0) continue;
                total++;
                if (l < 0.85 * peak) mid++;
            }
        }
        return total == 0 ? 0 : mid * 100.0 / total;
    }

    static double luma(BufferedImage img, int x, int y) {
        int p = img.getRGB(x, y);
        return 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
    }
}
