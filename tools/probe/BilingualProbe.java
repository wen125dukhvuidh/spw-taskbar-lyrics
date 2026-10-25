import com.spwtb.taskbarlyrics.lyrics.EmbeddedLyrics;
import com.spwtb.taskbarlyrics.lyrics.LrcParser;
import com.spwtb.taskbarlyrics.lyrics.LyricLine;
import com.spwtb.taskbarlyrics.lyrics.LyricsOrigin;
import com.spwtb.taskbarlyrics.lyrics.LyricsStore;
import com.spwtb.taskbarlyrics.render.LyricLineSelector;
import com.spwtb.taskbarlyrics.render.LyricSelection;
import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.SecondLineKind;
import com.spwtb.taskbarlyrics.render.SecondLineMode;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 双语歌词（原文 + 译文）的正确性验证。
 *
 * 存在的原因：双语 LRC 把原文和译文写成**同一时间戳的两行**，如果解析阶段
 * 不把它们配成一对，选行时会取到后一条（译文），于是主行显示译文、
 * 第二行显示下一句原文——整体错位一行。这种错位靠"看渲染结果"很难判断，
 * 必须机械地逐条核对。
 *
 * 两个子命令：
 *
 *   1) pair-check &lt;音乐根目录&gt;
 *      扫全库。对每一处「同时间戳两行」，直接拿**文件里的原始内容**当预期，
 *      核对解析结果的第一行是原文、第二行挂在 translation 上、且没有占位符 `//` 泄漏。
 *
 *   2) frame &lt;FLAC&gt; &lt;毫秒位置&gt; [宽] [高] [dpi缩放] [第二行模式]
 *      走真实链路：内嵌解析 → 选行 → 绘制，输出 PNG 并打印选中的两行。
 */
