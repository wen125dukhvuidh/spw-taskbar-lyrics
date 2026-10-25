import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 封面区域的精确测量与并排对比。
 *
 * 两个窗口跑在同一块屏上，像素尺寸可以直接比较。
 * 用「只扫左侧窄带」的方式隔离封面（文字在更右边），再量出封面的实际像素边长。
 */
public class CropCompare {

    /** 只扫这一段，避开右侧文字。 */
    private static final int SCAN_WIDTH = 74;
    private static final int ZOOM = 5;

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        BufferedImage ours = ImageIO.read(new File(outDir, "window-ours.png"));
        BufferedImage anync = ImageIO.read(new File(outDir, "window-anync.png"));

        System.out.println("窗口尺寸:  我们 " + ours.getWidth() + "x" + ours.getHeight()
                + "   原版 " + anync.getWidth() + "x" + anync.getHeight());
        System.out.println();

        Box boxA = measure(ours);
        Box boxB = measure(anync);
        System.out.println("封面实测（左侧 " + SCAN_WIDTH + "px 带内）:");
        System.out.println("  我们: " + boxA);
        System.out.println("  原版: " + boxB);
        System.out.println();
        System.out.printf("封面边长比: 原版/我们 = %.2f%n",
                boxB.side() / (double) boxA.side());
        System.out.printf("圆角半径（首行内容的左内缩）: 我们 %dpx   原版 %dpx%n",
                cornerInset(ours, boxA), cornerInset(anync, boxB));
        System.out.printf("边缘能量（拉普拉斯均值，越高越锐）: 我们 %.2f   原版 %.2f%n",
                edgeEnergy(ours, boxA), edgeEnergy(anync, boxB));
        System.out.println();

        BufferedImage a = crop(ours, boxA);
        BufferedImage b = crop(anync, boxB);
        int cell = Math.max(a.getWidth(), b.getWidth()) * ZOOM;
        int cellH = Math.max(a.getHeight(), b.getHeight()) * ZOOM;
        int gutter = 18;

        BufferedImage out = new BufferedImage(cell * 2 + gutter, cellH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(a, 0, 0, a.getWidth() * ZOOM, a.getHeight() * ZOOM, null);
            g.drawImage(b, cell + gutter, 0, b.getWidth() * ZOOM, b.getHeight() * ZOOM, null);
        } finally {
            g.dispose();
        }
        File file = new File(outDir, "cover-compare.png");
        ImageIO.write(out, "png", file);
        System.out.println("已输出 " + file.getName()
                + "（左=我们 右=原版，各按自身封面裁切后 " + ZOOM + " 倍最近邻）");
    }

    private static class Box {
        int x0, y0, x1, y1;

        int side() {
            return Math.max(x1 - x0 + 1, y1 - y0 + 1);
        }

        @Override
        public String toString() {
            return String.format("x %d..%d  y %d..%d  →  %dx%d",
                    x0, x1, y0, y1, x1 - x0 + 1, y1 - y0 + 1);
        }
    }

    /** 在左侧窄带里找非黑内容的包围盒，即封面。 */
    private static Box measure(BufferedImage img) {
        int w = Math.min(SCAN_WIDTH, img.getWidth());
        int h = img.getHeight();
        Box box = new Box();
        box.x0 = Integer.MAX_VALUE;
        box.y0 = Integer.MAX_VALUE;
        box.x1 = -1;
        box.y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (isContent(img.getRGB(x, y))) {
                    box.x0 = Math.min(box.x0, x);
                    box.y0 = Math.min(box.y0, y);
                    box.x1 = Math.max(box.x1, x);
                    box.y1 = Math.max(box.y1, y);
                }
            }
        }
        return box;
    }

    /**
     * 估算圆角半径：圆角矩形的**第一行**内容只覆盖中间平直段，
     * 从 `x0` 到第一个内容像素的距离就约等于半径。
     */
    private static int cornerInset(BufferedImage img, Box box) {
        for (int y = box.y0; y <= box.y1; y++) {
            for (int x = box.x0; x <= box.x1; x++) {
                if (isContent(img.getRGB(x, y))) {
                    return x - box.x0;
                }
            }
        }
        return -1;
    }

    /** 拉普拉斯绝对值的均值：反映局部对比，越高说明细节保留越多。 */
    private static double edgeEnergy(BufferedImage img, Box box) {
        double sum = 0;
        int count = 0;
        for (int y = box.y0 + 1; y < box.y1; y++) {
            for (int x = box.x0 + 1; x < box.x1; x++) {
                double c = lum(img.getRGB(x, y));
                double lap = 4 * c
                        - lum(img.getRGB(x - 1, y)) - lum(img.getRGB(x + 1, y))
                        - lum(img.getRGB(x, y - 1)) - lum(img.getRGB(x, y + 1));
                sum += Math.abs(lap);
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    private static double lum(int p) {
        return 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
    }

    private static boolean isContent(int p) {
        return ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF) > 40;
    }

    private static BufferedImage crop(BufferedImage src, Box box) {
        int x = Math.max(0, box.x0);
        int y = Math.max(0, box.y0);
        int w = Math.min(src.getWidth() - x, box.x1 - box.x0 + 1);
        int h = Math.min(src.getHeight() - y, box.y1 - box.y0 + 1);
        return src.getSubimage(x, y, w, h);
    }
}
