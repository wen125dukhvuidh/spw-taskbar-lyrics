import com.spwtb.taskbarlyrics.lyrics.CoverArt;
import com.spwtb.taskbarlyrics.lyrics.LyricCell;
import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 离屏渲染一帧完整的任务栏歌词，用来**在没有窗口的环境下验证版式**。
 *
 * 用途：调完字号/透明度这类参数后，直接产出 PNG，再用 TextProbe 逐行量，
 * 和原版抓图对比。不需要占用任务栏，也不受"任务栏没有空闲区"的影响。
 *
 * 参数：<输出目录> <FLAC 路径> [宽] [高] [dpi 缩放] [主行] [第二行] [行起] [行止] [当前位置]
 *
 * 后五个参数用于和原版做**同文字**对比：指定相同的歌词与时间点，
 * 渲染出来的字形才具备可比性。
 */
public class FrameProbe {

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        String flac = args.length > 1 ? args[1] : "D:/Music/赵雷 - 小行迹 (Live版).flac";
        int width = args.length > 2 ? Integer.parseInt(args[2]) : 558;
        int height = args.length > 3 ? Integer.parseInt(args[3]) : 72;
        double dpiScale = args.length > 4 ? Double.parseDouble(args[4]) : 1.5;
        boolean custom = args.length >= 7;
        String main = custom ? args[5] : "愿不枉啊 愿勇往啊";
        String second = custom ? args[6] : "这盛世每一天";
        long lineStart = args.length > 7 ? Long.parseLong(args[7]) : 0L;
        long lineEnd = args.length > 8 ? Long.parseLong(args[8]) : 5000L;
        long position = args.length > 9 ? Long.parseLong(args[9]) : 3200L;

        RenderSettings settings = new RenderSettings();
        // 可选第 11 个参数：文字 gamma 补偿百分比，用来做"补偿前/后"的同帧对照
        if (args.length > 10) {
            settings = settings.withTextGamma(Integer.parseInt(args[10]));
        }
        // 可选第 12 个参数：字体族名，用来做字体对照
        if (args.length > 11) {
            settings = settings.withFontFamily(args[11]);
        }
        double layout = dpiScale * settings.getLayoutScalePercent() / 100.0;
        int coverSize = (int) Math.round(settings.getCoverSize() * layout);
        int radius = (int) Math.round(settings.getCoverCornerRadius() * layout);
        BufferedImage cover = settings.getShowCover()
                ? CoverArt.INSTANCE.forSong("frame-probe", flac, coverSize, radius)
                : null;

        LyricsFrame frame = new LyricsFrame(
                main, second, cover, synthesize(main, lineStart, lineEnd),
                position, lineStart, lineEnd,
                com.spwtb.taskbarlyrics.render.SecondLineKind.PLACEHOLDER);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        new LyricsPainter().paint(image, frame, settings, dpiScale);

        // 转成非预乘再存，方便外部工具读原始 alpha / RGB
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        File file = new File(outDir, "frame-render.png");
        ImageIO.write(out, "png", file);
        System.out.printf("已输出 %s  (%dx%d, dpiScale=%.2f, 第二行比例=0.90)%n",
                file.getName(), width, height, dpiScale);
        System.out.println("主行: " + main + "   第二行: " + second);
    }

    private static List<LyricCell> synthesize(String text, long start, long end) {
        List<LyricCell> cells = new ArrayList<>();
        int n = text.length();
        for (int i = 0; i < n; i++) {
            cells.add(new LyricCell(
                    start + (end - start) * i / n,
                    start + (end - start) * (i + 1) / n,
                    text.substring(i, i + 1)));
        }
        return cells;
    }
}