public class BilingualProbe {

    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法: pair-check <音乐根目录> | frame <FLAC> <毫秒位置>");
            return;
        }
        switch (args[0]) {
            case "pair-check" -> pairCheck(args[1]);
            case "auto-check" -> autoCheck(args[1]);
            case "frame" -> frame(args);
            default -> System.out.println("未知子命令: " + args[0]);
        }
    }

    // ────────────────────────────────────────────── AUTO 模式的逐行校验

    /**
     * 扫全库，逐行核对 [LyricLineSelector.pick] 在 AUTO 模式下的取法是否等于原版规则：
     *
     * 1. 本句有翻译         → 显示译文（TRANSLATION）
     * 2. 整首有翻译、本句没有 → 显示 `…` 占位（PLACEHOLDER）
     * 3. 整首都没有翻译      → 显示下一句（NEXT）
     */
    private static void autoCheck(String root) throws Exception {
        List<Path> files;
        try (var stream = Files.walk(Paths.get(root))) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".flac") || n.endsWith(".mp3");
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }

        int songsChecked = 0;
        int linesChecked = 0;
        int bilingualSongs = 0;
        int failures = 0;
        List<String> details = new ArrayList<>();
        int[] kindCount = new int[3];   // TRANSLATION / NEXT / PLACEHOLDER

        for (Path path : files) {
            String raw;
            try {
                raw = EmbeddedLyrics.INSTANCE.read(path.toFile());
            } catch (Throwable t) {
                continue;
            }
            if (raw == null || raw.isBlank()) {
                continue;
            }
            List<LyricLine> lines = LrcParser.INSTANCE.parse(raw, true);
            if (lines.isEmpty()) {
                continue;
            }
            songsChecked++;

            boolean trackHasTranslation = false;
            for (LyricLine l : lines) {
                if (l.getTranslation() != null && !l.getTranslation().isBlank()) {
                    trackHasTranslation = true;
                    break;
                }
            }
            if (trackHasTranslation) {
                bilingualSongs++;
            }

            for (int i = 0; i < lines.size(); i++) {
                LyricLine line = lines.get(i);
                // 同一 startMs 上有多条（片头制作信息挤在一起）时，选行只会命中最后一条，
                // 逐行核对没有意义，跳过。
                if (i + 1 < lines.size() && lines.get(i + 1).getStartMs() == line.getStartMs()) {
                    continue;
                }
                if (i > 0 && lines.get(i - 1).getStartMs() == line.getStartMs()) {
                    continue;
                }

                LyricSelection sel = LyricLineSelector.INSTANCE.pick(
                        lines, line.getStartMs(), SecondLineMode.AUTO);
                if (sel == null) {
                    failures++;
                    details.add(path.getFileName() + " 第 " + i + " 行选行返回 null");
                    continue;
                }
                linesChecked++;

                if (!sel.getMainText().equals(line.getText())) {
                    failures++;
                    details.add(path.getFileName() + " 第 " + i + " 行主行不符：得到 ["
                            + sel.getMainText() + "] 期望 [" + line.getText() + "]");
                    continue;
                }

                String trans = line.getTranslation();
                boolean hasTrans = trans != null && !trans.isBlank();

                String expectedSecond;
                SecondLineKind expectedKind;
                if (hasTrans) {
                    expectedSecond = trans;
                    expectedKind = SecondLineKind.TRANSLATION;
                } else if (trackHasTranslation) {
                    expectedSecond = "…";
                    expectedKind = SecondLineKind.PLACEHOLDER;
                } else {
                    String next = null;
                    for (int k = i + 1; k < lines.size(); k++) {
                        if (!lines.get(k).getText().isBlank()) {
                            next = lines.get(k).getText();
                            break;
                        }
                    }
                    expectedSecond = next;
                    expectedKind = SecondLineKind.NEXT;
                }

                kindCount[expectedKind.ordinal()]++;

                boolean ok = expectedSecond == null
                        ? sel.getSecondText() == null
                        : expectedSecond.equals(sel.getSecondText());
                if (!ok || sel.getSecondKind() != expectedKind) {
                    failures++;
                    details.add(path.getFileName() + " 第 " + i + " 行第二行不符：得到 ["
                            + sel.getSecondText() + "](" + sel.getSecondKind() + ")"
                            + " 期望 [" + expectedSecond + "](" + expectedKind + ")"
                            + "  主行=[" + line.getText() + "]");
                    if (details.size() > 25) {
                        break;
                    }
                }
            }
        }

        System.out.println("音乐库            : " + root);
        System.out.println("校验歌曲          : " + songsChecked + "   （其中双语 " + bilingualSongs + " 首）");
        System.out.println("校验歌词行        : " + linesChecked);
        System.out.println("  ├ 显示译文      : " + kindCount[SecondLineKind.TRANSLATION.ordinal()]);
        System.out.println("  ├ 显示下一句    : " + kindCount[SecondLineKind.NEXT.ordinal()]);
        System.out.println("  └ 显示 … 占位   : " + kindCount[SecondLineKind.PLACEHOLDER.ordinal()]);
        System.out.println("校验失败项        : " + failures);
        System.out.println();
        if (failures == 0) {
            System.out.println("结论：AUTO 模式在全库逐行与原版规则一致。");
        } else {
            System.out.println("=== 失败明细 ===");
            details.forEach(d -> System.out.println("  " + d));
        }
    }

    // ────────────────────────────────────────────── 全库配对校验

    private static void pairCheck(String root) throws Exception {
        List<Path> files;
        try (var stream = Files.walk(Paths.get(root))) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".flac") || n.endsWith(".mp3");
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }

        int withLyrics = 0;
        int withPairs = 0;
        int pairCount = 0;
        int failures = 0;
        List<String> failureDetail = new ArrayList<>();

        for (Path path : files) {
            String raw;
            try {
                raw = EmbeddedLyrics.INSTANCE.read(path.toFile());
            } catch (Throwable t) {
                continue;
            }
            if (raw == null || raw.isBlank()) {
                continue;
            }
            withLyrics++;

            List<Long> rawTimes = new ArrayList<>();
            List<String> rawTexts = new ArrayList<>();
            collectRaw(raw, rawTimes, rawTexts);

            List<LyricLine> parsed;
            try {
                parsed = LrcParser.INSTANCE.parse(raw, true);
            } catch (Throwable t) {
                failures++;
                failureDetail.add(path.getFileName() + " 解析抛异常: " + t);
                continue;
            }

            // 1) 数据一致性：解析结果的每条 (startMs, text, translation) 必须能在原始分组里找到对应
            boolean fileBad = false;
            int i = 0;
            int pairs = 0;
            while (i < rawTimes.size()) {
                long t = rawTimes.get(i);
                int j = i;
                while (j < rawTimes.size() && rawTimes.get(j) == t) {
                    j++;
                }
                int count = j - i;

                List<LyricLine> atSameTime = parsed.stream()
                        .filter(l -> l.getStartMs() == t)
                        .collect(Collectors.toList());

                if (count == 2) {
                    pairs++;
                    String first = rawTexts.get(i);
                    String second = rawTexts.get(i + 1);
                    if (atSameTime.size() != 1) {
                        failures++;
                        fileBad = true;
                        failureDetail.add(path.getFileName() + " @" + t + " 同时间戳两条原始行，"
                                + "解析后却得到 " + atSameTime.size() + " 条（应为 1 条：原文+译文）");
                    } else {
                        LyricLine line = atSameTime.get(0);
                        if (!line.getText().equals(first)) {
                            failures++;
                            fileBad = true;
                            failureDetail.add(path.getFileName() + " @" + t
                                    + " 主行不是原文：得到 [" + line.getText() + "] 期望 [" + first + "]");
                        }
                        String expectedTrans = isPlaceholder(second) ? null : second;
                        String actualTrans = line.getTranslation();
                        if (expectedTrans == null ? actualTrans != null
                                : !expectedTrans.equals(actualTrans)) {
                            failures++;
                            fileBad = true;
                            failureDetail.add(path.getFileName() + " @" + t
                                    + " 译文不符：得到 [" + actualTrans + "] 期望 [" + expectedTrans + "]");
                        }
                    }
                } else if (count >= 3) {
                    // 片头制作信息被挤在同一个时间戳：应当原样保留，一条都不能丢
                    if (atSameTime.size() != count) {
                        failures++;
                        fileBad = true;
                        failureDetail.add(path.getFileName() + " @" + t + " 同时间戳 " + count
                                + " 行被合并成 " + atSameTime.size() + " 行，内容有丢失");
                    }
                } else if (atSameTime.size() != 1) {
                    failures++;
                    fileBad = true;
                    failureDetail.add(path.getFileName() + " @" + t + " 单行却得到 "
                            + atSameTime.size() + " 条");
                }
                i = j;
            }

            if (pairs > 0) {
                withPairs++;
                pairCount += pairs;
            }
            if (fileBad) {
                failureDetail.add("");
            }
        }

        System.out.println("音乐库            : " + root);
        System.out.println("音频文件          : " + files.size());
        System.out.println("读到内嵌歌词      : " + withLyrics);
        System.out.println("含双语配对的歌曲  : " + withPairs + "   （共 " + pairCount + " 处配对）");
        System.out.println("校验失败项        : " + failures);
        System.out.println();
        if (failures == 0) {
            System.out.println("结论：全部配对正确 —— 主行是原文、译文挂在 translation、没有内容丢失。");
        } else {
            System.out.println("=== 失败明细 ===");
            for (String d : failureDetail) {
                System.out.println("  " + d);
            }
        }
    }

    /** 用独立实现从原始文本抽取 (时间, 文本) 序列，作为核对用的预期值。 */
    private static void collectRaw(String raw, List<Long> times, List<String> texts) {
        long offset = 0;
        Matcher om = OFFSET.matcher(raw);
        if (om.find()) {
            offset = Long.parseLong(om.group(1));
        }
        List<long[]> order = new ArrayList<>();   // 用于稳定排序：记录文件内序号
        List<Object[]> staged = new ArrayList<>();
        int seq = 0;
        for (String line : raw.split("\r\n|\n|\r")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher m = TAG.matcher(trimmed);
            List<int[]> spans = new ArrayList<>();
            List<Long> ms = new ArrayList<>();
            while (m.find()) {
                spans.add(new int[]{m.start(), m.end()});
                ms.add(toMillis(m));
            }
            if (spans.isEmpty()) {
                continue;
            }
            String text = trimmed.substring(spans.get(spans.size() - 1)[1]);
            // Kotlin 的 `String.trim()` 同时看 Character.isWhitespace 与 isSpaceChar，
            // 因此会去掉不换行空格 U+00A0；Java 的 `String.trim()` 只处理 <= U+0020。
            // 本机《我记得 (Live版)》就有 5 行是「时间戳 + NBSP」的间奏空行，
            // 用 Java 的 trim 会误判成有内容。这里统一成 Kotlin 的语义。
            if (isBlank(text)) {
                continue;
            }
            text = text.trim();
            for (Long v : ms) {
                staged.add(new Object[]{v + offset, text, seq++});
            }
        }
        staged.sort((a, b) -> {
            int c = Long.compare((Long) a[0], (Long) b[0]);
            return c != 0 ? c : Integer.compare((Integer) a[2], (Integer) b[2]);
        });
        for (Object[] o : staged) {
            times.add((Long) o[0]);
            texts.add((String) o[1]);
        }
    }

    private static long toMillis(Matcher m) {
        long minutes = Long.parseLong(m.group(1));
        long seconds = Long.parseLong(m.group(2));
        String fraction = m.group(3);
        long millis = 0;
        if (fraction != null && !fraction.isEmpty()) {
            millis = switch (fraction.length()) {
                case 1 -> Long.parseLong(fraction) * 100;
                case 2 -> Long.parseLong(fraction) * 10;
                default -> Long.parseLong(fraction.substring(0, 3));
            };
        }
        return minutes * 60_000 + seconds * 1_000 + millis;
    }

    private static boolean isPlaceholder(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return true;
        }
        if (t.length() > 6) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLetterOrDigit(t.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 按 Kotlin `Char.isWhitespace()` 的语义判断：isWhitespace 或 isSpaceChar。 */
    private static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isWhitespace(c) || Character.isSpaceChar(c))) {
                return false;
            }
        }
        return true;
    }

    // ────────────────────────────────────────────── 真实链路渲染

    private static void frame(String[] args) throws Exception {
        String flac = args[1];
        long position = Long.parseLong(args[2]);
        int width = args.length > 3 ? Integer.parseInt(args[3]) : 482;
        int height = args.length > 4 ? Integer.parseInt(args[4]) : 66;
        double dpiScale = args.length > 5 ? Double.parseDouble(args[5]) : 1.5;
        SecondLineMode mode = args.length > 6
                ? SecondLineMode.valueOf(args[6]) : SecondLineMode.AUTO;

        File file = new File(flac);
        PlaybackExtensionPoint.MediaItem item = new PlaybackExtensionPoint.MediaItem(
                file.getName(), "", "", "", file.getAbsolutePath());

        // 走真实的换曲入口，让 LyricsStore 自己去解析内嵌歌词
        LyricsStore.INSTANCE.onSongChanged(item);

        List<LyricLine> lines = List.of();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            lines = LyricsStore.INSTANCE.getLines();
            if (!lines.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }

        System.out.println("歌曲            : " + file.getName());
        System.out.println("歌词来源        : " + LyricsStore.INSTANCE.getLyricsOrigin());
        System.out.println("时间轴行数      : " + lines.size());

        // 时间轴里不该出现「两行同一 startMs」——那正是双语没配对的症状
        int collisions = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).getStartMs() == lines.get(i - 1).getStartMs()) {
                collisions++;
            }
        }
        System.out.println("同 startMs 冲突 : " + collisions + "   （0 才算正常）");
        System.out.println();

        LyricSelection sel = LyricLineSelector.INSTANCE.pick(lines, position, mode);
        if (sel == null) {
            System.out.println("该位置没有可显示的行");
            return;
        }
        System.out.println("位置            : " + position + " ms");
        System.out.println("第二行模式      : " + mode + "  →  " + sel.getSecondKind());
        System.out.println("主行            : " + sel.getMainText());
        System.out.println("第二行          : " + sel.getSecondText());

        int idx = sel.getIndex();
        System.out.println();
        System.out.println("--- 时间轴前后各 2 行 ---");
        for (int i = Math.max(0, idx - 2); i < Math.min(lines.size(), idx + 3); i++) {
            LyricLine l = lines.get(i);
            System.out.printf("  %s [%7d] %-40s | 译文: %s%n",
                    i == idx ? "→" : " ", l.getStartMs(),
                    l.getText(), l.getTranslation());
        }

        // 绘制
        RenderSettings settings = new RenderSettings();
        LyricLine current = lines.get(idx);
        LyricsFrame f = new LyricsFrame(
                sel.getMainText(), sel.getSecondText(), null, current.getCells(),
                position, current.getStartMs(), current.getEndMs(), sel.getSecondKind());

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        new LyricsPainter().paint(image, f, settings, dpiScale);

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        String outDir = System.getenv("SHOTS");
        File target = new File(outDir == null ? "." : outDir, "bilingual-frame.png");
        ImageIO.write(out, "PNG", target);
        System.out.println();
        System.out.println("已输出 " + target.getAbsolutePath());
    }
}
