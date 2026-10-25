import com.spwtb.taskbarlyrics.lyrics.CoverArt;
import com.spwtb.taskbarlyrics.lyrics.LyricCell;
import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.SecondLineKind;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 把原版窗口里显示的那一句歌词**程序化地认出来**。
 *
 * 为什么需要它：任何"我们 vs 原版"的字形对比，前提都是两边显示同一段文字。
 * 肉眼认字不算依据；这里用**列墨量互相关**来判：
 * 同一段文字、同一款字、同一字号渲染出来的列墨量曲线应当高度相似（相关 > 0.9），
 * 不同文字则明显更低。
 *
 * 参数：<输出目录> <FLAC路径> <候选表> <被识别的窗口PNG> [最小行号] [最大行号]
 */
public class MatchLine {

    static final int W = 482;
    static final int H = 66;
    static final double DPI = 1.5;
    static final int START_X = 70;
    static final int MAIN_TOP = 10;
    static final int MAIN_BOTTOM = 37;
    static final int SECOND_TOP = 38;
    static final int SECOND_BOTTOM = 62;

    public static void main(String[] args) throws Exception {
        String outDir = args[0];
        String flac = args[1];
        String candidateFile = args[2];
        String targetFile = args[3];
        int fromIdx = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        int toIdx = args.length > 5 ? Integer.parseInt(args[5]) : Integer.MAX_VALUE;

        BufferedImage target = flatten(ImageIO.read(new File(outDir, targetFile)));
        double[] targetMain = columnProfile(target, MAIN_TOP, MAIN_BOTTOM);

        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(new File(candidateFile).toPath(), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", -1);
            int idx = Integer.parseInt(parts[0]);
            if (idx < fromIdx || idx > toIdx) continue;
            rows.add(parts);
        }

        List<double[]> results = new ArrayList<>();
        for (String[] r : rows) {
            int idx = Integer.parseInt(r[0]);
            long start = Long.parseLong(r[1]);
            long end = Long.parseLong(r[2]);
            String main = r[3];
            String next = r.length > 4 && !r[4].isEmpty() ? r[4] : null;
            BufferedImage ours = render(flac, main, next, start, end, (start + end) / 2);
            double[] profile = columnProfile(ours, MAIN_TOP, MAIN_BOTTOM);
            double score = bestCorrelation(targetMain, profile, 8);
            int width = bandWidth(profile);
            results.add(new double[]{idx, score, width});
        }

        int targetWidth = bandWidth(targetMain);
        System.out.println("原版主行横向跨度（列存在性 > 0 的列数）= " + targetWidth);
        System.out.println();

        results.sort((x, y) -> Double.compare(y[1], x[1]));
        System.out.println("=== 按互相关排序 ===");
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            int idx = (int) results.get(i)[0];
            System.out.printf("  行%-3d corr=%.4f  宽=%-4d  宽差=%-4d  %s%n",
                    idx, results.get(i)[1], (int) results.get(i)[2],
                    (int) results.get(i)[2] - targetWidth, text(rows, idx));
        }

        results.sort((x, y) -> Double.compare(
                Math.abs(x[2] - targetWidth), Math.abs(y[2] - targetWidth)));
        System.out.println();
        System.out.println("=== 按宽度接近程度排序 ===");
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            int idx = (int) results.get(i)[0];
            System.out.printf("  行%-3d 宽=%-4d (差%-4d)  corr=%.4f  %s%n",
                    idx, (int) results.get(i)[2], (int) results.get(i)[2] - targetWidth,
                    results.get(i)[1], text(rows, idx));
        }
    }

    /** 列存在性轮廓里"有字"的列数。 */
    static int bandWidth(double[] profile) {
        int n = 0;
        for (double v : profile) {
            if (v > 0) n++;
        }
        return n;
    }

    static String text(List<String[]> rows, int idx) {
        for (String[] r : rows) {
            if (Integer.parseInt(r[0]) == idx) return r[3];
        }
        return "?";
    }

    static BufferedImage render(String flac, String main, String next,
                                long start, long end, long pos) {
        RenderSettings settings = new RenderSettings();
        double layout = DPI * settings.getLayoutScalePercent() / 100.0;
        int coverSize = (int) Math.round(settings.getCoverSize() * layout);
        int radius = (int) Math.round(settings.getCoverCornerRadius() * layout);
        BufferedImage cover = settings.getShowCover()
                ? CoverArt.INSTANCE.forSong("match", flac, coverSize, radius)
                : null;
        LyricsFrame frame = new LyricsFrame(
                main, next, cover, synthesize(main, start, end), pos, start, end,
                SecondLineKind.PLACEHOLDER);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        new LyricsPainter().paint(img, frame, settings, DPI);
        return flatten(img);
    }

    static List<LyricCell> synthesize(String text, long start, long end) {
        List<LyricCell> cells = new ArrayList<>();
        int n = Math.max(1, text.length());
        for (int i = 0; i < text.length(); i++) {
            cells.add(new LyricCell(
                    start + (end - start) * i / n,
                    start + (end - start) * (i + 1) / n,
                    text.substring(i, i + 1)));
        }
        return cells;
    }

    /**
     * 某一行区间内的**列存在性**轮廓。
     *
     * 刻意不用亮度求和：主行有"已唱/未唱"两档亮度，扫描进度不同会让曲线整体变形，
     * 而扫描进度是抓图那一刻的瞬时状态、无法复现。改成统计"该列有多少像素亮过阈值"，
     * 只反映字形的横向分布，与扫描状态无关。
     */
    static double[] columnProfile(BufferedImage img, int top, int bottom) {
        int n = img.getWidth() - START_X;
        double[] out = new double[n];
        for (int y = top; y <= bottom && y < img.getHeight(); y++) {
            for (int x = START_X; x < img.getWidth(); x++) {
                if (luma(img, x, y) > PRESENCE_THRESHOLD) out[x - START_X] += 1;
            }
        }
        return out;
    }

    /** 判定"这一列有字"的亮度阈值。主行未唱档约 150，已唱约 226，取 100 两边都算。 */
    static final double PRESENCE_THRESHOLD = 100;

    /** 允许左右小范围平移，取最大的归一化相关。 */
    static double bestCorrelation(double[] a, double[] b, int shift) {
        double best = -1;
        for (int s = -shift; s <= shift; s++) {
            double c = correlation(a, b, s);
            if (c > best) best = c;
        }
        return best;
    }

    static double correlation(double[] a, double[] b, int shift) {
        int n = Math.min(a.length, b.length - Math.abs(shift));
        if (n <= 1) return -1;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = a[i];
            y[i] = b[i + Math.max(0, shift)];
        }
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        if (sxx <= 0 || syy <= 0) return -1;
        return sxy / Math.sqrt(sxx * syy);
    }

    static BufferedImage flatten(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    static double luma(BufferedImage img, int x, int y) {
        int p = img.getRGB(x, y);
        return 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
    }
}
