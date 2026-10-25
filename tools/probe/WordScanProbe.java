import com.spwtb.taskbarlyrics.lyrics.LyricCell;
import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.ScanSmoother;
import com.spwtb.taskbarlyrics.render.WordScan;
import com.spwtb.taskbarlyrics.render.SecondLineKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 验证逐字扫描进度是**连续**的，而不是「一次亮一个字」。
 *
 * 这是这次要修的毛病本身，所以判据必须能直接量化「跳变」：
 * 以 1 毫秒为步长扫过一整行，看相邻两帧的进度差有多大。
 * 整字累加会出现「一个字宽」的台阶；连续插值不会。
 *
 * 期望值一律**照抄原版**，不拿被测代码反推：
 * - `TaskbarLyrics.Core.Tests/LyricSyncServiceTests.cs` 的
 *   `GetDisplayFrameAsyncCalculatesContinuousWordScanProgressAndRecalculatesAfterSeek`
 *   给了 4 个断言点（"Hi there" / 8 字符）；
 * - `Web/Lyrics/style.css` 的 `.line.word-scan-smoothing` 给了 90ms linear；
 * - `Web/Lyrics/app.js` 的 `setLineWordScanProgress` 给了两个守卫阈值 0.02 / 0.35。
 *
 * 用法：WordScanProbe
 */
public class WordScanProbe {

    static int passed = 0;
    static int failed = 0;

    /** 原版 `wordScanResumeCatchUpDurationMs` 是另一回事；这里只对齐 smoothing 的 90ms。 */
    static final double SMOOTH_MS = 90.0;

