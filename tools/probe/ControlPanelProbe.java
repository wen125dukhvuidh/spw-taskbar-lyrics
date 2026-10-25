import com.spwtb.taskbarlyrics.render.ControlPanelMetrics;
import com.spwtb.taskbarlyrics.render.ControlPanelMetrics.Hit;
import com.spwtb.taskbarlyrics.render.ControlPanelMetrics.Rects;
import com.spwtb.taskbarlyrics.render.ControlPanelPainter;
import com.spwtb.taskbarlyrics.render.ControlPanelState;
import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsLayout;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.SecondLineKind;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

/**
 * 点封面弹出的控制面板：版式、定位、命中测试、缓动。
 *
 * 这些都**不需要屏幕亮着**，纯几何与画布就能验：
 * - 定位逐条对着 `LyricsControlPanelPlacement.cs` 的分支抄的期望值；
 * - 命中测试要能分辨出三个按钮和进度条，且它们互不重叠；
 * - 缓动照 `OnSlideFrame` 的 `1-(1-p)³` / `p²`。
 *
 * 另外一条很关键：**命中区必须和画出来的封面重合**。
 * `LyricsLayout.coverRect` 是绘制端与命中测试共用的同一个函数，
 * 这里用像素把它钉住：矩形内左上角要有墨，矩形外要干净。
 *
 * 用法：ControlPanelProbe
 */
public class ControlPanelProbe {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("== 1. 定位（照抄 LyricsControlPanelPlacement）==");

        // 任务栏在屏幕底部：锚点在工作区下方 → 面板应落在锚点**上方**，间隙 16×scale
        Rectangle2D work = new Rectangle2D.Double(0, 0, 2560, 1320);
        Rectangle2D anchor = new Rectangle2D.Double(1000, 1320, 48, 48);
        double scale = 1.5;
        double pw = ControlPanelMetrics.WINDOW_W * scale;
        double ph = ControlPanelMetrics.WINDOW_H * scale;
        double gap = ControlPanelMetrics.GAP * scale;

        kotlin.Pair<Double, Double> p = ControlPanelMetrics.INSTANCE.place(anchor, work, pw, ph, scale, false);
        System.out.printf("  底部任务栏 → x=%.1f y=%.1f（期望 x=%.1f y=%.1f）%n",
                p.getFirst(), p.getSecond(), anchor.getMinX(), anchor.getMinY() - ph - gap);
        checkClose("放在封面上方，左边缘与封面齐", p.getFirst(), anchor.getMinX());
        checkClose("与封面之间留 16 DIP × scale 的间隙", p.getSecond(), anchor.getMinY() - ph - gap);

        // 封面在右侧：右边缘与封面右缘对齐
        kotlin.Pair<Double, Double> pr = ControlPanelMetrics.INSTANCE.place(anchor, work, pw, ph, scale, true);
        checkClose("封面在右侧时右边缘对齐", pr.getFirst(), anchor.getMaxX() - pw);

        // 上方放不下（面板会顶出工作区）→ 翻到锚点下方
        Rectangle2D highAnchor = new Rectangle2D.Double(1000, 40, 48, 48);
        kotlin.Pair<Double, Double> below =
                ControlPanelMetrics.INSTANCE.place(highAnchor, work, pw, ph, scale, false);
        System.out.printf("  高处锚点 → y=%.1f（上方放不下，应翻到下方 %.1f）%n",
                below.getSecond(), highAnchor.getMaxY() + gap);
        checkClose("上方放不下时翻到下方", below.getSecond(), highAnchor.getMaxY() + gap);

        // 一定被夹在工作区内
        Rectangle2D leftEdge = new Rectangle2D.Double(2, 1320, 48, 48);
        kotlin.Pair<Double, Double> clamped =
                ControlPanelMetrics.INSTANCE.place(leftEdge, work, pw, ph, scale, false);
        check("横向被夹在工作区内（不贴屏幕边）",
                clamped.getFirst() >= work.getMinX() - 0.001,
                "x=" + clamped.getFirst());
        check("纵向不低于工作区顶部",
                clamped.getSecond() >= work.getMinY() - 0.001,
                "y=" + clamped.getSecond());
        check("纵向不超出工作区底部",
                clamped.getSecond() + ph <= work.getMaxY() + 0.001,
                "bottom=" + (clamped.getSecond() + ph));

