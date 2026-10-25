import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.SecondLineKind;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;

/**
 * 只画一帧**静止**（无过渡）的歌词，用来做「改前 / 改后」的像素级回归对照。
 *
 * 这个探针刻意只用两个版本共有的 API（`new RenderSettings()` + `paint(image, frame, s, dpi)`），
 * 这样同一份 class 既能跑在 1.0.8 的旧类上，也能跑在当前的新类上，
 * 产出的两张 PNG 可以直接逐像素相减。
 *
 * 参数：<输出 PNG 路径> [宽] [高] [dpi 缩放] [主行] [第二行]
 */
public class RestingShot {

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "resting.png";
        int width = args.length > 1 ? Integer.parseInt(args[1]) : 558;
        int height = args.length > 2 ? Integer.parseInt(args[2]) : 72;
        double dpiScale = args.length > 3 ? Double.parseDouble(args[3]) : 1.5;
        String main = args.length > 4 ? args[4] : "愿不枉啊 愿勇往啊";
        String second = args.length > 5 ? args[5] : "这盛世每一天";

        RenderSettings settings = new RenderSettings();
        LyricsFrame frame = new LyricsFrame(
                main, second, null, new ArrayList(),
                3200L, 0L, 5000L, SecondLineKind.NEXT);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        new LyricsPainter().paint(image, frame, settings, dpiScale);

        BufferedImage argb = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        ImageIO.write(argb, "png", new File(out));
        System.out.println("写出 " + out);
    }
}
