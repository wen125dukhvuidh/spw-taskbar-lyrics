import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 把两张图的文字区域裁出来放大并上下拼接，用于人眼确认"边缘是否被加粗"。
 *
 * 只做裁切 + 最近邻放大，不参与诊断——诊断一律走源码与数值。
 *
 * 参数：<目录> <图A> <图B> <输出名> [x0 y0 x1 y1] [倍数]
 */
public class ZoomCompare {

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        String fileA = args[1];
        String fileB = args[2];
        String outName = args[3];
        int x0 = args.length > 4 ? Integer.parseInt(args[4]) : 70;
        int y0 = args.length > 5 ? Integer.parseInt(args[5]) : 8;
        int x1 = args.length > 6 ? Integer.parseInt(args[6]) : 370;
        int y1 = args.length > 7 ? Integer.parseInt(args[7]) : 64;
        int zoom = args.length > 8 ? Integer.parseInt(args[8]) : 4;

        BufferedImage a = ImageIO.read(new File(dir, fileA));
        BufferedImage b = ImageIO.read(new File(dir, fileB));

        int cw = x1 - x0;
        int ch = y1 - y0;
        int gap = 6;

        BufferedImage out = new BufferedImage(
                cw * zoom, (ch * 2 + gap) * zoom, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(new Color(30, 30, 40));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());

        // 上：A（新版），下：B（旧版）
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int pa = onBlack(a, x0 + x, y0 + y);
                int pb = onBlack(b, x0 + x, y0 + y);
                g.setColor(new Color(pa, true));
                g.fillRect(x * zoom, y * zoom, zoom, zoom);
                g.setColor(new Color(pb, true));
                g.fillRect(x * zoom, (y + ch + gap) * zoom, zoom, zoom);
            }
        }
        g.dispose();

        File file = new File(dir, outName);
        ImageIO.write(out, "png", file);
        System.out.printf("已输出 %s  (%dx%d，上=%s 下=%s，放大 %d 倍)%n",
                file.getName(), out.getWidth(), out.getHeight(), fileA, fileB, zoom);
    }

    /** 压到黑底——带 alpha 的图直接取 RGB 是预乘值。 */
    static int onBlack(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) return 0;
        int p = img.getRGB(x, y);
        int a = (p >>> 24) & 0xFF;
        int r = (p >> 16) & 0xFF;
        int gg = (p >> 8) & 0xFF;
        int bb = p & 0xFF;
        // 图是 ARGB（非预乘），压到黑底就是直接乘 alpha
        return ((r * a / 255) << 16) | ((gg * a / 255) << 8) | (bb * a / 255);
    }
}