        System.out.println();
        System.out.println("== 2. 命中测试：三个按钮与进度条各就各位 ==");
        Rects r = new Rects(scale);
        check("点上一首按钮 → PREVIOUS",
                r.hit(centerX(r.getPrevious()), centerY(r.getPrevious())) == Hit.PREVIOUS,
                "得到 " + r.hit(centerX(r.getPrevious()), centerY(r.getPrevious())));
        check("点播放暂停 → PLAY_PAUSE",
                r.hit(centerX(r.getPlayPause()), centerY(r.getPlayPause())) == Hit.PLAY_PAUSE,
                "得到 " + r.hit(centerX(r.getPlayPause()), centerY(r.getPlayPause())));
        check("点下一首 → NEXT",
                r.hit(centerX(r.getNext()), centerY(r.getNext())) == Hit.NEXT,
                "得到 " + r.hit(centerX(r.getNext()), centerY(r.getNext())));
        check("点进度条 → SEEK",
                r.hit(r.getSeek().getCenterX(), r.getSeek().getCenterY()) == Hit.SEEK,
                "得到 " + r.hit(r.getSeek().getCenterX(), r.getSeek().getCenterY()));
        check("点封面 → ARTWORK",
                r.hit(centerX(r.getArtwork()), centerY(r.getArtwork())) == Hit.ARTWORK,
                "得到 " + r.hit(centerX(r.getArtwork()), centerY(r.getArtwork())));
        check("点标题区 → INFO",
                r.hit((r.getInfoLeft() + r.getInfoRight()) / 2, r.getTrackTop() + 10) == Hit.INFO,
                "得到 " + r.hit((r.getInfoLeft() + r.getInfoRight()) / 2, r.getTrackTop() + 10));

        // 按钮不能互相重叠，也不能压到信息区
        check("三个按钮互不重叠",
                !r.getPrevious().intersects(r.getPlayPause()) && !r.getPlayPause().intersects(r.getNext()),
                "有重叠");
        check("按钮区在信息区右侧（不压住标题）",
                r.getPrevious().x >= r.getInfoRight() - 0.001,
                String.format("按钮左缘 %.1f < 信息区右缘 %.1f", r.getPrevious().x, r.getInfoRight()));
        check("信息区宽度为正",
                r.getInfoRight() - r.getInfoLeft() > 40,
                String.format("宽 %.1f", r.getInfoRight() - r.getInfoLeft()));
        check("所有控件都在面板内",
                r.getPrevious().x >= 0 && r.getNext().getMaxX() <= r.getWidth() + 0.001 &&
                        r.getSeek().getMaxY() <= r.getHeight() + 0.001,
                "有控件越界");

        System.out.println();
        System.out.println("== 3. 缓动（照抄 OnSlideFrame）==");
        checkClose("入场 p=0 → 0", ControlPanelMetrics.INSTANCE.ease(true, 0), 0.0);
        checkClose("入场 p=1 → 1", ControlPanelMetrics.INSTANCE.ease(true, 1), 1.0);
        // 1-(1-0.5)^3 = 0.875
        checkClose("入场 p=0.5 → 0.875", ControlPanelMetrics.INSTANCE.ease(true, 0.5), 0.875);
        check("入场是缓出（前半段走得比线性快）",
                ControlPanelMetrics.INSTANCE.ease(true, 0.25) > 0.25,
                String.format("p=0.25 时 %.4f", ControlPanelMetrics.INSTANCE.ease(true, 0.25)));
        checkClose("退场 p=0.5 → 0.25", ControlPanelMetrics.INSTANCE.ease(false, 0.5), 0.25);
        check("退场是缓入（前半段走得比线性慢）",
                ControlPanelMetrics.INSTANCE.ease(false, 0.25) < 0.25,
                String.format("p=0.25 时 %.4f", ControlPanelMetrics.INSTANCE.ease(false, 0.25)));

