import com.spwtb.taskbarlyrics.render.LineTransition;
import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.SecondLineKind;
import com.spwtb.taskbarlyrics.render.TransitionMode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;

/**
 * 出一张「动画胶片条」：同一帧在过渡的各个时间点上各渲一张，竖着拼起来。
 *
 * 为了看清位置关系，每行左边标了它对应的时间比例 t 与真实毫秒数。
 *
 * 参数：<输出 PNG> [宽] [高]
 */
public class TransitionStrip {

    static final int W = 420;
    static final int H = 72;
    static final double DPI = 1.5;

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "transition-strip.png";
        double[] raws = {0.0, 0.08, 0.16, 0.25, 0.35, 0.5, 0.7, 1.0};

        RenderSettings s = new RenderSettings();
        LyricsPainter painter = new LyricsPainter();
        LyricsFrame previous = new LyricsFrame(
                "旧的一行歌词在这里", "更早的第二行", null, new ArrayList(),
                4200L, 0L, 5000L, SecondLineKind.NEXT);

        // 预热（第一次渲染要加载字体，会吃掉整个 560ms）
        render(painter, s, null, null);

        BufferedImage strip = new BufferedImage(W, (H + 2) * raws.length, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = strip.createGraphics();
        try {
            g.setPaint(new Color(16, 16, 22));
            g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
            for (int i = 0; i < raws.length; i++) {
                double raw = raws[i];
                LineTransition t = new LineTransition(
                        TransitionMode.SINGLE, previous,
                        System.nanoTime() - (long) (raw * TransitionMode.SINGLE.getDurationMs() * 1e6));
                BufferedImage f = render(painter, s, t, null);
                g.drawImage(f, 0, i * (H + 2), null);
                g.setPaint(new Color(255, 255, 255, 150));
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 10));
                g.drawString(String.format("t=%.2f  %.0fms", raw, raw * 560), 4, i * (H + 2) + 12);
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(strip, "png", new File(out));
        System.out.println("写出 " + out + "  " + strip.getWidth() + "x" + strip.getHeight());
    }

    static BufferedImage render(LyricsPainter painter, RenderSettings s, LineTransition t, Object unused) {
        LyricsFrame frame = new LyricsFrame(
                "新的一行歌词来了啊", "再下一句歌词", null, new ArrayList(),
                1000L, 0L, 5000L, SecondLineKind.NEXT);
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        painter.paint(image, frame, s, DPI, t);
        BufferedImage argb = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return argb;
    }
}
