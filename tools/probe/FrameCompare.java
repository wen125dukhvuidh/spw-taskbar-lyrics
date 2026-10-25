import com.spwtb.taskbarlyrics.render.LineTransition;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 画一张「改前 / 改后」的帧采样对比图。
 *
 * 曲线是原版的滚动缓动 `cubic-bezier(0.22, 0.72, 0.24, 1)`，阶梯是**实际画出来的帧**：
 * 每一级台阶就是一帧停在那个位置上不动的时间。台阶越粗，看起来越顿。
 *
 * 用法：./run-probe.sh FrameCompare -- shots/transition/frame-compare.png
 */
public class FrameCompare {

    static final int W = 900;
    static final int H = 460;
    static final long DURATION_MS = 560;

    public static void main(String[] args) {
        String out = args.length > 0 ? args[0] : "frame-compare.png";
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);

        Font title = new Font("Microsoft YaHei", Font.BOLD, 17);
        Font label = new Font("Microsoft YaHei", Font.PLAIN, 13);
        Font small = new Font("Microsoft YaHei", Font.PLAIN, 12);

        g.setColor(new Color(0x11, 0x18, 0x27));
        g.setFont(title);
        g.drawString("一次换行滚动（560ms，行距 28.5px）实际画出来的帧", 40, 42);
        g.setFont(small);
        g.setColor(new Color(0x66, 0x66, 0x66));
        g.drawString("曲线 = 原版缓动；台阶 = 每一帧停留的位置与时长。台阶越粗越顿。", 40, 64);

        draw(g, "改前：33ms 一拍 —— 16 帧，每帧平均走 1.877px", 33.0, 40, 96, label, small,
                new Color(0xB0, 0xB7, 0xC3), new Color(0x6B, 0x72, 0x80));
        draw(g, "改后：跟随 60Hz 刷新率 —— 33 帧，每帧平均走 0.881px", 16.67, 40, 276, label, small,
                new Color(0x4C, 0x8D, 0xF5), new Color(0x1F, 0x4E, 0xA8));

        g.dispose();
        try {
            ImageIO.write(img, "png", new File(out));
            System.out.println("写出 " + out);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void draw(Graphics2D g, String title, double budgetMs, int x0, int y0,
                     Font label, Font small, Color curve, Color step) {
        int plotW = W - x0 - 60;
        int plotH = 130;
        int baseY = y0 + plotH;

        // 网格
        g.setColor(new Color(0xED, 0xEF, 0xF2));
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= 4; i++) {
            int x = x0 + plotW * i / 4;
            g.drawLine(x, y0, x, baseY);
        }

        g.setFont(label);
        g.setColor(new Color(0x11, 0x18, 0x27));
        g.drawString(title, x0, y0 - 8);

        // 理想曲线
        g.setColor(curve);
        g.setStroke(new BasicStroke(1.6f));
        int prevX = x0;
        int prevY = baseY;
        for (int px = 0; px <= plotW; px++) {
            double t = px / (double) plotW;
            double y = easing(t);
            int x = x0 + px;
            int yy = baseY - (int) Math.round(y * plotH);
            if (px > 0) {
                g.drawLine(prevX, prevY, x, yy);
            }
            prevX = x;
            prevY = yy;
        }

        // 实际帧：阶梯
        g.setColor(step);
        g.setStroke(new BasicStroke(2f));
        int frames = 0;
        double t = 0.0;
        int lastY = baseY;
        while (t < 1.0) {
            double y = easing(t);
            int yy = baseY - (int) Math.round(y * plotH);
            int x = x0 + (int) Math.round(t * plotW);
            g.drawLine(x, lastY, x, yy);          // 竖直：跳变
            if (frames > 0) {
                g.drawLine(x, yy, x + (int) Math.round(budgetMs / DURATION_MS * plotW), yy);
            }
            lastY = yy;
            frames++;
            t += budgetMs / DURATION_MS;
        }
        // 收尾
        int endX = x0 + plotW;
        g.drawLine(endX, lastY, endX, baseY - plotH);

        // 采样点
        g.setColor(step);
        t = 0.0;
        while (t < 1.0) {
            double y = easing(t);
            int x = x0 + (int) Math.round(t * plotW);
            int yy = baseY - (int) Math.round(y * plotH);
            g.fillOval(x - 3, yy - 3, 6, 6);
            t += budgetMs / DURATION_MS;
        }

        g.setFont(small);
        g.setColor(new Color(0x88, 0x88, 0x88));
        g.drawString("0", x0 - 4, baseY + 16);
        g.drawString("560ms", x0 + plotW - 16, baseY + 16);
        g.drawString("位置", x0 - 34, y0 + 10);
    }

    /** 原版位移曲线：行距 × cubic-bezier(0.22, 0.72, 0.24, 1)。 */
    static double easing(double t) {
        return LineTransition.Companion.getROLL_EASING().value(t);
    }
}