    public static void main(String[] args) {
        System.out.println("== 1. 复刻原版测试用例 \"Hi there\"（8 字符） ==");
        // 原版：ParsedLyricLine(text: "Hi there", segments: [ (0s,1s,"Hi"), (1s,3s,"there") ])
        List<LyricCell> hi = Arrays.asList(
                new LyricCell(0L, 1000L, "Hi"),
                new LyricCell(1000L, 3000L, "there"));
        // 500ms：第一个 segment 唱到一半 → 2 × 0.5 = 1，1/8
        checkClose("500ms → 1/8", progressOf(frame(hi, "Hi there", 500L)), 1.0 / 8);
        // 2000ms：第二个 segment 唱到一半 → 基数 3（含空格）+ 5 × 0.5 = 5.5，5.5/8
        checkClose("2000ms → 5.5/8", progressOf(frame(hi, "Hi there", 2000L)), 5.5 / 8);
        // 250ms：第一个 segment 的四分之一 → 2 × 0.25 = 0.5
        checkClose("250ms → 0.5/8", progressOf(frame(hi, "Hi there", 250L)), 0.5 / 8);
        // 3000ms：整行唱完 → 1
        checkClose("3000ms → 1", progressOf(frame(hi, "Hi there", 3000L)), 1.0);

        // 基数 3 而不是 2 这一条单独钉死：它证明 indexOf 是从 searchIndex 之后找的，
        // 且空格被算进了长度。写错成 2 的话，2000ms 会得到 4.5/8。
        double at2000 = progressOf(frame(hi, "Hi there", 2000L));
        check("第二个字的基数是字符下标（含空格），不是累计字宽",
                Math.abs(at2000 - 5.5 / 8) < 1e-9 && Math.abs(at2000 - 4.5 / 8) > 1e-6,
                String.format("实测 %.6f，若按累计字宽应得 %.6f", at2000, 4.5 / 8.0));

        System.out.println();
        System.out.println("== 2. 连续性的直接判据：逐毫秒扫描，看最大跳变 ==");

        // 中文逐字：10 个字，每个 300ms，整行 3000ms
        List<LyricCell> cn = new ArrayList<>();
        String cnText = "这一行有十个汉字正好";
        for (int i = 0; i < cnText.length(); i++) {
            cn.add(new LyricCell(i * 300L, (i + 1) * 300L, cnText.substring(i, i + 1)));
        }
        double[] series = new double[3001];
        for (int ms = 0; ms <= 3000; ms++) {
            series[ms] = progressOf(frame(cn, cnText, (long) ms));
        }
        double maxStep = 0;
        double firstStepAt = -1;
        for (int ms = 1; ms <= 3000; ms++) {
            double step = Math.abs(series[ms] - series[ms - 1]);
            if (step > maxStep) {
                maxStep = step;
                firstStepAt = ms;
            }
        }
        // 连续插值下，1ms 的进度增量 = 1/10 字 ÷ 300ms ≈ 0.000333
        // 整字累加下会出现整整一个字 = 0.1，是它的 300 倍
        double idealStep = 1.0 / cnText.length() / 300.0;
        System.out.printf("  最大单毫秒跳变 = %.6f（理论的 %.6f，整字累加会是 %.1f）%n",
                maxStep, idealStep, 1.0 / cnText.length());
        check("单毫秒跳变应等于连续插值的步长（不是整字宽度）",
                maxStep <= idealStep * 1.5 + 1e-9,
                String.format("实测 %.6f，连续插值应 ≤ %.6f", maxStep, idealStep * 1.5));
        check("最大跳变比「一个字」小两个数量级",
                maxStep < (1.0 / cnText.length()) / 50,
                String.format("实测 %.6f，一个字 = %.6f", maxStep, 1.0 / cnText.length()));

        // 单调：整行扫描过程不许回退
        boolean monotonic = true;
        for (int ms = 1; ms <= 3000; ms++) {
            if (series[ms] < series[ms - 1] - 1e-12) {
                monotonic = false;
                break;
            }
        }
        check("整行扫描单调不回退", monotonic, "在 " + firstStepAt + "ms 附近出现最大值");

        // 端点
        checkClose("0ms → 0", series[0], 0.0);
        checkClose("3000ms → 1", series[3000], 1.0);

        // 对照组：把**旧算法**（整字累加）在同一行上算一遍。
        // 光说「新的很平滑」不算证据——得同时量出旧的有多台阶，
        // 才能把「100% 的帧在变」归因到这次修改上。
        double[] legacy = new double[3001];
        for (int ms = 0; ms <= 3000; ms++) {
            int acc = 0;
            for (LyricCell c : cn) {
                if (c.getStartMs() <= ms) {
                    acc += c.getText().length();
                } else {
                    break;
                }
            }
            legacy[ms] = acc / (double) cnText.length();
        }
        int legacyMoving = 0;
        double legacyMax = 0;
        for (int ms = 1; ms <= 3000; ms++) {
            double step = Math.abs(legacy[ms] - legacy[ms - 1]);
            if (step > 1e-12) {
                legacyMoving++;
            }
            legacyMax = Math.max(legacyMax, step);
        }
        int newMoving = 0;
        for (int ms = 1; ms <= 3000; ms++) {
            if (Math.abs(series[ms] - series[ms - 1]) > 1e-12) {
                newMoving++;
            }
        }
        System.out.printf("  逐毫秒「有变化」的比例：旧算法 %.1f%%，新算法 %.1f%%%n",
                legacyMoving / 30.0, newMoving / 30.0);
        System.out.printf("  单毫秒最大跳变：旧算法 %.4f（= 整整一个字），新算法 %.6f%n",
                legacyMax, maxStep);
        check("旧算法确实是台阶（一个字宽），新算法把它摊平了",
                legacyMax > 0.5 / cnText.length() && maxStep < legacyMax / 100,
                String.format("旧 %.4f / 新 %.6f，倍数 %.1f", legacyMax, maxStep,
                        legacyMax / Math.max(maxStep, 1e-12)));

        System.out.println();
        System.out.println("== 3. 字时长为 0 的单元：整段计入，不参与插值 ==");
        // 原版：`if (syllable.Duration <= TimeSpan.Zero) { scanned = index + len; continue; }`
        List<LyricCell> zero = Arrays.asList(
                new LyricCell(0L, 0L, "AB"),
                new LyricCell(0L, 1000L, "CD"));
        // 0ms：第一个时长为 0 的字在 elapsed <= offset 时应 break（progress 尚未起步）
        checkClose("时长 0 的单元不影响起步", progressOf(frame(zero, "ABCD", 0L)), 0.0);
        // 500ms：跳过零时长单元后，第二个字唱到一半 → 2 + 2 × 0.5 = 3，3/4
        checkClose("零时长单元整段计入后继续插值", progressOf(frame(zero, "ABCD", 500L)), 3.0 / 4);

        System.out.println();
        System.out.println("== 4. 没有字级数据时返回 null（交给匀速退路） ==");
        check("cells 为空 → null",
                Double.isNaN(progressOf(frame(new ArrayList<>(), "没有字级数据", 500L))),
                "应返回 null（探针里以 NaN 表示）");

        System.out.println();
        System.out.println("== 5. 90ms 追赶器：把跳变摊开 ==");
        // 虚时钟，从 0 起算
        ScanSmoother sm = new ScanSmoother(90L);

        // 第一次目标必然是硬切（原版 previousProgress 为 NaN 时 isContinuousProgress=false）
        sm.retarget(0.40, nano(0));
        checkClose("首次目标是硬切，不是从 0 爬上来", sm.level(nano(0)), 0.40);

        // 从 0.40 前进到 0.44（跨度 0.04，在 0.35 以内）→ 走 90ms 线性
        sm.retarget(0.44, nano(0));
        checkClose("追赶起点 = 变化前的实际值 0.40", sm.level(nano(0)), 0.40);
        checkClose("走到一半（45ms）= 0.42", sm.level(nano(45)), 0.42);
        checkClose("走满 90ms = 0.44", sm.level(nano(90)), 0.44);
        checkClose("超过 90ms 之后停在 0.44", sm.level(nano(200)), 0.44);

        // 中途改目标：从**当时的实际值**重新起算，不是从上一个目标值。
        //
        // 这一条钉的是 `from = level(now)` 而不是 `from = 上一个 target`：
        // 若写成后者，下面第一行的实测会变成 0.30（上一个目标）而不是 0.15。
        //
        // 目标是继续**前进**而只是速率变了（0.30 → 0.40）。不能写成"目标回撤"：
        // 回撤超过 0.02 会走守卫直接跳（见第 6 组），测不到平滑。
        ScanSmoother sm2 = new ScanSmoother(90L);
        sm2.retarget(0.0, nano(0));
        sm2.retarget(0.30, nano(0));
        double midway = sm2.level(nano(45));                 // = 0.15
        checkClose("中途值 = 0.15", midway, 0.15);
        sm2.retarget(0.40, nano(45));                        // 目标前移
        checkClose("改目标后起点 = 当时的实际值 0.15（不是上一个目标 0.30）",
                sm2.level(nano(45)), 0.15);
        checkClose("改目标后 45ms 走到 0.275", sm2.level(nano(90)), 0.275);
        checkClose("改目标后 90ms 到位 0.40", sm2.level(nano(135)), 0.40);

        // 幂等：重复设同一个目标不重启时间线
        ScanSmoother sm3 = new ScanSmoother(90L);
        sm3.retarget(0.0, nano(0));
        sm3.retarget(0.60, nano(0));
        sm3.level(nano(60));                                  // 已经走到 2/3
        sm3.retarget(0.60, nano(60));                         // 同目标，不该重启
        checkClose("相同目标幂等，不重启时间线", sm3.level(nano(90)), 0.60);

        System.out.println();
        System.out.println("== 6. 守卫：大跨度直接跳，不平滑 ==");
        ScanSmoother sm4 = new ScanSmoother(90L);
        sm4.retarget(0.10, nano(0));
        // 前进 0.50 > 0.35：换行 / seek 属于这种情况，平滑它只会显得拖泥带水
        sm4.retarget(0.60, nano(0));
        checkClose("前进超过 0.35 → 直接跳", sm4.level(nano(0)), 0.60);

        ScanSmoother sm5 = new ScanSmoother(90L);
        sm5.retarget(0.50, nano(0));
        sm5.retarget(0.30, nano(0));                          // 倒退 0.20 > 0.02
        checkClose("倒退超过 0.02 → 直接跳", sm5.level(nano(0)), 0.30);

        // 两端不做平滑：0 和 1 中间没有可插的值
        ScanSmoother sm6 = new ScanSmoother(90L);
        sm6.retarget(0.05, nano(0));
        sm6.retarget(1.0, nano(0));
        checkClose("到 1（唱完）→ 直接跳", sm6.level(nano(0)), 1.0);
        ScanSmoother sm7 = new ScanSmoother(90L);
        sm7.retarget(0.05, nano(0));
        sm7.retarget(0.0, nano(0));
        checkClose("回 0（还没开始唱）→ 直接跳", sm7.level(nano(0)), 0.0);

        // 倒退一点点（0.01）在容差内，仍走平滑
        ScanSmoother sm8 = new ScanSmoother(90L);
        sm8.retarget(0.50, nano(0));
        sm8.retarget(0.49, nano(0));
        checkClose("倒退 0.01 在容差内，仍平滑（起点 0.50）", sm8.level(nano(0)), 0.50);

        System.out.println();
        System.out.println("== 7. reset 之后从头硬切 ==");
        ScanSmoother sm9 = new ScanSmoother(90L);
        sm9.retarget(0.80, nano(0));
        sm9.reset();
        sm9.retarget(0.20, nano(1000));
        checkClose("reset 后下一次是硬切", sm9.level(nano(1000)), 0.20);

        System.out.println();
        System.out.printf("%s：%d 项通过，%d 项失败%n",
                failed == 0 ? "全部通过" : "存在失败", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    /** 造一个只含主行的 frame；第二行与封面用不上。 */
    static LyricsFrame frame(List<LyricCell> cells, String text, long positionMs) {
        return new LyricsFrame(text, null, null, cells, positionMs, 0L, 3000L,
                SecondLineKind.NEXT);
    }

    /** 毫秒 → 虚时钟纳秒。基准任意，只用于求差。 */
    static long nano(long ms) {
        return ms * 1_000_000L;
    }

    static double progressOf(LyricsFrame f) {
        Double p = WordScan.INSTANCE.progress(f, f.getMainText());
        return p == null ? Double.NaN : p;
    }

    static void checkClose(String name, double actual, double expected) {
        check(name, Math.abs(actual - expected) < 1e-6,
                String.format("实测 %.9f，期望 %.9f", actual, expected));
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
