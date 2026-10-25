import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 字体字面探测。
 *
 * 目的：原版 `FontWeight = "Bold"`，用自带的 `SourceHanSansSC-Bold.otf`。
 * Java2D 的 `new Font(family, Font.BOLD, size)` **不一定**能拿到真正的 Bold 字面——
 * 家族里没有 Bold 时它会"合成加粗"（把笔画描粗一圈），结果比真 Bold 明显偏细。
 *
 * 这个探针把三件事量出来：
 * 1. 候选家族在 PLAIN / BOLD 下各自解析到哪个字面（getFontName 能看出是不是同一个）
 * 2. 直接按文件 `Font.createFont` 加载得到的字面
 * 3. 各自渲染同一段中文的**墨量**——这是"粗细"的可比数字
 */
public class FontProbe2 {

    static final String SAMPLE = "愿不枉啊 愿勇往啊";
    static final int SIZE = 21;

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        StringBuilder sb = new StringBuilder();

        // ── 1. 候选家族清单 ──────────────────────────────────────────────
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        sb.append("=== 相关家族（共 ").append(families.length).append(" 个）===\n");
        for (String f : families) {
            String low = f.toLowerCase();
            if (low.contains("noto") || low.contains("source han") || f.contains("思源")
                    || low.contains("yahei") || f.contains("雅黑") || low.contains("dengxian")
                    || f.contains("等线") || low.contains("simhei") || f.contains("黑体")) {
                sb.append("  ").append(f).append('\n');
            }
        }
        sb.append('\n');

        // ── 2. PLAIN / BOLD 解析到的字面 ────────────────────────────────
        sb.append("=== 家族的 PLAIN / BOLD 字面（关键：两者是否不同）===\n");
        String[] candidates = {
                "Source Han Sans SC", "Source Han Sans CN", "思源黑体 CN",
                "Noto Sans SC", "Noto Sans SC Medium",
                "Microsoft YaHei UI", "微软雅黑", "SimHei", "DengXian"
        };
        Map<String, Double> inkByLabel = new TreeMap<>();
        for (String c : candidates) {
            if (!isAvailable(c, families)) {
                sb.append(String.format("  %-24s (未安装)%n", c));
                continue;
            }
            Font plain = new Font(c, Font.PLAIN, SIZE);
            Font bold = new Font(c, Font.BOLD, SIZE);
            boolean realBold = !plain.getFontName().equals(bold.getFontName())
                    || hasBoldAttribute(c);
            sb.append(String.format("  %-24s PLAIN→%-28s BOLD→%-28s 真Bold=%s%n",
                    c, plain.getFontName(), bold.getFontName(), realBold ? "是" : "否(合成)"));
            inkByLabel.put(c + " / BOLD", ink(bold));
        }
        sb.append('\n');

        // ── 3. 按文件直接加载 ───────────────────────────────────────────
        sb.append("=== 按文件 Font.createFont 加载 ===\n");
        String[] files = {
                "C:/Windows/Fonts/Noto Sans SC Bold (TrueType).otf",
                "C:/Windows/Fonts/Noto Sans SC (TrueType).otf",
                "C:/Windows/Fonts/Noto Sans SC Medium (TrueType).otf",
                "C:/Users/<user>/AppData/Local/Microsoft/Windows/Fonts/SourceHanSansSC-Bold-2.otf",
                "C:/Users/<user>/AppData/Local/Microsoft/Windows/Fonts/SourceHanSansSC-Medium-2.otf",
                "C:/Users/<user>/AppData/Local/Microsoft/Windows/Fonts/SourceHanSansSC-Regular-2.otf",
                "<TaskbarLyrics>/Assets/Fonts/SourceHanSansSC-Bold.otf"
        };
        for (String path : files) {
            File f = new File(path);
            if (!f.isFile()) {
                sb.append(String.format("  %-70s (不存在)%n", shortName(path)));
                continue;
            }
            try {
                Font font = Font.createFont(Font.TRUETYPE_FONT, f).deriveFont((float) SIZE);
                sb.append(String.format("  %-70s %s / %s%n",
                        shortName(path), font.getFamily(), font.getFontName()));
                inkByLabel.put(shortName(path), ink(font));
            } catch (Throwable t) {
                sb.append(String.format("  %-70s 加载失败: %s%n", shortName(path), t));
            }
        }
        sb.append('\n');