        System.out.println();
        System.out.println("== 4. 命中区 == 画出来的封面（像素级）==");
        // 画一帧带封面的歌词，验证 coverRect 圈出来的地方确实有内容
        int W = 420, H = 72;
        double layout = 1.5;
        RenderSettings s = new RenderSettings().withShowCover(true);
        BufferedImage cover = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB_PRE);
        {
            java.awt.Graphics2D cg = cover.createGraphics();
            cg.setColor(java.awt.Color.WHITE);
            cg.fillRect(0, 0, 64, 64);
            cg.dispose();
        }
        LyricsFrame frame = new LyricsFrame("封面命中区验证", "第二行", cover,
                new ArrayList<>(), 1000L, 0L, 3000L, SecondLineKind.NEXT);
        BufferedImage shot = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        LyricsPainter lp = new LyricsPainter();
        lp.paint(shot, frame, s, layout);                 // 预热
        lp.paint(shot, frame, s, layout);

        double boxH = LyricsLayout.INSTANCE.boxHeight(H, layout);
        Rectangle2D cr = LyricsLayout.INSTANCE.coverRect(s, layout, W, boxH, true);
        check("coverRect 有值（开着封面且有图）", cr != null, "得到 null");
        if (cr != null) {
            System.out.printf("  coverRect = (%.1f, %.1f) %.1fx%.1f%n",
                    cr.getX(), cr.getY(), cr.getWidth(), cr.getHeight());
            int x0 = (int) Math.round(cr.getX());
            int y0 = (int) Math.round(cr.getY());
            int x1 = (int) Math.round(cr.getMaxX());
            int y1 = (int) Math.round(cr.getMaxY());
            check("命中区内确实有封面（左上角不透明）",
                    (shot.getRGB(x0 + 2, y0 + 2) >>> 24) > 200,
                    "alpha=" + (shot.getRGB(x0 + 2, y0 + 2) >>> 24));
            check("命中区**上方**是空的（矩形没框错位置）",
                    (shot.getRGB(x0 + 2, Math.max(0, y0 - 3)) >>> 24) < 60,
                    "alpha=" + (shot.getRGB(x0 + 2, Math.max(0, y0 - 3)) >>> 24));
            check("命中区**左方**是空的",
                    (shot.getRGB(Math.max(0, x0 - 3), y0 + 2) >>> 24) < 60,
                    "alpha=" + (shot.getRGB(Math.max(0, x0 - 3), y0 + 2) >>> 24));
            check("命中区右下角仍在窗口内",
                    x1 <= W && y1 <= H, String.format("(%d,%d)", x1, y1));
        }

        System.out.println();
        System.out.println("== 5. 面板绘制 ==");
        Rects r1 = new Rects(1.0);
        BufferedImage panel = new BufferedImage(
                (int) Math.round(r1.getWidth()), (int) Math.round(r1.getHeight()),
                BufferedImage.TYPE_INT_ARGB_PRE);
        ControlPanelPainter cp = new ControlPanelPainter();
        cp.paint(panel, new ControlPanelState("测试曲名", "测试艺术家", "Salt Player for Windows",
                null, true, 61000, 245000, true, Hit.NONE, null), 1.0);
        check("面板尺寸 = 360×124", panel.getWidth() == 360 && panel.getHeight() == 124,
                panel.getWidth() + "x" + panel.getHeight());
        check("底铺满不透明背景（不是透明的）",
                (panel.getRGB(180, 62) >>> 24) == 255,
                "alpha=" + (panel.getRGB(180, 62) >>> 24));
        long ink = 0;
        for (int y = 0; y < panel.getHeight(); y++) {
            for (int x = 0; x < panel.getWidth(); x++) {
                ink += panel.getRGB(x, y) >>> 24;
            }
        }
        check("画面有内容（墨量 > 0）", ink > 0, "墨量 " + ink);

        // 时间格式
        check("时间格式 m:ss", "1:01".equals(cp.formatTime(61000L)), cp.formatTime(61000L));
        check("超过一小时是 h:mm:ss", "1:04:05".equals(cp.formatTime(3845000L)),
                cp.formatTime(3845000L));

        System.out.println();
        System.out.printf("%s：%d 项通过，%d 项失败%n",
                failed == 0 ? "全部通过" : "存在失败", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    static double centerX(Rectangle2D r) {
        return r.getCenterX();
    }

    static double centerY(Rectangle2D r) {
        return r.getCenterY();
    }

    static void checkClose(String name, double actual, double expected) {
        check(name, Math.abs(actual - expected) < 0.01,
                String.format("实测 %.4f，期望 %.4f", actual, expected));
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + " —— " + detail);
        }
    }
}
