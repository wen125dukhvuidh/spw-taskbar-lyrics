import com.spwtb.taskbarlyrics.lyrics.AudioDuration;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * `AudioDuration` 的机械验证。
 *
 * 期望值**全部由构造文件时自己给定的数字算出**，不从被测代码推导：
 * 探针里先定好「采样率 44100、总采样 44100×N」，再把对应的字节写进文件，
 * 然后要求读出来的毫秒数等于 N×1000。这样通过与否才有意义。
 *
 * 没有用真实音乐文件，是因为那样只能拿到「某个播放器说它是多少」的二手值；
 * 而本机现在也确实没有可用的曲库文件。合成文件反而能把三种格式的
 * 打包规则（FLAC 的位打包、MP4 的原子树、MP3 的 ID3 同步安全整数）
 * 各自钉死在一个已知答案上。
 *
 * 用法：AudioDurationProbe
 */
public class AudioDurationProbe {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("spw-duration").toFile();

        // ── 1. FLAC：采样率 44100，总采样数 = 44100 × 210 → 210000 ms ──
        long flacSamples = 44100L * 210;
        byte[] flac = buildFlac(44100, flacSamples);
        File flacFile = write(dir, "a.flac", flac);
        long flacMs = AudioDuration.INSTANCE.read(flacFile);
        System.out.printf("  1. FLAC 44100Hz / %d 采样 → %d ms（期望 210000）%n", flacSamples, flacMs);
        check("FLAC 时长 = 总采样数 / 采样率", flacMs, 210000L);

        // 换一组数：48000Hz，123.456 秒 → 123456 ms
        byte[] flac2 = buildFlac(48000, 48000L * 123 + 21888);
        File flac2File = write(dir, "b.flac", flac2);
        long flac2Ms = AudioDuration.INSTANCE.read(flac2File);
        long expect2 = (48000L * 123 + 21888) * 1000 / 48000;
        System.out.printf("  2. FLAC 48000Hz → %d ms（期望 %d）%n", flac2Ms, expect2);
        check("换一组采样率/采样数仍然对", flac2Ms, expect2);

        // 后面追加垃圾数据不该影响结果（STREAMINFO 在文件头）
        byte[] padded = concat(buildFlac(44100, 44100L * 100), new byte[64 * 1024]);
        File paddedFile = write(dir, "c.flac", padded);
        check("文件后面有音频数据不影响头部解析",
                AudioDuration.INSTANCE.read(paddedFile), 100000L);

        // ── 3. MP4：timescale 600，duration 600×95 → 95000 ms ──
        byte[] mp4 = buildMp4(600, 600L * 95);
        File mp4File = write(dir, "d.m4a", mp4);
        long mp4Ms = AudioDuration.INSTANCE.read(mp4File);
        System.out.printf("  3. MP4 timescale=600 duration=%d → %d ms（期望 95000）%n",
                600L * 95, mp4Ms);
        check("MP4 时长 = duration / timescale", mp4Ms, 95000L);

        // ── 4. MP3：ID3v2 里有 TLEN，用它的精确值 ──
        byte[] mp3Tlen = buildMp3WithTlen(254321);
        File mp3TlenFile = write(dir, "e.mp3", mp3Tlen);
        check("MP3 有 TLEN 时用 TLEN 的毫秒值",
                AudioDuration.INSTANCE.read(mp3TlenFile), 254321L);

        // ── 5. MP3：没有 TLEN，按首帧码率估算 ──
        // 128 kbps（MPEG1 Layer3 索引 9）→ 毫秒 = 字节数 × 8 / 128
        int audioBytes = 400_000;
        byte[] mp3 = buildMp3NoTlen(audioBytes);
        File mp3File = write(dir, "f.mp3", mp3);
        long mp3Ms = AudioDuration.INSTANCE.read(mp3File);
        long expect5 = audioBytes * 8L / 128;
        System.out.printf("  5. MP3 无 TLEN，%d 音频字节 @128kbps → %d ms（期望 %d）%n",
                audioBytes, mp3Ms, expect5);
        check("MP3 无 TLEN 时按码率估算", mp3Ms, expect5);

        // ── 6. 认不出来 / 打不开 → 0，不能抛 ──
        check("空文件 → 0", AudioDuration.INSTANCE.read(write(dir, "g.bin", new byte[0])), 0L);
        check("纯文本 → 0",
                AudioDuration.INSTANCE.read(
                        write(dir, "h.txt", "hello".getBytes(StandardCharsets.UTF_8))), 0L);
        check("不存在的文件 → 0", AudioDuration.INSTANCE.read(new File(dir, "nope.flac")), 0L);

        // ── 7. 时长必须为正，且不能离谱（> 24 小时基本一定是解析错了）──
        byte[] huge = buildFlac(44100, 44100L * 3600 * 30);
        long hugeMs = AudioDuration.INSTANCE.read(write(dir, "i.flac", huge));
        System.out.printf("  7. 30 小时的 FLAC → %d ms（期望 %d）%n", hugeMs, 3600L * 30 * 1000);
        check("超长文件也按同一条公式", hugeMs, 3600L * 30 * 1000);

