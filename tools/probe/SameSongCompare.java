import com.spwtb.taskbarlyrics.lyrics.CoverArt;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;

/**
 * 同一首歌、同尺寸的封面画质对比（确定性）。
 *
 *  1. **原版实际渲染的像素** —— 直接从它窗口的抓图里裁出来，是它屏幕上的真实结果
 *  2. 我们**旧算法**的输出（一步双线性）
 *  3. 我们**新算法**的输出（多级减半 + 双三次 + 轻微锐化）
 *
 * 三者都是 51×51，原图同为该 FLAC 的内嵌 PICTURE 块。
 */
public class SameSongCompare {

    private static final int SIZE = 51;
    private static final int ZOOM = 5;

    /** 从抓图里量出来的封面位置，两边一致。 */
    private static final int COVER_X = 12;
    private static final int COVER_Y = 10;

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        String flac = args.length > 1 ? args[1] : "D:/Music/赵雷 - 凌晨计程车.flac";

        File source = new File(flac);
        System.out.println("源文件: " + flac);

        byte[] raw = CoverArt.INSTANCE.readEmbeddedPicture(source);
        if (raw == null) {
            System.out.println("没有内嵌封面");
            return;
        }
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(raw));
        System.out.printf("内嵌封面 %dx%d%n", original.getWidth(), original.getHeight());

        BufferedImage anync = crop(ImageIO.read(new File(outDir, "window-anync.png")),
                COVER_X, COVER_Y, SIZE);
        BufferedImage oldWay = naive(original, SIZE);
        BufferedImage newWay = CoverArt.INSTANCE.forSong("probe", flac, SIZE);

        System.out.println();
        System.out.println("          边缘能量(越高越锐)   与左图差异");
        System.out.printf("  原版实际   %6.2f%n", edgeEnergy(anync));
        System.out.printf("  旧算法     %6.2f          %6.2f%n",
                edgeEnergy(oldWay), diff(anync, oldWay));
        System.out.printf("  新算法     %6.2f          %6.2f%n",
                edgeEnergy(newWay), diff(anync, newWay));
        System.out.println();

        BufferedImage out = strip(anync, oldWay, newWay);
        File file = new File(outDir, "same-song-compare.png");
        ImageIO.write(out, "png", file);
        System.out.println("已输出 " + file.getName()
                + "（左=原版实际 | 中=我们旧算法 | 右=我们新算法，均 " + ZOOM + " 倍）");
    }

    private static BufferedImage crop(BufferedImage src, int x, int y, int size) {
        return src.getSubimage(x, y, size, size);
    }

    private static BufferedImage naive(BufferedImage src, int size) {
        int side = Math.min(src.getWidth(), src.getHeight());
        int sx = (src.getWidth() - side) / 2;
        int sy = (src.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, size, size, sx, sy, sx + side, sy + side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static double edgeEnergy(BufferedImage img) {
        double sum = 0;
        int count = 0;
        for (int y = 1; y < img.getHeight() - 1; y++) {
            for (int x = 1; x < img.getWidth() - 1; x++) {
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

    /** 平均绝对差：衡量与参照图的接近程度。 */
    private static double diff(BufferedImage a, BufferedImage b) {
        double sum = 0;
        int count = 0;
        for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y++) {
            for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x++) {
                sum += Math.abs(lum(a.getRGB(x, y)) - lum(b.getRGB(x, y)));
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    private static double lum(int p) {
        return 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
    }

    private static BufferedImage strip(BufferedImage... panels) {
        int cell = panels[0].getWidth() * ZOOM;
        int gutter = 16;
        BufferedImage out = new BufferedImage(
                cell * panels.length + gutter * (panels.length - 1), cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (int i = 0; i < panels.length; i++) {
                g.drawImage(panels[i], i * (cell + gutter), 0, cell, cell, null);
            }
        } finally {
            g.dispose();
        }
        return out;
    }
}
