import com.spwtb.taskbarlyrics.lyrics.CoverArt;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * 封面缩放的画质对比。
 *
 * 面板依次为：
 *   1. 旧做法：一步双线性（欠采样，发脏）
 *   2. 多级 + 双线性 + 锐化（插件当前方案）
 *   3. 多级 + 双三次（不锐化）
 *   4. 多级 + 双三次 + 锐化
 *
 * 都按最近邻放大 [ZOOM] 倍输出，便于肉眼比较锐度与干净程度。
 */
public class CoverQualityProbe {

    private static final int ZOOM = 5;
    private static final double SHARPEN_AMOUNT = 0.3;

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        int target = args.length > 1 ? Integer.parseInt(args[1]) : 51;
        String flac = args.length > 2 ? args[2] : findFirstFlac(Paths.get("D:/Music"));
        if (flac == null) {
            System.out.println("找不到样本 FLAC");
            return;
        }
        System.out.println("样本: " + flac);

        File file = new File(flac);
        byte[] raw = CoverArt.INSTANCE.readEmbeddedPicture(file);
        if (raw == null) {
            System.out.println("该文件没有内嵌封面");
            return;
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(raw));
        System.out.printf("内嵌封面 %dx%d，目标 %dpx（缩小 %.1f 倍）%n",
                source.getWidth(), source.getHeight(), target,
                Math.min(source.getWidth(), source.getHeight()) / (double) target);

        BufferedImage oneStep = naive(source, target);
        BufferedImage bilinear = sharpen(pipeline(source, target,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR), SHARPEN_AMOUNT);
        BufferedImage bicubic = pipeline(source, target,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        BufferedImage bicubicSharp = sharpen(bicubic, SHARPEN_AMOUNT);

        File out = new File(outDir, "cover-quality.png");
        ImageIO.write(strip(oneStep, bilinear, bicubic, bicubicSharp), "png", out);
        System.out.println("已输出 " + out.getName());
        System.out.println("  1 一步双线性 | 2 多级+双线性+锐化(当前) | 3 多级+双三次 | 4 多级+双三次+锐化");
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

    /** 逐级减半到 ≤2 倍余量，再走最后一步。 */
    private static BufferedImage pipeline(BufferedImage src, int size, Object interpolation) {
        int side = Math.min(src.getWidth(), src.getHeight());
        int sx = (src.getWidth() - side) / 2;
        int sy = (src.getHeight() - side) / 2;
        BufferedImage current = toArgb(src.getSubimage(sx, sy, side, side));
        while (current.getWidth() > size * 2) {
            current = resize(current, Math.max(size, current.getWidth() / 2), interpolation);
        }
        if (current.getWidth() != size) {
            current = resize(current, size, interpolation);
        }
        return current;
    }

    private static BufferedImage resize(BufferedImage src, int target, Object interpolation) {
        BufferedImage out = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, target, target, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage sharpen(BufferedImage image, double amount) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] src = new int[w * h];
        image.getRGB(0, 0, w, h, src, 0, w);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sr = 0, sg = 0, sb = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = Math.min(h - 1, Math.max(0, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = Math.min(w - 1, Math.max(0, x + dx));
                        int p = src[ny * w + nx];
                        sr += (p >> 16) & 0xFF;
                        sg += (p >> 8) & 0xFF;
                        sb += p & 0xFF;
                        n++;
                    }
                }
                int c = src[y * w + x];
                int a = (c >>> 24) & 0xFF;
                out[y * w + x] = (a << 24)
                        | (boost((c >> 16) & 0xFF, sr / n, amount) << 16)
                        | (boost((c >> 8) & 0xFF, sg / n, amount) << 8)
                        | boost(c & 0xFF, sb / n, amount);
            }
        }
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, w, h, out, 0, w);
        return result;
    }

    private static int boost(int value, int blurred, double amount) {
        return Math.min(255, Math.max(0, (int) (value + amount * (value - blurred))));
    }

    private static BufferedImage strip(BufferedImage... panels) {
        int cell = panels[0].getWidth() * ZOOM;
        int gutter = 14;
        int total = cell * panels.length + gutter * (panels.length - 1);
        BufferedImage out = new BufferedImage(total, cell, BufferedImage.TYPE_INT_ARGB);
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

    private static String findFirstFlac(Path root) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().toLowerCase().endsWith(".flac"))
                    .findFirst().map(Path::toString).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
