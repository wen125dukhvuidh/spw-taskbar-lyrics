import com.spwtb.taskbarlyrics.lyrics.CoverArt;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 封面圆角的专项对比。
 *
 *  左 = 原版 TaskbarLyrics 的实际像素（从它窗口抓图里裁出）
 *  右 = 我们新算法：多级双三次降采样 + 抗锯齿圆角烘焙
 *
 * 两个角都放大 [ZOOM] 倍并垫在中灰底上，这样半透明的抗锯齿边缘看得清清楚楚。
 * 同时打印我们这张图左上角的 alpha 剖面——只有出现过 1..254 之间的值，
 * 才说明圆角是**抗锯齿**的；全是 0/255 就是硬边。
 */
public class CornerProbe {

    private static final int SIZE = 51;
    private static final int PAD = 24;      // 抓图里封面的位置
    private static final int ZOOM = 12;
    private static final int CORNER = 18;   // 取多少像素见方的角

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        String flac = args.length > 1 ? args[1] : "D:/Music/赵雷 - 小行迹 (Live版).flac";
        int radius = args.length > 2 ? Integer.parseInt(args[2]) : 9;

        // 原版实际像素
        BufferedImage anyncWin = ImageIO.read(new File(outDir, "window-anync.png"));
        BufferedImage anyncCover = anyncWin.getSubimage(12, 10, SIZE, SIZE);

        // 我们的新算法（半径按 150% 缩放算）
        BufferedImage ours = CoverArt.INSTANCE.forSong("corner-probe", flac, SIZE, radius);
        System.out.println("我们的封面: " + ours.getWidth() + "x" + ours.getHeight()
                + "   圆角半径参数 " + radius + "px");

        System.out.println();
        System.out.println("左上角 alpha 剖面（第 0..8 行，第 0..12 列）：");
        for (int y = 0; y <= 8; y++) {
            StringBuilder sb = new StringBuilder("  y=" + y + "  ");
            for (int x = 0; x <= 12; x++) {
                int a = (ours.getRGB(x, y) >>> 24) & 0xFF;
                sb.append(String.format("%3d ", a));
            }
            System.out.println(sb);
        }
        int partial = 0;
        for (int y = 0; y < ours.getHeight(); y++) {
            for (int x = 0; x < ours.getWidth(); x++) {
                int a = (ours.getRGB(x, y) >>> 24) & 0xFF;
                if (a > 0 && a < 255) partial++;
            }
        }
        System.out.println();
        System.out.println("半透明（抗锯齿）像素数: " + partial
                + (partial > 0 ? "  → 圆角是抗锯齿的 ✓" : "  → 圆角是硬边 ✗"));

        // 并排放大，垫中灰
        BufferedImage a = corner(anyncCover);
        BufferedImage b = corner(ours);
        int cell = CORNER * ZOOM;
        int gutter = 16;
        BufferedImage out = new BufferedImage(cell * 2 + gutter, cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setColor(new java.awt.Color(90, 90, 96));
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(a, 0, 0, cell, cell, null);
            g.drawImage(b, cell + gutter, 0, cell, cell, null);
        } finally {
            g.dispose();
        }
        File file = new File(outDir, "corner-compare.png");
        ImageIO.write(out, "png", file);
        System.out.println("已输出 " + file.getName()
                + "（左=原版实际 右=我们，左上角 " + CORNER + "px 放大 " + ZOOM + " 倍）");
    }

    /** 把左上角区域放到中灰底上，保留 alpha 以便观察抗锯齿。 */
    private static BufferedImage corner(BufferedImage src) {
        int n = Math.min(CORNER, Math.min(src.getWidth(), src.getHeight()));
        BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(new java.awt.Color(90, 90, 96));
            g.fillRect(0, 0, n, n);
            g.drawImage(src.getSubimage(0, 0, n, n), 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
