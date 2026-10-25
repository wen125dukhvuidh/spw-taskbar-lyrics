import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 歌词文字的逐行测量。
 *
 * 对两个窗口的抓图，各自在「封面右侧的文字区」里逐行统计：
 *   - 有多少像素算作文字（亮度阈值）
 *   - 这些文字像素的平均颜色
 *
 * 由此可以客观地比出：
 *   - **行高**（≈ 字号）
 *   - **两行的垂直位置**
 *   - **第二行相对主行的亮度比例**（颜色/透明度差异）
 */
public class TextProbe {

    private static final int MIN_RUN = 3;      // 一行至少这么多像素才算文字，滤掉噪点
    private static final int LUMA_THRESHOLD = 45;

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        String fileA = args.length > 1 ? args[1] : "window-ours.png";
        String fileB = args.length > 2 ? args[2] : "window-anync.png";
        // 先合成到纯黑底再测：带 alpha 的图直接取 RGB 会读到预乘后的值，
        // 结果里混进了「总不透明度」而不是「屏幕上看到的亮度」。
        BufferedImage ours = onBlack(ImageIO.read(new File(outDir, fileA)));
        BufferedImage anync = onBlack(ImageIO.read(new File(outDir, fileB)));

        report("我们", ours);
        System.out.println();
        report("原版", anync);

        System.out.println();
        System.out.println("=== 汇总对比 ===");
        Band[] a = bands(ours);
        Band[] b = bands(anync);
        System.out.printf("%-10s %-22s %-22s%n", "窗口", "我们", "原版");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String left = i < a.length ? a[i].describe() : "-";
            String right = i < b.length ? b[i].describe() : "-";
            System.out.printf("  第 %d 行   %-22s %-22s%n", i + 1, left, right);
        }
        System.out.println();
        if (a.length >= 2 && b.length >= 2) {
            System.out.printf("主行亮度比:   我们 %.1f   原版 %.1f%n", a[0].luma, b[0].luma);
            System.out.printf("第二行亮度比: 我们 %.1f   原版 %.1f%n", a[1].luma, b[1].luma);
            System.out.printf("第二行/主行:  我们 %.2f   原版 %.2f%n",
                    a[1].luma / a[0].luma, b[1].luma / b[0].luma);
            System.out.printf("第二行/主行高（字号比）: 我们 %.2f   原版 %.2f%n",
                    a[1].height() / (double) a[0].height(),
                    b[1].height() / (double) b[0].height());
            System.out.println();
            System.out.printf("主行颜色:   我们 RGB%s   原版 RGB%s%n", a[0].rgb(), b[0].rgb());
            System.out.printf("第二行颜色: 我们 RGB%s   原版 RGB%s%n", a[1].rgb(), b[1].rgb());
        }
    }

    /** 把带 alpha 的图压到纯黑底上，得到「屏幕上实际看到的亮度」。 */
    private static BufferedImage onBlack(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static class Band {
        int top, bottom;
        int minX = Integer.MAX_VALUE, maxX = -1;
        double luma;
        double r, g, b;

        int height() {
            return bottom - top + 1;
        }

        int width() {
            return maxX < 0 ? 0 : maxX - minX + 1;
        }

        String describe() {
            return String.format("y %d..%d (高%2d) x %d..%d (宽%3d) 亮度%5.1f",
                    top, bottom, height(), minX, maxX, width(), luma);
        }

        String rgb() {
            return String.format("(%3.0f,%3.0f,%3.0f)", r, g, b);
        }
    }

    private static void report(String label, BufferedImage img) {
        System.out.printf("=== %s  %dx%d ===%n", label, img.getWidth(), img.getHeight());
        Band[] bands = bands(img);
        for (int i = 0; i < bands.length; i++) {
            System.out.printf("  第 %d 行  %s   颜色 %s%n",
                    i + 1, bands[i].describe(), bands[i].rgb());
        }
        if (bands.length == 0) {
            System.out.println("  （没找到文字）");
        }
    }

    /** 在封面右侧的文字区逐行统计，切出连续的文字行带。 */
    private static Band[] bands(BufferedImage img) {
        int x0 = 76;                                  // 跳过封面（封面在 x 12..62）
        int x1 = Math.min(img.getWidth() - 6, 76 + 460);
        int h = img.getHeight();

        int[] count = new int[h];
        int[] minXRow = new int[h];
        int[] maxXRow = new int[h];
        double[] lumSum = new double[h];
        double[] rSum = new double[h];
        double[] gSum = new double[h];
        double[] bSum = new double[h];
        java.util.Arrays.fill(minXRow, Integer.MAX_VALUE);
        java.util.Arrays.fill(maxXRow, -1);

        for (int y = 0; y < h; y++) {
            for (int x = x0; x < x1; x++) {
                int p = img.getRGB(x, y);
                double r = (p >> 16) & 0xFF;
                double g = (p >> 8) & 0xFF;
                double b = p & 0xFF;
                double luma = 0.299 * r + 0.587 * g + 0.114 * b;
                if (luma > LUMA_THRESHOLD) {
                    count[y]++;
                    lumSum[y] += luma;
                    rSum[y] += r;
                    gSum[y] += g;
                    bSum[y] += b;
                    if (x < minXRow[y]) {
                        minXRow[y] = x;
                    }
                    if (x > maxXRow[y]) {
                        maxXRow[y] = x;
                    }
                }
            }
        }

        java.util.List<Band> list = new java.util.ArrayList<>();
        int start = -1;
        for (int y = 0; y <= h; y++) {
            boolean text = y < h && count[y] >= MIN_RUN;
            if (text && start < 0) {
                start = y;
            } else if (!text && start >= 0) {
                Band band = new Band();
                band.top = start;
                band.bottom = y - 1;
                double lc = 0, rc = 0, gc = 0, bc = 0;
                int total = 0;
                for (int i = start; i < y; i++) {
                    lc += lumSum[i];
                    rc += rSum[i];
                    gc += gSum[i];
                    bc += bSum[i];
                    total += count[i];
                    if (minXRow[i] <= maxXRow[i]) {
                        band.minX = Math.min(band.minX, minXRow[i]);
                        band.maxX = Math.max(band.maxX, maxXRow[i]);
                    }
                }
                band.luma = total == 0 ? 0 : lc / total;
                band.r = total == 0 ? 0 : rc / total;
                band.g = total == 0 ? 0 : gc / total;
                band.b = total == 0 ? 0 : bc / total;
                list.add(band);
                start = -1;
            }
        }
        return list.toArray(new Band[0]);
    }
}