        System.out.printf("%n结果：%d 通过 / %d 失败%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ── 构造 FLAC ────────────────────────────────────────────────────────
    //
    // "fLaC" + 块头(0x00, len=34) + 34 字节 STREAMINFO。
    // 位布局：0..15 最小块 / 16..31 最大块 / 32..55 最小帧 / 56..79 最大帧
    //         / 80..99 采样率 / 100..102 声道-1 / 103..107 位深-1 / 108..143 总采样

    static byte[] buildFlac(int sampleRate, long totalSamples) {
        byte[] info = new byte[34];
        setBits(info, 0, 16, 4096);
        setBits(info, 16, 16, 4096);
        setBits(info, 32, 24, 0);
        setBits(info, 56, 24, 0);
        setBits(info, 80, 20, sampleRate);
        setBits(info, 100, 3, 1);            // 2 声道
        setBits(info, 103, 5, 15);           // 16 bit
        setBits(info, 108, 36, totalSamples);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('f');
        out.write('L');
        out.write('a');
        out.write('C');
        out.write(0x00);                      // 非最后一块 + 类型 0(STREAMINFO)
        out.write(0x00);
        out.write(0x00);
        out.write(34);                        // 长度 34
        out.write(info, 0, info.length);
        return out.toByteArray();
    }

    static void setBits(byte[] data, int from, int count, long value) {
        for (int i = 0; i < count; i++) {
            int bit = from + i;
            long b = (value >>> (count - 1 - i)) & 1L;
            if (b != 0) {
                data[bit >>> 3] |= (byte) (1 << (7 - (bit & 7)));
            } else {
                data[bit >>> 3] &= (byte) ~(1 << (7 - (bit & 7)));
            }
        }
    }

    // ── 构造 MP4 ────────────────────────────────────────────────────────

    static byte[] buildMp4(int timescale, long duration) {
        // 原子头是 [4 字节大小][4 字节类型]，不是反的
        byte[] mvhd = new byte[100];
        putBe32(mvhd, 0, 100);               // 这个原子的大小
        putBe32(mvhd, 4, 0x6D766864);        // "mvhd"
        mvhd[8] = 0;                          // version 0
        putBe32(mvhd, 20, timescale);
        putBe32(mvhd, 24, (int) duration);

        byte[] moov = new byte[8 + mvhd.length];
        putBe32(moov, 0, moov.length);
        putBe32(moov, 4, 0x6D6F6F76);        // "moov"
        System.arraycopy(mvhd, 0, moov, 8, mvhd.length);

        byte[] ftyp = new byte[16];
        putBe32(ftyp, 0, 16);
        putBe32(ftyp, 4, 0x66747970);        // "ftyp"
        putBe32(ftyp, 8, 0x4D344120);        // "M4A "

        return concat(ftyp, moov);
    }

    // ── 构造 MP3 ────────────────────────────────────────────────────────

    /** ID3v2.3 + 一个 TLEN 帧（十进制毫秒字符串）。 */
    static byte[] buildMp3WithTlen(long ms) {
        String text = Long.toString(ms);
        byte[] body = new byte[1 + text.length()];
        body[0] = 0;                          // 编码：ISO-8859-1
        System.arraycopy(text.getBytes(StandardCharsets.ISO_8859_1), 0, body, 1, text.length());

        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        try {
            frames.write("TLEN".getBytes(StandardCharsets.ISO_8859_1));
            writeBe32(frames, body.length);
            frames.write(0);
            frames.write(0);
            frames.write(body);
        } catch (IOException ignored) {
        }
        byte[] tag = frames.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write("ID3".getBytes(StandardCharsets.ISO_8859_1));
            out.write(3);
            out.write(0);
            out.write(0);
            writeSyncSafe(out, tag.length);
            out.write(tag);
            out.write(new byte[512]);
        } catch (IOException ignored) {
        }
        return out.toByteArray();
    }

    /** ID3v2（无 TLEN）+ 一个 MPEG1 Layer3 128kbps 44.1kHz 的帧头 + 若干音频字节。 */
    static byte[] buildMp3NoTlen(int audioBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write("ID3".getBytes(StandardCharsets.ISO_8859_1));
            out.write(3);
            out.write(0);
            out.write(0);
            writeSyncSafe(out, 64);
            out.write(new byte[64]);
            // 帧头：FF FB 90 00 → MPEG1 / Layer3 / 128kbps / 44100Hz
            out.write(0xFF);
            out.write(0xFB);
            out.write(0x90);
            out.write(0x00);
            out.write(new byte[audioBytes]);
        } catch (IOException ignored) {
        }
        return out.toByteArray();
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    static void writeBe32(ByteArrayOutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /** ID3v2 的「同步安全」整数：每字节只用低 7 位。 */
    static void writeSyncSafe(ByteArrayOutputStream out, int v) throws IOException {
        out.write((v >>> 21) & 0x7F);
        out.write((v >>> 14) & 0x7F);
        out.write((v >>> 7) & 0x7F);
        out.write(v & 0x7F);
    }

    static void putBe32(byte[] a, int off, int v) {
        a[off] = (byte) (v >>> 24);
        a[off + 1] = (byte) (v >>> 16);
        a[off + 2] = (byte) (v >>> 8);
        a[off + 3] = (byte) v;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static File write(File dir, String name, byte[] data) throws IOException {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f;
    }

    static void check(String what, long actual, long expected) {
        if (actual == expected) {
            passed++;
            System.out.println("     ✓ " + what);
        } else {
            failed++;
            System.out.printf("     ✗ %s：实际 %d，期望 %d%n", what, actual, expected);
        }
    }
}
