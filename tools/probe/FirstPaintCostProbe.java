import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.SecondLineKind;

import java.awt.image.BufferedImage;
import java.util.ArrayList;

/**
 * 首帧到底贵在哪儿——把 300ms 的淡入吞掉的那几百毫秒是哪一次调用花掉的。
 *
 * 起因：`WindowFadeProbe` 里启动段的留档只有 2 条，`0ms:0` 之后直接 `741ms:255`，
 * 中间没有任何一帧。这说明**第一帧和第二帧之间隔了 741ms**，而时间线是从第一帧
 * 开始算的，于是整段淡入还没来得及画就已经"跑完"了。
 *
 * 这个探针不改任何东西，只把每一次调用的耗时量出来，用来判断该在哪里预热。
 */
public class FirstPaintCostProbe {

    static final int W = 760;
    static final int H = 72;
    static final double LAYOUT = 1.5;

    public static void main(String[] args) {
        RenderSettings s = new RenderSettings();
        LyricsFrame f = new LyricsFrame(
                "窗口生灭字幕渐变自检这一句", "second line", null, new ArrayList<>(),
                5000L, 0L, 5000L, SecondLineKind.NEXT);

        mark("构建 RenderSettings + LyricsFrame");

        LyricsPainter p = new LyricsPainter();
        mark("构建 LyricsPainter");

        BufferedImage c1 = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        long t = System.nanoTime();
        p.paint(c1, f, s, LAYOUT, null, 0.0);
        report("paint #1（冷，含字形/字体首次加载）", System.nanoTime() - t);

        for (int i = 2; i <= 5; i++) {
            BufferedImage c = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
            t = System.nanoTime();
            p.paint(c, f, s, LAYOUT, null, 0.0);
            report("paint #" + i + "（热）", System.nanoTime() - t);
        }

        // 新建一个 painter 再画一次：区分「全局一次性成本」与「每个实例的首次成本」
        LyricsPainter p2 = new LyricsPainter();
        mark("再构建一个 LyricsPainter");
        BufferedImage c2 = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        t = System.nanoTime();
        p2.paint(c2, f, s, LAYOUT, null, 0.0);
        report("新实例的 paint #1", System.nanoTime() - t);

        System.out.println();
        System.out.println("结论看 paint #1 与 paint #2 的差：如果 #1 独占大头，"
                + "就必须把它挪到窗口没观众的时候先付掉，否则淡入必被它吞掉。");
    }

    static long last = System.nanoTime();

    static void mark(String name) {
        long now = System.nanoTime();
        report(name, now - last);
        last = now;
    }

    static void report(String name, long nanos) {
        System.out.printf("  %-42s %7.1f ms%n", name, nanos / 1_000_000.0);
    }
}
