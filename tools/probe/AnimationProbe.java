import com.spwtb.taskbarlyrics.lyrics.LyricCell;
import com.spwtb.taskbarlyrics.render.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 端到端跑一次真实的换行滚动，数一数**到底画了多少帧、每帧走多少像素**。
 *
 * 这就是「丝滑」的量化定义：同样 560ms 的位移，帧数越多、每帧步子越小、
 * 步长越均匀，看起来越顺。改前写死 33ms 一拍，只有 17 帧。
 *
 * 与 ScheduleProbe 的差别：那一支测的是空转调度，这一支把真实的
 * LyricsPainter.paint 放进循环（每帧 1.5~2ms），验证帧时间线能把绘制耗时吸收掉。
 *
 * 用法：./run-probe.sh AnimationProbe --
 */
public class AnimationProbe {

    static final int W = 420;
    static final int H = 72;

    public static void main(String[] args) {
        RenderSettings s = new RenderSettings();
        LyricsPainter painter = new LyricsPainter();
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        LyricsFrame prev = frame("上一句歌词在这里啊", "更早的第二行", SecondLineKind.NEXT, 500L);
        LyricsFrame next = frame("新的一行歌词来了啊", "再下一句歌词", SecondLineKind.NEXT, 1000L);

        // 预热：字体加载 + JIT
        for (int i = 0; i < 60; i++) {
            painter.paint(img, next, s, 1.5);
        }

        double pitch = LyricsLayout.INSTANCE.rowCenters(H, 1.5).getSecond()
                - LyricsLayout.INSTANCE.rowCenters(H, 1.5).getFirst();
        System.out.printf("行距 = %.2f px（一次滚动要走完的距离）%n%n", pitch);

        Win32.beginHighResolutionTimer();
        try {
            run("改前：33ms 一拍", painter, img, s, prev, next, 33_000_000L, pitch);
            run("改后：16.67ms（60Hz）", painter, img, s, prev, next, 16_670_000L, pitch);
            run("改后：8.33ms（120Hz 屏）", painter, img, s, prev, next, 8_330_000L, pitch);
        } finally {
            Win32.endHighResolutionTimer();
        }
    }

    static void run(String label, LyricsPainter painter, BufferedImage img, RenderSettings s,
                    LyricsFrame prev, LyricsFrame next, long budget, double pitch) {
        // 预热这一档的调度，避免第一帧带 JIT 开销
        spin(painter, img, s, prev, next, budget, false);

        List<Double> raws = new ArrayList<>();
        List<Long> gaps = new ArrayList<>();
        List<Long> costs = new ArrayList<>();

        LineTransition tr = new LineTransition(TransitionMode.SINGLE, prev, System.nanoTime());
        ReentrantLock lock = new ReentrantLock();
        Condition cond = lock.newCondition();
        long nextAt = System.nanoTime() + budget;
        long prevAt = System.nanoTime();
        while (!tr.finished(System.nanoTime())) {
            long t0 = System.nanoTime();
            painter.paint(img, next, s, 1.5, tr);
            costs.add(System.nanoTime() - t0);
            long now = System.nanoTime();
            raws.add(tr.state(now).getRaw());
            gaps.add(now - prevAt);
            prevAt = now;
            nextAt = (now - nextAt >= budget) ? now + budget : nextAt + budget;
            timeline(lock, cond, nextAt);
        }

        int frames = raws.size();
        long[] g = gaps.stream().mapToLong(Long::longValue).toArray();
        double meanGap = Arrays.stream(g).average().orElse(0) / 1e6;
        double maxGap = Arrays.stream(g).max().orElse(0) / 1e6;
        double minGap = Arrays.stream(g).min().orElse(0) / 1e6;
        double meanCost = costs.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;

        // 每帧走多少像素：位移 = 行距 × (move 的增量)
        double[] step = new double[Math.max(0, frames - 1)];
        for (int i = 1; i < frames; i++) {
            double m0 = LineTransition.Companion.getROLL_EASING().value(raws.get(i - 1));
            double m1 = LineTransition.Companion.getROLL_EASING().value(raws.get(i));
            step[i - 1] = Math.abs(m1 - m0) * pitch;
        }
        double meanStep = step.length > 0 ? Arrays.stream(step).average().orElse(0) : 0;
        double maxStep = step.length > 0 ? Arrays.stream(step).max().orElse(0) : 0;

        System.out.printf("%-26s 帧数 %3d  间隔 均%5.2f ms [%5.2f,%5.2f]  绘制均 %.2fms  "
                        + "每帧位移 均 %.3f px 最大 %.3f px%n",
                label, frames, meanGap, minGap, maxGap, meanCost, meanStep, maxStep);
    }

    static void spin(LyricsPainter painter, BufferedImage img, RenderSettings s,
                     LyricsFrame prev, LyricsFrame next, long budget, boolean unused) {
        LineTransition tr = new LineTransition(TransitionMode.SINGLE, prev, System.nanoTime());
        ReentrantLock lock = new ReentrantLock();
        Condition cond = lock.newCondition();
        long nextAt = System.nanoTime() + budget;
        while (!tr.finished(System.nanoTime())) {
            painter.paint(img, next, s, 1.5, tr);
            long now = System.nanoTime();
            nextAt = (now - nextAt >= budget) ? now + budget : nextAt + budget;
            timeline(lock, cond, nextAt);
        }
    }

    static void timeline(ReentrantLock lock, Condition cond, long target) {
        lock.lock();
        try {
            while (true) {
                long remaining = target - System.nanoTime();
                if (remaining <= 0) return;
                if (remaining > 1_000_000L) {
                    try {
                        cond.awaitNanos(remaining - 1_000_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    Thread.onSpinWait();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    static LyricsFrame frame(String main, String second, SecondLineKind kind, long pos) {
        return new LyricsFrame(main, second, null, new ArrayList<LyricCell>(),
                pos, 0L, 5000L, kind);
    }
}
