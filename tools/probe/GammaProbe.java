import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 标定文字 gamma 补偿系数。
 *
 * 背景：Java2D 的文字抗锯齿用**线性混合**，Chromium/DirectWrite 用 **gamma 校正混合**。
 * 深色背景上的浅色文字，后者会明显更"实"——实测同一款字、同一句话，
 * Java2D 的归一化墨量比 Chromium 低约 20%，笔画游程从 3px 掉到 2px。
 *
 * 补偿办法：把字形的覆盖率 c 按 `c^(1/γ)` 重映射（γ>1 时整体变粗）。
 * 这个探针扫一遍 γ，把各自的归一化墨量打出来，找与 Chromium 一致的那个值。
 *
 * 归一化墨量 = 亮度总和 ÷ 峰值，**与透明度无关**，只反映字形覆盖面积。
 *
 * 用法：java GammaProbe <输出目录>
 */
public class GammaProbe {

    static final String TEXT = "微带着后悔";
    static final int SIZE = 21;
    static final int W = 482;
    static final int H = 66;

    /** 字体候选：本机 Noto Bold / 思源 Bold / 原版自带的思源 Bold */
    static final String[] FONT_FILES = {
            "C:/Windows/Fonts/Noto Sans SC Bold (TrueType).otf",
            "C:/Users/<user>/AppData/Local/Microsoft/Windows/Fonts/SourceHanSansSC-Bold-2.otf",
            "<TaskbarLyrics>/Assets/Fonts/SourceHanSansSC-Bold.otf"
    };

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        double[] gammas = {1.0, 1.10, 1.20, 1.30, 1.40, 1.50, 1.60, 1.80};

        for (String path : FONT_FILES) {
            File f = new File(path);
            if (!f.isFile()) continue;
            Font font = Font.createFont(Font.TRUETYPE_FONT, f).deriveFont((float) SIZE);
            System.out.println("=== " + f.getName() + " ===");
            for (double gamma : gammas) {
                BufferedImage img = render(font, gamma);
                Stats s = stats(img);
                System.out.printf("  γ=%.2f  归一墨量=%7.1f  笔画中位=%dpx 均值=%.2fpx  游程=%d%n",
                        gamma, s.norm, s.medianRun, s.meanRun, s.runs);
            }
            System.out.println();
        }

        // 参考目标：Chromium 渲染同一句话的实测值
        System.out.println("参考目标（Chromium/Skia，本机 Edge 无头渲染实测）：");
        System.out.println("  思源黑体 Bold : 归一号墨量 1194.2  笔画均值 5.92px");
        System.out.println("  Noto Bold     : 归一墨量 1184.3  笔画均值 6.03px");
    }

    static BufferedImage render(Font font, double gamma) {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = (int) Math.round(255 * Math.pow(i / 255.0, 1.0 / gamma));
        }

        // 1) 先把字形画到掩膜（白字、透明底）
        BufferedImage mask = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = mask.createGraphics();
        hints(mg);
        mg.setColor(Color.WHITE);
        mg.setFont(font);
        mg.drawString(TEXT, 0, 30);
        mg.dispose();

        // 2) 逐像素做 gamma 映射
        int[] px = new int[W * H];
        mask.getRGB(0, 0, W, H, px, 0, W);
        for (int i = 0; i < px.length; i++) {
            int a = (px[i] >>> 24) & 0xFF;
            px[i] = (lut[a] << 24) | 0xFFFFFF;
        }

        // 3) 以 0.886 的恒定 alpha 合成到黑底（对齐原版已唱档）
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, H);
        java.awt.AlphaComposite ac = java.awt.AlphaComposite.getInstance(
                java.awt.AlphaComposite.SRC_OVER, 0.886f);
        g.setComposite(ac);
        java.awt.image.WritableRaster wr = mask.getRaster();
        BufferedImage scaled = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        scaled.setRGB(0, 0, W, H, px, 0, W);
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return out;
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    static class Stats {
        double norm;
        int medianRun;
        double meanRun;
        int runs;
    }

    static Stats stats(BufferedImage img) {
        double peak = 0;
        double total = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                double l = luma(img, x, y);
                if (l > peak) peak = l;
                total += l;
            }
        }
        double cut = peak * 0.5;
        java.util.List<Integer> runs = new java.util.ArrayList<>();
        for (int y = 0; y < img.getHeight(); y++) {
            int run = 0;
            for (int x = 0; x < img.getWidth(); x++) {
                if (luma(img, x, y) > cut) {
                    run++;
                } else {
                    if (run > 0) runs.add(run);
                    run = 0;
                }
            }
            if (run > 0) runs.add(run);
        }
        java.util.Collections.sort(runs);
        Stats s = new Stats();
        s.norm = peak == 0 ? 0 : total / peak;
        s.medianRun = runs.isEmpty() ? 0 : runs.get(runs.size() / 2);
        s.meanRun = runs.isEmpty() ? 0
                : runs.stream().mapToInt(Integer::intValue).average().orElse(0);
        s.runs = runs.size();
        return s;
    }

    static double luma(BufferedImage img, int x, int y) {
        int p = img.getRGB(x, y);
        return 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
    }
}
