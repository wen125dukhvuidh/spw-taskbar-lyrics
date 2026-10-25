import com.spwtb.taskbarlyrics.render.Win32;

import java.util.Arrays;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 帧调度实测：为什么动画不丝滑。
 *
 * 背景：绘制一帧只要 0.19ms（静止）/ 1.9ms（过渡）（见 PerfProbe），
 * 所以瓶颈全在"多久画一帧"。这里直接量四种调度方式的**帧间隔分布**：
 *
 * 1. 老做法：Thread.sleep(33)          —— 1.0.9 之前
 * 2. Thread.sleep(16)                  —— 天真地改成 60fps
 * 3. timeBeginPeriod(1) + sleep(16)    —— 解决定时器粒度
 * 4. timeBeginPeriod(1) + 条件变量绝对时间线 —— 现在的做法
 *
 * 关键指标不是均值，是**抖动**：间隔忽长忽短才是"一顿一顿"的来源。
 * Windows 默认定时器粒度 15.625ms，sleep(16) 会取整到 31.25ms。
 *
 * 用法：./run-probe.sh ScheduleProbe --
 */
public class ScheduleProbe {

    static final int N = 120;

    public static void main(String[] args) {
        int hz = Win32.displayRefreshHz();
        System.out.println("主显示器刷新率 = " + hz + " Hz  → 目标帧间隔 " + String.format("%.2f", 1000.0 / hz) + " ms");
        System.out.println();

        run("1) Thread.sleep(33)（改前）", false, 33, Mode.SLEEP);
        run("2) Thread.sleep(16)", false, 16, Mode.SLEEP);
        run("3) timeBeginPeriod(1) + sleep(16)", true, 16, Mode.SLEEP);
        run("4) timeBeginPeriod(1) + 绝对时间线", true, 16, Mode.TIMELINE);
        run("5) 时间线 + 最后 1ms 自旋", true, 16, Mode.TIMELINE_SPIN);
        run("6) 时间线 + 自旋（120fps 预算）", true, 8, Mode.TIMELINE_SPIN);
    }

    enum Mode { SLEEP, TIMELINE, TIMELINE_SPIN }

    static void run(String label, boolean highRes, int budgetMs, Mode mode) {
        if (highRes) {
            Win32.beginHighResolutionTimer();
        }
        try {
            // 预热，避开 JIT 与首次调用开销
            spin(budgetMs, mode, 30);
            long[] gaps = spin(budgetMs, mode, N);
            long[] sorted = gaps.clone();
            Arrays.sort(sorted);
            double mean = Arrays.stream(sorted).average().orElse(0) / 1e6;
            double p50 = sorted[N / 2] / 1e6;
            double p95 = sorted[(int) (N * 0.95)] / 1e6;
            double min = sorted[0] / 1e6;
            double max = sorted[N - 1] / 1e6;
            // 抖动：相邻帧间隔之差的绝对值平均。这才是"顺不顺"的直接体感指标。
            double jitter = 0;
            for (int i = 1; i < N; i++) {
                jitter += Math.abs(gaps[i] - gaps[i - 1]) / 1e6;
            }
            jitter /= (N - 1);
            System.out.printf("%-38s 均%6.2fms 中位%6.2f p95%6.2f 区间[%5.2f,%6.2f] 抖动%6.2fms → %5.1f fps%n",
                    label, mean, p50, p95, min, max, jitter, 1000.0 / mean);
        } finally {
            if (highRes) {
                Win32.endHighResolutionTimer();
            }
        }
    }

    static long[] spin(int budgetMs, Mode mode, int n) {
        long[] gaps = new long[n];
        long budgetNanos = budgetMs * 1_000_000L;
        long prev = System.nanoTime();
        long nextAt = prev + budgetNanos;
        ReentrantLock lock = new ReentrantLock();
        Condition cond = lock.newCondition();
        for (int i = 0; i < n; i++) {
            // 模拟真实一帧的工作量（实测 0.2~2ms，这里取 2ms 的保守值）
            work(2);
            if (mode == Mode.SLEEP) {
                sleep(budgetMs);
            } else {
                long now = System.nanoTime();
                nextAt = (now - nextAt >= budgetNanos) ? now + budgetNanos : nextAt + budgetNanos;
                timeline(lock, cond, nextAt, mode == Mode.TIMELINE_SPIN ? 1_000_000L : 0L);
            }
            long t = System.nanoTime();
            gaps[i] = t - prev;
            prev = t;
        }
        return gaps;
    }

    static double acc;

    /** 一点可预测的计算量，避免空循环被 JIT 优化掉。 */
    static void work(int ms) {
        long until = System.nanoTime() + ms * 1_000_000L;
        double v = 0;
        while (System.nanoTime() < until) {
            v += Math.sqrt(v + 1);
        }
        acc += v;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void timeline(ReentrantLock lock, Condition cond, long target, long spinWindow) {
        lock.lock();
        try {
            while (true) {
                long remaining = target - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                if (remaining > spinWindow) {
                    try {
                        cond.awaitNanos(remaining - spinWindow);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    // 最后一段自旋收尾：等待唤醒的实际时刻有 ±1ms 浮动，
                    // 用自旋把帧间隔钉死，抖动从 1.5ms 量级压到 0.1ms 量级
                    Thread.onSpinWait();
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
