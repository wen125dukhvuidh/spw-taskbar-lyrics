import com.spwtb.taskbarlyrics.lyrics.LyricCell;
import com.spwtb.taskbarlyrics.render.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 单帧绘制耗时测量。
 *
 * 动机：用户反馈「有动画了，但不如原版丝滑」。原版是 Chromium 合成器，动画按显示器
 * 刷新率逐帧插值（60Hz = 16.7ms 一帧，560ms 滚动 33 帧）。我们的渲染循环写死 33ms
 * 一帧，560ms 只有 17 帧，而且这 33ms **不含**绘制本身的耗时。
 *
 * 所以先测清楚：一帧到底要多久？能不能撑住 60fps？瓶颈在哪一段？
 *
 * 用法：./run-probe.sh PerfProbe --
 */
public class PerfProbe {

    static final int W = 420;
    static final int H = 72;

    public static void main(String[] args) {
        LyricsPainter warmPainter = new LyricsPainter();
        BufferedImage warmImg = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        // 预热：第一次渲染要加载系统字体（实测 780ms），必须排除在统计之外
        long t0 = System.nanoTime();
        for (int i = 0; i < 30; i++) {
            warmPainter.paint(warmImg, frame("预热", "预热第二行", i), base(), 1.5);
        }
        System.out.printf("（预热 30 帧 %.0f ms，已排除）%n%n", (System.nanoTime() - t0) / 1e6);

        measure("静止帧 默认（封面+阴影+扫描）", base(), null);
        measure("静止帧 关阴影", base().withTextShadow(false), null);
        measure("静止帧 关封面", base().withShowCover(false), null);
        measure("静止帧 关逐字扫描", base().withWordScanning(false), null);
        measure("静止帧 关阴影+封面", base().withTextShadow(false).withShowCover(false), null);
        measure("静止帧 单行（无第二行）", base(), "single");

        for (double raw : new double[]{0.15, 0.5, 0.85}) {
            measure(String.format("过渡帧 单行 raw=%.2f", raw), base(), "single:" + raw);
            measure(String.format("过渡帧 翻译对 raw=%.2f", raw), base(), "pair:" + raw);
        }
    }

    static RenderSettings base() {
        return new RenderSettings();
    }

    static LyricsFrame frame(String main, String second, int i) {
        return new LyricsFrame(main, second, null, new ArrayList<LyricCell>(),
                1000L + i * 16L, 0L, 5000L, SecondLineKind.NEXT);
    }

    static void measure(String label, RenderSettings s, String mode) {
        LyricsPainter painter = new LyricsPainter();
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);

        // JIT 预热：不预热的话前几十帧是解释执行，均值会被拉高好几倍
        for (int i = 0; i < 300; i++) {
            painter.paint(img, frame("微带着后悔", "后悔的翻译在这里", i), s, 1.5, at(mode, i));
        }

        int n = 400;
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            long a = System.nanoTime();
            painter.paint(img, frame("微带着后悔", "后悔的翻译在这里", i), s, 1.5, at(mode, i));
            t[i] = System.nanoTime() - a;
        }
        Arrays.sort(t);
        double mean = Arrays.stream(t).average().orElse(0) / 1e6;
        double p50 = t[n / 2] / 1e6;
        double p95 = t[(int) (n * 0.95)] / 1e6;
        double max = t[n - 1] / 1e6;
        System.out.printf("%-32s 均%6.2fms  中位%6.2f  p95%6.2f  max%6.2f  → 上限 %3.0f fps%n",
                label, mean, p50, p95, max, 1000.0 / mean);
    }

    static LineTransition at(String mode, int i) {
        if (mode == null || mode.equals("single")) return null;
        double raw = Double.parseDouble(mode.substring(mode.indexOf(':') + 1));
        boolean pair = mode.startsWith("pair");
        LyricsFrame prev = new LyricsFrame("上一句歌词在这里啊", pair ? "上一句的翻译" : "更早的第二行",
                null, new ArrayList<LyricCell>(), 500L, 0L, 1000L,
                pair ? SecondLineKind.TRANSLATION : SecondLineKind.NEXT);
        TransitionMode tm = pair ? TransitionMode.TRANSLATION_PAIR : TransitionMode.SINGLE;
        // 进度靠 startNanos 反推钉死：state() 读的是实时时钟，不钉的话 560ms 之后
        // 过渡就结束了，测到的全是静止帧。每帧新建一个对象，开销可忽略。
        return new LineTransition(tm, prev,
                System.nanoTime() - (long) (raw * tm.getDurationMs() * 1e6));
    }
}