        // ── 4. 墨量对比（越高越粗）──────────────────────────────────────
        sb.append("=== 同一段中文的墨量（" ).append(SAMPLE).append("，")
                .append(SIZE).append("px，越高越粗）===\n");
        double max = inkByLabel.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        for (Map.Entry<String, Double> e : inkByLabel.entrySet()) {
            sb.append(String.format("  %-58s %10.0f  (%.1f%%)%n",
                    e.getKey(), e.getValue(), e.getValue() / max * 100));
        }
        sb.append('\n');

        // 渲染一张对照图：候选并列
        renderStrip(outDir, families);

        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(new File(outDir, "font-probe2.txt")), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        System.out.print(sb);
    }

    static String shortName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    static boolean isAvailable(String name, String[] families) {
        for (String f : families) {
            if (f.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** 家族里是否真的有 BOLD 字面（用 getAvailableAttributes 判断，比名字可靠）。 */
    static boolean hasBoldAttribute(String family) {
        Font base = new Font(family, Font.PLAIN, SIZE);
        if (base.canDisplayUpTo("愿") >= 0) {
            return false;   // 连中文都显示不了的家族直接排除
        }
        // 弱证据：真 Bold 与 PLAIN 解析到的物理字面不同时，字体名通常也不同。
        // 强证据是墨量，见 inkByLabel。
        return !new Font(family, Font.PLAIN, SIZE).getFontName()
                .equals(new Font(family, Font.BOLD, SIZE).getFontName());
    }

    /** 墨量：把字画到黑底上，累加亮度。同一句话下这就是"粗细"的代理量。 */
    static double ink(Font font) {
        int w = 480;
        int h = 48;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.WHITE);
        g.setFont(font);
        FontRenderContext frc = g.getFontRenderContext();
        double width = font.getStringBounds(SAMPLE, frc).getWidth();
        g.drawString(SAMPLE, (int) ((w - width) / 2), 32);
        g.dispose();

        double sum = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                sum += 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF)
                        + 0.114 * (p & 0xFF);
            }
        }
        return sum;
    }

    /** 把几个候选渲染成上下并列的对照图。 */
    static void renderStrip(String outDir, String[] families) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        addRow(rows, families, "Source Han Sans SC", Font.BOLD);
        addRow(rows, families, "Noto Sans SC", Font.BOLD);
        addFileRow(rows, "SourceHanSansSC-Bold-2.otf");
        addFileRow(rows, "Noto Sans SC Bold (TrueType).otf");
        addFileRow(rows, "SourceHanSansSC-Regular-2.otf");

        int rowH = 40;
        int w = 520;
        BufferedImage img = new BufferedImage(w, rowH * rows.size(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.WHITE);
        for (int i = 0; i < rows.size(); i++) {
            g.setFont((Font) rows.get(i)[0]);
            g.drawString(SAMPLE, 8, i * rowH + 28);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(outDir, "font-strip.png"));
    }

    static void addRow(List<Object[]> rows, String[] families, String family, int style) {
        if (!isAvailable(family, families)) return;
        rows.add(new Object[]{new Font(family, style, SIZE), family});
    }

    static void addFileRow(List<Object[]> rows, String fileName) {
        for (String dir : new String[]{
                "C:/Windows/Fonts/",
                "C:/Users/<user>/AppData/Local/Microsoft/Windows/Fonts/",
                "<TaskbarLyrics>/Assets/Fonts/"}) {
            File f = new File(dir + fileName);
            if (!f.isFile()) continue;
            try {
                rows.add(new Object[]{
                        Font.createFont(Font.TRUETYPE_FONT, f).deriveFont((float) SIZE), fileName});
            } catch (Throwable ignored) {
            }
            return;
        }
    }
}
